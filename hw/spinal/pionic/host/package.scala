package pionic

import jsteward.blocks.misc.RegAllocatorFactory
import pionic.ConfigDatabase.OneShot
import pionic.net.PacketDescType
import spinal.core._

import scala.language.postfixOps

package object host {
  /** Type of request to a host CPU core. */
  object HostReqType extends SpinalEnum {
    val error, bypass, oncRpcCall, oncRpcReply = newElement()

    def addMackerel(f: RegAllocatorFactory)(implicit c: ConfigDatabase) = {
      f.addMackerelEpilogue(getClass,
        s"""
           |constants host_packet_desc_type width(${Widths.bptw}) "Host Packet Descriptor Type" {
           |  error         = 0b00 "Error";
           |  bypass        = 0b01 "Bypass";
           |  onc_rpc_call  = 0b10 "ONC-RPC Call";
           |  onc_rpc_reply = 0b11 "ONC-RPC Reply";
           |};""".stripMargin)
    }
  }

  case class HostReqOncRpcCall()(implicit c: ConfigDatabase) extends Bundle {
    val funcPtr = Bits(64 bits)
    val pid = PID()
    val xid = Bits(32 bits)
    val args = Bits(Widths.oargw bits)
  }

  case class HostReqBypassHeaders()(implicit c: ConfigDatabase) extends Bundle {
    val ty = PacketDescType()
    val hdr = Bits(Widths.bphw bits)
  }

  case class HostReqData()(implicit c: ConfigDatabase) extends Union {
    val bypassMeta = newElement(HostReqBypassHeaders())
    val oncRpcCall = newElement(HostReqOncRpcCall())
  }

  /**
    * Request to a host CPU core, transmitted over ECI in a CL read ([[pionic.host.eci.EciDecoupledRxTxProtocol]]) or
    * PCIe MMIO reads ([[pionic.host.pcie.PcieBridgeInterfacePlugin]]).  Translated from [[pionic.net.PacketDesc]].
    */
  case class HostReq()(implicit c: ConfigDatabase) extends Bundle {
    override def clone = HostReq()

    val buffer = PacketBufDesc()
    val ty = HostReqType()
    val data = HostReqData()

    // FIXME: think of a way to automate this -- with reflection?
    // TODO: generic framework to dump Bundle/Union structure
    c.post("host req type width", ty.getBitsWidth, OneShot)
    c.post("host req type error", 0, OneShot)
    c.post("host req type bypass", 1, OneShot)
    c.post("host req type onc rpc call", 2, OneShot)
    c.post("host req type onc rpc reply", 3, OneShot)
  }
}