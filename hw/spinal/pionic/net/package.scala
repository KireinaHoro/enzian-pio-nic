package pionic

import spinal.core._
import jsteward.blocks.misc.RegAllocatorFactory
import pionic.host.{HostReq, HostReqBypassHeaders}
import pionic.net.ethernet.EthernetMetadata

import scala.language.postfixOps

import Global._

package object net {
  /**
   * Type of the (potentially partially) decoded packet. Used by [[PacketDesc]] as well as [[pionic.host.HostReqBypassHeaders]].
   */
  object PacketDescType extends SpinalEnum {
    val raw, ethernet, ip, udp, oncRpcCall, oncRpcReply = newElement()

    def selectData[T <: ProtoMetadata](ty: PacketDescType.E, data: PacketDescData): T = {
      ty match {
        case `raw` => NoMetadata().asInstanceOf[T]
        case `ethernet` => data.ethernet.asInstanceOf[T]
        case `ip` => data.ip.asInstanceOf[T]
        case `udp` => data.udp.asInstanceOf[T]
        case `oncRpcCall` => data.oncRpcCall.asInstanceOf[T]
        case `oncRpcReply` => data.oncRpcCall.asInstanceOf[T]
      }
    }

    def addMackerel() = {
      ALLOC.addMackerelEpilogue(getClass,
        s"""
          |constants packet_desc_type width(${PKT_DESC_TY_WIDTH.get}) "Packet Descriptor Type" {
          |  hdr_raw           = 0b000 "Raw";
          |  hdr_ethernet      = 0b001 "Ethernet";
          |  hdr_ip            = 0b010 "IP";
          |  hdr_udp           = 0b011 "UDP";
          |  hdr_onc_rpc_call  = 0b100 "ONC-RPC Call (bypass)";
          |  hdr_onc_rpc_reply = 0b101 "ONC-RPC Reply (bypass)";
          |};""".stripMargin)
    }
  }

  /**
   * Protocol metadata passed along in the decoder pipeline.  Should provide all information needed to reconstruct the
   * entire packet for exceptional delivery to the bypass interface.  [[DmaControlPlugin]] will translate this to a
   * [[pionic.host.HostReq]] to enqueue into the [[Scheduler]].
   *
   * Also used in the encoder pipeline.  Each stage is only responsible for filling in the information that it can get.
   * E.g. ONC-RPC encoder does not need to fill in Ethernet addresses; this is the responsibility of the IP
   * encoder.
   */
  trait ProtoMetadata extends Data {
    /** tag metadata sent to cores */
    def getType: PacketDescType.E
    /** size of payload for the payload of this stage */
    def getPayloadSize: UInt
    /** header bits needed to reconstruct packet for bypass delivery */
    def collectHeaders: Bits
    /** cast to union for assigning to [[PacketDescData]] */
    def asUnion: PacketDescData
  }

  case class PacketDescData() extends Union {
    val ethernet = newElement(EthernetMetadata())
    val ip = newElement(IpMetadata())
    val udp = newElement(UdpMetadata())
    val oncRpcCall = newElement(OncRpcCallMetadata())
  }

  /**
   * [[ProtoMetadata]] plus type information. Used between [[RxDecoderSink]] and [[DmaControlPlugin]].
   */
  case class PacketDesc() extends Bundle {
    override def clone = PacketDesc()

    val ty = PacketDescType()
    val metadata = PacketDescData()

    def getPayloadSize: UInt = new Composite(this, "getPayloadSize") {
      val ret = UInt(16 bits)
      ret.assignDontCare()
      switch (ty) {
        import PacketDescType._
        is (raw) { ret := 0 }
        is (ethernet) { ret := metadata.ethernet.getPayloadSize }
        is (ip) { ret := metadata.ip.getPayloadSize }
        is (udp) { ret := metadata.udp.getPayloadSize }
        is (oncRpcCall) { ret := metadata.oncRpcCall.getPayloadSize }
        default { report("packet desc type not supported yet", FAILURE) }
      }
    }.ret

    /**
      * Collect all headers to generate [[pionic.host.HostReqBypassHeaders]].  Called by [[DmaControlPlugin]] to pack
      * incoming request into a bypass [[HostReq]] to pass to host.
      */
    def collectHeaders: Bits = {
      val ret = CombInit(B(0, BYPASS_HDR_WIDTH bits))
      switch (ty) {
        import PacketDescType._
        is (ethernet) { ret := metadata.ethernet.collectHeaders.resized }
        is (ip) { ret := metadata.ip.collectHeaders.resized }
        is (udp) { ret := metadata.udp.collectHeaders.resized }
        default {
          // only decoders with downstream decoders can be passed to host as bypass
          report("RX packet on bypass interface has unsupported type", FAILURE)
        }
      }
      ret
    }

    /**
      * Take header bits in [[HostReqBypassHeaders]] passed by host and fill out relevant fields in this
      * [[PacketDesc]].  Called by [[DmaControlPlugin]] to pass an outgoing packet on the bypass interface to the
      * encoder pipeline [[TxEncoderSource]].
      */
    def fromHeaders(bypassMeta: HostReqBypassHeaders): Unit = {
      ty := bypassMeta.ty
      switch (bypassMeta.ty) {
        import PacketDescType._
        is (raw) { metadata.assignDontCare() }
        is (ethernet) { metadata.ethernet.assignFromHdrBits(bypassMeta.hdr) }
        is (ip) { metadata.ip.assignFromHdrBits(bypassMeta.hdr) }
        is (udp) { metadata.udp.assignFromHdrBits(bypassMeta.hdr) }
        default {
          report("RPC requests should not be sent as bypass", FAILURE)
        }
      }
    }

    PKT_DESC_TY_WIDTH.set(ty.getBitsWidth)
  }
}
