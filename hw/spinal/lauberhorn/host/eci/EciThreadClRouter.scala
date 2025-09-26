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

/** Global module to route requests from threads on CPUs to the correct
  * 2F2F protocol state machine i.e. [[EciDecoupledRxTxProtocol]].
  *
  * The module maintains a lookup table from thread physical address starts
  * to the actual physical addresses of the backing worker.  It translates
  * AXI requests and DCS invalidation requests.
  *
  * This module works with UNALIASED ECI addresses.
  */
case class ClRouterPort(config: Axi4Config) extends Bundle {
  val axiFromDcs, axiToProto = Axi4(config)
  val lciFromProto, lciaFromDcs, ulFromProto = Stream(EciWord())
  val lciToDcs, lciaToProto, ulToDcs = Stream(EciWord())
}

case class ThreadDef() extends Bundle {
  val threadIdx = Bits(log2Up(NUM_THREADS) bits)
  val enabled = Bool()
}

class EciThreadClRouter extends FiberPlugin {
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    val writePort = ThreadDef()
    writePort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("ctrl", s"Thread def $name", name, attr = WO))
    }

    logic.threadDb.update.setIdle()

    val idx = U(0, log2Up(NUM_WORKER_CORES) bits)
    val idxAddr = alloc("ctrl", "Index of thread routing entry to update", "tblIdx", attr = WO)
    busCtrl.write(idx, idxAddr)
    busCtrl.onWrite(idxAddr) {
      logic.threadDb.update.valid := True
      logic.threadDb.update.idx := idx
      logic.threadDb.update.value := writePort
    }
  }

  val logic = during setup new Area {
    val axiConfig = host[EciInterfacePlugin].axiConfig
    val ports = Seq.fill(2)(ClRouterPort(axiConfig))

    awaitBuild()

    val threadDb = LookupTable(ThreadDef(), NUM_WORKER_CORES) { v =>
      v.enabled init False
    }

    val coreShift = log2Up(ECI_CORE_OFFSET)
    val coreMask = ((U("1") << coreShift) - 1).asBits

    def testPrefix(addr: UInt, prefix: Bits): Bool = {
      prefix.resized === (addr >> coreShift).asBits
    }
    def setPrefix(addr: UInt, prefix: Bits): UInt = {
      ((prefix << coreShift).resized | (addr.asBits & coreMask.resized)).asUInt
    }

    ports.zipWithIndex.foreach { case (p, pidx) =>
      def mapAx(locator: Axi4 => Stream[Axi4Ax], portName: String) = {
        val (axLookup, axResult, _) = threadDb.makePort(axiConfig.addressType, locator(p.axiFromDcs).payload,
          name = portName,
          singleMatch = true) { (v, q, _) =>
          v.enabled && testPrefix(q, v.threadIdx)
        }

        axLookup.translateFrom(locator(p.axiFromDcs)) { case (lk, fd) =>
          lk.userData := fd
          lk.query := fd.addr
        }

        axResult.translateInto(locator(p.axiToProto)) { case (tp, r) =>
          val outPrefix = (r.idx + 1).asBits.resize(16)
          when (!r.matched) {
            // mangle to an unmapped prefix to use AXI interconnect's error generation
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

      def mapChan(from: Stream[EciWord], to: Stream[EciWord], locator: EciWord => Bits, portName: String) = {
        val (chanLookup, chanResult, _) = threadDb.makePort(EciAddress, EciWord(),
          name = portName,
          singleMatch = true) { (v, q, _) =>
          v.enabled && testPrefix(q.asUInt, v.threadIdx)
        }

        chanLookup.translateFrom(from) { case (lk, f) =>
          lk.userData := f
          lk.query := locator(f)
        }

        chanResult.translateInto(to) { case (t, r) =>
          t := r.userData.mapElement(locator) { a => setPrefix(a.asUInt, (r.idx + 1).asBits).asBits }
        }

        when (chanResult.valid) {
          assert(chanResult.matched, "non-existent map for LCIA (thread => physical)")
        }
      }

      def unmapChan(from: Stream[EciWord], to: Stream[EciWord], locator: EciWord => Bits, portName: String) = {
        val (chanLookup, chanResult, _) = threadDb.makePort(EciAddress, EciWord(),
          name = portName,
          singleMatch = true) { (v, q, idx) =>
          v.enabled && testPrefix(q.asUInt, idx)
        }

        chanLookup.translateFrom(from) { case (lk, f) =>
          lk.userData := f
          lk.query := locator(f)
        }

        chanResult.translateInto(to) { case (t, r) =>
          t := r.userData.mapElement(locator) { a => setPrefix(a.asUInt, r.value.threadIdx).asBits }
        }

        when (chanResult.valid) {
          assert(chanResult.matched, "non-existent unmap for LCI/UL (physical => thread)")
        }
      }

      unmapChan(p.lciFromProto, p.lciToDcs, _.lci.address, s"lci_$pidx")
      mapChan(p.lciaFromDcs, p.lciaToProto, _.lcia.address, s"lcia_$pidx")
      unmapChan(p.ulFromProto, p.ulToDcs, _.ul.address, s"ul_$pidx")
    }
  }
}
