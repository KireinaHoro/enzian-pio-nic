package lauberhorn.host.eci

import jsteward.blocks.eci.{DcsAppLclInterface, EciCmdDefs}
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn._
import lauberhorn.host.{DatapathPlugin, HostReq}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinal.lib.bus.misc.{BusSlaveFactory, SizeMapping}

/** PIO cacheline protocol state machine interface.
  *
  * Keeps track of the two cache-line 2F2F state machine:
  *  - responds to AXI requests from the DCS
  *  - issues cache line state changes via the RX/TX LCI, LCIA, and UL interfaces
  *  - produces/consumes packet descriptors to/from [[DmaControlPlugin]] (bypass) and [[Scheduler]] (RX worker)
  */
trait EciPioProtocol extends DatapathPlugin {
  /** RX/TX DCS commands */
  val rxLcl = during setup DcsAppLclInterface()
  val txLcl = during setup DcsAppLclInterface()

  /**
    * Create access ports for protocol elements.  Returns tuple of two lists of AXI nodes:
    * - first list contains slave nodes to be accessed by the DCS and their mappings
    * - second list contains master nodes to access the packet buffer
    */
  def makeAccessPorts(dcsSlaveConfig: Axi4Config, memMasterConfig: Axi4Config): (Seq[(Axi4, SizeMapping)], Seq[Axi4])

  /** Drive control registers. */
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit

  /** Size of the DCS-facing address map for this protocol. */
  def sizePerCore: BigInt

  /** Request to preempt a scheduled handler.
    *
    * FIXME: is this allowed to happen to a handler that's actually __running__?
    *
    * This is forwarded from the [[lauberhorn.host.PreemptionService]] to allow the protocol
    * to clean up state in the data path.
    */
  def preemptReq: Event
}
