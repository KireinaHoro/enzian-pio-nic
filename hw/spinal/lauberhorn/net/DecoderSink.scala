package lauberhorn.net

import jsteward.blocks.axi.AxiStreamMux
import jsteward.blocks.misc.RegBlockAlloc
import lauberhorn.{DmaControlPlugin, MacInterfaceService, PacketBuffer, RxPacketDescWithSource}
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib.StreamPipe.FULL
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable
import scala.language.postfixOps

/**
 * Service for RX decoder pipeline plugins as well as the AXI DMA engine to invoke.
 *
 * Most decoder plugins inheriting [[lauberhorn.net.Decoder]] should not need to interact with this service directly,
 * as the API is used in the base class already.
 */
trait DecoderSinkService {
  /** called by packet decoders to post packets for DMA */
  def consume[T <: DecoderMetadata](payloadSink: Axi4Stream, metadataSink: Stream[T], isBypass: Boolean = false): Area
  /** packet payload stream consumed by AXI DMA engine, to write into packet buffers */
  def packetSink: Axi4Stream
  def isPromisc: Bool

  def retainer: Retainer
}

/**
  * Dispatch unit for decoded RX packet metadata and payload, collected from all decoder stages.
  *
  * [[PacketDesc]] from decoder stages gets muxed into a single stream, before passed to [[DmaControlPlugin]] for
  * further translation (into [[lauberhorn.host.HostReq]]).  Payload data is arbitrated into a single AXI-Stream and fed
  * into the DMA engine in [[PacketBuffer]].
  */
class DecoderSink extends FiberPlugin with DecoderSinkService {
  lazy val ms = host[MacInterfaceService]
  lazy val dc = host[DmaControlPlugin].logic
  val retainer = Retainer()

  // possible decoder upstreams for the scheduler (once for every protocol that called produceFinal)
  lazy val descSources = mutable.ListBuffer[Stream[RxPacketDescWithSource]]()
  lazy val payloadSources = mutable.ListBuffer[Axi4Stream]()
  def consume[T <: DecoderMetadata](payloadSink: Axi4Stream, metadataSink: Stream[T], isBypass: Boolean) = new Area {
    payloadSink.assertPersistence()
    metadataSink.assertPersistence()

    // handle payload data
    payloadSources.append(payloadSink)

    // handle metadata
    val tagged = metadataSink.map { md =>
      val ret = RxPacketDescWithSource()
      ret.desc.ty := md.getType
      ret.desc.metadata := md.asUnion
      ret.isBypass := Bool(isBypass)
      ret
    }

    // take care not to introduce latency in the forward path, due to the timing requirement between
    // the descriptor and its payload
    descSources.append(tagged.s2mPipe())
  }
  override def packetSink = logic.axisMux.m_axis

  lazy val promisc = Bool()
  val logic = during build new Area {
    retainer.await()

    assert(descSources.length == payloadSources.length)
    assert(descSources.length > 1)

    // mux payload data axis to DMA:
    // select upstream port based on which desc port had a request.
    // a arbiter mux might mix up desc and payload from different decoders
    val axisMux = new AxiStreamMux(ms.axisConfig, numSlavePorts = payloadSources.length)
    axisMux.s_axis zip payloadSources foreach { case (sl, ms) =>
      sl << ms
    }

    // set payload mux to take from upstream that emitted a descriptor.
    val pldSelNext = UInt(log2Up(payloadSources.length) bits)
    val pldSel = RegNext(pldSelNext)
    pldSelNext := pldSel

    // we can't enforce that the payload must come IMMEDIATELY AFTER the descriptor,
    // since interfaces might get pipelined and will get out of sync.  We only activate
    // the mux after each descriptor and disable it after each payload, to avoid the
    // following situation:
    //
    // ETH Hdr           h
    // ETH Pld             pppp
    // UDP Hdr      h         h
    // UDP Pld  pppppppp  pppppppp
    //
    // In the above situation, if we don't disable the payload mux, the second UDP payload
    // will be confused as the payload for the Ethernet packet.
    val pldSelEnNext = Bool()
    val pldSelEn = RegNext(pldSelEnNext) init False
    pldSelEnNext := pldSelEn

    axisMux.io.select := pldSelNext
    axisMux.io.enable := pldSelEnNext

    when (axisMux.m_axis.valid) {
      pldSelEnNext := False
    }

    val descArbiter = StreamArbiterFactory().lowerFirst.buildOn(descSources)
    when (descArbiter.io.output.fire) {
      pldSelNext := descArbiter.io.chosen
      pldSelEnNext := True
    }

    descArbiter.io.output >> dc.incomingDesc
  }

  def isPromisc: Bool = promisc
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    busCtrl.driveAndRead(promisc, alloc("ctrl", "promisc")) init False
  }
}
