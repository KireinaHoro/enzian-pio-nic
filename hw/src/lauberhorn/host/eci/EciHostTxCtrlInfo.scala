package lauberhorn.host.eci

import lauberhorn.Global._
import lauberhorn._
import lauberhorn.host.{HostReq, HostReqType}
import lauberhorn.net.PacketDescType
import spinal.core._
import spinal.lib.EndiannessSwap

import scala.language.postfixOps

/** TX-side ECI control info. RPC scalars are host-order; bypass headers are raw bytes. */
case class EciHostTxCtrlInfo() extends Bundle {
  override def clone: EciHostTxCtrlInfo = EciHostTxCtrlInfo()

  val ty = HostReqType() // 4 bits
  val len = PacketLength() // 16 bits
  val data = new Union {
    case class NativeTcpBundle() extends Bundle {
      val execution = Bits(2 bits)
      val padding = Bits(10 bits)
      val endpoint = Bits(32 bits)
    }
    val nativeTcp = newElement(NativeTcpBundle())
    case class NativeUdpBundle() extends Bundle {
      val execution = Bits(2 bits)
      val padding = Bits(10 bits)
      val endpoint = Bits(32 bits)
      val daddr = Bits(32 bits)
      val dport = Bits(16 bits)
      val relatedRx = Bits(16 bits)
    }
    val nativeUdp = newElement(NativeUdpBundle())
    case class BypassBundle() extends Bundle {
      val ty = PacketDescType() // 4 bits
      val xb9 = Bits((12 - PKT_DESC_TY_WIDTH.get) bits) /* make sure header is word aligned */
      val hdr = Bits(BYPASS_HDR_WIDTH bits)
    }
    val bypass = newElement(BypassBundle())

    case class OncRpcReplyTxBundle() extends Bundle {
      val execution = Bits(2 bits) // end=1, begin=2, end/begin=3
      val xb2 = Bits(2 bits)
      val status = Bits(8 bits) // byte aligned, no payload offset change
      val requestToken = Bits(32 bits)
      val xb64 = Bits(64 bits)
      val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
    }
    val oncRpcReplyTx = newElement(OncRpcReplyTxBundle())

    case class OncRpcCallTxBundle() extends Bundle {
      val execution = Bits(2 bits)
      val xb10 = Bits(10 bits) /* make sure RPC fields are aligned */
      val pid = PID()
      val xb16 = Bits(16 bits) /* align cookie and following 32-bit fields */
      val cookie = Bits(32 bits)
      val xid = Bits(32 bits)
      val daddr = Bits(32 bits)
      val sport = Bits(16 bits)
      val dport = Bits(16 bits)
      val progNum = Bits(32 bits)
      val progVer = Bits(32 bits)
      val proc = Bits(32 bits)
      val xb32 = Bits(32 bits) /* align nested call data to 64-bit word */
      val data = Bits(ONCRPC_NESTED_CALL_INLINE_BYTES * 8 bits)
    }
    val oncRpcCallTx = newElement(OncRpcCallTxBundle())
  }

  // Bypass placement follows its internal header discriminator. Application
  // variants retain bit 23; their reply status begins at byte 3.
  def inlinePayload: Bool = Mux(ty === HostReqType.bypass,
    data.asBits(PKT_DESC_TY_WIDTH.get), data.asBits(3))

  /** Convert an [[EciHostTxCtrlInfo]] to a [[HostReq]], to send out to the encoder pipeline. */
  def unpackTo(desc: HostReq, addr: PacketAddr) = {
    desc.ty := ty
    desc.data.assignDontCare()
    switch (ty) {
      is (HostReqType.nativeTcpTx) {
        desc.data.nativeTcp.endpoint := data.nativeTcp.endpoint
        desc.buffer.size := len
      }
      is (HostReqType.nativeUdpTx) {
        desc.data.nativeUdp.endpoint := data.nativeUdp.endpoint
        desc.data.nativeUdp.daddr := EndiannessSwap(data.nativeUdp.daddr)
        desc.data.nativeUdp.dport := EndiannessSwap(data.nativeUdp.dport)
        desc.buffer.size := len
      }
      is (HostReqType.executionControl) { desc.buffer.size.bits := 0 }
      is (HostReqType.bypass) {
        desc.data.bypassMeta.assignSomeByName(data.bypass)
        desc.buffer.size := len
      }
      is (HostReqType.oncRpcReplyTx) {
        desc.data.oncRpcReplyTx.status := data.oncRpcReplyTx.status
        desc.data.oncRpcReplyTx.requestToken := data.oncRpcReplyTx.requestToken
        desc.data.oncRpcReplyTx.data    := 0
        desc.data.oncRpcReplyTx.replyLen := len
        desc.buffer.size := len
      }
      is (HostReqType.oncRpcCallTx) {
        desc.data.oncRpcCallTx.pid     := data.oncRpcCallTx.pid
        desc.data.oncRpcCallTx.cookie  := data.oncRpcCallTx.cookie
        desc.data.oncRpcCallTx.xid     := EndiannessSwap(data.oncRpcCallTx.xid)
        desc.data.oncRpcCallTx.daddr   := EndiannessSwap(data.oncRpcCallTx.daddr)
        desc.data.oncRpcCallTx.sport   := EndiannessSwap(data.oncRpcCallTx.sport)
        desc.data.oncRpcCallTx.dport   := EndiannessSwap(data.oncRpcCallTx.dport)
        desc.data.oncRpcCallTx.progNum := EndiannessSwap(data.oncRpcCallTx.progNum)
        desc.data.oncRpcCallTx.progVer := EndiannessSwap(data.oncRpcCallTx.progVer)
        desc.data.oncRpcCallTx.proc    := EndiannessSwap(data.oncRpcCallTx.proc)
        desc.data.oncRpcCallTx.data    := 0
        desc.data.oncRpcCallTx.callLen := len
        desc.buffer.size := len
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
         |datatype host_tx_placement lsbfirst(64) "Common TX payload placement" {
         |  ty ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Descriptor type";
         |  len ${PKT_BUF_LEN_WIDTH.get} "Actual payload length";
         |  _ 3 rsvd;
         |  inline_payload 1 "Reservation is inline, independent of actual length";
         |  _ 40 rsvd;
         |};
         |
         |datatype host_ctrl_info_native_tcp_tx lsbfirst(64) "Native TCP TX" {
         |  ty ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "native_tcp_tx";
         |  len ${PKT_BUF_LEN_WIDTH.get} "Complete IPv4/TCP packet bytes";
         |  execution 2 "Execution interval";
         |  _ 1 rsvd;
         |  inline_payload 1 "Payload resides in control CL upper half";
         |  _ 8 rsvd;
         |  endpoint 32 "Kernel endpoint handle";
         |};
         |
         |datatype host_ctrl_info_native_udp_tx lsbfirst(64) "Native UDP TX" {
         |  ty ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "native_udp_tx";
         |  len ${PKT_BUF_LEN_WIDTH.get} "UDP payload bytes";
         |  execution 2 "Execution interval";
         |  _ 1 rsvd;
         |  inline_payload 1 "Payload resides in control CL upper half";
         |  _ 8 rsvd;
         |  endpoint 32 "Kernel endpoint handle";
         |  daddr 32 "Destination IPv4 address, host order";
         |  dport 16 "Destination UDP port, host order";
         |  related_rx 16 "Trace only: echoed RX trace_ref, zero if unrelated";
         |};
         |
         |datatype host_ctrl_info_bypass_tx lsbfirst(64) "ECI Host Control Info (Bypass TX)" {
         |  ty       ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be bypass)";
         |  len      ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  hdr_ty   ${PKT_DESC_TY_WIDTH.get} type(packet_desc_type) "Type of bypass header";
         |  inline_payload 1 "Payload resides in control CL upper half";
         |  _        ${11 - PKT_DESC_TY_WIDTH.get} rsvd;
         |  // hdr follows -- need to calculate address manually
         |};
         |
         |datatype host_ctrl_info_execution_tx lsbfirst(64) "Local execution notification" {
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "execution_control";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Must be zero";
         |  execution 2 "Execution interval: end=1, begin=2, end/begin=3";
         |  _         1 rsvd;
         |  inline_payload 1 "Must be zero for execution control";
         |  _         40 rsvd;
         |};
         |
         |datatype host_ctrl_info_onc_rpc_reply_tx lsbfirst(64) "ECI Host Control Info (ONC-RPC Server Reply TX)" {
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_reply_tx)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of contiguous reply payload";
         |  execution 2 "Execution interval: end=1, begin=2, end/begin=3";
         |  _         1 rsvd;
         |  inline_payload 1 "Payload resides in control CL upper half";
         |  status    8 "ONC-RPC accept status (host order)";
         |  request_token 32 "Opaque incoming request context token";
         |  _         64 rsvd;
         |  // Payload placement comes from host_tx_placement.
         |};
         |
         |datatype host_ctrl_info_onc_rpc_call_tx lsbfirst(64) "ECI Host Control Info (ONC-RPC Nested Call TX)" {
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_call_tx)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of contiguous call arguments";
         |  execution 2 "Execution interval: end=1, begin=2, end/begin=3";
         |  _         1 rsvd;
         |  inline_payload 1 "Payload resides in control CL upper half";
         |  _         8 rsvd;
         |  pid       ${PID_WIDTH.get} "Destination process for eventual reply completion";
         |  _         16 rsvd;
         |  cookie    32 "Runtime completion cookie";
         |  xid       32 "XID of outgoing request (host order)";
         |  daddr     32 "Destination IPv4 address (host order)";
         |  sport     16 "Source UDP port (host order)";
         |  dport     16 "Destination UDP port (host order)";
         |  prog_num  32 "ONC-RPC program number (host order)";
         |  prog_ver  32 "ONC-RPC program version (host order)";
         |  proc      32 "ONC-RPC procedure number (host order)";
         |  _         32 rsvd;
         |  _         128 rsvd;
         |  _         64 rsvd;
         |};
         |""".stripMargin)
  }
}
