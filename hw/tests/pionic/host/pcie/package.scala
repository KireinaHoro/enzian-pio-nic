package pionic.host

import jsteward.blocks.misc.RegBlockReadBack
import spinal.lib._
import pionic._
import jsteward.blocks.misc.sim.BigIntRicher
import org.pcap4j.packet.EthernetPacket
import spinal.core.{IntToBuilder, roundUp}
import spinal.lib.bus.amba4.axi.sim.Axi4Master

package object pcie {
  def aw(implicit c: ConfigDatabase) = c[Int]("pkt buf addr width")
  def lw(implicit c: ConfigDatabase) = c[Int]("pkt buf len width")
  def tw(implicit c: ConfigDatabase) = c[Int]("host packet desc type width")
  def dw(implicit c: ConfigDatabase) = c[Int]("host packet desc data width")

  def readRxPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[HostPacketDescSim] = {
    val numBytes = roundUp(aw+lw+tw+dw, 8) / 8
    val data = master.read(coreBlock("hostRx"), numBytes).bytesToBigInt
    (data & 1).toInt match {
      case 0 => None
      case 1 => Some(HostPacketDescSim.fromBigInt(data >> 1))
    }
  }

  def readTxBufDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[PacketBufDescSim] = {
    val data = master.read(coreBlock("hostTx"), 8).bytesToBigInt
    if (data(0)) {
      Some(PacketBufDescSim(data(aw downto 1), data(aw + lw downto aw + 1)))
    } else {
      None
    }
  }

  sealed abstract class HostPacketDescSim {
    // fields of HostPacketDesc()
    val addr, size: BigInt
    def ty: Int
    def data: BigInt

    /** generate a [[pionic.PacketBufDesc]] for freeing */
    def toRxAck(implicit c: ConfigDatabase): BigInt = {
      BigInt(0)
        .assignToRange(aw-1     downto 0, addr)
        .assignToRange(aw+lw-1  downto aw, size)
    }
    def toTxAck(implicit c: ConfigDatabase): BigInt = {
      BigInt(0)
        .assignToRange(tw-1          downto 0, ty)
        .assignToRange(tw+dw-1       downto tw, data)
        .assignToRange(tw+dw+aw-1    downto tw+dw, addr)
        .assignToRange(tw+dw+aw+lw-1 downto tw+dw+aw, size)
    }
  }
  case class PacketBufDescSim(addr: BigInt, size: BigInt) extends HostPacketDescSim {
    // XXX: transition use only when we don't have the encoder pipeline
    def ty = 0 // "error" host desc type
    def data = BigInt(0)
  }
  case class BypassPacketDescSim(addr: BigInt, size: BigInt, packetType: BigInt, packetHdr: BigInt)(implicit c: ConfigDatabase) extends HostPacketDescSim {
    // decode hdr as actual ethernet packet
    // XXX: we won't actually have endianness problem on the CPU, since to bypass is a simple memcpy
    //      however, since SpinalSim passes vectors in little endian, we still need to reverse
    private val hdrBytes = packetHdr.toByteArray.reverse
    val pkt = EthernetPacket.newPacket(hdrBytes, 0, hdrBytes.length)
    // TODO: implement when we have the TX encoder pipeline
    def ty = 1
    def data = {
      val btw = c[Int]("proto packet desc type width")
      BigInt(0)
        .assignToRange(btw-1 downto 0, packetType)
        .assignToRange(dw-1  downto btw, packetHdr) }
  }
  case class OncRpcCallPacketDescSim(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, args: BigInt)(implicit c: ConfigDatabase) extends HostPacketDescSim {
    // TODO: implement when we have the TX encoder pipeline
    // XXX: TX call is only needed for nested RPC calls
    def ty = 2
    def data = {
      val argw = c[Int]("max onc rpc inline bytes") * 8
      BigInt(0)
        .assignToRange(63      downto 0, funcPtr)
        .assignToRange(95      downto 64, xid)
        .assignToRange(argw+95 downto 96, args)
    }
  }
  case class OncRpcReplyPacketDescSim(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, rets: BigInt)(implicit c: ConfigDatabase) extends HostPacketDescSim {
    // TODO: do we have enough info here?
    // TODO: implement when we have the TX encoder pipeline
    // XXX: RX reply is only needed for nested RPC calls
    def ty = 3
    def data = {
      val argw = c[Int]("max onc rpc inline bytes") * 8
      BigInt(0)
        .assignToRange(63      downto 0, funcPtr)
        .assignToRange(95      downto 64, xid)
        .assignToRange(argw+95 downto 96, rets)
    }
  }

  object HostPacketDescSim {
    def fromBigInt(v: BigInt)(implicit c: ConfigDatabase) = {
      val ty =   v(tw-1          downto 0).toInt
      val data = v(tw+dw-1       downto tw)
      val addr = v(tw+dw+aw-1    downto tw+dw).toInt
      val size = v(tw+dw+aw+lw-1 downto tw+dw+aw).toInt
      ty match {
        case 0 => throw new RuntimeException("error host packet desc received")
        case 1 =>
          val btw = c[Int]("proto packet desc type width")
          BypassPacketDescSim(addr, size,
            data(btw-1 downto 0),
            data(dw-1  downto btw))
        case 2 =>
          val argsWidth = c[Int]("max onc rpc inline bytes") * 8
          OncRpcCallPacketDescSim(addr, size,
            data(63 downto 0),
            data(95 downto 64),
            data(argsWidth + 95 downto 96))
      }
    }
  }
}
