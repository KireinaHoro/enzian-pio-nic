package pionic.host.pcie

import pionic._
import pionic.net.ProtoPacketDescType
import spinal.core._

/**
  * Control info struct sent to the CPU in RX descriptor load through hostRx,
  * as well as struct received from the CPU for TX through hostTxAck.  Same
  * principle as [[pionic.host.eci.EciHostCtrlInfo]].
  */
// TODO: reduce redundant code
case class PcieHostCtrlInfo()(implicit c: ConfigDatabase) extends Bundle {
  override def clone: PcieHostCtrlInfo = PcieHostCtrlInfo()

  // reserve one bit from valid in readStream
  val buffer = PacketBufDesc() // 24 + 16b
  val ty = HostPacketDescType() // 2b
  val data = new Union {
    case class BypassBundle() extends Bundle {
      val ty = ProtoPacketDescType() // 2b
      val xb19 = Bits(19 bits) // make sure header is word aligned
      val hdr = Bits(Widths.bphw bits)
    }
    val bypass = newElement(BypassBundle())

    case class OncRpcCallBundle() extends Bundle {
      val xb21 = Bits(21 bits)
      val xid = Bits(32 bits)
      val funcPtr = Bits(64 bits)
      val args = Bits(Widths.oargw bits)
    }
    val oncRpcCall = newElement(OncRpcCallBundle())
  }

  def unpackTo(desc: HostPacketDesc) = {
    desc.ty := ty
    desc.data.assignDontCare()
    switch (ty) {
      is (HostPacketDescType.bypass) {
        desc.data.bypassMeta.assignSomeByName(data.bypass)
      }
      is (HostPacketDescType.oncRpcCall) {
        desc.data.oncRpcCall.assignSomeByName(data.oncRpcCall)
      }
    }
    desc.buffer := buffer
  }

  def addMackerel = {
    // post header type enum to mackerel
    HostPacketDescType.addMackerel(c.f)
    ProtoPacketDescType.addMackerel(c.f)

    // post descriptor header to mackerel
    import Widths._
    c.f.addMackerelEpilogue(this.getClass,
      s"""
         |datatype host_ctrl_info_error lsbfirst(64) "PCIe Host Control Info (Error)" {
         |  valid 1   "RX descriptor valid (rsvd for TX)";
         |  addr  $aw "Address in packet buffer";
         |  size  $lw "Length of packet";
         |  ty    $tw type(host_packet_desc_type) "Type of descriptor (should be error)";
         |  _     21  rsvd;
         |};
         |datatype host_ctrl_info_bypass lsbfirst(64) "PCIe Host Control Info (Bypass)" {
         |  valid  1   "RX descriptor valid (rsvd for TX)";
         |  addr   $aw "Address in packet buffer";
         |  size   $lw "Length of packet";
         |  ty     $tw type(host_packet_desc_type) "Type of descriptor (should be bypass)";
         |  hdr_ty $bptw type(proto_packet_desc_type) "Type of bypass header";
         |  _      19 rsvd;
         |  // hdr follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Possible approach:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |};
         |datatype host_ctrl_info_onc_rpc_call lsbfirst(64) "PCIe Host Control Info (ONC-RPC Call)" {
         |  valid  1   "RX descriptor valid (rsvd for TX)";
         |  addr   $aw "Address in packet buffer";
         |  size   $lw "Length of packet";
         |  ty     $tw type(host_packet_desc_type) "Type of descriptor (should be onc_rpc_call)";
         |  _      21   rsvd;
         |  xid    32  "XID of incoming request";
         |  func_ptr 64 "Function pointer for RPC call handler";
         |  // args follows -- need to calculate address manually
         |  // TODO: actually define args in the datatype.  Two possible approaches:
         |  // - as an address-only field, so no hdr+size pointer calculation in user code
         |  // - as an array, so Mackerel would emit access functions
         |};
         |""".stripMargin
    )
  }
}

object PcieHostCtrlInfo {
  def packFrom(desc: HostPacketDesc)(implicit c: ConfigDatabase) = new Area {
    val ret = PcieHostCtrlInfo()
    ret.ty := desc.ty
    ret.data.assignDontCare()
    switch (desc.ty) {
      is (HostPacketDescType.bypass) {
        ret.data.bypass.assignSomeByName(desc.data.bypassMeta)
        ret.data.bypass.xb19 := 0
      }
      is (HostPacketDescType.oncRpcCall) {
        ret.data.oncRpcCall.assignSomeByName(desc.data.oncRpcCall)
        ret.data.oncRpcCall.xb21 := 0
      }
    }
    ret.buffer := desc.buffer
  }.ret
}
