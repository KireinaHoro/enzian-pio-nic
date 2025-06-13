package pionic.net

import pionic.{DmaControlPlugin, MacInterfaceService, PacketBuffer, PioNicPlugin}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import jsteward.blocks.axi.AxiStreamDemux
import spinal.lib.bus.amba4.axis.Axi4Stream.Axi4Stream

trait TxEncoderSourceService {
  /** Connects to DMA engine in [[PacketBuffer]] */
  def packetSource: Axi4Stream
  /** Connect the host-facing packet descriptor and payload ports for an encoder */
  def connect(ty: PacketDescType.E, desc: Stream[PacketDescData], payload: Axi4Stream): Unit
}

class TxEncoderSource extends PioNicPlugin with TxEncoderSourceService {
  lazy val ms = host[MacInterfaceService]

  val logic = during setup new Area {
    /** Packet descriptors from [[pionic.DmaControlPlugin]] */
    val packetDesc = host[DmaControlPlugin].logic.outgoingDesc

    val numTypes = PacketDescType.elements.length
    val descForDecoders = Seq.fill(numTypes)(Stream(PacketDescData()))
    val payloadForDecoders = Seq.fill(numTypes)(Axi4Stream(ms.axisConfig))

    awaitBuild()

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
  }

  def packetSource = logic.axisDemux.s_axis
  def connect(ty: PacketDescType.E, desc: Stream[PacketDescData], payload: Axi4Stream) = new Area {
    val idx = ty.position
    logic.descForDecoders(idx) >> desc
    logic.payloadForDecoders(idx) >> payload
  }
}
