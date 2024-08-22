package pionic.host

import jsteward.blocks.misc.RegBlockReadBack
import spinal.lib._
import pionic._
import jsteward.blocks.misc.sim.BigIntRicher
import org.pcap4j.packet.{EthernetPacket, UdpPacket}
import pionic.sim._
import spinal.core.{IntToBuilder, roundUp}
import spinal.lib.bus.amba4.axi.sim.Axi4Master

package object pcie {
  def readRxPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[PacketDescSim] = {
    val numBytes = roundUp(c[Int]("host packet desc width"), 8) / 8
    val data = master.read(coreBlock("hostRx"), numBytes).bytesToBigInt
    (data & 1).toInt match {
      case 0 => None
      case 1 => Some(PacketDescSim.fromBigInt(data >> 1))
    }
  }

  implicit class PacketDescBytesRicher(lb: List[Byte])(implicit c: ConfigDatabase) {
    def toTxPacketDesc = {
      PacketDescSim.fromBigInt(lb.bytesToBigInt >> 1)
    }
  }

  sealed abstract class PacketDescSim {
    val addr, size: BigInt
    /** generate a [[pionic.PacketBufDesc]] for freeing */
    def toAck(implicit c: ConfigDatabase): BigInt = {
      val aw = c[Int]("pkt buf addr width")
      val lw = c[Int]("pkt buf len width")
      BigInt(0)
        .assignToRange(aw - 1 downto 0, addr)
        .assignToRange(aw + lw - 1 downto aw, size)
    }
  }
  case class BypassPacketDescSim(addr: BigInt, size: BigInt, ty: BigInt, hdr: BigInt) extends PacketDescSim {
    // decode hdr as actual ethernet packet
    private val hdrBytes = hdr.toByteArray
    val pkt = EthernetPacket.newPacket(hdrBytes, 0, hdrBytes.length)
  }
  case class OncRpcCallPacketDescSim(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, args: BigInt) extends PacketDescSim {
    // TODO
  }

  object PacketDescSim {
    def fromBigInt(v: BigInt)(implicit c: ConfigDatabase) = {
      val aw = c[Int]("pkt buf addr width")
      val lw = c[Int]("pkt buf len width")
      val htw = c[Int]("host packet desc type width")
      val dw = c[Int]("host packet desc width")

      val addr = v(aw - 1 downto 0).toInt
      val size = v(aw + lw - 1 downto aw).toInt
      val ty = v(htw + aw + lw - 1 downto aw + lw).toInt
      val data = v(dw + htw + aw + lw - 1 downto htw + aw + lw)
      ty match {
        case 0 => throw new RuntimeException("error host packet desc received")
        case 1 =>
          val btw = c[Int]("proto packet desc type width")
          BypassPacketDescSim(addr, size, data(btw - 1 downto 0), data(dw - 1 downto btw))
        case 2 =>
          val argsWidth = c[Int]("max onc rpc inline bytes") * 8
          OncRpcCallPacketDescSim(addr, size, data(63 downto 0), data(95 downto 64), data(argsWidth + 95 downto 96))
      }
    }
  }
}
