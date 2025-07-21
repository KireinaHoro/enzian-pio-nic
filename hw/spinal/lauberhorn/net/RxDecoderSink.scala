package lauberhorn.net

import jsteward.blocks.axi.AxiStreamArbMux
import lauberhorn.{DmaControlPlugin, GlobalCSRPlugin, MacInterfaceService, PacketBuffer}
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable

/**
 * Service for RX decoder pipeline plugins as well as the AXI DMA engine to invoke.
 *
 * Most decoder plugins inheriting [[lauberhorn.net.ProtoDecoder]] should not need to interact with this service directly,
 * as the API is used in the base class already.
 */
trait RxDecoderSinkService {
  /** called by packet decoders to post packets for DMA */
  def consume[T <: ProtoMetadata](payloadSink: Axi4Stream, metadataSink: Stream[T], isBypass: Boolean = false): Area
  /** packet payload stream consumed by AXI DMA engine, to write into packet buffers */
  def packetSink: Axi4Stream

  def retainer: Retainer
}

/**
  * Dispatch unit for decoded RX packet metadata and payload, collected from all decoder stages.
  *
  * [[PacketDesc]] from decoder stages gets muxed into a single stream, before passed to [[DmaControlPlugin]] for
  * further translation (into [[lauberhorn.host.HostReq]]).  Payload data is arbitrated into a single AXI-Stream and fed
  * into the DMA engine in [[PacketBuffer]].
  */
class RxDecoderSink extends FiberPlugin with RxDecoderSinkService {
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val ms = host[MacInterfaceService]
  lazy val dc = host[DmaControlPlugin].logic
  val retainer = Retainer()

  // possible decoder upstreams for the scheduler (once for every protocol that called produceFinal)
  lazy val bypassUpstreams, requestUpstreams = mutable.ListBuffer[Stream[PacketDesc]]()
  lazy val payloadSources = mutable.ListBuffer[Axi4Stream]()
  def consume[T <: ProtoMetadata](payloadSink: Axi4Stream, metadataSink: Stream[T], isBypass: Boolean) = new Area {
    payloadSink.assertPersistence()
    metadataSink.assertPersistence()

    // handle payload data
    payloadSources.append(payloadSink)

    // handle metadata
    val tagged = metadataSink.map { md =>
      val ret = PacketDesc()
      ret.ty := md.getType
      ret.metadata := md.asUnion
      ret
    }

    // dispatch to cores
    if (isBypass) {
      // dispatching to the bypass-core (#0) only
      bypassUpstreams.append(tagged)
    } else {
      requestUpstreams.append(tagged.s2mPipe())
    }
  }
  override def packetSink = logic.axisMux.m_axis

  val logic = during build new Area {
    retainer.await()

    // mux payload data axis to DMA -- lower first
    val axisMux = new AxiStreamArbMux(ms.axisConfig, numSlavePorts = payloadSources.length)
    axisMux.s_axis zip payloadSources foreach { case (sl, ms) =>
      sl << ms
    }

    def mux(ss: IterableOnce[Stream[PacketDesc]]): Stream[PacketDesc] = {
      val sq = ss.iterator.to(Seq)
      if (sq.length == 1) sq.head
      else {
        // use the same arbitration policy as axisMux
        StreamArbiterFactory().lowerFirst.on(sq)
      }
    }

    dc.requestDesc << mux(requestUpstreams)
    dc.bypassDesc << mux(bypassUpstreams)
  }
}
