package pionic.host.eci

import jsteward.blocks.eci.EciCmdDefs
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi.Axi4SlaveFactory
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.RO
import jsteward.blocks.misc.RegBlockAlloc

/**
  * Backing storage for preemption control cacheline.  Fits the following information to pass to host:
  *  - READY & BUSY bits
  */
case class PreemptionControlCl()(implicit c: ConfigDatabase) extends Bundle {
  val busy = Bool()
  val ready = Bool()
}

/**
  * Extra information to pass to kernel.  This register will be read by kernel; the read also functions
  * as an ACK for the IPI.  Information included here:
  *  - RX & TX parity bits
  *  - whether the process was force-killed
  *  - next PID
  */
case class IpiAckReg()(implicit c: ConfigDatabase) extends Bundle {
  // TODO: Mackerel def
  val rxParity, txParity = Bool()
  val killed = Bool()
  val xb5 = Bits(5 bits)
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

  lazy val csr = host[GlobalCSRPlugin].logic.get
  
  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc) = {
    val ipiAckAddr = alloc("ipiAck", attr = RO, readSensitive = true)
    busCtrl.read(logic.ipiAck, ipiAckAddr)
     
    busCtrl.onRead(ipiAckAddr) {
      logic.ipiAckReadReq := True
    }
  }

  val requiredAddrSpace = 0x80
  val controlClAddr = 0x0

  assert(coreID != 0, "bypass core does not need preemption control!")

  val logic = during setup new Area {
    val proto = host.list[EciDecoupledRxTxProtocol].apply(coreID)

    // DCS interfaces
    val lci = Stream(EciCmdDefs.EciAddress)
    val lcia = Stream(EciCmdDefs.EciAddress)
    val ul = Stream(EciCmdDefs.EciAddress)
    
    lci.assertPersistence()
    ul.assertPersistence()
    
    // TODO: interface to ECI gateway for IPI

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

    val ipiAck = Reg(IpiAckReg())
    val ipiAckReadReq = CombInit(False)

    awaitBuild()

    // Parity bits to serve to the kernel in the preemption control cacheline.  These
    // are also accessible directly as register reads (exposed originally for debugging)
    // in [[EciDecoupledRxTxProtocol]] -- this optimization will save the extra I/O roundtrip
    ipiAck.rxParity := proto.logic.rxCurrClIdx
    ipiAck.txParity := proto.logic.txCurrClIdx
    // TODO: drive killed with counter output
    ipiAck.killed := False
    ipiAck.xb5 := 0
    ipiAck.pid := preemptReq.payload

    // Preemption request to forward to the datapath.  Issued AFTER clearing READY bit
    // to ACK the pending packet (if any) and drop ctrl (& data, if any) CLs from L2 cache
    val rxProtoPreemptReq = proto.preemptReq
    rxProtoPreemptReq.setIdle()
    
    // Timer for CPU to exit critical section (unset BUSY), before the FPGA kills the
    // process (sets killed === True) before sending IPI
    val preemptTimer = Counter(regWidth bits)

    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          preemptTimer.clear()
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
            goto(unlockCheckBusy)
          }
        }
      }
      val unlockCheckBusy: State = new State {
        whenIsActive {
          preemptTimer.increment()
          ul.valid := True
          when (ul.ready) {
            when (preemptCtrlCl.busy) {
              // busy when we unset ready
              when (preemptTimer >= csr.ctrl.preemptCritSecTimeout) {
                // timer has expired -- kill
                ipiAck.killed := True
                goto(issueIpi)
              } otherwise {
                // timer has not expired -- poll again
                goto(readBusyReq)
              }
            } otherwise {
              // busy already low but locked, we can issue IPI
              goto(issueIpi)
            }
          }
        }
      }
      val readBusyReq: State = new State {
        whenIsActive {
          preemptTimer.increment()
          lci.valid := True
          when (lci.ready) {
            goto(readBusy)
          }
        }
      }
      val readBusy: State = new State {
        whenIsActive {
          preemptTimer.increment()
          lcia.freeRun()
          when (lcia.valid) {
            goto(unlockCheckBusy)
          }
        }
      }
      val issueIpi: State = new State {
        whenIsActive {
          // TODO: how to issue IPI?
          goto(ipiWaitAck)
        }
      }
      val ipiWaitAck: State = new State {
        whenIsActive {
          when (ipiAckReadReq) {
            // we can only trigger data path preemption once we are sure we are
            // in the kernel, or the old user thread might have a chance to
            // corrupt the clean state (e.g. sneak covert data in)
            goto(preemptDataPath)
          }
        }
      }
      val preemptDataPath: State = new State {
        whenIsActive {
          rxProtoPreemptReq.valid := True
          when (rxProtoPreemptReq.ready) {
            // set ready, which the kernel will poll to be 1 before it returns
            goto(setReadyReq)
          }
        }
      }
      val setReadyReq: State = new State {
        whenIsActive {
          lci.valid := True
          when (lci.ready) {
            goto(setReady)
          }
        }
      }
      val setReady: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.valid) {
            preemptCtrlCl.ready := True
            goto(unlockToIdle)
          }
        }
      }
      val unlockToIdle: State = new State {
        whenIsActive {
          ul.valid := True
          when (ul.ready) {
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
