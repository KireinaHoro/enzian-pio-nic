package pionic.host

import pionic.ConfigDatabase
import pionic.sim._
import spinal.core.IntToBuilder
import jsteward.blocks.misc.sim.BigIntRicher

package object eci {
  sealed abstract class EciHostCtrlInfoSim extends HostPacketDescSim {
    def len: Int
    // FIXME: is this abstraction enough for both decoupled and coupled protocols?
    def toRxAck(implicit c: ConfigDatabase): BigInt = BigInt(0)
    // TODO: what do we send back?
    def toTxAck(implicit c: ConfigDatabase): BigInt = BigInt(0)
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

  case class BypassCtrlInfoSim(len: Int, packetType: BigInt, packetHdr: BigInt) extends EciHostCtrlInfoSim with BypassPacketDescSim
  case class OncRpcCallCtrlInfoSim(len: Int, funcPtr: BigInt, xid: BigInt, args: BigInt) extends EciHostCtrlInfoSim with OncRpcCallPacketDescSim
  case class OncRpcReplyCtrlInfoSim(len: Int, funcPtr: BigInt, xid: BigInt, rets: BigInt) extends EciHostCtrlInfoSim with OncRpcReplyPacketDescSim
}
