package pionic.host.eci

import jsteward.blocks.eci.EciCmdDefs
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4

/**
  * Preemption control plugin for ECI.  Interfaces with [[EciInterfacePlugin]] to issue interrupts to CPU as IPI.
  *
  * READY/BUSY flags are implemented in a **preemption control cacheline** that's normally pinned in the L2 cache.
  * We take parity flags as input, to return them inside this cacheline as well.
  */
class EciPreemptionControlPlugin(val coreID: Int) extends PreemptionService {
  withPrefix(s"core_$coreID")

  val requiredAddrSpace = 0x80

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

    val preemptReq = Stream(PID())

    val proto = host.list[EciDecoupledRxTxProtocol].apply(coreID)

    awaitBuild()

    val rxParity = proto.logic.rxCurrClIdx
    val txParity = proto.logic.txCurrClIdx

    val rxProtoPreemptReq = proto.preemptReq
    rxProtoPreemptReq.setIdle()
  }

  override def preemptReq: Stream[PID] = logic.preemptReq
  def driveDcsBus(bus: Axi4, lci: Stream[Bits], lcia: Stream[Bits], ul: Stream[Bits]) = {
    // TODO: connect bus
    bus.setBlocked()

    lci  << logic.lci
    ul   << logic.ul
    lcia >> logic.lcia
  }
}
