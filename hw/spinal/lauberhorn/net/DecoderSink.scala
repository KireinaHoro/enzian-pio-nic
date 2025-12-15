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

case class DecoderOutput(priority: Int, name: String,
                         desc: Stream[RxPacketDescWithSource], pld: Axi4Stream,
                         payloadAck: Bool,   // can this decoder emit another desc?
                         dropped: Bool,      // did this decoder just drop a packet?
                        )

/**
 * Service for RX decoder pipeline plugins as well as the AXI DMA engine to invoke.
 *
 * Most decoder plugins inheriting [[lauberhorn.net.Decoder]] should not need to interact with this service directly,
 * as the API is used in the base class already.
 */
trait DecoderSinkService {
  /** called by packet decoders to post packets for DMA */
  def consume(dec: DecoderOutput): Area
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
  lazy val decoderOutputs = mutable.ListBuffer[DecoderOutput]()
  lazy val pktDropped = Bool()
  def consume(dec: DecoderOutput) = new Area {
    dec.pld.assertPersistence()
    dec.desc.assertPersistence()

    decoderOutputs.append(dec.copy(desc = dec.desc.pipelined(FULL)))

    // Payload is ack'ed when:
    // - we disable the AXIS mux, packet sent to DMA
    // - a decoder dropped this packet
    dec.payloadAck := pldMuxDisable || pktDropped
  }
  override def packetSink = logic.axisMux.m_axis

  lazy val promisc = Bool()
  lazy val pldMuxDisable = Bool()
  val logic = during build new Area {
    retainer.await()

    val numDecoders = decoderOutputs.length
    assert(numDecoders > 1)

    // every decoder gets to see if anyone dropped
    pktDropped := decoderOutputs.map(_.dropped).orR

    // Sort by priority.  Downstream decoders (higher up in OSI stack) has higher
    // priority -- e.g. UDP > IP > Ethernet
    val sortedDecoders = decoderOutputs.sortBy(_.priority)(Ordering[Int].reverse)

    println("Decoders registered with sink:")
    sortedDecoders.zipWithIndex foreach { case (d, idx) =>
      println(s"#$idx: ${d.name}\t(priority ${d.priority})")
    }

    // mux payload data axis to DMA:
    // select upstream port based on which desc port had a request.
    // a arbiter mux might mix up desc and payload from different decoders
    val axisMux = new AxiStreamMux(ms.axisConfig, numSlavePorts = numDecoders)
    axisMux.s_axis zip sortedDecoders foreach { case (sl, d) =>
      sl << d.pld
    }

    // set payload mux to take from upstream that emitted a descriptor.
    val pldSelNext = UInt(log2Up(numDecoders) bits)
    val pldSel = RegNext(pldSelNext)
    pldSelNext := pldSel

    // We can't enforce that the payload must come IMMEDIATELY AFTER the descriptor,
    // since interfaces might get pipelined and will get out of sync.  We only activate
    // the mux after each descriptor and disable it after each payload, to avoid the
    // following situation:
    //
    // ETH Hdr           h
    // ETH Pld             pppp
    // UDP Hdr      h         h
    // UDP Pld  pppppppp  pppppppp
    //
    // In the above situation, if we would use a round-robin arbiter for payloads, the
    // second UDP payload will be confused as the payload for the Ethernet packet.
    //
    // This introduces a deadlock due to back pressure: a later Ethernet packet
    // can flip the payload mux and block an earlier Ip packet from draining,
    // which then back pressures the Ethernet decoder from fully outputting the
    // first packet.  We really need to refactor to use TUSER for packet header...
    val pldSelEnNext = Bool()
    val pldSelEn = RegNext(pldSelEnNext) init False
    pldSelEnNext := pldSelEn

    axisMux.io.select := pldSelNext
    axisMux.io.enable := pldSelEnNext

    when (axisMux.m_axis.valid) {
      pldSelEnNext := False
    }
    pldMuxDisable := pldSelEn.fall(False)

    val descArbiter = StreamArbiterFactory().lowerFirst.buildOn(sortedDecoders.map(_.desc))
    when (descArbiter.io.output.fire) {
      pldSelNext := descArbiter.io.chosen
      pldSelEnNext := True

      // also allow next header when this packet did not have payload
      when (descArbiter.io.output.desc.getPayloadSize === 0) {
        pldMuxDisable := True
      }
    }

    descArbiter.io.output >> dc.incomingDesc
  }

  def isPromisc: Bool = promisc
  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    busCtrl.driveAndRead(promisc, alloc("ctrl", "Enable promiscuous mode", "promisc")) init False
  }
}
