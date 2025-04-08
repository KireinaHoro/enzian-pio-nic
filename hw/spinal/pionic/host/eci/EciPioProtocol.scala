package pionic.host.eci

import jsteward.blocks.eci.EciCmdDefs
import jsteward.blocks.misc.RegBlockAlloc
import pionic._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.misc.BusSlaveFactory

/** PIO cacheline protocol state machine interface.
 * Does not handle actual packet data (in the second half-cacheline and into the overflow cachelines;
 * that is handled inside [[pionic.host.eci.EciInterfacePlugin]].
 */
trait EciPioProtocol extends PioNicPlugin {
  // DCS commands
  val lci = during setup Stream(EciCmdDefs.EciAddress)
  val lcia = during setup Stream(EciCmdDefs.EciAddress)
  val ul = during setup Stream(EciCmdDefs.EciAddress)

  // core control interface
  val hostTx = during setup Stream(PacketBufDesc())
  val hostTxAck = during setup Stream(HostPacketDesc())
  val hostRx = during setup Stream(HostPacketDesc())
  val hostRxAck = during setup Stream(PacketBufDesc())
  val hostRxReq = during setup Bool()

  // dcs busCtrl for control cacheline
  def driveDcsBus(bus: Axi4, rxPktBuffer: Mem[Bits], txPktBuffer: Mem[Bits])

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc)

  def sizePerCore: BigInt

  // preempt request interface
  def preemptReq: Event
}
