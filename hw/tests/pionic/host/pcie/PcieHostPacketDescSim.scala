package pionic.host.pcie

import jsteward.blocks.misc.sim.BigIntRicher
import pionic.ConfigDatabase
import pionic.sim._
import spinal.core.IntToBuilder

sealed abstract class PcieHostPacketDescSim extends HostPacketDescSim {
  import pionic.Widths._
  def addr: BigInt
  def size: BigInt
  def encode(implicit c: ConfigDatabase): BigInt

  /** generate a [[pionic.PacketBufDesc]] for freeing */
  def toRxAck(implicit c: ConfigDatabase): BigInt = {
    BigInt(0)
      .assignToRange(aw     downto 1, addr)
      .assignToRange(aw+lw  downto aw+1, size)
  }
  /** generate a [[PcieHostCtrlInfo]] for TX use */
  def toTxDesc(implicit c: ConfigDatabase): List[Byte] = {
    val b = BigInt(0)
      .assignToRange(aw          downto 1, addr)
      .assignToRange(aw+lw       downto aw+1, size)
      .assignToRange(aw+lw+tw    downto aw+lw+1, ty)
      .assignToRange(aw+lw+tw+dw downto aw+lw+tw+1, encode)
    // make sure we encode all zero bytes as well
    spinal.core.sim.SimBigIntPimper(b).toBytes(aw+lw+tw+dw+1).toList
  }
}

object PcieHostPacketDescSim {
  def fromBigInt(v: BigInt)(implicit c: ConfigDatabase) = {
    import pionic.Widths._
    val addr = v(aw-1          downto 0).toInt
    val size = v(aw+lw-1       downto aw).toInt
    val ty =   v(aw+lw+tw-1    downto aw+lw).toInt
    val data = v(aw+lw+tw+dw-1 downto aw+lw+tw)
    ty match {
      case 0 => throw new RuntimeException("error host packet desc received")
      case 1 =>
        val pad = 19
        BypassPacketDescSimPcie(addr, size,
          data(bptw-1 downto 0),
          data(bptw+bphw+pad-1 downto bptw+pad))
      case 2 =>
        val pad = 21
        OncRpcCallPacketDescSimPcie(addr, size,
          data(63+32+pad downto 32+pad),
          data(31+pad downto pad),
          data(oargw+64+32+pad-1 downto 64+32+pad))
      case 3 => ???
    }
  }
}

/** only used when Tx pipeline is not implemented */
case class ErrorPacketDescSimPcie(addr: BigInt, size: BigInt) extends PcieHostPacketDescSim with ErrorPacketDescSim {
  override def encode(implicit c: ConfigDatabase): BigInt = BigInt(0)
}
case class BypassPacketDescSimPcie(addr: BigInt, size: BigInt, packetType: BigInt, packetHdr: BigInt)(implicit val c: ConfigDatabase) extends PcieHostPacketDescSim with BypassPacketDescSim {
  override def encode(implicit c: ConfigDatabase): BigInt = {
    val pad = 19
    import pionic.Widths._
    BigInt(0)
      .assignToRange(bptw-1 downto 0, packetType)
      .assignToRange(bptw+bphw+pad-1 downto bptw+pad, packetHdr)
  }
}
case class OncRpcCallPacketDescSimPcie(addr: BigInt, size: BigInt, funcPtr: BigInt, xid: BigInt, args: BigInt) extends PcieHostPacketDescSim with OncRpcCallPacketDescSim {
  override def encode(implicit c: ConfigDatabase): BigInt = {
    import pionic.Widths._
    val pad = 21
    BigInt(0)
      .assignToRange(32+pad-1 downto pad, xid)
      .assignToRange(64+32+pad-1 downto 32+pad, funcPtr)
      .assignToRange(oargw+64+32+pad-1 downto 64+32+pad, args)
  }
}
