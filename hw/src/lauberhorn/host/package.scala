package lauberhorn

import jsteward.blocks.misc.RegAllocatorFactory
import lauberhorn.Global._
import lauberhorn.net.PacketDescType
import spinal.core._
import spinal.lib.NoData

import scala.language.postfixOps

package object host {
  /** Type of request to a host CPU core. */
  object HostReqType extends SpinalEnum {
    val error, bypass, arpReq, oncRpcCall, oncRpcReply = newElement()

    def addMackerel() = {
      ALLOC.addMackerelEpilogue(
        s"""
           |constants host_req_type width(${HOST_REQ_TY_WIDTH.get}) "Host Request Type" {
           |  error         = 0b000 "Error";
           |  bypass        = 0b001 "Bypass";
           |  arp_req       = 0b010 "ARP Request";
           |  onc_rpc_call  = 0b011 "ONC-RPC Call";
           |  onc_rpc_reply = 0b100 "ONC-RPC Reply";
           |};""".stripMargin)
    }
  }

  /** Passed to host on an incoming ONC-RPC call.  Separate from [[lauberhorn.net.oncrpc.OncRpcReplyTxMeta]] since that
    * needs the number of words filled in the data array.
    */
  case class HostReqOncRpcCallRx() extends Bundle {
    val funcPtr = Bits(64 bits)
    val pid = PID()
    val xid = Bits(32 bits)
    val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
  }

  /** Sent by host as an ONC-RPC reply.  Trace IDs are owned by packet-processing
    * modules and must not be encoded in host-visible descriptors.
    */
  case class HostReqOncRpcReplyTx() extends Bundle {
    val funcPtr = Bits(64 bits)
    val xid = Bits(32 bits)
    val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)

    /** Total length of the entire reply message, including inlined bits. */
    val replyLen = PacketLength()
  }

  // TODO: client bundles for sending a nested call and receiving a reply

  /** Received by host for bypass packets.  Also used for sending bypass packets; when used
    * for sending, [[hdr]] encodes a command to the selected decoder that will get translated
    * into the respective [[lauberhorn.net.EncoderMetadata]] (by [[lauberhorn.net.PacketDesc.fromHeaders]]).
    */
  case class HostReqBypassHeaders() extends Bundle {
    val ty = PacketDescType()
    val hdr = Bits(BYPASS_HDR_WIDTH bits)
  }

  /** Passed to host when the encoder pipeline had a miss in neighbor cache lookup. */
  case class HostReqArpRequest() extends Bundle {
    val ipAddr = Bits(32 bits)
    val neighTblIdx = UInt(log2Up(NUM_NEIGHBOR_ENTRIES) bits)
  }

  case class HostReqData() extends Union {
    val bypassMeta = newElement(HostReqBypassHeaders())
    val oncRpcCallRx = newElement(HostReqOncRpcCallRx())
    val oncRpcReplyTx = newElement(HostReqOncRpcReplyTx())
    val arpReq = newElement(HostReqArpRequest())
  }

  /**
    * Request to a host CPU core, transmitted over ECI in a CL read ([[lauberhorn.host.eci.EciDecoupledRxTxProtocol]]) or
    * PCIe MMIO reads ([[lauberhorn.host.pcie.PcieBridgeInterfacePlugin]]).  Translated from [[lauberhorn.net.PacketDesc]].
    */
  case class HostReq() extends Bundle {
    override def clone = HostReq()

    val buffer = PacketBufDesc()
    val len = PacketLength()
    val ty = HostReqType()
    val data = HostReqData()

    HOST_REQ_TY_WIDTH.set(ty.getBitsWidth)
  }

  /** Trace-only sideband carried next to a host request.  This must never be
    * encoded into host-visible descriptors.
    */
  case class HostReqWithTrace() extends Bundle {
    override def clone = HostReqWithTrace()

    val req = HostReq()
    val hostMsgId = UInt(HostMsgID.width bits)
  }
}
