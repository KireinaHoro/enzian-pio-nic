package lauberhorn.host.eci

import lauberhorn.sim._
import spinal.core.IntToBuilder
import jsteward.blocks.misc.sim.{BigIntBuilder, BigIntParser, BigIntRicher}
import lauberhorn.Global._
import lauberhorn.sim.PacketType._
import org.pcap4j.util.MacAddress
import spinal.lib.BytesRicher
import spinal.lib.misc.database.Element.toValue

import java.net.Inet4Address

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
        RxBypassCtrlInfoSim(
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

trait BypassCtrlInfoSim extends EciHostCtrlInfoSim with BypassPacketDescSim {
  def len: Int
  def packetType: BigInt
  def packetHdr: BigInt

  override def encode: BigInt = {
    (new BigIntBuilder)
      .push(PKT_DESC_TY_WIDTH, packetType)
      .push(BYPASS_HDR_WIDTH, packetHdr, skip = 11)
      .toBigInt
  }

  override def toString: String = {
    s"Bypass (packetType $packetType), $len bytes"
  }
}

case class RxBypassCtrlInfoSim(len: Int, packetType: BigInt, packetHdr: BigInt) extends BypassCtrlInfoSim

case class TxEthernetCmdSim(len: Int, dst: MacAddress, proto: Int) extends BypassCtrlInfoSim {
  def packetType = Ethernet.id
  def packetHdr =
    (new BigIntBuilder)
      .push(48, dst.getAddress.toList.bytesToBigInt)
      .push(16, proto)
      .toBigInt
}
case class TxIpCmdSim(len: Int, dst: Inet4Address, proto: Int) extends BypassCtrlInfoSim {
  def packetType = Ip.id
  def packetHdr =
    (new BigIntBuilder)
      .push(32, dst.getAddress.toList.bytesToBigInt)
      .push(16, len)
      .push(8, proto)
      .toBigInt
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