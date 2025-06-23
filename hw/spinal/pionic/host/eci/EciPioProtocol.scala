package pionic.host.eci

import jsteward.blocks.eci.EciCmdDefs
import jsteward.blocks.misc.RegBlockAlloc
import pionic._
import pionic.host.{DatapathPlugin, HostReq}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.misc.BusSlaveFactory

/** PIO cacheline protocol state machine interface.
  *
  * Keeps track of the two cache-line 2F2F state machine:
  *  - responds to AXI requests from the DCS
  *  - issues cache line state changes via the [[lci]], [[lcia]], [[ul]] interfaces
  *  - produces/consumes packet descriptors to/from [[DmaControlPlugin]] (bypass) and [[Scheduler]] (RX worker)
  */
trait EciPioProtocol extends DatapathPlugin {
  /** DCS commands */
  val lci = during setup Stream(EciCmdDefs.EciAddress)
  val lcia = during setup Stream(EciCmdDefs.EciAddress)
  val ul = during setup Stream(EciCmdDefs.EciAddress)

  /**
    * Connect slave interface to the DCS interfaces.
    * @param bus DCS interface to respond to, demux'ed for this core
    * @param pktBufAxi access to [[PacketBuffer]], demux'ed for this core
    */
  def driveDcsBus(bus: Axi4, pktBufAxi: Axi4): Unit

  /** Drive control registers. */
  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit

  /** Size of the DCS-facing address map for this protocol. */
  def sizePerCore: BigInt

  /** Request to preempt a scheduled handler.
    *
    * FIXME: is this allowed to happen to a handler that's actually __running__?
    *
    * This is forwarded from the [[pionic.host.PreemptionService]] to allow the protocol
    * to clean up state in the data path.
    */
  def preemptReq: Event
}
