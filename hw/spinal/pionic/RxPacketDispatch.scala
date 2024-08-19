package pionic

import jsteward.blocks.axi.AxiStreamArbMux
import jsteward.blocks.misc.StreamDispatcherWithEnable
import pionic.net.{ProtoPacketDesc, TaggedProtoPacketDesc}
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream

import scala.collection.mutable

/**
 * Service for RX decoder pipeline plugins as well as the AXI DMA engine to invoke.
 *
 * Most decoder plugins inheriting [[pionic.net.ProtoDecoder]] should not need to interact with this service directly,
 * as the API is used in the base class already.
 */
trait RxPacketDispatchService {
  /** called by packet decoders to post packets for DMA */
  def consume[T <: ProtoPacketDesc](payloadSink: Axi4Stream, metadataSink: Stream[T], coreMask: Bits = null, coreMaskChanged: Bool = null): Unit
  /** packet payload stream consumed by AXI DMA engine, to write into packet buffers */
  def packetSink: Axi4Stream

  def retainer: Retainer
}

/**
 * Dispatch unit for RX packet metadata and payload.  Metadata from decoder stages gets dispatched to cores and muxed
 * into a single stream, before passed to [[CoreControlPlugin]] for further translation.  Bypass metadata gets collected
 * and dispatched to the bypass core (#0).  Payload data is arbitrated into a single AXI-Stream and fed into [[AxiDmaPlugin]].
 */
class RxPacketDispatch extends PioNicPlugin with RxPacketDispatchService {
  lazy val ms = host[MacInterfaceService]
  lazy val cores = host.list[CoreControlPlugin]
  val retainer = Retainer()

  lazy val coreDescUpstreams = Seq.fill(cores.length)(mutable.ListBuffer[Stream[TaggedProtoPacketDesc]]())
  lazy val payloadSources = mutable.ListBuffer[Axi4Stream]()
  def consume[T <: ProtoPacketDesc](payloadSink: Axi4Stream, metadataSink: Stream[T], coreMask: Bits, coreMaskChanged: Bool): Unit = new Area {
    // handle payload data
    payloadSources.append(payloadSink)

    // handle metadata
    val tagged = metadataSink.map { md =>
      val ret = TaggedProtoPacketDesc()
      ret.ty := md.getType
      ret.metadata := md.asUnion
      ret
    }

    // dispatch to cores
    if (coreMask == null) {
      // bypass-core (#0) only
      coreDescUpstreams.head.append(tagged)
    } else {
      // round-robin dispatch to all other (non-bypass) cores that are enabled
      // TODO: replace with more complicated scheduler?
      StreamDispatcherWithEnable(
        input = tagged,
        outputCount = cores.length - 1,
        enableMask = coreMask,
        maskChanged = coreMaskChanged,
      ) zip coreDescUpstreams.tail foreach { case (td, cl) =>
        cl.append(td)
      }
    }
  }
  override def packetSink = logic.axisMux.masterPort

  val logic = during build new Area {
    retainer.await()

    // mux payload data axis to DMA
    val axisMux = new AxiStreamArbMux(ms.axisConfig, numSlavePorts = payloadSources.length)
    axisMux.slavePorts zip payloadSources foreach { case (sl, ms) =>
      sl << ms
    }

    // drive packet descriptors interface of core control modules
    cores zip coreDescUpstreams foreach { case (c, us) =>
      val cio = c.logic.io

      // TODO: priority among different protocols?
      cio.igMetadata << StreamArbiterFactory().roundRobin.on(us)
    }
  }
}
