package pionic.sim

import jsteward.blocks.misc.sim.BigIntRicher
import org.pcap4j.packet.EthernetPacket
import pionic.ConfigDatabase
import spinal.core.IntToBuilder

trait HostPacketDescSim {
  def ty: Int
}

trait ErrorPacketDescSim { this: HostPacketDescSim =>
  // XXX: transition use only when we don't have the encoder pipeline
  override final def ty = 0
}

trait BypassPacketDescSim { this: HostPacketDescSim =>
  def packetType: BigInt
  def packetHdr: BigInt
  implicit def c: ConfigDatabase

  // decode hdr as actual ethernet packet
  // XXX: we won't actually have endianness problem on the CPU, since to bypass
  //      is a simple memcpy; however, since SpinalSim passes vectors in little
  //      endian, we still need to reverse
  // XXX: we should always use the max possible length to avoid losing zero bytes
  //      at the end of packet header
  private val hdrMaxLen = pionic.Widths.bphw / 8
  private val hdrBytes = packetHdr.toByteArray.reverse.padTo(hdrMaxLen, 0.toByte)
  val hdrP4jPacket = EthernetPacket.newPacket(hdrBytes, 0, hdrMaxLen)

  override final def ty = 1
}

trait OncRpcCallPacketDescSim { this: HostPacketDescSim =>
  // XXX: TX call is only needed for nested RPC calls
  def funcPtr: BigInt
  def xid: BigInt
  def args: BigInt
  override final def ty = 2
}

trait OncRpcReplyPacketDescSim { this: HostPacketDescSim =>
  // XXX: RX reply is only needed for nested RPC calls
  def funcPtr: BigInt
  def xid: BigInt
  def rets: BigInt
  override final def ty = 3
}
