package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import jsteward.blocks.misc.{RegAllocatorFactory, RegBlockAlloc}
import pionic.ConfigDatabase.OneShot
import spinal.lib.bus.misc.BusSlaveFactory

import scala.collection.mutable
import scala.language.postfixOps
import scala.reflect.ClassTag

package object net {
  /**
   * Type of the (potentially partially) decoded packet. Used by [[PacketDesc]] as well as [[pionic.host.HostReqBypassHeaders]].
   */
  object PacketDescType extends SpinalEnum {
    val ethernet, ip, udp, oncRpcCall /*, oncRpcReply */= newElement()

    def addMackerel(f: RegAllocatorFactory) = {
      f.addMackerelEpilogue(getClass,
        """
          |constants packet_desc_type width(2) "Protocol Packet Descriptor Type" {
          |  hdr_ethernet     = 0b00 "Ethernet";
          |  hdr_ip           = 0b01 "IP";
          |  hdr_udp          = 0b10 "UDP";
          |  hdr_onc_rpc_call = 0b11 "ONC-RPC Call (bypass)";
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

  case class PacketDescData()(implicit c: ConfigDatabase) extends Union {
    val ethernet = newElement(EthernetMetadata())
    val ip = newElement(IpMetadata())
    val udp = newElement(UdpMetadata())
    val oncRpcCall = newElement(OncRpcCallMetadata())
  }

  /**
   * [[ProtoMetadata]] plus type information. Used between [[RxPacketDispatch]] and [[DmaControlPlugin]].
   */
  case class PacketDesc()(implicit c: ConfigDatabase) extends Bundle {
    override def clone = PacketDesc()

    val ty = PacketDescType()
    val metadata = PacketDescData()

    def getPayloadSize: UInt = {
      val ret = UInt(16 bits)
      switch (ty) {
        import PacketDescType._
        is (ethernet) { ret := metadata.ethernet.getPayloadSize }
        is (ip) { ret := metadata.ip.getPayloadSize }
        is (udp) { ret := metadata.udp.getPayloadSize }
        is (oncRpcCall) { ret := metadata.oncRpcCall.getPayloadSize }
      }
      ret
    }

    def collectHeaders(implicit c: ConfigDatabase): Bits = {
      val ret = CombInit(B(0, Widths.bphw bits))
      switch (ty) {
        import PacketDescType._
        is (ethernet) { ret := metadata.ethernet.collectHeaders.resized }
        is (ip) { ret := metadata.ip.collectHeaders.resized }
        is (udp) { ret := metadata.udp.collectHeaders.resized }
        is (oncRpcCall) {
          report("oncRpcCall header too big and cannot fit into bypass, should not be collected")
        }
      }
      ret
    }

    c.post("packet desc type width", PacketDescType().getBitsWidth, OneShot)
    c.post("packet desc type ethernet", 0, OneShot)
    c.post("packet desc type ip", 1, OneShot)
    c.post("packet desc type udp", 2, OneShot)
    c.post("packet desc type onc rpc call", 3, OneShot)
  }
}
