package lauberhorn.host.eci

import lauberhorn.Global._
import lauberhorn._
import lauberhorn.host.{HostReq, HostReqType}
import lauberhorn.net.PacketDescType
import spinal.core._

import scala.language.postfixOps

/**
  * Control info struct sent to the CPU in cache-line reloads for RX packets,
  * as well as struct received from the CPU for TX packets.
  *
  * This is separate from the rest of the data inside the packet buffer because:
  * - it is expensive to enable unaligned access for the AXI DMA engine
  * - we don't want to pack metadata into the packet buffer SRAM, due to lack of write port
  *
  * We repack [[HostReq]] to save space by dropping fields the host won't need.  We
  * also add padding to align things nicer for CPU.
  *
  * We need the length field here:
  * - for TX, to know how many cache-lines we should invalidate for TX
  * - for TX, to fetch exactly what's needed from the packet buffer with the AXI DMA
  * - for RX, to know how many bytes we should read for bypass packets
  * - for RX, to check if we got a too-short RPC call request
  */
case class EciHostCtrlInfo() extends Bundle {
  override def clone: EciHostCtrlInfo = EciHostCtrlInfo()

  /* reserve one bit for valid in readStream */                       // [0 : 1)  = 1b
  val ty = HostReqType()           // [1 : 4)  = 3b
  val len = PacketLength()                                            // [4 : 20) = 16b
  val data = new Union {
    case class BypassBundle() extends Bundle {
      val ty = PacketDescType() // [20: 23) = 3b
      val xb9 = Bits(9 bits) /* make sure header is word aligned */   // [23: 32) = 9b
      val hdr = Bits(BYPASS_HDR_WIDTH bits)
    }
    val bypass = newElement(BypassBundle())

    /** Shared by two types of ONC-RPC flows:
      *  - receiving an RPC call
      *  - sending an RPC reply
      *
      * This is the ECI-specific version of [[lauberhorn.host.HostReqOncRpcCallRx]].
      *
      * Note that while the reply encoder [[lauberhorn.net.oncrpc.OncRpcReplyEncoder]] requires the length of inlined
      * data in its metadata [[lauberhorn.net.oncrpc.OncRpcReplyTxMeta]], this is passed with the generic
      * [[EciHostCtrlInfo.len]] field and thus not separately encoded.
      */
    case class OncRpcServerBundle() extends Bundle {
      val xb12 = Bits(12 bits) /* make sure RPC fields are aligned */ // [20: 32) = 12 b
      val xid = Bits(32 bits)
      val funcPtr = Bits(64 bits)
      val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
    }
    val oncRpcServer = newElement(OncRpcServerBundle())

    // TODO: client bundles for sending a nested call and receiving a reply

    /** ECI-specific version of [[lauberhorn.host.HostReqArpRequest]]. */
    case class ArpReqBundle() extends Bundle {
      val neighTblIdx = Bits(log2Up(NUM_NEIGHBOR_ENTRIES) bits)       // [20: 23) = 3b
      val xb9 = Bits(9 bits) /* make sure IP addr is aligned */       // [23: 32) = 9b
      val ipAddr = Bits(32 bits)
    }
    val arpReq = newElement(ArpReqBundle())
  }

  /** Convert a [[EciHostCtrlInfo]] to a [[HostReq]], to send out to the encoder pipeline */
  def unpackTo(desc: HostReq, addr: PacketAddr) = {
    desc.ty := ty
    desc.data.assignDontCare()
    switch (ty) {
      is (HostReqType.bypass) {
        desc.data.bypassMeta.assignSomeByName(data.bypass)
        desc.buffer.size := len
      }
      is (HostReqType.oncRpcReply) {
        desc.data.oncRpcReplyTx.assignSomeByName(data.oncRpcServer)
        desc.data.oncRpcReplyTx.replyLen := len
        when (len.bits > ONCRPC_INLINE_BYTES.get) {
          // more reply spilled over to overflow CLs
          desc.buffer.size.bits            := len.bits - ONCRPC_INLINE_BYTES.get
        } otherwise {
          // everything fits inside the inline half-CL
          desc.buffer.size.bits            := 0
        }
      }
    }
    desc.buffer.addr := addr
  }

  // plus one for readStreamBlockCycles
  assert(getBitsWidth + 1 <= HOST_REQ_WIDTH, "host control info larger than half a cacheline")

  def addMackerel = {
    // post header type enum to mackerel
    HostReqType.addMackerel()
    PacketDescType.addMackerel()

    // post descriptor header to mackerel (first 128 bits)
    // FIXME: should we include valid here? valid is present for rx but not for tx
    //        currently included as rsvd bit
    ALLOC.addMackerelEpilogue(this.getClass,
      s"""
         |datatype host_ctrl_info_error lsbfirst(64) "ECI Host Control Info (Error)" {
         |  valid 1 "RX descriptor valid (rsvd for TX)";
         |  ty    ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be error)";
         |  len   ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  _     12 rsvd;
         |};
         |
         |datatype host_ctrl_info_bypass lsbfirst(64) "ECI Host Control Info (Bypass)" {
         |  valid    1 "RX descriptor valid (rsvd for TX)";
         |  ty       ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be bypass)";
         |  len      ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  hdr_ty   ${PKT_DESC_TY_WIDTH.get} type(packet_desc_type) "Type of bypass header";
         |  _        9 rsvd;
         |  // hdr follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Possible approach:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |};
         |
         |datatype host_ctrl_info_onc_rpc_server lsbfirst(64) "ECI Host Control Info (ONC-RPC Direct Call / Reply)" {
         |  valid     1 "RX descriptor valid (rsvd for TX)";
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_call / onc_rpc_reply)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of packet (includes inlined bytes for TX, does not include inlined bytes for RX)";
         |  _         12 rsvd;
         |  xid       32 "XID of incoming request (big endian)";
         |  func_ptr  64 "Function pointer for RPC call handler";
         |  // args follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Two possible approaches:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |  // - as an array, so Mackerel would emit access functions
         |};
         |
         |datatype host_ctrl_info_arp_req lsbfirst(64) "ECI Host Control Info (ARP Request for bypass core)" {
         |  valid     1 "RX descriptor valid (rsvd for TX)";
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be arp_req)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  tbl_idx   ${log2Up(NUM_NEIGHBOR_ENTRIES)} "Index of INCOMPLETE entry in neighbor table";
         |  _         9 rsvd;
         |  ip_addr   32 "IP address of the target host";
         |};
         |
         |// TODO: separate datatype for making a nested RPC call (progNum, ver...)?
         |
         |datatype host_ctrl_info_onc_rpc_reply lsbfirst(64) "ECI Host Control Info (ONC-RPC Reply)" {
         |  // TODO: this datatype does not exist yet, use host_ctrl_info_bypass to send a raw Ethernet frame
         |  valid     1 "RX descriptor valid (rsvd for TX)";
         |  ty        ${HOST_REQ_TY_WIDTH.get} type(host_req_type) "Type of descriptor (should be onc_rpc_reply)";
         |  len       ${PKT_BUF_LEN_WIDTH.get} "Length of packet";
         |  _         12 rsvd;
         |  // buffer follows
         |  // TODO: for now, reply software-serialized data...
         |};
         |
         |datatype host_worker_ctrl lsbfirst(64) "ECI Host Worker Control Info" {
         |  ready     1 "worker ready to serve the next request (the thread is allowed to enter the critical section)";
         |  busy      1 "the thread is in the critical section";
         |  _         6 rsvd;
         |};
         """.stripMargin)
  }
}

object EciHostCtrlInfo {
  /** Convert a [[HostReq]] from the decoder pipeline into a [[EciHostCtrlInfo]] to send to a CPU core */
  def packFrom(desc: HostReq) = new Area {
    val ret = EciHostCtrlInfo()
    ret.ty := desc.ty
    ret.data.assignDontCare()
    switch (desc.ty) {
      is (HostReqType.bypass) {
        ret.data.bypass.assignSomeByName(desc.data.bypassMeta)
        ret.data.bypass.xb9 := 0
      }
      is (HostReqType.oncRpcCall) {
        ret.data.oncRpcServer.assignSomeByName(desc.data.oncRpcCallRx)
        ret.data.oncRpcServer.xb12 := 0
      }
      is (HostReqType.arpReq) {
        ret.data.arpReq.assignSomeByName(desc.data.arpReq)
        ret.data.arpReq.xb9 := 0
      }
    }
    ret.len := desc.buffer.size
  }.ret
}
