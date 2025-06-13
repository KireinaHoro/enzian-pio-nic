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
 * Does not handle actual packet data (in the second half-cacheline and into the overflow cachelines;
 * that is handled inside [[pionic.host.eci.EciInterfacePlugin]].
 */
trait EciPioProtocol extends DatapathPlugin {
  /** DCS commands */
  val lci = during setup Stream(EciCmdDefs.EciAddress)
  val lcia = during setup Stream(EciCmdDefs.EciAddress)
  val ul = during setup Stream(EciCmdDefs.EciAddress)

  /** datapath interfaces */
  val hostTx = during setup Stream(PacketBufDesc())
  val hostTxAck = during setup Stream(HostReq())
  val hostRx = during setup Stream(HostReq())
  val hostRxAck = during setup Stream(PacketBufDesc())
  val hostRxReq = during setup Bool()

  // dcs busCtrl for control cacheline
  def driveDcsBus(bus: Axi4, rxPktBufAxi: Axi4, txPktBuffer: Axi4)

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc)

  def sizePerCore: BigInt

  // cascaded: preemption needs to clear states in the data path as well
  def preemptReq: Event
}
