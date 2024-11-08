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

  // decode hdr as actual ethernet packet
  // XXX: we won't actually have endianness problem on the CPU, since to bypass is a simple memcpy
  //      however, since SpinalSim passes vectors in little endian, we still need to reverse
  private val hdrBytes = packetHdr.toByteArray.reverse
  val pkt = EthernetPacket.newPacket(hdrBytes, 0, hdrBytes.length)

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
