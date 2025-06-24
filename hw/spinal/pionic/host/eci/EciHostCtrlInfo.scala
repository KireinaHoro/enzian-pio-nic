package pionic.host.eci

import pionic.Global._
import pionic._
import pionic.host.{HostReq, HostReqType}
import pionic.net.PacketDescType
import spinal.core._

import scala.language.postfixOps

/**
 * Control info struct sent to the CPU in cache-line reloads for RX packets,
 * as well as struct received from the CPU for TX packets.
 *
 * This is separate from the rest of the data inside packet buffer because:
 * - it is expensive to enable unaligned access for the AXI DMA engine
 * - we don't want to pack metadata into the packet buffer SRAM, due to lack of write port
 *
 * We repack [[pionic.HostPacketDesc]] to save space and also align things nicer for CPU.
 *
 * We need the length field here:
 * - for TX, to know how many cache-lines we should invalidate for TX
 * - for TX, to fetch exactly what's needed from the packet buffer with the AXI DMA
 * - for RX, to know how many bytes we should read for bypass packets
 * - for RX, to check if we got a too-short RPC call request
 */
case class EciHostCtrlInfo() extends Bundle {
  override def clone: EciHostCtrlInfo = EciHostCtrlInfo()

  // reserve one bit for valid in readStream
  val ty = HostReqType() // 2b
  val len = PacketLength() // 16b
  val data = new Union {
    case class BypassBundle() extends Bundle {
      val ty = PacketDescType() // 2b
      val xb11 = Bits(11 bits) // make sure header is word aligned
      val hdr = Bits(BYPASS_HDR_WIDTH bits)
    }
    val bypass = newElement(BypassBundle())

    case class OncRpcCallBundle() extends Bundle {
      val xb13 = Bits(13 bits)
      val xid = Bits(32 bits)
      val funcPtr = Bits(64 bits)
      val args = Bits(ONCRPC_INLINE_BYTES * 8 bits)
    }
    val oncRpcCall = newElement(OncRpcCallBundle())
  }

  def unpackTo(desc: HostReq) = {
    desc.ty := ty
    desc.data.assignDontCare()
    switch (ty) {
      is (HostReqType.bypass) {
        desc.data.bypassMeta.assignSomeByName(data.bypass)
      }
      is (HostReqType.oncRpcCall) {
        desc.data.oncRpcCall.assignSomeByName(data.oncRpcCall)
      }
    }
    desc.buffer.size := len
  }

  // plus one for readStreamBlockCycles
  assert(getBitsWidth + 1 <= HOST_REQ_WIDTH, "host control info larger than half a cacheline")

  def addMackerel = {
    // post header type enum to mackerel
    // HostReqType.addMackerel(c.f)
    PacketDescType.addMackerel(c.f)

    // post descriptor header to mackerel (first 128 bits)
    import Widths._
    // FIXME: should we include valid here? valid is present for rx but not for tx
    //        currently included as rsvd bit
    c.f.addMackerelEpilogue(this.getClass,
      s"""
         |datatype host_ctrl_info_error lsbfirst(64) "ECI Host Control Info (Error)" {
         |  valid 1 "RX descriptor valid (rsvd for TX)";
         |  ty    $HOST_REQ_TY_WIDTH type(host_req_type) "Type of descriptor (should be error)";
         |  len   $PKT_BUF_LEN_WIDTH "Length of packet";
         |  _     13 rsvd;
         |};
         |
         |datatype host_ctrl_info_bypass lsbfirst(64) "ECI Host Control Info (Bypass)" {
         |  valid    1 "RX descriptor valid (rsvd for TX)";
         |  ty       $HOST_REQ_TY_WIDTH type(host_req_type) "Type of descriptor (should be bypass)";
         |  len      $PKT_BUF_LEN_WIDTH "Length of packet";
         |  hdr_ty   $PKT_DESC_TY_WIDTH type(packet_desc_type) "Type of bypass header";
         |  _        11 rsvd;
         |  // hdr follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Possible approach:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |};
         |
         |datatype host_ctrl_info_onc_rpc_call lsbfirst(64) "ECI Host Control Info (ONC-RPC Call)" {
         |  valid     1 "RX descriptor valid (rsvd for TX)";
         |  ty        $HOST_REQ_TY_WIDTH type(host_req_type) "Type of descriptor (should be onc_rpc_call)";
         |  len       $PKT_BUF_LEN_WIDTH "Length of packet";
         |  _         13 rsvd;
         |  xid       32 "XID of incoming request";
         |  func_ptr  64 "Function pointer for RPC call handler";
         |  // args follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Two possible approaches:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |  // - as an array, so Mackerel would emit access functions
         |};""".stripMargin)
  }
}

object EciHostCtrlInfo {
  def packFrom(desc: HostReq) = new Area {
    val ret = EciHostCtrlInfo()
    ret.ty := desc.ty
    ret.data.assignDontCare()
    switch (desc.ty) {
      is (HostReqType.bypass) {
        ret.data.bypass.assignSomeByName(desc.data.bypassMeta)
        ret.data.bypass.xb11 := 0
      }
      is (HostReqType.oncRpcCall) {
        ret.data.oncRpcCall.assignSomeByName(desc.data.oncRpcCall)
        ret.data.oncRpcCall.xb13 := 0
      }
    }
    ret.len := desc.buffer.size
  }.ret
}
