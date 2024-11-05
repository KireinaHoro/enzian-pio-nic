package pionic.sim

import jsteward.blocks.misc.sim.BigIntRicher
import org.pcap4j.packet.EthernetPacket
import pionic.ConfigDatabase
import spinal.core.IntToBuilder

trait HostPacketDescSim {
  def ty: Int
  def data(implicit c: ConfigDatabase): BigInt
}

trait ErrorPacketDescSim { this: HostPacketDescSim =>
  // XXX: transition use only when we don't have the encoder pipeline
  override final def ty = 0
  override final def data(implicit c: ConfigDatabase) = BigInt(0)
}

trait BypassPacketDescSim { this: HostPacketDescSim =>
  import pionic.Widths._
  def packetType: BigInt
  def packetHdr: BigInt

  // decode hdr as actual ethernet packet
  // XXX: we won't actually have endianness problem on the CPU, since to bypass is a simple memcpy
  //      however, since SpinalSim passes vectors in little endian, we still need to reverse
  private val hdrBytes = packetHdr.toByteArray.reverse
  val pkt = EthernetPacket.newPacket(hdrBytes, 0, hdrBytes.length)

  override final def ty = 1
  override final def data(implicit c: ConfigDatabase) = {
    BigInt(0)
      .assignToRange(bptw-1 downto 0, packetType)
      .assignToRange(dw-1   downto bptw, packetHdr)
  }
}

trait OncRpcCallPacketDescSim { this: HostPacketDescSim =>
  import pionic.Widths._
  // XXX: TX call is only needed for nested RPC calls
  def funcPtr: BigInt
  def xid: BigInt
  def args: BigInt
  override final def ty = 2
  override final def data(implicit c: ConfigDatabase) = {
    BigInt(0)
      .assignToRange(63 downto 0, funcPtr)
      .assignToRange(95 downto 64, xid)
      .assignToRange(oargw + 95 downto 96, args)
  }
}

trait OncRpcReplyPacketDescSim { this: HostPacketDescSim =>
  import pionic.Widths._
  // XXX: RX reply is only needed for nested RPC calls
  def funcPtr: BigInt
  def xid: BigInt
  def rets: BigInt
  override final def ty = 3
  override final def data(implicit c: ConfigDatabase) = {
    BigInt(0)
      .assignToRange(63 downto 0, funcPtr)
      .assignToRange(95 downto 64, xid)
      .assignToRange(oargw + 95 downto 96, rets)
  }
}
