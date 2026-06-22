package lauberhorn.host.eci

import lauberhorn.Global._
import lauberhorn._
import lauberhorn.host.{HostReq, HostReqType}
import lauberhorn.net.PacketDescType
import spinal.core._

import scala.language.postfixOps

/** TX-side ECI control info as written by the host. */
case class EciHostTxCtrlInfo() extends Bundle {
  override def clone: EciHostTxCtrlInfo = EciHostTxCtrlInfo()

  val ty = HostReqType() // 3 bits
  val len = PacketLength() // 16 bits
  val data = new Union {
    case class BypassBundle() extends Bundle {
      val ty = PacketDescType() // 3 bits
      val xb10 = Bits(10 bits) /* make sure header is word aligned */
      val hdr = Bits(BYPASS_HDR_WIDTH bits)
    }
    val bypass = newElement(BypassBundle())

    case class OncRpcReplyTxBundle() extends Bundle {
      val xb13 = Bits(13 bits) /* make sure RPC fields are aligned */
      val xid = Bits(32 bits)
      val funcPtr = Bits(64 bits)
      val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
    }
    val oncRpcReplyTx = newElement(OncRpcReplyTxBundle())

    case class OncRpcCallTxBundle() extends Bundle {
      val xb13 = Bits(13 bits) /* make sure RPC fields are aligned */
      val pid = PID()
      val cookie = Bits(32 bits)
      val xid = Bits(32 bits)
      val daddr = Bits(32 bits)
      val sport = Bits(16 bits)
      val dport = Bits(16 bits)
      val progNum = Bits(32 bits)
      val progVer = Bits(32 bits)
      val proc = Bits(32 bits)
      val xb48 = Bits(48 bits) /* align nested call data to 64-bit word */
      val data = Bits(ONCRPC_NESTED_CALL_INLINE_BYTES * 8 bits)
    }
    val oncRpcCallTx = newElement(OncRpcCallTxBundle())
  }

  /** Convert an [[EciHostTxCtrlInfo]] to a [[HostReq]], to send out to the encoder pipeline. */
  def unpackTo(desc: HostReq, addr: PacketAddr) = {
    desc.ty := ty
    desc.data.assignDontCare()
    switch (ty) {
      is (HostReqType.bypass) {
        desc.data.bypassMeta.assignSomeByName(data.bypass)
        desc.buffer.size := len
      }
      is (HostReqType.oncRpcReplyTx) {
        desc.data.oncRpcReplyTx.xid     := data.oncRpcReplyTx.xid
        desc.data.oncRpcReplyTx.funcPtr := data.oncRpcReplyTx.funcPtr
        desc.data.oncRpcReplyTx.data    := data.oncRpcReplyTx.data
        desc.data.oncRpcReplyTx.replyLen := len
        when (len.bits > ONCRPC_INLINE_BYTES.get) {
          desc.buffer.size.bits            := len.bits - ONCRPC_INLINE_BYTES.get
        } otherwise {
          desc.buffer.size.bits            := 0
        }
      }
      is (HostReqType.oncRpcCallTx) {
        desc.data.oncRpcCallTx.pid     := data.oncRpcCallTx.pid
        desc.data.oncRpcCallTx.cookie  := data.oncRpcCallTx.cookie
        desc.data.oncRpcCallTx.xid     := data.oncRpcCallTx.xid
        desc.data.oncRpcCallTx.daddr   := data.oncRpcCallTx.daddr
        desc.data.oncRpcCallTx.sport   := data.oncRpcCallTx.sport
        desc.data.oncRpcCallTx.dport   := data.oncRpcCallTx.dport
        desc.data.oncRpcCallTx.progNum := data.oncRpcCallTx.progNum
        desc.data.oncRpcCallTx.progVer := data.oncRpcCallTx.progVer
        desc.data.oncRpcCallTx.proc    := data.oncRpcCallTx.proc
        desc.data.oncRpcCallTx.data    := data.oncRpcCallTx.data
        desc.data.oncRpcCallTx.callLen := len
        when (len.bits > ONCRPC_NESTED_CALL_INLINE_BYTES.get) {
          desc.buffer.size.bits            := len.bits - ONCRPC_NESTED_CALL_INLINE_BYTES.get
        } otherwise {
          desc.buffer.size.bits            := 0
        }
      }
    }
    desc.buffer.addr := addr
  }

  assert(getBitsWidth <= HOST_REQ_WIDTH, "TX host control info larger than half a cacheline")
}

object EciHostTxCtrlInfo {
  def addMackerel() = {
    ALLOC.addMackerelEpilogue(
      s"""
         |datatype host_ctrl_info_bypass_tx lsbfirst(64) "ECI Host Control Info (Bypass TX)" {
         |  ty       ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be bypass)";
         |  len      ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  hdr_ty   ${PKT_DESC_TY_WIDTH.get} type(packet_desc_type) "Type of bypass header";
         |  _        10 rsvd;
         |  // hdr follows -- need to calculate address manually
         |};
         |
         |datatype host_ctrl_info_onc_rpc_reply_tx lsbfirst(64) "ECI Host Control Info (ONC-RPC Server Reply TX)" {
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_reply_tx)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of reply payload, including inlined bytes";
         |  _         13 rsvd;
         |  xid       32 "XID of request being replied to (big endian)";
         |  func_ptr  64 "Function pointer/session identifier for RPC reply";
         |  // reply data follows -- need to calculate address manually
         |};
         |
         |datatype host_ctrl_info_onc_rpc_call_tx lsbfirst(64) "ECI Host Control Info (ONC-RPC Nested Call TX)" {
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_call_tx)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of call args, including inlined bytes";
         |  _         13 rsvd;
         |  pid       ${PID_WIDTH.get} "Destination process for eventual reply completion";
         |  cookie    32 "Runtime completion cookie";
         |  xid       32 "XID of outgoing request (big endian)";
         |  daddr     32 "Destination IPv4 address";
         |  sport     16 "Source UDP port";
         |  dport     16 "Destination UDP port";
         |  prog_num  32 "ONC-RPC program number";
         |  prog_ver  32 "ONC-RPC program version";
         |  proc      32 "ONC-RPC procedure number";
         |  _         48 rsvd;
         |  data0     64 "First 8 inline argument bytes";
         |  data1     64 "Second 8 inline argument bytes";
         |  data2     64 "Third 8 inline argument bytes";
         |};
         |""".stripMargin)
  }
}
