package lauberhorn.host.eci

import lauberhorn.Global._
import lauberhorn._
import lauberhorn.host.{HostReq, HostReqType}
import lauberhorn.net.PacketDescType
import spinal.core._

import scala.language.postfixOps

/**
  * Control info struct sent to the CPU in cache-line reloads for RX packets.
  *
  * This is separate from the rest of the data inside the packet buffer because:
  * - it is expensive to enable unaligned access for the AXI DMA engine
  * - we don't want to pack metadata into the packet buffer SRAM, due to lack of write port
  *
  * We repack [[HostReq]] to save space by dropping fields the host won't need.  We
  * also add padding to align things nicer for CPU.
  *
  * We need the length field here:
  * - for RX, to know how many bytes we should read for bypass packets
  * - for RX, to check if we got a too-short RPC call request
  */
case class EciHostRxCtrlInfo() extends Bundle {
  override def clone: EciHostRxCtrlInfo = EciHostRxCtrlInfo()

  /* reserve one bit for valid in readStream */                       // [0 : 1)  = 1b
  val ty = HostReqType()                                              // [1 : 4)  = 3b
  val len = PacketLength()                                            // [4 : 20) = 16b
  val data = new Union {
    case class BypassBundle() extends Bundle {
      val ty = PacketDescType()                                       // [20: 23) = 3b
      val xb9 = Bits(9 bits) /* make sure header is word aligned */   // [23: 32) = 9b
      val hdr = Bits(BYPASS_HDR_WIDTH bits)
    }
    val bypass = newElement(BypassBundle())

    /** ECI descriptor for server call RX. */
    case class OncRpcCallRxBundle() extends Bundle {
      val xb12 = Bits(12 bits) /* make sure RPC fields are aligned */ // [20: 32) = 12 b
      val xid = Bits(32 bits)
      val funcPtr = Bits(64 bits)
      val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
    }
    val oncRpcCallRx = newElement(OncRpcCallRxBundle())

    /** ECI-specific version of [[lauberhorn.host.HostReqOncRpcReplyRx]].
      *
      * The source address/ports are not host-visible on ECI: keeping the same
      * 48-byte inline reply payload leaves room for completion routing
      * metadata, but not the full network tuple.
      */
    case class OncRpcReplyRxBundle() extends Bundle {
      val xb12 = Bits(12 bits) /* make sure RPC fields are aligned */ // [20: 32) = 12 b
      val pid = PID()
      val cookie = Bits(32 bits)
      val xid = Bits(32 bits)
      val xb16 = Bits(16 bits) /* align nested reply data to 64-bit word */
      val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
    }
    val oncRpcReplyRx = newElement(OncRpcReplyRxBundle())

    /** ECI-specific version of [[lauberhorn.host.HostReqArpRequest]]. */
    case class ArpReqBundle() extends Bundle {
      val neighTblIdx = Bits(log2Up(NUM_NEIGHBOR_ENTRIES) bits)       // [20: 23) = 3b
      val xb9 = Bits(9 bits) /* make sure IP addr is aligned */       // [23: 32) = 9b
      val ipAddr = Bits(32 bits)
    }
    val arpReq = newElement(ArpReqBundle())
  }

  // plus one for readStreamBlockCycles
  assert(getBitsWidth + 1 <= HOST_REQ_WIDTH, "RX host control info larger than half a cacheline")

  def addMackerel() = {
    // post header type enum to mackerel
    HostReqType.addMackerel()
    PacketDescType.addMackerel()
    net.ip.IpNeighborEntryState.addMackerel()

    ALLOC.addMackerelEpilogue(
      s"""
         |datatype host_ctrl_info_error lsbfirst(64) "ECI Host Control Info (Error)" {
         |  valid 1 "RX descriptor valid";
         |  ty    ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be error)";
         |  len   ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  _     12 rsvd;
         |};
         |
         |datatype host_ctrl_info_bypass_rx lsbfirst(64) "ECI Host Control Info (Bypass RX)" {
         |  valid    1 "RX descriptor valid";
         |  ty       ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be bypass)";
         |  len      ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  hdr_ty   ${PKT_DESC_TY_WIDTH.get} type(packet_desc_type) "Type of bypass header";
         |  _        9 rsvd;
         |  // hdr follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Possible approach:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |};
         |
         |datatype host_ctrl_info_onc_rpc_call_rx lsbfirst(64) "ECI Host Control Info (ONC-RPC Server Call RX)" {
         |  valid     1 "RX descriptor valid";
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_call_rx)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of packet overflow, not including inlined bytes";
         |  _         12 rsvd;
         |  xid       32 "XID of incoming request (big endian)";
         |  func_ptr  64 "Function pointer for RPC call handler";
         |  // args follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Two possible approaches:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |  // - as an array, so Mackerel would emit access functions
         |};
         |
         |datatype host_ctrl_info_onc_rpc_reply_rx lsbfirst(64) "ECI Host Control Info (ONC-RPC Nested Reply RX)" {
         |  valid     1 "RX descriptor valid";
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_reply_rx)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of reply payload overflow, not including inlined bytes";
         |  _         12 rsvd;
         |  pid       ${PID_WIDTH.get} "Destination process for reply completion";
         |  cookie    32 "Runtime completion cookie";
         |  xid       32 "XID of incoming reply (big endian)";
         |  _         16 rsvd;
         |  // reply data follows -- need to calculate address manually
         |};
         |
         |datatype host_ctrl_info_arp_req lsbfirst(64) "ECI Host Control Info (ARP Request for bypass core)" {
         |  valid     1 "RX descriptor valid";
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be arp_req)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  tbl_idx   ${log2Up(NUM_NEIGHBOR_ENTRIES)} "Index of INCOMPLETE entry in neighbor table";
         |  _         9 rsvd;
         |  ip_addr   32 "IP address of the target host";
         |};
         """.stripMargin)
  }
}

object EciHostRxCtrlInfo {
  /** Convert a [[HostReq]] from the decoder pipeline into a [[EciHostRxCtrlInfo]] to send to a CPU core */
  def packFrom(desc: HostReq) = new Area {
    val ret = EciHostRxCtrlInfo()
    ret.ty := desc.ty
    ret.data.assignDontCare()
    switch (desc.ty) {
      is (HostReqType.bypass) {
        ret.data.bypass.assignSomeByName(desc.data.bypassMeta)
        ret.data.bypass.xb9 := 0
      }
      is (HostReqType.oncRpcCallRx) {
        ret.data.oncRpcCallRx.assignSomeByName(desc.data.oncRpcCallRx)
        ret.data.oncRpcCallRx.xb12 := 0
      }
      is (HostReqType.oncRpcReplyRx) {
        ret.data.oncRpcReplyRx.assignSomeByName(desc.data.oncRpcReplyRx)
        ret.data.oncRpcReplyRx.xb12 := 0
        ret.data.oncRpcReplyRx.xb16 := 0
      }
      is (HostReqType.arpReq) {
        ret.data.arpReq.assignSomeByName(desc.data.arpReq)
        ret.data.arpReq.xb9 := 0
      }
    }
    ret.len := desc.len
  }.ret
}
