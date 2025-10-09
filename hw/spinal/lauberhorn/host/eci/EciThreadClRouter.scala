package lauberhorn.host.eci

import jsteward.blocks.eci.EciCmdDefs.EciAddress
import jsteward.blocks.eci.EciWord
import jsteward.blocks.misc._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Ax, Axi4Config}
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.misc.plugin.FiberPlugin
import lauberhorn.Global._
import spinal.lib.bus.regif.AccessType.WO

import scala.language.postfixOps

/** AXI port pair for datapath.  Two of these will be instantiated, one for each DCS.
  * This way the AXI dispatch to each protocol state machine can stay a fully connected
  * crossbar.
  */
case class ClAxiPort(config: Axi4Config) extends Bundle {
  val axiFromDcs, axiToProto = Axi4(config)
}

/** Pair of ports for the LCI/UL channel to the DC and LCIA from the DC.  We only
  * instantiate one of this, since the LCI/UL direction cannot be a full crossbar:
  * simply translating the two outgoing ports won't work, as the translation
  * potentially changes the address to be odd or even, therefore they would need
  * to be sent to the opposite DCS.
  *
  * As a result, [[EciInterfacePlugin]] has to implement binding of these channels
  * as one mux and then one demux, unlike the crossbar as with AXI.  This is fine
  * since these are control-path and low throughput.
  */
case class ClLclPort() extends Bundle {
  val lciFromProto, lciaFromDcs, ulFromProto = Stream(EciWord())
  val lciToDcs, lciaToProto, ulToDcs = Stream(EciWord())
}

case class ThreadDef() extends Bundle {
  val addrPrefix = Bits(16 bits)
  val enabled = Bool()

  assert(addrPrefix.getWidth >= log2Up(NUM_THREADS.get + 1), "must allow at least all threads to get a prefix")
}

/** Global module to route requests from threads on CPUs to the correct
  * 2F2F protocol state machine i.e. [[EciDecoupledRxTxProtocol]].
  *
  * The module maintains a lookup table from thread physical address starts
  * to the actual physical addresses of the backing worker.  It translates
  * AXI requests and DCS invalidation requests.
  *
  * This module works with UNALIASED ECI addresses.
  */
class EciThreadClRouter extends FiberPlugin {
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    val writePort = ThreadDef()
    writePort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("ctrl", s"Thread def $name", name, attr = WO))
    }

    logic.threadDb.update.setIdle()

    val idx = U(0, log2Up(NUM_CORES) bits)
    val idxAddr = alloc("ctrl", "Core ID to map this thread to (including bypass)", "coreIdx", attr = WO)
    busCtrl.write(idx, idxAddr)
    busCtrl.onWrite(idxAddr) {
      logic.threadDb.update.valid := True
      logic.threadDb.update.idx := idx
      logic.threadDb.update.value := writePort
    }

    // check that no same thread prefix should be enabled twice
    GenerationFlags simulation new Area {
      val prefixOnCores = Vec(Reg(new Bundle {
        val enabled = Bool()
        val prefix = Bits(16 bits)
      }), NUM_CORES.get)

      busCtrl.onWrite(idxAddr) {
        prefixOnCores(idx).enabled := writePort.enabled
        prefixOnCores(idx).prefix := writePort.addrPrefix

        assert(!prefixOnCores.sExist { rec =>
          rec.enabled && rec.prefix === writePort.addrPrefix
        }, "trying to enable thread prefix that is already active")
      }
    }
  }

  val logic = during setup new Area {
    val axiConfig = host[EciInterfacePlugin].axiConfig
    val axi = Seq.fill(2)(ClAxiPort(axiConfig))
    val lcl = ClLclPort()

    awaitBuild()

    // Includes the base that runs on the bypass core (logical "thread")
    val threadDb = LookupTable(ThreadDef(), NUM_CORES) { v =>
      v.enabled init False
    }

    val coreShift = log2Up(BigInt(ECI_CORE_OFFSET))
    val coreMask = ((U("1") << coreShift) - 1).asBits

    def testPrefix(addr: UInt, prefix: Bits): Bool = {
      prefix.resized === (addr >> coreShift).asBits
    }
    def setPrefix(addr: UInt, prefix: Bits): UInt = {
      ((prefix << coreShift).resized | (addr.asBits & coreMask.resized)).asUInt
    }

    axi.zipWithIndex.foreach { case (p, pidx) =>
      def mapAx(locator: Axi4 => Stream[Axi4Ax], portName: String) = {
        val (axLookup, axResult, _) = threadDb.makePort(axiConfig.addressType, locator(p.axiFromDcs).payload,
          portName = portName,
          singleMatch = true) { (v, q, _) =>
          v.enabled && testPrefix(q, v.addrPrefix)
        }

        axLookup.translateFrom(locator(p.axiFromDcs)) { case (lk, fd) =>
          lk.userData := fd
          lk.query := fd.addr
        }

        axResult.translateInto(locator(p.axiToProto)) { case (tp, r) =>
          val outPrefix = r.idx.asBits.resize(16)
          when(!r.matched) {
            // only prefix 0 - NUM_CORES exist;
            // mangle to a non-existing downstream prefix to use AXI interconnect's error generation
            outPrefix := B("16'xFFFF")
          }

          tp := r.userData.mapElement(_.addr) { a => setPrefix(a, outPrefix) }
        }
      }

      mapAx(_.ar.asInstanceOf[Stream[Axi4Ax]], s"ar_$pidx")
      p.axiFromDcs.r << p.axiToProto.r

      mapAx(_.aw.asInstanceOf[Stream[Axi4Ax]], s"aw_$pidx")
      p.axiFromDcs.w >> p.axiToProto.w
      p.axiFromDcs.b << p.axiToProto.b
    }

    def mapChan(from: Stream[EciWord], to: Stream[EciWord], locator: EciWord => Bits, portName: String) = {
      val (chanLookup, chanResult, _) = threadDb.makePort(EciAddress, EciWord(),
        portName = portName,
        singleMatch = true) { (v, q, _) =>
        v.enabled && testPrefix(q.asUInt, v.addrPrefix)
      }

      chanLookup.translateFrom(from) { case (lk, f) =>
        lk.userData := f
        lk.query := locator(f)
      }

      chanResult.translateInto(to) { case (t, r) =>
        t := r.userData.mapElement(locator) { a => setPrefix(a.asUInt, r.idx.asBits).asBits }
      }

      when (chanResult.valid) {
        assert(chanResult.matched, "non-existent map for LCIA (thread => physical)")
      }
    }

    def unmapChan(from: Stream[EciWord], to: Stream[EciWord], locator: EciWord => Bits, portName: String) = {
      val (chanLookup, chanResult, _) = threadDb.makePort(EciAddress, EciWord(),
        portName = portName,
        singleMatch = true) { (v, q, idx) =>
        v.enabled && testPrefix(q.asUInt, idx)
      }

      chanLookup.translateFrom(from) { case (lk, f) =>
        lk.userData := f
        lk.query := locator(f)
      }

      chanResult.translateInto(to) { case (t, r) =>
        t := r.userData.mapElement(locator) { a => setPrefix(a.asUInt, r.value.addrPrefix).asBits }
      }

      when (chanResult.valid) {
        assert(chanResult.matched, s"non-existent unmap for $portName (physical => thread)")
      }
    }

    unmapChan(lcl.lciFromProto, lcl.lciToDcs, _.lci.address, "lci")
    mapChan(lcl.lciaFromDcs, lcl.lciaToProto, _.lcia.address, "lcia")
    unmapChan(lcl.ulFromProto, lcl.ulToDcs, _.ul.address, "ul")
  }
}
