package lauberhorn.net

import lauberhorn.{DmaControlPlugin, MacInterfaceService, PacketBuffer}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import jsteward.blocks.axi.AxiStreamDemux
import spinal.core.fiber.Retainer
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable

trait EncoderSourceService {
  /** Connects to DMA engine in [[PacketBuffer]] */
  def packetSource: Axi4Stream
  /** Connect the host-facing packet descriptor and payload ports for an encoder.  Should be called in the setup
    * phase by subclasses of [[Encoder]]. */
  def connect(ty: PacketDescType.E, desc: Stream[PacketDescData], payload: Axi4Stream): Unit
}

/** Packet source for egress (TX) packets into the encoder pipeline.  Packets come here through
  * [[DmaControlPlugin]] and gets streamed into downstream encoders that inherit [[Encoder]].
  */
class EncoderSource extends FiberPlugin with EncoderSourceService {
  lazy val ms = host[MacInterfaceService]
  val retainer = Retainer()

  // record encoders that called connect
  val registeredEncoders = mutable.ListBuffer[Int]()

  val numTypes = PacketDescType.elements.length
  val descForDecoders = during setup Seq.fill(numTypes)(Stream(PacketDescData()))
  val payloadForDecoders = during setup Seq.fill(numTypes)(Axi4Stream(ms.axisConfig))

  val logic = during build new Area {
    /** Packet descriptors from [[lauberhorn.DmaControlPlugin]] */
    val packetDesc = host[DmaControlPlugin].logic.outgoingDesc

    // find index of packet type in demuxer outputs
    val descTyOh = PacketDescType.elements.map { ty =>
      packetDesc.ty === ty
    }

    // demux packet descriptors
    StreamDemuxOh(packetDesc, descTyOh) zip descForDecoders foreach { case (demuxed, port) =>
      demuxed.map(_.metadata) >> port
    }

    // demux AXI stream
    val axisDemux = new AxiStreamDemux(ms.axisConfig, numTypes)
    axisDemux.io.enable := True
    axisDemux.io.select := RegNextWhen(OHToUInt(descTyOh), packetDesc.fire)
    axisDemux.io.drop := False

    axisDemux.m_axis zip payloadForDecoders foreach { case (demuxed, port) =>
      demuxed >> port
    }

    // tie off unused ports
    retainer.await()
    (descForDecoders zip payloadForDecoders).zipWithIndex.foreach { case ((d, p), idx) =>
      if (!registeredEncoders.contains(idx)) {
        d.setBlocked()
        p.setBlocked()
      }
    }
  }

  def packetSource = logic.axisDemux.s_axis
  def connect(ty: PacketDescType.E, desc: Stream[PacketDescData], payload: Axi4Stream) = new Area {
    val idx = ty.position
    descForDecoders(idx) >> desc
    payloadForDecoders(idx) >> payload

    registeredEncoders += ty.position
  }
}
