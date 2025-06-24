package pionic.net

import pionic.{DmaControlPlugin, MacInterfaceService, PacketBuffer}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import jsteward.blocks.axi.AxiStreamDemux
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream
import spinal.lib.misc.plugin.FiberPlugin

import scala.collection.mutable

trait TxEncoderSourceService {
  /** Connects to DMA engine in [[PacketBuffer]] */
  def packetSource: Axi4Stream
  /** Connect the host-facing packet descriptor and payload ports for an encoder.  Should be called in the setup
    * phase by subclasses of [[ProtoEncoder]]. */
  def connect(ty: PacketDescType.E, desc: Stream[PacketDescData], payload: Axi4Stream): Unit
}

/** Packet source for egress (TX) packets into the encoder pipeline.  Packets come here through
  * [[DmaControlPlugin]] and gets streamed into downstream encoders that inherit [[ProtoEncoder]].
  */
class TxEncoderSource extends FiberPlugin with TxEncoderSourceService {
  lazy val ms = host[MacInterfaceService]

  // record encoders that called connect
  val registeredEncoders = mutable.ListBuffer[Int]()
  var inBuild = false

  val logic = during setup new Area {
    /** Packet descriptors from [[pionic.DmaControlPlugin]] */
    val packetDesc = host[DmaControlPlugin].logic.outgoingDesc

    val numTypes = PacketDescType.elements.length
    val descForDecoders = Seq.fill(numTypes)(Stream(PacketDescData()))
    val payloadForDecoders = Seq.fill(numTypes)(Axi4Stream(ms.axisConfig))

    awaitBuild()

    inBuild = true

    // find index of packet type in demuxer outputs
    val descTyOh = PacketDescType.elements.map { ty =>
      packetDesc.ty === ty
    }
    // save type of current packet for AXI Stream demuxer
    val descTyIdx = RegNextWhen(OHToUInt(descTyOh), packetDesc.fire)

    // demux packet descriptors
    StreamDemuxOh(packetDesc, descTyOh) zip descForDecoders foreach { case (demuxed, port) =>
      demuxed.map(_.metadata) >> port
    }

    // demux AXI stream
    val axisDemux = new AxiStreamDemux(ms.axisConfig, numTypes)
    axisDemux.io.enable := True
    axisDemux.io.select := descTyIdx
    axisDemux.io.drop := False

    axisDemux.m_axis zip payloadForDecoders foreach { case (demuxed, port) =>
      demuxed >> port
    }

    // tie off unused ports
    (descForDecoders zip payloadForDecoders).zipWithIndex.foreach { case ((d, p), idx) =>
      if (!registeredEncoders.contains(idx)) {
        d.setBlocked()
        p.setBlocked()
      }
    }
  }

  def packetSource = logic.axisDemux.s_axis
  def connect(ty: PacketDescType.E, desc: Stream[PacketDescData], payload: Axi4Stream) = new Area {
    assert(!inBuild, "connect must be called in setup phase")

    val idx = ty.position
    logic.descForDecoders(idx) >> desc
    logic.payloadForDecoders(idx) >> payload

    registeredEncoders += ty.position
  }
}
