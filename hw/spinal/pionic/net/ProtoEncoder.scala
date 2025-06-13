package pionic.net

import pionic.PioNicPlugin
import pionic.host.HostReq
import spinal.core.Composite
import spinal.lib.{Stream, StreamArbiterFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream

import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * Base class of one stage in the TX encoder pipeline.
  *
  * Encoder stages form a DAG, analogous to [[ProtoDecoder]].  The DAG is specified by [[to]].
  *
  * @tparam T input metadata type
  */
trait ProtoEncoder[T <: ProtoMetadata] extends PioNicPlugin {
  /** Create one instance of the concrete [[ProtoMetadata]] for this decoder */
  def getMetadata: T

  /**
    * Upstream encoders interfaces.  E.g. Ip.ups = [ Tcp, Udp ]
    *
    * Tuple members: (name, header, payload)
    */
  private val producers = mutable.ListBuffer[(String, Stream[T], Axi4Stream)]()

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
    * Collect all payload streams of this encoder, from either the host CPU or previous decoder stages.  Must be invoked
    * from inside the decoder; can be invoked at most once during the setup phase.
    *
    * Packets from previous decoder stages are registered through the [[to]] method.
    *
    * Packets from the host CPU directly (after translated by [[pionic.DmaControlPlugin]]) will be pulled in as
    *
    * @param metadata metadata consumed by this stage
    * @param payload payload data stream consumed by this stage
    */
  protected def collectInto(metadata: Stream[T], payload: Axi4Stream): Unit = new Composite(this, "consume") {
    // TODO: get host-produced descriptor for this encoder from [[TxEncoderSource]]
    val hostDesc = ???
    metadata << StreamArbiterFactory().roundRobin.on(
      producers.map(_._2)
    )
  }
}
