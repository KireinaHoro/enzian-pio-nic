package lauberhorn.host.eci

import lauberhorn.sim._
import spinal.core.{IntToBuilder, log2Up}
import jsteward.blocks.misc.sim.{BigIntBuilder, BigIntParser, BigIntRicher, IntRicherEndianAware}
import lauberhorn.Global._
import lauberhorn.sim.PacketType._
import org.pcap4j.util.MacAddress
import spinal.lib.BytesRicher
import spinal.lib.misc.database.Element.toValue

import java.net.Inet4Address

sealed abstract class EciHostCtrlInfoSim extends HostPacketDescSim {
  def len: Int
  def encode: BigInt
  /** generate an [[EciHostTxCtrlInfo]] for TX use */
  def toTxDesc: List[Byte] = {
    val b = (new BigIntBuilder)
      .push(HOST_REQ_TY_WIDTH, ty)
      .push(PKT_BUF_LEN_WIDTH, len)
      .pushTo(HOST_REQ_WIDTH, encode)
      .toBigInt
    // make sure we encode all zero bytes as well
    spinal.core.sim.SimBigIntPimper(b).toBytes(HOST_REQ_WIDTH).toList
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
          dp.pop(BYPASS_HDR_WIDTH, skip = 11 - PKT_DESC_TY_WIDTH.get))
      case 2 =>
        TxNeighborMissSim(
          len.toInt,
          dp.pop(log2Up(NUM_NEIGHBOR_ENTRIES)).toInt,
          dp.pop(32, skip = 8).toInt,
          dp.pop(32).toInt,
          dp.pop(8).toInt
        )
      case 3 =>
        val token = dp.pop(32, skip = 11)
        RxOncRpcCallSim(
          len.toInt,
          dp.pop(64),
          token,
          dp.pop(ONCRPC_INLINE_BYTES*8))
      case 11 => RxNativeTcpSim(len.toInt, dp.pop(32, skip = 11))
      case 10 => RxDeadlineSim(dp.pop(64, skip = 43))
      case 8 => RxNativeUdpSim(len.toInt, dp.pop(32, skip = 11), dp.pop(32), dp.pop(32), dp.pop(16), dp.pop(16), dp.pop(16))
      case 4 => throw new RuntimeException("not expecting a onc_rpc_reply")
      case 6 =>
        val status = dp.pop(8, skip = 3).toInt
        val pid = dp.pop(PID_WIDTH)
        val cookie = dp.pop(32, skip = 16)
        val xid = dp.pop(32)
        RxOncRpcReplySim(
          len.toInt,
          pid,
          cookie,
          xid,
          dp.pop(ONCRPC_INLINE_BYTES * 8), status)
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
      .push(BYPASS_HDR_WIDTH, packetHdr, skip = 12 - PKT_DESC_TY_WIDTH.get)
      .toBigInt
  }

  override def toString: String = {
    s"Bypass (packetType $packetType), $len bytes"
  }
}

case class RxBypassCtrlInfoSim(len: Int, packetType: BigInt, packetHdr: BigInt) extends BypassCtrlInfoSim

case class TxNeighborMissSim(len: Int,
                             neighTblIdx: Int,
                             ipAddr: Int,
                             saddr: Int,
                             proto: Int) extends EciHostCtrlInfoSim with NeighborMissPacketDescSim {
  /** not implemented due to neighbor miss descriptor never sent out */
  def encode: BigInt = ???
}

case class TxEthernetCmdSim(len: Int, dst: MacAddress, proto: Short) extends BypassCtrlInfoSim {
  def packetType = Ethernet.id
  def packetHdr =
    (new BigIntBuilder)
      .push(48, dst.getAddress.toList.bytesToBigInt)
      .pushBytes(proto.toBytesBE)
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

case class TxOncRpcReplySim(len: Int, requestToken: BigInt, args: BigInt, status: Int = 0, execution: Int = 0) extends EciHostCtrlInfoSim with OncRpcReplyTxPacketDescSim {
  override def encode: BigInt = {
    (new BigIntBuilder)
      .push(2, execution)
      .push(8, status, skip = 2)
      .push(32, requestToken)
      .push(64, 0)
      .push(ONCRPC_INLINE_BYTES * 8, args)
      .toBigInt
  }

  override def toString = {
    val argsMask = (BigInt(1) << (ONCRPC_INLINE_BYTES * 8)) - 1
    f"OncRpcReply (context $requestToken%x), $len bytes total, inlined args: ${args & argsMask}%x"
  }
}

case class RxOncRpcCallSim(len: Int, funcPtr: BigInt, requestToken: BigInt, args: BigInt) extends EciHostCtrlInfoSim with OncRpcCallRxPacketDescSim {
  /** not implemented due to call Rx descriptor never sent out */
  override def encode = ???
}

case class TxOncRpcCallSim(len: Int,
                           pid: BigInt,
                           cookie: BigInt,
                           xid: BigInt,
                           daddr: BigInt,
                           sport: BigInt,
                           dport: BigInt,
                           progNum: BigInt,
                           progVer: BigInt,
                           proc: BigInt,
                           args: BigInt) extends EciHostCtrlInfoSim with OncRpcCallTxPacketDescSim {
  override def encode: BigInt = {
    (new BigIntBuilder)
      .push(PID_WIDTH, pid, skip = 12)
      .push(32, cookie, skip = 16)
      .push(32, xid)
      .push(32, daddr)
      .push(16, sport)
      .push(16, dport)
      .push(32, progNum)
      .push(32, progVer)
      .push(32, proc)
      .push(ONCRPC_NESTED_CALL_INLINE_BYTES * 8, args, skip = 32)
      .toBigInt
  }

  override def toString: String = {
    val argsMask = (BigInt(1) << (ONCRPC_NESTED_CALL_INLINE_BYTES * 8)) - 1
    f"OncRpcCallTx (xid $xid%x), $len bytes total, inlined args: ${args & argsMask}%x"
  }
}

case class RxOncRpcReplySim(len: Int,
                            pid: BigInt,
                            cookie: BigInt,
                            xid: BigInt,
                            args: BigInt, status: Int = 0) extends EciHostCtrlInfoSim with OncRpcReplyRxPacketDescSim {
  /** not implemented due to reply Rx descriptor never sent out */
  override def encode = ???
}

case class TxExecutionSim(command: Int) extends EciHostCtrlInfoSim {
  def ty = 7
  def len = 0
  def encode = BigInt(command)
}

case class RxNativeUdpSim(len: Int, endpoint: BigInt, saddr: BigInt, daddr: BigInt,
    sport: BigInt, dport: BigInt, traceRef: BigInt = 0) extends EciHostCtrlInfoSim {
  def ty = 8
  def encode: BigInt = throw new UnsupportedOperationException("RX only")
}
case class TxNativeUdpSim(len: Int, endpoint: BigInt, daddr: BigInt, dport: BigInt, relatedRx: BigInt = 0) extends EciHostCtrlInfoSim {
  def ty = 9
  def encode: BigInt = (new BigIntBuilder).push(32, endpoint, skip = 12)
    .push(32, daddr).push(16, dport).push(16, relatedRx).toBigInt
}

case class RxDeadlineSim(generation: BigInt) extends EciHostCtrlInfoSim {
  def len = 0
  def ty = 10
  def encode: BigInt = generation << 43
}

case class RxNativeTcpSim(len: Int, endpoint: BigInt) extends EciHostCtrlInfoSim {
  def ty = 11
  def encode: BigInt = endpoint << 11
}

case class TxNativeTcpSim(len: Int, endpoint: BigInt) extends EciHostCtrlInfoSim {
  def ty = 12
  def encode: BigInt = endpoint << 12
}
