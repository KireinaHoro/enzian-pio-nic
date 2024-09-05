package pionic.host.pcie

import jsteward.blocks.misc.sim.BigIntRicher
import pionic.ConfigDatabase
import pionic.sim._
import spinal.core.IntToBuilder

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
      case 3 =>
        OncRpcReplyPacketDescSimPcie(addr, size,
          data(63 downto 0),
          data(95 downto 64),
          data(oargw + 95 downto 96))
    }
  }
}

/** only used when Tx pipeline is not implemented */
case class ErrorPacketDescSimPcie(addr: BigInt, size: BigInt) extends PcieHostPacketDescSim with ErrorPacketDescSim
case class BypassPacketDescSimPcie(addr: BigInt, size: BigInt, packetType: BigInt, packetHdr: BigInt) extends PcieHostPacketDescSim with BypassPacketDescSim
case class OncRpcCallPacketDescSimPcie(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, args: BigInt) extends PcieHostPacketDescSim with OncRpcCallPacketDescSim
case class OncRpcReplyPacketDescSimPcie(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, rets: BigInt) extends PcieHostPacketDescSim with OncRpcReplyPacketDescSim
