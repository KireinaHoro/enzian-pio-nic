package pionic.host.eci

import pionic.sim._
import spinal.core.IntToBuilder
import jsteward.blocks.misc.sim.{BigIntBuilder, BigIntParser, BigIntRicher}
import pionic.Global._
import spinal.lib.misc.database.Element.toValue

sealed abstract class EciHostCtrlInfoSim extends HostPacketDescSim {
  def len: Int
  def encode: BigInt
  /** generate a [[EciHostCtrlInfo]] for TX use */
  def toTxDesc: List[Byte] = {
    val b = (new BigIntBuilder)
      .push(HOST_REQ_TY_WIDTH, ty, skip = 1) // valid bit left as zero
      .push(PKT_BUF_LEN_WIDTH, len)
      .pushTo(HOST_REQ_WIDTH+1, encode)
      .toBigInt
    // make sure we encode all zero bytes as well
    spinal.core.sim.SimBigIntPimper(b).toBytes(HOST_REQ_WIDTH+1).toList
  }
}

object EciHostCtrlInfoSim {
  def fromBigInt(v: BigInt): EciHostCtrlInfoSim = {
    val p = new BigIntParser(v)
    val ty   = p.pop(HOST_REQ_TY_WIDTH).toInt
    val len  = p.pop(PKT_BUF_LEN_WIDTH)
    val data = p.popTo(HOST_REQ_WIDTH)

    val dp = new BigIntParser(data)
    ty match {
      case 0 => throw new RuntimeException("error host packet desc received")
      case 1 =>
        BypassCtrlInfoSim(
          len.toInt,
          dp.pop(PKT_DESC_TY_WIDTH),
          dp.pop(BYPASS_HDR_WIDTH, skip = 11))
      case 2 =>
        val xid = dp.pop(32, skip = 13)
        OncRpcCallCtrlInfoSim(
          len.toInt,
          dp.pop(64),
          xid,
          dp.pop(ONCRPC_INLINE_BYTES*8))
      case 3 => throw new RuntimeException("not expecting a onc_rpc_reply")
    }
  }
}

/** only used when Tx pipeline is not implemented */
case class ErrorCtrlInfoSim(len: Int) extends EciHostCtrlInfoSim with ErrorPacketDescSim {
  override def encode: BigInt = BigInt(0)
}
case class BypassCtrlInfoSim(len: Int, packetType: BigInt, packetHdr: BigInt) extends EciHostCtrlInfoSim with BypassPacketDescSim {
  override def encode: BigInt = {
    (new BigIntBuilder)
      .push(PKT_DESC_TY_WIDTH, packetType)
      .push(BYPASS_HDR_WIDTH, packetHdr, skip = 11)
      .toBigInt
  }
}
case class OncRpcCallCtrlInfoSim(len: Int, funcPtr: BigInt, xid: BigInt, args: BigInt) extends EciHostCtrlInfoSim with OncRpcCallPacketDescSim {
  override def encode: BigInt = {
    (new BigIntBuilder)
      .push(32, xid, skip = 13)
      .push(64, funcPtr)
      .push(BYPASS_HDR_WIDTH, args)
      .toBigInt
  }
}