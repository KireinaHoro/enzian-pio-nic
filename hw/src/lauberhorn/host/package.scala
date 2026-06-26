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
    val error, bypass, neighborMiss, oncRpcCallRx, oncRpcReplyTx, oncRpcCallTx, oncRpcReplyRx = newElement()

    def addMackerel() = {
      ALLOC.addMackerelEpilogue(
        s"""
           |constants host_req_type width(${HOST_REQ_TY_WIDTH.get}) "Host Request Type" {
           |  error            = 0b000 "Error";
           |  bypass           = 0b001 "Bypass";
           |  neighbor_miss    = 0b010 "Neighbor miss";
           |  onc_rpc_call_rx  = 0b011 "ONC-RPC Server Call RX";
           |  onc_rpc_reply_tx = 0b100 "ONC-RPC Server Reply TX";
           |  onc_rpc_call_tx  = 0b101 "ONC-RPC Nested Call TX";
           |  onc_rpc_reply_rx = 0b110 "ONC-RPC Nested Reply RX";
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

  /** Sent by host as a nested/client ONC-RPC call. */
  case class HostReqOncRpcCallTx() extends Bundle {
    val pid = PID()
    val cookie = Bits(32 bits)
    val xid = Bits(32 bits)
    val daddr = Bits(32 bits)
    val sport = Bits(16 bits)
    val dport = Bits(16 bits)
    val progNum = Bits(32 bits)
    val progVer = Bits(32 bits)
    val proc = Bits(32 bits)
    val data = Bits(ONCRPC_NESTED_CALL_INLINE_BYTES * 8 bits)

    /** Total length of the call argument payload, including inlined bytes. */
    val callLen = PacketLength()
  }

  /** Passed to host on an incoming nested/client ONC-RPC reply completion. */
  case class HostReqOncRpcReplyRx() extends Bundle {
    val pid = PID()
    val cookie = Bits(32 bits)
    val xid = Bits(32 bits)
    val saddr = Bits(32 bits)
    val sport = Bits(16 bits)
    val dport = Bits(16 bits)
    val data = Bits(ONCRPC_INLINE_BYTES * 8 bits)
  }

  /** Received by host for bypass packets.  Also used for sending bypass packets; when used
    * for sending, [[hdr]] encodes a command to the selected decoder that will get translated
    * into the respective [[lauberhorn.net.EncoderMetadata]] (by [[lauberhorn.net.PacketDesc.fromHeaders]]).
    */
  case class HostReqBypassHeaders() extends Bundle {
    val ty = PacketDescType()
    val hdr = Bits(BYPASS_HDR_WIDTH bits)
  }

  /** Passed to host when the encoder pipeline had a miss in neighbor cache lookup. */
  case class HostReqNeighborMiss() extends Bundle {
    val ipAddr = Bits(32 bits)
    val neighTblIdx = UInt(log2Up(NUM_NEIGHBOR_ENTRIES) bits)
    val saddr = Bits(32 bits)
    val proto = Bits(8 bits)
  }

  case class HostReqData() extends Union {
    val bypassMeta = newElement(HostReqBypassHeaders())
    val oncRpcCallRx = newElement(HostReqOncRpcCallRx())
    val oncRpcReplyTx = newElement(HostReqOncRpcReplyTx())
    val oncRpcCallTx = newElement(HostReqOncRpcCallTx())
    val oncRpcReplyRx = newElement(HostReqOncRpcReplyRx())
    val neighborMiss = newElement(HostReqNeighborMiss())
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
