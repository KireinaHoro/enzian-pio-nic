package pionic.host.eci

import jsteward.blocks.misc.RegAllocatorFactory
import pionic._
import spinal.core._

/**
 * Control info struct sent to the CPU in cache-line reloads for RX packets, as well as struct received from the
 * CPU for TX packets.
 *
 * This is separate from the rest of the data inside packet buffer because:
 * - it is expensive to enable unaligned access for the AXI DMA engine
 * - we don't want to pack metadata into the packet buffer SRAM, due to lack of write port
 *
 * We need the length field here:
 * - for TX, to know how many cache-lines we should invalidate for TX
 * - for TX, to fetch exactly what's needed from the packet buffer with the AXI DMA
 * - for RX, to know how many bytes we should read for bypass packets
 * - for RX, to check if we got a too-short RPC call request
 */
case class EciHostCtrlInfo()(implicit c: ConfigDatabase) extends Bundle {
  val ty = HostPacketDescType()
  val len = PacketLength()
  val data = HostPacketDescData()

  // plus one for readStreamBlockCycles
  assert(getBitsWidth + 1 <= c[Int]("max host desc size") * 8, "host control info larger than half a cacheline")

  def addMackerel = {
    // post layout declarations to regfactory
    HostPacketDescType.addMackerel(c.f)

    val o = OncRpcCallData()

    import Widths._
    c.f.addMackerelEpilogue(this.getClass,
      s"""
         |datatype eci_host_ctrl_info_error lsbfirst(64) "ECI Host Control Info (Error)" {
         |  ty   $tw type(host_packet_desc_type) "Type of descriptor (should be error)";
         |  len  $lw "Length of packet";
         |  _    ${512-tw-lw} rsvd;
         |};
         |
         |datatype eci_host_ctrl_info_bypass lsbfirst(64) "ECI Host Control Info (Bypass)" {
         |  ty       $tw type(host_packet_desc_type) "Type of descriptor (should be bypass)";
         |  len      $lw "Length of packet";
         |  hdr_ty   $bptw type(proto_packet_desc_type) "Type of bypass header";
         |  hdr      $bphw "Header data";
         |  _        ${512-tw-lw-HostBypassHeaders().getBitsWidth} rsvd;
         |};
         |
         |datatype eci_host_ctrl_info_onc_rpc_call lsbfirst(64) "ECI Host Control Info (ONC-RPC Call)" {
         |  ty        $tw type(host_packet_desc_type) "Type of descriptor (should be onc_rpc_call)";
         |  len       $lw "Length of packet";
         |  func_ptr  ${o.funcPtr.getBitsWidth} "Function pointer for RPC call handler";
         |  xid       ${o.xid.getBitsWidth} "XID of incoming request";
         |  args      ${o.args.getBitsWidth} "Inlined ONC-RPC Call arguments";
         |  _         ${512-tw-lw-o.getBitsWidth} rsvd;
         |};""".stripMargin)
  }
}