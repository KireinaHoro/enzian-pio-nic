package pionic.host

import jsteward.blocks.misc.RegBlockReadBack
import spinal.lib._
import pionic._
import jsteward.blocks.misc.sim.BigIntRicher
import org.pcap4j.packet.EthernetPacket
import spinal.core.{IntToBuilder, roundUp}
import spinal.lib.bus.amba4.axi.sim.Axi4Master

package object pcie {
  def readRxPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[HostPacketDescSim] = {
    val numBytes = roundUp(c[Int]("host packet desc width"), 8) / 8
    val data = master.read(coreBlock("hostRx"), numBytes).bytesToBigInt
    (data & 1).toInt match {
      case 0 => None
      case 1 => Some(HostPacketDescSim.fromBigInt(data >> 1))
    }
  }

  def readTxBufDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[PacketBufDescSim] = {
    val data = master.read(coreBlock("hostTx"), 8).bytesToBigInt
    if (data(0)) {
      val aw = c[Int]("pkt buf addr width")
      val lw = c[Int]("pkt buf len width")
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
      val aw = c[Int]("pkt buf addr width")
      val lw = c[Int]("pkt buf len width")
      BigInt(0)
        .assignToRange(aw-1     downto 0, addr)
        .assignToRange(aw+lw-1  downto aw, size)
    }
    def toTxAck(implicit c: ConfigDatabase): BigInt = {
      val aw = c[Int]("pkt buf addr width")
      val lw = c[Int]("pkt buf len width")
      val htw = c[Int]("host packet desc type width")
      val dw = c[Int]("host packet desc width")
      BigInt(0)
        .assignToRange(htw-1          downto 0, ty)
        .assignToRange(htw+dw-1       downto htw, data)
        .assignToRange(htw+dw+aw-1    downto htw+dw, addr)
        .assignToRange(htw+dw+aw+lw-1 downto htw+dw+aw, size)
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
      val dw = c[Int]("host packet desc width")
      val btw = c[Int]("proto packet desc type width")
      BigInt(0)
        .assignToRange(btw-1 downto 0, packetType)
        .assignToRange(dw-1  downto btw, packetHdr)
    }
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
      val aw = c[Int]("pkt buf addr width")
      val lw = c[Int]("pkt buf len width")
      val htw = c[Int]("host packet desc type width")
      val dw = c[Int]("host packet desc width")

      val addr = v(aw-1           downto 0).toInt
      val size = v(aw+lw-1        downto aw).toInt
      val ty =   v(aw+lw+htw-1    downto aw+lw).toInt
      val data = v(aw+lw+htw+dw-1 downto aw+lw+htw)
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
