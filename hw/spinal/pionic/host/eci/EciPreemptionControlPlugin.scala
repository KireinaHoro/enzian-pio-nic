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
case class PreemptionControlCl()(implicit c: Config) extends Bundle {
  val busy, ready = Bool()
  val rxParity, txParity = Bool()
  val pid = Bits(Widths.pidw bits)
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

    // TODO
    lci.setIdle()
    ul.setIdle()
    lcia.setBlocked()

    val preemptCtrlCl = Reg(PreemptionControlCl()) init 0
    assert(requiredAddrSpace >= preemptCtrlCl.getWidth, "not all information fits in the preemption control CL")

    // Preemption request from scheduler.  When issued (valid === True), the scheduler
    // must have:
    // - blocked off new requests from entering the granted buffer (i.e. the
    //   datapath will not receive a new packet)
    // - updated queue mapping to pop from new PID queue
    //
    // When [[preemptReq]] is acknowledged (valid && ready === True), the kernel would have
    // signalled that they finished all steps and will immediately return to user space.
    // The scheduler can allow
    val preemptReq = Stream(PID())
    preemptReq.setBlocked()

    val proto = host.list[EciDecoupledRxTxProtocol].apply(coreID)

    awaitBuild()

    // Parity bits to serve to the kernel in the preemption control cacheline.  These
    // are also accessible directly as register reads (exposed originally for debugging)
    // in [[EciDecoupledRxTxProtocol]] -- this optimization will save the extra I/O roundtrip
    val rxParity = proto.logic.rxCurrClIdx
    val txParity = proto.logic.txCurrClIdx

    // Preemption request to forward to the datapath.  Issued AFTER clearing READY bit
    // to ACK the pending packet (if any) and drop ctrl (& data, if any) CLs from L2 cache
    val rxProtoPreemptReq = proto.preemptReq
    rxProtoPreemptReq.setIdle()

    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        when (preemptReq.valid) {

          goto(unsetReadyReq)
        }
      }
      val unsetReadyReq: State = new State {

      }
      val unsetReady: State = new State {

      }
      val preemptDataPath: State = new State {

      }
      val readBusyReq: State = new State {

      }
      val readBusy: State = new State {

      }
      val issueIpi: State = new State {

      }
      val setReady: State = new State {

      }
      val waitKernelPreemptFinished: State = new State {

      }
    }
  }

  override def preemptReq: Stream[PID] = logic.preemptReq
  def driveDcsBus(bus: Axi4, lci: Stream[Bits], lcia: Stream[Bits], ul: Stream[Bits]) = {
    bus.read(controlClAddr, logic.preemptCtrlCl)

    lci  << logic.lci
    ul   << logic.ul
    lcia >> logic.lcia
  }
}
