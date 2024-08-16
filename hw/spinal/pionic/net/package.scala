package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import spinal.lib.bus.misc.BusSlaveFactory

import scala.collection.mutable
import scala.reflect.ClassTag

package object net {
  /**
   * Type of the (potentially partially) decoded packet. Used by [[TaggedProtoPacketDesc]] as well as [[HostPacketDesc]].
   */
  object ProtoPacketDescType extends SpinalEnum {
    val ethernet, ip, udp, oncRpcCall = newElement()
  }

  /**
   * Protocol metadata passed along in the decoder pipeline and to the core control module.
   *
   * Should also provide all information needed to reconstruct the entire packet for exceptional delivery to the TAP
   * interface (bypass).  [[CoreControlPlugin]] will translate this to a [[HostPacketDesc]], dropping fields that the
   * host does not need.
   */
  trait ProtoPacketDesc extends Data {
    // used to tag metadata sent to cores
    def getType: ProtoPacketDescType.E
    def getPayloadSize: UInt
    def collectHeaders: Bits
    def asUnion: ProtoPacketDescData
  }

  case class ProtoPacketDescData()(implicit config: PioNicConfig) extends Union {
    val ethernet = newElement(EthernetMetadata())
    val ip = newElement(IpMetadata())
    val udp = newElement(UdpMetadata())
    val oncRpcCall = newElement(OncRpcCallMetadata())
  }

  /**
   * [[ProtoPacketDesc]] plus type information. Used between [[PacketSink]] and [[CoreControlPlugin]].
   */
  case class TaggedProtoPacketDesc()(implicit config: PioNicConfig) extends Bundle {
    override def clone = TaggedProtoPacketDesc()

    val ty = ProtoPacketDescType()
    val metadata = ProtoPacketDescData()

    def getPayloadSize: UInt = {
      val ret = CombInit(U("16'x0"))
      switch (ty) {
        import ProtoPacketDescType._
        is (ethernet) { ret := metadata.ethernet.getPayloadSize }
        is (ip) { ret := metadata.ip.getPayloadSize }
        is (udp) { ret := metadata.udp.getPayloadSize }
        is (oncRpcCall) { ret := metadata.oncRpcCall.getPayloadSize }
      }
      ret
    }

    def collectHeaders: Bits = {
      val ret = CombInit(B("512'x0"))
      switch (ty) {
        import ProtoPacketDescType._
        is (ethernet) { ret := metadata.ethernet.collectHeaders }
        is (ip) { ret := metadata.ip.collectHeaders }
        is (udp) { ret := metadata.udp.collectHeaders }
        is (oncRpcCall) { ret := metadata.oncRpcCall.collectHeaders }
      }
      ret
    }
  }

  trait ProtoDecoder[T <: ProtoPacketDesc] extends FiberPlugin {
    // downstream decoder, condition to match
    // e.g. Ip.downs = [ (Tcp, proto === 6), (Udp, proto === 17) ]
    val consumers = mutable.ListBuffer[(T => Bool, Stream[T], Axi4Stream)]()
    lazy val csr = host[GlobalCSRPlugin].logic

    // possible upstream carriers
    // e.g. oncRpc.from(Tcp -> <port registered>, Udp -> <port registered>)
    // this is not called for the source decoder (ethernet)

    /**
     * Specify one possible upstream decoder, where this decoder takes packets from.
     * @param matcher condition when a packet from the upstream can be decoded by us.  This precludes other decoders from
     *                attempting to decode.
     * @param metadata output metadata stream
     * @param payload output payload stream
     * @tparam M type of upstream packet descriptor
     * @tparam D type of upstream packet decoder
     */
    def from[M <: ProtoPacketDesc, D <: ProtoDecoder[M]: ClassTag](matcher: M => Bool, metadata: Stream[M], payload: Axi4Stream): Unit = {
      host[D].consumers.append((matcher, metadata, payload))
    }

    /**
     * Specify output of this decoder, for downstream decoders to consume.  Forks the streams for all consumers and
     * produce a copy for bypass to the [[PacketSink]].
     * @param metadata metadata stream produced by this stage
     * @param payload payload data stream produced by this stage
     */
    def produce(metadata: Stream[T], payload: Axi4Stream): Unit = new Area {
      // FIXME: do we need synchronous here?
      val forkedHeaders = StreamFork(metadata, consumers.length + 1)//, synchronous = true)
      val forkedPayloads = StreamFork(payload, consumers.length + 1)

      val attempted = CombInit(False)
      consumers.zipWithIndex foreach { case ((matchFunc, headerSink, payloadSink), idx) => new Area {
        // is it possible to go to this consumer?
        val hdr = forkedHeaders(idx)
        val pld = forkedPayloads(idx)

        // FIXME: timing!  what happens if the downstream decoder is busy?
        val attempt = matchFunc(hdr.payload)

        // track attempt for bypass
        attempted := attempted | attempt

        headerSink << hdr.takeWhen(attempt)
        payloadSink << pld.takeFrameWhen(attempt)
      }
      }

      val bypassHeader = forkedHeaders.last.takeWhen(!attempted).setName(s"bypassHeader_${getClass.getName}")
      val bypassPayload = forkedPayloads.last.takeFrameWhen(!attempted).setName(s"bypassPayload_${getClass.getName}")

      host[PacketSinkService].consume(bypassPayload, bypassHeader)
    }

    def driveControl(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit
  }

  trait ProtoEncoder {

  }
}
