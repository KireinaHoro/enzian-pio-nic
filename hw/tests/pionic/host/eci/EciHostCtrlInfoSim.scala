package pionic.host.eci

import pionic.ConfigDatabase
import pionic.sim._
import spinal.core.IntToBuilder
import jsteward.blocks.misc.sim.BigIntRicher

sealed abstract class EciHostCtrlInfoSim extends HostPacketDescSim {
  import Widths._
  def len: Int
  def toBigInt(implicit c: ConfigDatabase): BigInt = BigInt(0)
    .assignToRange(tw-1       downto 0, ty)
    .assignToRange(tw+lw-1    downto tw, len)
    .assignToRange(tw+lw+dw-1 downto tw+lw, data)
  def toBytes(implicit c: ConfigDatabase): List[Byte] = spinal.core.sim.SimBigIntPimper(toBigInt)
    // make sure we encode all zero bytes as well
    .toBytes(tw+lw+dw).toList
}

object EciHostCtrlInfoSim {
  def fromBigInt(v: BigInt)(implicit c: ConfigDatabase): EciHostCtrlInfoSim = {
    import Widths._
    val ty   = v(tw-1       downto 0).toInt
    val len  = v(tw+lw-1    downto tw).toInt
    val data = v(tw+lw+dw-1 downto tw+lw)
    ty match {
      case 0 => throw new RuntimeException("error host packet desc received")
      case 1 =>
        BypassCtrlInfoSim(
          len,
          data(bptw-1 downto 0),
          data(dw-1   downto bptw))
      case 2 =>
        OncRpcCallCtrlInfoSim(
          len,
          data(63 downto 0),
          data(95 downto 64),
          data(oargw + 95 downto 96))
      case 3 =>
        OncRpcReplyCtrlInfoSim(
          len,
          data(63 downto 0),
          data(95 downto 64),
          data(oargw + 95 downto 96))
    }
  }
}

/** only used when Tx pipeline is not implemented */
case class ErrorCtrlInfoSim(len: Int) extends EciHostCtrlInfoSim with ErrorPacketDescSim
case class BypassCtrlInfoSim(len: Int, packetType: BigInt, packetHdr: BigInt) extends EciHostCtrlInfoSim with BypassPacketDescSim
case class OncRpcCallCtrlInfoSim(len: Int, funcPtr: BigInt, xid: BigInt, args: BigInt) extends EciHostCtrlInfoSim with OncRpcCallPacketDescSim
case class OncRpcReplyCtrlInfoSim(len: Int, funcPtr: BigInt, xid: BigInt, rets: BigInt) extends EciHostCtrlInfoSim with OncRpcReplyPacketDescSim