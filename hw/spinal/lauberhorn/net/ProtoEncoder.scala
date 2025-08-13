package lauberhorn.net

import jsteward.blocks.axi.AxiStreamArbMux
import spinal.core._
import spinal.lib.{Stream, StreamArbiterFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * Base class of one stage in the TX encoder pipeline.
  *
  * Encoder stages form a DAG, analogous to [[ProtoDecoder]].  The DAG is specified by [[to]].
  *
  * @tparam T input metadata type
  */
trait ProtoEncoder[T <: ProtoMetadata] extends FiberPlugin {
  /** Create one instance of the concrete [[ProtoMetadata]] for this encoder */
  def getMetadata: T

  /**
    * Upstream encoders interfaces.  E.g. Ip.ups = [ Tcp, Udp ]
    *
    * Tuple members: (name, header, payload)
    */
  private val producers = mutable.ListBuffer[(String, Stream[T], Axi4Stream)]()

  val txRg = during setup retains(host[TxEncoderSource].retainer)

  /**
    * Specify one possible downstream encoder, where this encoder pushes packets to.  Can be invoked multiple times
    * in the setup phase.
    * @param metadata output metadata stream for downstream decoder
    * @param payload output payload stream (with our header encoded)
    * @tparam M type of downstream packet descriptor
    * @tparam E type of downstream packet decoder
    */
  protected def to[M <: ProtoMetadata, E <: ProtoEncoder[M]: ClassTag](metadata: Stream[M], payload: Axi4Stream): Unit = {
    host[E].producers.append((this.getDisplayName(), metadata, payload))
  }

  /**
    * Collect all payload streams of this encoder, from previous decoder stages.  Will provide packets from:
    *  - previous decoder stages, registered by them calling the [[to]] method
    *  - packets from the host, passed through [[TxEncoderSource]]
    *
    * Must be invoked from inside the decoder; must be invoked exactly once during the build phase.
    *
    * @param metadata metadata consumed by this stage
    * @param payload payload data stream consumed by this stage
    * @param acceptHostPackets whether to allow packets from host (e.g. as bypass)
    */
  protected def collectInto(metadata: Stream[T], payload: Axi4Stream, acceptHostPackets: Boolean = false): Unit = new Composite(this, "consume") {
    val descUpstreams = mutable.ListBuffer.from(producers.map(_._2))
    val payloadUpstreams = mutable.ListBuffer.from(producers.map(_._3))

    txRg.release()

    if (acceptHostPackets) {
      val hostDesc = Stream(PacketDescData())
      val hostPayload = payload.clone
      val md = getMetadata
      val ty = md.getType

      host[TxEncoderSource].connect(ty, hostDesc, hostPayload)

      descUpstreams += hostDesc.map { pld =>
        PacketDescType.selectData[T](ty, pld)
      }

      payloadUpstreams += hostPayload
    }

    if (payloadUpstreams.length == 1) {
      assert(descUpstreams.length == 1)

      metadata << descUpstreams.head
      payload << payloadUpstreams.head
    } else new Area {
      metadata << StreamArbiterFactory(s"${getName()}_descMux").roundRobin.on(descUpstreams)
      val axisMux = new AxiStreamArbMux(payload.config, payloadUpstreams.length)

      axisMux.s_axis zip payloadUpstreams foreach { case (sl, ms) => sl << ms }
      axisMux.m_axis >> payload
    }
  }
}
