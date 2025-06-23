package pionic.host

import pionic._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4

/** Service to bind host-side interfaces to the main packet processing pipeline.  Implemented by the host datapath
  * modules (e.g. [[pionic.host.eci.EciPioProtocol]]) and consumed by [[Scheduler]] (for fully decoded requests)
  * or [[DmaControlPlugin]] directly (for bypass).
  */
trait DatapathService {
  def coreID: Int

  /** Host ready to process the next request (for statistics) */
  def hostRxReq: Bool
  /** Fully decoded and translated request to pass to host core */
  def hostRx: Stream[HostReq]
  /** Packet buffer slot to free up */
  def hostRxAck: Stream[PacketBufDesc]

  /** Buffer descriptor for the host to write a packet into */
  def hostTx: Stream[PacketBufDesc]
  /** Packet descriptor from the host core to send */
  def hostTxAck: Stream[HostReq]
}
