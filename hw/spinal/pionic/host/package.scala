package pionic

import jsteward.blocks.misc.RegAllocatorFactory
import pionic.Global._
import pionic.net.PacketDescType
import spinal.core._

import scala.language.postfixOps

package object host {
  /** Type of request to a host CPU core. */
  object HostReqType extends SpinalEnum {
    val error, bypass, oncRpcCall, oncRpcReply = newElement()

    def addMackerel() = {
      ALLOC.addMackerelEpilogue(getClass,
        s"""
           |constants host_req_type width(${HOST_REQ_TY_WIDTH.get}) "Host Request Type" {
           |  error         = 0b00 "Error";
           |  bypass        = 0b01 "Bypass";
           |  onc_rpc_call  = 0b10 "ONC-RPC Call";
           |  onc_rpc_reply = 0b11 "ONC-RPC Reply";
           |};""".stripMargin)
    }
  }

  case class HostReqOncRpcCall() extends Bundle {
    val funcPtr = Bits(64 bits)
    val pid = PID()
    val xid = Bits(32 bits)
    val args = Bits(ONCRPC_INLINE_BYTES * 8 bits)
  }

  case class HostReqBypassHeaders() extends Bundle {
    val ty = PacketDescType()
    val hdr = Bits(BYPASS_HDR_WIDTH bits)
  }

  case class HostReqData() extends Union {
    val bypassMeta = newElement(HostReqBypassHeaders())
    val oncRpcCall = newElement(HostReqOncRpcCall())
  }

  /**
    * Request to a host CPU core, transmitted over ECI in a CL read ([[pionic.host.eci.EciDecoupledRxTxProtocol]]) or
    * PCIe MMIO reads ([[pionic.host.pcie.PcieBridgeInterfacePlugin]]).  Translated from [[pionic.net.PacketDesc]].
    */
  case class HostReq() extends Bundle {
    override def clone = HostReq()

    val buffer = PacketBufDesc()
    val ty = HostReqType()
    val data = HostReqData()

    HOST_REQ_TY_WIDTH.set(ty.getBitsWidth)
  }
}