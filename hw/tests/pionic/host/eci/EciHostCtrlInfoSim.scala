package pionic.host.eci

import pionic.ConfigDatabase
import pionic.sim._
import spinal.core.IntToBuilder
import jsteward.blocks.misc.sim.BigIntRicher

sealed abstract class EciHostCtrlInfoSim extends HostPacketDescSim {
  import pionic.Widths._
  def len: Int
  def encode(implicit c: ConfigDatabase): BigInt
  def toBigInt(implicit c: ConfigDatabase): BigInt = BigInt(0)
    .assignToRange(tw-1       downto 0, ty)
    .assignToRange(tw+lw-1    downto tw, len)
    .assignToRange(511        downto tw+lw, encode)
  def toBytes(implicit c: ConfigDatabase): List[Byte] = spinal.core.sim.SimBigIntPimper(toBigInt)
    // make sure we encode all zero bytes as well
    .toBytes(512).toList
}

object EciHostCtrlInfoSim {
  def fromBigInt(v: BigInt)(implicit c: ConfigDatabase): EciHostCtrlInfoSim = {
    import pionic.Widths._
    val ty   = v(tw-1       downto 0).toInt
    val len  = v(tw+lw-1    downto tw).toInt
    val data = v(511        downto tw+lw)
    ty match {
      case 0 => throw new RuntimeException("error host packet desc received")
      case 1 =>
        BypassCtrlInfoSim(
          len,
          data(bptw-1 downto 0),
          data(bptw+bphw+11-1   downto bptw+11))
      case 2 =>
        OncRpcCallCtrlInfoSim(
          len,
          data(63+32+13 downto 32+13),
          data(31+13 downto 13),
          data(oargw + 64+32+13-1 downto 64+32+13))
      case 3 => ???
    }
  }
}

/** only used when Tx pipeline is not implemented */
case class ErrorCtrlInfoSim(len: Int) extends EciHostCtrlInfoSim with ErrorPacketDescSim {
  override def encode(implicit c: ConfigDatabase): BigInt = BigInt(0)
}
case class BypassCtrlInfoSim(len: Int, packetType: BigInt, packetHdr: BigInt) extends EciHostCtrlInfoSim with BypassPacketDescSim {
  override def encode(implicit c: ConfigDatabase): BigInt = {
    import pionic.Widths._
    BigInt(0)
      .assignToRange(bptw-1 downto 0, packetType)
      .assignToRange(bptw+bphw+11-1 downto bptw+11, packetHdr)
  }
}
case class OncRpcCallCtrlInfoSim(len: Int, funcPtr: BigInt, xid: BigInt, args: BigInt) extends EciHostCtrlInfoSim with OncRpcCallPacketDescSim {
  override def encode(implicit c: ConfigDatabase): BigInt = {
    import pionic.Widths._
    BigInt(0)
      .assignToRange(32+13-1 downto 13, xid)
      .assignToRange(64+32+13-1 downto 32+13, funcPtr)
      .assignToRange(oargw+64+32+13-1 downto 64+32+13, args)
  }
}
case class OncRpcReplyCtrlInfoSim(len: Int, funcPtr: BigInt, xid: BigInt, rets: BigInt) extends EciHostCtrlInfoSim with OncRpcReplyPacketDescSim {
  override def encode(implicit c: ConfigDatabase): BigInt = ???
}