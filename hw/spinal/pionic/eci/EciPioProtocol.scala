package pionic.eci

import jsteward.blocks.eci.EciCmdDefs
import pionic.{PacketDesc, PacketLength, PioNicConfig}
import spinal.core._
import spinal.core.fiber.Handle
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.misc.plugin.FiberPlugin

/** PIO cacheline protocol state machine interface.
 * Does not handle actual packet data (in the second half-cacheline and into the overflow cachelines;
 * that is handled inside [[pionic.eci.EciInterfacePlugin]].
 */
trait EciPioProtocol extends FiberPlugin {
  implicit val config: PioNicConfig

  // DCS commands
  val lci = during setup Stream(EciCmdDefs.EciAddress)
  val lcia = during setup Stream(EciCmdDefs.EciAddress)
  val ul = during setup Stream(EciCmdDefs.EciAddress)

  // core control interface
  val hostTx = during setup Stream(PacketDesc())
  val hostTxAck = during setup Stream(PacketLength())
  val hostRxNext = during setup Stream(PacketDesc())
  val hostRxNextAck = during setup Stream(PacketDesc())
  val hostRxNextReq = during setup Bool()

  // dcs busCtrl for control cacheline
  def driveDcsBus(bus: Axi4, rxPktBuffer: Mem[Bits], txPktBuffer: Mem[Bits])

  def sizePerCore: BigInt
}
