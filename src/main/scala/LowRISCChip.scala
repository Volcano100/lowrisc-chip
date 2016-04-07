// See LICENSE for license details.

package lowrisc_chip

import Chisel._
import cde.{Parameters, ParameterDump, Field, Config}
import junctions._
import uncore._
import rocket._
import rocket.Util._

trait HasTopLevelParameters {
  implicit val p: Parameters
  lazy val nTiles : Int = p(NTiles)
  lazy val nBanks : Int = p(NBanks)
  lazy val bankLSB : Int = p(BankIdLSB)
  lazy val bankMSB = bankLSB + log2Up(nBanks) - 1
  require(isPow2(nBanks))
}

class TopIO(implicit val p: Parameters) extends ParameterizedBundle()(p) with HasTopLevelParameters {
  val nasti       = new NastiIO()(p.alterPartial({case BusId => "nasti"}))
  val nasti_lite  = new NastiIO()(p.alterPartial({case BusId => "lite"}))
  val interrupt   = UInt(INPUT, p(XLen))
}

class Top(topParams: Parameters) extends Module with HasTopLevelParameters {
  implicit val p = topParams
  val io = new TopIO

  ////////////////////////////////////////////
  // Rocket Tiles
  val tiles = (0 until nTiles) map { i =>
    Module(new RocketTile(i)(p.alterPartial({case TLId => "L1toL2", case IOTLId => "L1toIO" })))
  }

  // DMA and IO space
  val dmaOpt = if (p(UseDma))
    Some(Module(new DmaEngine(
      DmaCtrlRegNumbers.CSR_BASE + DmaCtrlRegNumbers.OUTSTANDING)))
    else None
  val mmioBase = p(MMIOBase)

  ////////////////////////////////////////////
  // The crossbar between tiles and L2
  def routeL1ToL2(addr: UInt) = if (nBanks > 1) addr(bankMSB,bankLSB) else UInt(0)
  def routeL2ToL1(id: UInt) = id
  val l2Network = Module(new TileLinkCrossbar(
    routeL1ToL2, routeL2ToL1,
    TileLinkDepths(2,2,2,2,2),
    TileLinkDepths(0,0,1,0,0)   //Berkeley: TODO: had EOS24 crit path on inner.release
  )(p.alterPartial({case TLId => "L1toL2"})))

  l2Network.io.clients <>
  ( tiles.map(_.io.cached).flatten ++
    tiles.map(_.io.uncached).flatten.map(
      TileLinkIOWrapper(_)(p.alterPartial({case TLId => "L1toL2"}))
    ) ++
    dmaOpt.map(_.io.mem)
  )

  ////////////////////////////////////////////
  // L2 Banks
  val banks = (0 until nBanks) map { i =>
    Module(new L2HellaCacheBank()(p.alterPartial({
      case CacheId => i
      case CacheName => "L2Bank"
      case InnerTLId => "L1toL2"
      case OuterTLId => "L2toTC"
      case TLId => "L1toL2"   // dummy
    })))
  }

  l2Network.io.managers <> banks.map(_.innerTL)
  banks.foreach(_.incoherent := UInt(0)) // !!! need to revise

  ////////////////////////////////////////////
  // the network between L2 and tag cache
  def routeL2ToTC(addr: UInt) = UInt(0)
  def routeTCToL2(id: UInt) = id
  val tcNetwork = Module(new TileLinkCrossbar(routeL2ToTC, routeTCToL2)(p.alterPartial({case TLId => "L2toTC" })))

  tcNetwork.io.clients <> banks.map(_.outerTL)

  ////////////////////////////////////////////
  // tag cache
  //val tc = Module(new TagCache, {case TLId => "L2ToTC"; case CacheName => "TagCache"})
  // currently a TileLink to NASTI converter
  val conv = Module(new NastiMasterIOTileLinkIOConverter()(p.alterPartial({case BusId => "nasti"; case TLId => "L2toTC"})))

  tcNetwork.io.managers(0) <> conv.io.tl
  io.nasti.ar              <> Queue(conv.io.nasti.ar)
  io.nasti.aw              <> Queue(conv.io.nasti.aw)
  io.nasti.w               <> Queue(conv.io.nasti.w)
  conv.io.nasti.r          <> Queue(io.nasti.r)
  conv.io.nasti.b          <> Queue(io.nasti.b)

  ////////////////////////////////////////////
  // MMIO TileLink
  def routeL1ToIO(addr: UInt) = UInt(0)
  def routeIOToL1(id: UInt) = id
  val mmioTileLink = Module(new TileLinkCrossbar(
    routeL1ToIO, routeIOToL1,
    TileLinkDepths(1,1,1,1,1),
    TileLinkDepths(0,0,0,0,0)
  )(p.alterPartial({case TLId => "L1toIO"})))

  val mmioManager = Module(new MMIOTileLinkManager()(p.alterPartial({
    case TLId => "L1toIO"
    case InnerTLId => "L1toIO"
    case OuterTLId => "L1ToIO"
  })))
  mmioTileLink.io.managers(0) <> mmioManager.io.inner

  val mmioConv = Module(new NastiIOTileLinkIOConverter()(p.alterPartial({case BusId => "lite"; case TLId => "L1ToIO"})))
  mmioConv.io.tl <> mmioTileLink.io.outer

  ////////////////////////////////////////////
  // IO space
  val addrMap = p(GlobalAddrMap)
  val addrHashMap = new AddrHashMap(addrMap, mmioBase)
  val nIOMasters = (if (dmaOpt.isEmpty) 2 else 3)
  val nIOSlaves = addrHashMap.nInternalPorts

  println("Generated Address Map")
  for ((name, base, size, _) <- addrHashMap.sortedEntries) {
    println(f"\t$name%s $base%x - ${base + size - 1}%x")
  }

  ////////////////////////////////////////////
  // IO interconnet
  val mmio_ic = Module(new NastiRecursiveInterconnect(nIOMasters, nIOSlaves, addrMap, mmioBase)(p.alterPartial({case BusId => "lite"})))

  // Rocket cores (master)
  mmio_ic.io.master(0) <> mmioConv.io.nasti

  // RTC (master)
  val rtc = Module(new RTC(CSRs.mtime))
  mmio_ic.io.masters(1) <> rtc.io

  // DMA (master)
  dmaOpt.foreach { dma =>
    mmio_ic.io.masters(2) <> dma.io.mmio
    dma.io.ctrl <> mmio_ic.io.slaves(addrHashMap("devices:dma").port)
  }
}

object Run extends App with FileSystemUtilities {
  val projectName = "lowrisc_chip"
  val topModuleName = args(0)
  val configClassName = args(1)

  val config = try {
    Class.forName(s"$projectName.$configClassName").newInstance.asInstanceOf[Config]
  } catch {
    case e: java.lang.ClassNotFoundException =>
      throwException(s"Could not find the cde.Config subclass you asked for " +
        "(i.e. \"$configClassName\"), did you misspell it?", e)
  }

  val world = config.toInstance
  val paramsFromConfig: Parameters = Parameters.root(world)

  val gen = () =>
  Class.forName(s"$projectName.$topModuleName")
    .getConstructor(classOf[cde.Parameters])
    .newInstance(paramsFromConfig)
    .asInstanceOf[Module]

  chiselMain.run(args.drop(2), gen)

  val pdFile = createOutputFile(s"$topModuleName.$configClassName.prm")
  pdFile.write(ParameterDump.getDump)
  pdFile.close
}
