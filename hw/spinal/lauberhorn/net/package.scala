package lauberhorn

import spinal.core._
import jsteward.blocks.misc.RegAllocatorFactory
import lauberhorn.host.{HostReq, HostReqBypassHeaders}
import lauberhorn.net.ethernet.{EthernetRxMeta, EthernetTxMeta}

import scala.language.postfixOps
import Global._
import lauberhorn.net.ip.{IpRxMeta, IpTxMeta}
import lauberhorn.net.oncrpc.{OncRpcCallRxMeta, OncRpcReplyTxMeta}

package object net {
  /**
   * Type of the (potentially partially) decoded packet. Used by [[PacketDesc]] as well as [[lauberhorn.host.HostReqBypassHeaders]].
   */
  object PacketDescType extends SpinalEnum {
    val ethernet, ip, udp, oncRpcCall, oncRpcReply = newElement()

    /** Convert a [[PacketDescData]] (generated from a [[HostReqBypassHeaders]] by [[PacketDesc.fromHeaders]]) to
      * [[EncoderMetadata]], for sending to a specific encoder. */
    def selectData[T <: EncoderMetadata](ty: PacketDescType.E, data: PacketDescData): T = {
      ty match {
        case `ethernet` => data.ethernetTx.get().asInstanceOf[T]
        case `ip` => data.ipTx.get().asInstanceOf[T]
        // case `udp` => data.udpTx.get().asInstanceOf[T]
        // case `oncRpcCall` => data.oncRpcCall.get().asInstanceOf[T]
        case `oncRpcReply` => data.oncRpcReply.get().asInstanceOf[T]
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
   * [[lauberhorn.host.HostReq]] to enqueue into the [[Scheduler]].
   */
  trait DecoderMetadata extends Data {
    /** tag metadata sent to cores */
    def getType: PacketDescType.E
    /** size of payload for the payload of this stage */
    def getPayloadSize: UInt
    /** header bits needed to reconstruct packet for bypass delivery */
    def collectHeaders: Bits
    /** cast to union for assigning to [[PacketDescData]] */
    def asUnion: PacketDescData
  }

  /**
    * Metadata for the encoder pipeline.  Every encoder takes a [[EncoderMetadata]] for itself (either another
    * encoder or the host) and generates the metadata for the next stage encoder.  For example, the IP encoder
    * [[lauberhorn.net.ip.IpEncoder]] takes a [[IpTxMetadata]]
    * it can get; e.g. the IP encoder need
    */
  trait EncoderMetadata extends Data {
    /** tag metadata sent from cores */
    def getType: PacketDescType.E
  }

  case class PacketDescData() extends Union {
    // Used by decoder pipeline
    val ethernetRx = newElement(EthernetRxMeta())
    val ipRx = newElement(IpRxMeta())
    val udpRx = newElement(UdpRxMeta())
    val oncRpcCall = newElement(OncRpcCallRxMeta())

    // Used by encoder pipeline
    val ethernetTx = newElement(EthernetTxMeta())
    val ipTx = newElement(IpTxMeta())
    val oncRpcReply = newElement(OncRpcReplyTxMeta())
  }

  /**
   * [[DecoderMetadata]] plus type information. Used between [[DecoderSink]] and [[DmaControlPlugin]].
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
        is (ethernet) { ret := metadata.ethernetRx.getPayloadSize }
        is (ip) { ret := metadata.ipRx.getPayloadSize }
        is (udp) { ret := metadata.udpRx.getPayloadSize }
        is (oncRpcCall) { ret := metadata.oncRpcCall.getPayloadSize }
        default { report("packet desc type not supported yet", FAILURE) }
      }
    }.ret

    /**
      * Collect all headers to generate [[lauberhorn.host.HostReqBypassHeaders]].  Called by [[DmaControlPlugin]] to pack
      * incoming request into a bypass [[HostReq]] to pass to host.
      */
    def collectHeaders: Bits = {
      val ret = CombInit(B(0, BYPASS_HDR_WIDTH bits))
      switch (ty) {
        import PacketDescType._
        is (ethernet) { ret := metadata.ethernetRx.collectHeaders.resized }
        is (ip) { ret := metadata.ipRx.collectHeaders.resized }
        is (udp) { ret := metadata.udpRx.collectHeaders.resized }
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
      * encoder pipeline [[EncoderSource]].
      */
    def fromHeaders(bypassMeta: HostReqBypassHeaders): Unit = {
      ty := bypassMeta.ty
      metadata.assignDontCare()
      switch (bypassMeta.ty) {
        import PacketDescType._
        is (ethernet) { metadata.ethernetTx.assignFromBits(bypassMeta.hdr) }
        is (ip) { metadata.ipTx.assignFromBits(bypassMeta.hdr) }
        // is (udp) { metadata.udpTx.assignFromHdrBits(bypassMeta.hdr) }
        default {
          report("Attempting to send unsupported protocol in bypass", FAILURE)
        }
      }
    }

    PKT_DESC_TY_WIDTH.set(ty.getBitsWidth)
  }
}
