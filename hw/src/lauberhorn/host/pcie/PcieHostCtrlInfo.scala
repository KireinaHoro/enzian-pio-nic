package lauberhorn.host.pcie

import lauberhorn._
import lauberhorn.host.{HostReq, HostReqType}
import lauberhorn.net.PacketDescType
import spinal.core._

import Global._

import scala.language.postfixOps

/**
  * Control info struct sent to the CPU in RX descriptor load through hostRx,
  * as well as struct received from the CPU for TX through hostTxAck.  Same
  * principle as [[lauberhorn.host.eci.EciHostRxCtrlInfo]] to generate aligned memory
  * locations.
  */
// TODO: reduce redundant code
case class PcieHostCtrlInfo() extends Bundle {
  override def clone: PcieHostCtrlInfo = PcieHostCtrlInfo()

  // reserve one bit from valid in readStream
  val buffer = PacketBufDesc() // 24 + 16b
  val ty = HostReqType() // 2b
  val data = new Union {
    case class BypassBundle() extends Bundle {
      val ty = PacketDescType() // 2b
      val xb19 = Bits(19 bits) // make sure header is word aligned
      val hdr = Bits(BYPASS_HDR_WIDTH bits)
    }
    val bypass = newElement(BypassBundle())

    case class OncRpcCallRxBundle() extends Bundle {
      val xb21 = Bits(21 bits)
      val xid = Bits(32 bits)
      val funcPtr = Bits(64 bits)
      val args = Bits(ONCRPC_INLINE_BYTES * 8 bits)
    }
    val oncRpcCallRx = newElement(OncRpcCallRxBundle())
  }

  def unpackTo(desc: HostReq) = {
    desc.ty := ty
    desc.data.assignDontCare()
    switch (ty) {
      is (HostReqType.bypass) {
        desc.data.bypassMeta.assignSomeByName(data.bypass)
      }
      is (HostReqType.oncRpcCallRx) {
        desc.data.oncRpcCallRx.assignSomeByName(data.oncRpcCallRx)
      }
    }
    desc.buffer := buffer
  }

  def addMackerel = {
    // post header type enum to mackerel
    HostReqType.addMackerel()
    PacketDescType.addMackerel()

    // post descriptor header to mackerel
    ALLOC.addMackerelEpilogue(
      s"""
         |datatype host_ctrl_info_error lsbfirst(64) "PCIe Host Control Info (Error)" {
         |  valid 1   "RX descriptor valid";
         |  addr  ${PKT_BUF_ADDR_WIDTH.get} "Address in packet buffer";
         |  size  ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  ty    ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be error)";
         |  _     21  rsvd;
         |};
         |datatype host_ctrl_info_bypass lsbfirst(64) "PCIe Host Control Info (Bypass)" {
         |  valid  1   "RX descriptor valid";
         |  addr   ${PKT_BUF_ADDR_WIDTH.get} "Address in packet buffer";
         |  size   ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  ty     ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be bypass)";
         |  hdr_ty ${PKT_DESC_TY_WIDTH.get} type(packet_desc_type) "Type of bypass header";
         |  _      19 rsvd;
         |  // hdr follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Possible approach:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |};
         |datatype host_ctrl_info_onc_rpc_call_rx lsbfirst(64) "PCIe Host Control Info (ONC-RPC Server Call RX)" {
         |  valid  1   "RX descriptor valid";
         |  addr   ${PKT_BUF_ADDR_WIDTH.get} "Address in packet buffer";
         |  size   ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  ty     ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_call_rx)";
         |  _      21  rsvd;
         |  xid    32  "XID of incoming request";
         |  func_ptr 64 "Function pointer for RPC call handler";
         |  // args follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Two possible approaches:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |  // - as an array, so Mackerel would emit access functions
         |};
         |datatype host_ctrl_info_onc_rpc_reply_tx lsbfirst(64) "PCIe Host Control Info (ONC-RPC Server Reply TX)" {
         |  // TODO: this does not exist yet, use bypass with raw Ethernet frame for sending
         |  valid  1   "RX descriptor valid";
         |  addr   ${PKT_BUF_ADDR_WIDTH.get} "Address in packet buffer";
         |  size   ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  ty     ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_reply_tx)";
         |  _      21  rsvd;
         |  // buffer follows
         |  // TODO: for now, reply software-serialized data...
         |};
         |""".stripMargin
    )
  }
}

object PcieHostCtrlInfo {
  def packFrom(desc: HostReq) = new Area {
    val ret = PcieHostCtrlInfo()
    ret.ty := desc.ty
    ret.data.assignDontCare()
    switch (desc.ty) {
      is (HostReqType.bypass) {
        ret.data.bypass.assignSomeByName(desc.data.bypassMeta)
        ret.data.bypass.xb19 := 0
      }
      is (HostReqType.oncRpcCallRx) {
        ret.data.oncRpcCallRx.assignSomeByName(desc.data.oncRpcCallRx)
        ret.data.oncRpcCallRx.xb21 := 0
      }
    }
    ret.buffer.addr := desc.buffer.addr
    ret.buffer.size := desc.len
  }.ret
}
