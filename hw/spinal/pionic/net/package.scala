package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin._
import jsteward.blocks.axi._
import spinal.lib.bus.misc.BusSlaveFactory

import scala.collection.mutable
import scala.language.postfixOps
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
    /** tag metadata sent to cores */
    def getType: ProtoPacketDescType.E
    /** size of payload for the payload of this stage */
    def getPayloadSize: UInt
    /** header bits needed to reconstruct packet for bypass delivery */
    def collectHeaders: Bits
    /** cast to union for assigning to [[ProtoPacketDescData]] */
    def asUnion: ProtoPacketDescData
  }

  case class ProtoPacketDescData()(implicit c: ConfigDatabase) extends Union {
    val ethernet = newElement(EthernetMetadata())
    val ip = newElement(IpMetadata())
    val udp = newElement(UdpMetadata())
    val oncRpcCall = newElement(OncRpcCallMetadata())
  }

  /**
   * [[ProtoPacketDesc]] plus type information. Used between [[RxPacketDispatch]] and [[CoreControlPlugin]].
   */
  case class TaggedProtoPacketDesc()(implicit c: ConfigDatabase) extends Bundle {
    override def clone = TaggedProtoPacketDesc()

    val ty = ProtoPacketDescType()
    val metadata = ProtoPacketDescData()

    def getPayloadSize: UInt = {
      val ret = UInt(16 bits)
      switch (ty) {
        import ProtoPacketDescType._
        is (ethernet) { ret := metadata.ethernet.getPayloadSize }
        is (ip) { ret := metadata.ip.getPayloadSize }
        is (udp) { ret := metadata.udp.getPayloadSize }
        is (oncRpcCall) { ret := metadata.oncRpcCall.getPayloadSize }
      }
      ret
    }

    def collectHeaders(implicit c: ConfigDatabase): Bits = {
      val ret = CombInit(B(0, c[Int]("bypass header max width") bits))
      switch (ty) {
        import ProtoPacketDescType._
        is (ethernet) { ret := metadata.ethernet.collectHeaders.resized }
        is (ip) { ret := metadata.ip.collectHeaders.resized }
        is (udp) { ret := metadata.udp.collectHeaders.resized }
        is (oncRpcCall) {
          report("oncRpcCall header too big and cannot fit into bypass, should not be collected")
        }
      }
      ret
    }
  }

  /**
   * Base class of one stage in the RX decoder pipeline.  Should in general decode one protocol header (or one segment
   * in a protocol, e.g. IPv6 optional headers).
   *
   * Decoder stages form a DAG.  For example, UDP hosts multiple protocols like ONCRPC, gRPC, and memcached;
   * ONCRPC might take data from UDP and TCP.  The DAG is specified by [[from]].
   * @tparam T output metadata type
   */
  trait ProtoDecoder[T <: ProtoPacketDesc] extends PioNicPlugin {
    /**
     * Downstream decoders interfaces and their conditions to match.
     * e.g. Ip.downs = [ (Tcp, proto === 6), (Udp, proto === 17) ]
     */
    private val consumers = mutable.ListBuffer[(String, T => Bool, Stream[T], Axi4Stream)]()

    val rxRg = during setup retains(host[RxPacketDispatchService].retainer)

    /**
     * Specify one possible upstream decoder, where this decoder takes packets from.  Can be invoked multiple times
     * in the setup phase.
     * @param matcher condition when a packet from the upstream can be decoded by us.  This precludes other decoders from
     *                attempting to decode.
     * @param metadata output metadata stream
     * @param payload output payload stream
     * @tparam M type of upstream packet descriptor
     * @tparam D type of upstream packet decoder
     */
    protected def from[M <: ProtoPacketDesc, D <: ProtoDecoder[M]: ClassTag](matcher: M => Bool, metadata: Stream[M], payload: Axi4Stream): Unit = {
      host[D].consumers.append((this.getDisplayName(), matcher, metadata, payload))
    }

    /**
     * Specify output of this decoder, for downstream decoders to consume.  Forks the streams for all consumers and
     * produce a copy for bypass to the [[RxPacketDispatch]].  Should only be invoked **once** in the build phase.
     *
     * @param metadata metadata stream produced by this stage
     * @param payload payload data stream produced by this stage
     */
    protected def produce(metadata: Stream[T], payload: Axi4Stream): Unit = new Composite(this, "produce") {
      // FIXME: do we need synchronous here?
      val forkedHeaders = StreamFork(metadata, consumers.length + 1)//, synchronous = true)
      val forkedPayloads = StreamFork(payload, consumers.length + 1)

      val attempts = mutable.ListBuffer[Bool]()
      consumers.zipWithIndex foreach { case ((name, matchFunc, headerSink, payloadSink), idx) => new Composite(this, s"to_$name") {
        // is it possible to go to this consumer?
        val hdr = forkedHeaders(idx)
        val pld = forkedPayloads(idx)

        // FIXME: timing!  what happens if the downstream decoder is busy?
        val attempt = matchFunc(hdr.payload)
        attempts.append(attempt)

        headerSink << hdr.takeWhen(attempt)
        payloadSink << pld.takeFrameWhen(hdr.asFlow ~ attempt)
      }
      }

      val attempted = attempts.reduceBalancedTree(_ || _)
      val bypassThrow = Flow(Bool())
      bypassThrow.payload := attempted
      bypassThrow.valid := attempted

      val bypassHeader = forkedHeaders.last.takeWhen(!attempted)
      val bypassPayload = forkedPayloads.last.throwFrameWhen(bypassThrow) setName "bypassPayload"

      host[RxPacketDispatchService].consume(bypassPayload, bypassHeader) setCompositeName(this, "dispatchBypass")
    }

    /**
     * Specify output of this decoder, for the host CPU to consume.  This gets fed to [[RxPacketDispatch]] directly.  May be
     * invoked multiple times during setup phase; useful when decoder takes multiple upstreams (using [[from]]).
     *
     * @param metadata metadata stream produced by this stage
     * @param payload payload data stream produced by this stage
     * @param coreMask enable mask of non-bypass cores; used for scheduling
     */
    protected def produceFinal(metadata: Stream[T], payload: Axi4Stream, coreMask: Flow[Bits]): Unit = {
      host[RxPacketDispatchService].consume(payload, metadata, coreMask) setCompositeName(this, "dispatch")
    }

    /** Release retainer from packet dispatcher to allow it to continue elaborating */
    protected def produceDone(): Unit = rxRg.release()

    /**
     * Drive control interface for this plugin.  Should be called from a host plugin, like [[pionic.host.eci.EciInterfacePlugin]].
     * @param busCtrl bus slave factory to host register access
     * @param alloc reg allocator
     */
    def driveControl(busCtrl: BusSlaveFactory, alloc: (String, String) => BigInt): Unit
  }

  trait ProtoEncoder {

  }
}
