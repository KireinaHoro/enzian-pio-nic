package pionic.net

import pionic.PioNicPlugin
import spinal.core.Composite
import spinal.lib.Stream
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
  /**
    * Upstream encoders interfaces.  E.g. Ip.ups = [ Tcp, Udp ]
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

  protected def consume(metadata: Stream[T], payload: Axi4Stream): Unit = new Composite(this, "consume") {
    // TODO
  }
}
