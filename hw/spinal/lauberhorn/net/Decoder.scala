package lauberhorn.net

import jsteward.blocks.misc.RegBlockAlloc
import jsteward.blocks.axi._
import spinal.core._
import spinal.lib.StreamPipe.{FULL, M2S}
import spinal.lib._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * Base class of one stage in the RX decoder pipeline.  Should in general decode one protocol header (or one segment
  * in a protocol, e.g. IPv6 optional headers).
  *
  * Decoder stages form a DAG.  For example, UDP hosts multiple protocols like ONCRPC, gRPC, and memcached;
  * ONCRPC might take data from UDP and TCP.  The DAG is specified by [[from]].
  *
  * @tparam T output metadata type
  */
trait Decoder[T <: DecoderMetadata] extends FiberPlugin {
  /**
    * Downstream decoders interfaces and their conditions to match.
    * e.g. Ip.downs = [ (Tcp, proto === 6), (Udp, proto === 17) ]
    *
    * Tuple members: (name, matchFunc, header, payload)
    */
  private val consumers = mutable.ListBuffer[(String, T => Bool, Stream[T], Axi4Stream)]()

  val rxRg = during setup retains(host[DecoderSinkService].retainer)

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
  protected def from[M <: DecoderMetadata, D <: Decoder[M]: ClassTag](matcher: M => Bool, metadata: Stream[M], payload: Axi4Stream): Unit = {
    host[D].consumers.append((this.getDisplayName(), matcher, metadata, payload))
  }

  /**
    * Specify output of this decoder, for downstream decoders to consume.  Forks the streams for all consumers and
    * produce a copy for bypass to the [[DecoderSink]].  Should only be invoked **once** in the build phase.
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

      // mark attempt when header is valid
      // we don't mark on fire, since decoder should still attempt eventually, even if it's busy right now
      val attempt = matchFunc(hdr.payload) && hdr.valid
      attempts.append(attempt)

      // XXX: this works even when downstream decoder is not immediately ready:
      //      hdr is supposed to be persistent
      headerSink << hdr.takeWhen(attempt)
      payloadSink </< pld.takeFrameWhen(hdr.asFlow ~ attempt)
    }
    }

    // did at least one downstream decoder attempt to decode this packet?
    // XXX: we assume decoder outputs the captured header first, before giving output
    //      otherwise all beats before header would not be thrown properly
    val attempted = attempts.orR
    val bypassThrow = Flow(Bool())
    bypassThrow.payload := attempted
    bypassThrow.valid := attempted

    // do not give to bypass (throw), when any downstream decoders would attempt to decode
    // do not pipeline header for bypass: otherwise payload can get through before header;
    // this leads to different interleaving of header and payload between different protocols
    val bypassHeader = forkedHeaders.last.throwWhen(attempted)
    val bypassPayload = forkedPayloads.last.throwFrameWhen(bypassThrow).pipelined(FULL)

    host[DecoderSinkService].consume(bypassPayload, bypassHeader, isBypass = true) setCompositeName(this, "dispatchBypass")
  }

  /**
    * Specify output of this decoder, for the host CPU to consume.  This gets fed to [[DecoderSink]] directly.  May be
    * invoked multiple times during setup phase; useful when decoder takes multiple upstreams (using [[from]]).
    *
    * @param metadata metadata stream produced by this stage
    * @param payload payload data stream produced by this stage
    */
  protected def produceFinal(metadata: Stream[T], payload: Axi4Stream): Unit = {
    host[DecoderSinkService].consume(payload, metadata) setCompositeName(this, "dispatch")
  }

  /** Release retainer from packet dispatcher to allow it to continue elaborating */
  protected def produceDone(): Unit = rxRg.release()

  /**
    * Drive control interface for this plugin.  Should be called from a host plugin, like [[lauberhorn.host.eci.EciInterfacePlugin]].
    *
    * TODO: move to [[ProtoState]]
    *
    * @param bus AXILite4 for register access
    * @param alloc reg allocator
    */
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit
  def decoderName: String = getClass.getSimpleName

  def isPromisc: Bool = host[DecoderSinkService].isPromisc
}
