package pionic

import jsteward.blocks.axi._
import jsteward.blocks.misc.RichBundle
import pionic.host._
import pionic.net.RxDecoderSinkService
import spinal.core._
import spinal.lib.bus.amba4.axi._

/** RX DMA tag used to construct [[HostReq]] after DMA.  Filled from [[pionic.net.PacketDesc]] */
case class RxDmaTag()(implicit c: ConfigDatabase) extends Bundle {
  /** packet buffer address from allocator.  used to fill buffer in [[HostReq]] */
  val addr = PacketAddr()
  val ty = HostReqType()
  val data = HostReqData()
}

/**
  * Global packet buffer for payloads of RX and TX packets.  These compliment the decoded headers held in
  * [[pionic.net.PacketDesc]].  Contains AXI-attached packet buffers (one for RX and one for TX) and an AXI DMA
  * engine.  [[DmaControlPlugin]] generates DMA commands from [[pionic.net.PacketDesc]] to operate the DMA engine.
  *
  * For RX, the payload of a packet lands here after decoded by the streaming-mode decoders (Ethernet, IP, UDP,
  * ONC-RPC).  Packet data comes in as AXI-Stream and gets written to the AXI packet buffer.  A master (muxed from all
  * host-side access protocols) reads the data out.
  *
  * For TX, the host-side access protocols write the packet payload.  The DMA engine reads the payload out as
  * AXI-Stream and then pushes this through the streaming-mode encoders.
  *
  * TODO: add access points for memory-mode de/encoders
  */
class PacketBuffer extends PioNicPlugin {
  lazy val dc = host[DmaControlPlugin].logic
  lazy val ms = host[MacInterfaceService]

  // config for packetBufDmaMaster
  // we fix here and downstream should adapt
  val axiConfig = Axi4Config(
    addressWidth = 64,
    dataWidth = 512,
    idWidth = 4,
    useQos = false,
    useRegion = false,
  )

  lazy val dmaConfig = AxiDmaConfig(axiConfig, ms.axisConfig, tagWidth = RxDmaTag().getBitsWidth, lenWidth = pktBufLenWidth)

  val logic = during build new Area {
    val axiDma = new AxiDma(dmaConfig)

    // TX data to send -- currently directly to [[MacInterfaceService]]
    // TODO: replace with encoders input
    axiDma.m_axis_read_data >> ms.txStream

    // RX data from the streaming-mode decoder pipeline
    // may contain gaps at the beginning, align first
    val aligner = AxiStreamAligner(dmaConfig.axisConfig)
    host[RxDecoderSinkService].packetSink >> aligner.io.input
    axiDma.s_axis_write_data <-/< aligner.io.output

    axiDma.io.read_enable := True
    axiDma.io.write_enable := True
    axiDma.io.write_abort := False

    val memAddrWidth = log2Up(pktBufSize)
    // TX packet buffers located after all RX buffers, one "rounded mtu" per core
    c.post("tx pkt buf offset", numCores * c[Int]("rx pkt buf size per core"))

    val axiMem = new AxiDpRam(axiConfig.copy(addressWidth = memAddrWidth))
    axiMem.io.s_axi_a <> axiDma.io.m_axi

    // connect descriptors from [[DmaControlPlugin]]
    axiDma.io.s_axis_read_desc << dc.readDesc
    axiDma.io.m_axis_read_desc_status >> dc.readDescStatus
    axiDma.io.s_axis_write_desc << dc.writeDesc
    axiDma.io.m_axis_write_desc_status >> dc.writeDescStatus
  }
}
