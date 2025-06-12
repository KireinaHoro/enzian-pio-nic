package pionic

import jsteward.blocks.axi.AxiStreamArbMux
import jsteward.blocks.misc.StreamDispatcherWithEnable
import pionic.net.{ProtoMetadata, PacketDesc}
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
  def consume[T <: ProtoMetadata](payloadSink: Axi4Stream, metadataSink: Stream[T], isBypass: Boolean = false): Area
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
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val ms = host[MacInterfaceService]
  lazy val cores = host.list[CoreControlPlugin]
  lazy val preempts = host.list[PreemptionService]
  lazy val sched = host[Scheduler].logic
  val retainer = Retainer()

  // possible decoder upstreams for the scheduler (once for every protocol that called produceFinal)
  lazy val bypassUpstreams, schedulerUpstreams = mutable.ListBuffer[Stream[PacketDesc]]()
  lazy val payloadSources = mutable.ListBuffer[Axi4Stream]()
  def consume[T <: ProtoMetadata](payloadSink: Axi4Stream, metadataSink: Stream[T], isBypass: Boolean) = new Area {
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
      schedulerUpstreams.append(tagged.s2mPipe())
    }
  }
  override def packetSink = logic.axisMux.m_axis

  val logic = during build new Area {
    retainer.await()

    // mux payload data axis to DMA
    val axisMux = new AxiStreamArbMux(ms.axisConfig, numSlavePorts = payloadSources.length)
    axisMux.s_axis zip payloadSources foreach { case (sl, ms) =>
      sl << ms
    }

    sched.rxMeta << StreamArbiterFactory().roundRobin.on(schedulerUpstreams)
    preempts zip sched.corePreempt foreach { case (pu, sp) => pu.preemptReq << sp }
    
    // drive packet descriptors interface of core control modules
    cores.head.logic.io.igMetadata << StreamArbiterFactory().roundRobin.on(bypassUpstreams)
    cores.tail zip sched.coreMeta foreach { case (cc, so) =>
      cc.logic.io.igMetadata << so
    }
  }
}
