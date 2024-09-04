package pionic.host

import jsteward.blocks.misc.RegBlockReadBack
import spinal.lib._
import pionic._
import jsteward.blocks.misc.sim.BigIntRicher
import org.pcap4j.packet.EthernetPacket
import pionic.sim._
import spinal.core.{IntToBuilder, roundUp}
import spinal.lib.bus.amba4.axi.sim.Axi4Master

package object pcie {
  def readRxPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[PcieHostPacketDescSim] = {
    import Widths._
    val numBytes = roundUp(aw+lw+tw+dw, 8) / 8
    val data = master.read(coreBlock("hostRx"), numBytes).bytesToBigInt
    (data & 1).toInt match {
      case 0 => None
      case 1 => Some(PcieHostPacketDescSim.fromBigInt(data >> 1))
    }
  }

  def readTxBufDesc(master: Axi4Master, coreBlock: RegBlockReadBack)(implicit c: ConfigDatabase): Option[PacketBufDescSimPcie] = {
    import Widths._
    val data = master.read(coreBlock("hostTx"), 8).bytesToBigInt
    if (data(0)) {
      Some(PacketBufDescSimPcie(data(aw downto 1), data(aw + lw downto aw + 1)))
    } else {
      None
    }
  }

  sealed abstract class PcieHostPacketDescSim extends HostPacketDescSim {
    import Widths._

    // fields of HostPacketDesc()
    val addr, size: BigInt

    /** generate a [[pionic.PacketBufDesc]] for freeing */
    def toRxAck(implicit c: ConfigDatabase): BigInt = {
      BigInt(0)
        .assignToRange(aw-1     downto 0, addr)
        .assignToRange(aw+lw-1  downto aw, size)
    }
    def toTxAck(implicit c: ConfigDatabase): BigInt = {
      BigInt(0)
        // FIXME: alignment on CPU?
        .assignToRange(aw-1          downto 0, addr)
        .assignToRange(aw+lw-1       downto aw, size)
        .assignToRange(aw+lw+tw-1    downto aw+lw, ty)
        .assignToRange(aw+lw+tw+dw-1 downto aw+lw+tw, data)
        // XXX: set one extra bit to trigger writing to last word
        .assignToRange(aw+lw+tw+dw   downto aw+lw+tw+dw, 1)
    }
  }

  object PcieHostPacketDescSim {
    def fromBigInt(v: BigInt)(implicit c: ConfigDatabase) = {
      import Widths._
      val addr = v(aw-1          downto 0).toInt
      val size = v(aw+lw-1       downto aw).toInt
      val ty =   v(aw+lw+tw-1    downto aw+lw).toInt
      val data = v(aw+lw+tw+dw-1 downto aw+lw+tw)
      ty match {
        case 0 => throw new RuntimeException("error host packet desc received")
        case 1 =>
          BypassPacketDescSimPcie(addr, size,
            data(bptw-1 downto 0),
            data(dw-1   downto bptw))
        case 2 =>
          OncRpcCallPacketDescSimPcie(addr, size,
            data(63 downto 0),
            data(95 downto 64),
            data(oargw + 95 downto 96))
      }
    }
  }

  case class PacketBufDescSimPcie(addr: BigInt, size: BigInt) extends PcieHostPacketDescSim with ErrorPacketDescSim
  case class BypassPacketDescSimPcie(addr: BigInt, size: BigInt, packetType: BigInt, packetHdr: BigInt) extends PcieHostPacketDescSim with BypassPacketDescSim
  case class OncRpcCallPacketDescSimPcie(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, args: BigInt) extends PcieHostPacketDescSim with OncRpcCallPacketDescSim
  case class OncRpcReplyPacketDescSimPcie(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, rets: BigInt) extends PcieHostPacketDescSim with OncRpcReplyPacketDescSim
}
