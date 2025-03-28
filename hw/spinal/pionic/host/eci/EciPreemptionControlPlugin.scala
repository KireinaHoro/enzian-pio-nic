package pionic.host.eci

import jsteward.blocks.eci.EciCmdDefs
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi.Axi4SlaveFactory

/**
  * Backing storage for preemption control cacheline.  Fits the following information to pass to host:
  *  - READY & BUSY bits
  *  - RX & TX parity bits
  *  - next PID
  */
case class PreemptionControlCl()(implicit c: ConfigDatabase) extends Bundle {
  val busy = Bool()
  val xb7 = B("7'x0")
  val ready = Bool()
  val xb7_2 = B("7'x0")
  val rxParity, txParity = Bool()
  val pid = PID()
}

/**
  * Preemption control plugin for ECI.  Interfaces with [[EciInterfacePlugin]] to issue interrupts to CPU as IPI.
  *
  * READY/BUSY flags are implemented in a **preemption control cacheline** that's normally pinned in the L2 cache.
  * We take parity flags as input, to return them inside this cacheline as well.
  */
class EciPreemptionControlPlugin(val coreID: Int) extends PreemptionService {
  withPrefix(s"core_$coreID")

  val requiredAddrSpace = 0x80
  val controlClAddr = 0x0

  assert(coreID != 0, "bypass core does not need preemption control!")

  val logic = during setup new Area {
    // DCS interfaces
    val lci = Stream(EciCmdDefs.EciAddress)
    val lcia = Stream(EciCmdDefs.EciAddress)
    val ul = Stream(EciCmdDefs.EciAddress)

    lci.valid := False
    lci.payload := controlClAddr

    ul.payload := controlClAddr
    ul.valid := False

    lcia.setBlocked()
    when (lcia.valid) {
      assert(lcia.payload === controlClAddr, "LCIA does not match control address!")
    }

    val preemptCtrlCl = Reg(PreemptionControlCl())
    assert(requiredAddrSpace >= preemptCtrlCl.getBitsWidth, "not all information fits in the preemption control CL")

    // Preemption request from scheduler.  When issued (valid === True), the scheduler
    // must have:
    // - blocked off new requests from entering the granted buffer (i.e. the
    //   datapath will not receive a new packet)
    // - updated queue mapping to pop from new PID queue
    //
    // When [[preemptReq]] is acknowledged (valid && ready === True), the kernel would have
    // signalled that they finished all steps and will immediately return to user space.
    // The scheduler can then allow new requests into the granted buffer.
    val preemptReq = Stream(PID())
    preemptReq.setBlocked()

    val proto = host.list[EciDecoupledRxTxProtocol].apply(coreID)

    awaitBuild()

    // Parity bits to serve to the kernel in the preemption control cacheline.  These
    // are also accessible directly as register reads (exposed originally for debugging)
    // in [[EciDecoupledRxTxProtocol]] -- this optimization will save the extra I/O roundtrip
    preemptCtrlCl.rxParity := proto.logic.rxCurrClIdx
    preemptCtrlCl.txParity := proto.logic.txCurrClIdx

    // Preemption request to forward to the datapath.  Issued AFTER clearing READY bit
    // to ACK the pending packet (if any) and drop ctrl (& data, if any) CLs from L2 cache
    val rxProtoPreemptReq = proto.preemptReq
    rxProtoPreemptReq.setIdle()

    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          when (preemptReq.valid) {
            lci.valid := True
            when (lci.ready) {
              goto(unsetReady)
            }
          }
        }
      }
      val unsetReady: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.valid) {
            preemptCtrlCl.ready := False
            when (preemptCtrlCl.busy) {
              // still busy, give the CPU a chance to de-assert busy
              goto(unsetReadyUnlock)
            } otherwise {
              // already not busy, don't unlock now
              goto(preemptDataPath)
            }
          }
        }
      }
      val unsetReadyUnlock: State = new State {
        whenIsActive {
          ul.valid := True
          when (ul.ready) {
            goto(preemptDataPath)
          }
        }
      }
      val preemptDataPath: State = new State {
        whenIsActive {
          rxProtoPreemptReq.valid := True
          when (rxProtoPreemptReq.ready) {
            when (preemptCtrlCl.busy) {
              // busy and we unlocked -- poll again
              goto(readBusyReq)
            } otherwise {
              // busy already low but locked, we can issue IPI
              goto(issueIpi)
            }
          }
        }
      }
      val readBusyReq: State = new State {
        whenIsActive {
          lci.valid := True
          when (lci.ready) {
            goto(readBusy)
          }
        }
      }
      val readBusy: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.valid) {
            when (preemptCtrlCl.busy) {
              // still busy, unlock and poll again
              goto(readBusyUnlock)      
            } otherwise {
              // not busy anymore, but locked -- issue IPI
              goto(issueIpi)
            }
          }
        }
      }
      val readBusyUnlock: State = new State {
        whenIsActive {
          ul.valid := True
          when (ul.ready) {
            goto(readBusyReq)
          }
        }
      }
      val issueIpi: State = new State {
        whenIsActive {
          // TODO: how to issue IPI?
          goto(setReady)
        }
      }
      val setReady: State = new State {
        whenIsActive {
          preemptCtrlCl.ready := True
          // we are guaranteed to be in kernel now, release PID
          preemptCtrlCl.pid := preemptReq.payload
          ul.valid := True
          when (ul.ready) {
            goto(waitKernelPreemptFinished)
          }
        }
      }
      val waitKernelPreemptFinished: State = new State {
        whenIsActive {
          // TODO: what to wait for here?  e.g. an I/O read/write that blocks?
          when (True) {
            preemptReq.ready := True
            // no need to clear preemptCtrlCl.pid -- kernel will clear and then pin to L2
            goto(idle)
          }
        }
      }
    }
  }

  override def preemptReq: Stream[PID] = logic.preemptReq
  def driveDcsBus(bus: Axi4, lci: Stream[Bits], lcia: Stream[Bits], ul: Stream[Bits]) = {
    val busCtrl = Axi4SlaveFactory(bus)
    busCtrl.readAndWrite(logic.preemptCtrlCl, controlClAddr)

    lci  << logic.lci
    ul   << logic.ul
    lcia >> logic.lcia
  }
}
