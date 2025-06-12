package pionic.host

import pionic._

import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4

/** Service to bind host-side interfaces to the main packet processing pipeline. */
trait DatapathService {
  /** AXI master to RX packet buffer in [[PacketBuffer]] */
  def rxPktBuffer: Axi4
  /** AXI master to TX packet buffer in [[PacketBuffer]] */
  def txPktBuffer: Axi4

  /** Host ready to process next request (for statistics) */
  def hostRxReq: Bool
  def
}
