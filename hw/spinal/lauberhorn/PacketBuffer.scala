package lauberhorn

import jsteward.blocks.axi._
import lauberhorn.Global._
import lauberhorn.host._
import lauberhorn.net.{RxDecoderSinkService, TxEncoderSourceService}
import spinal.core._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.plugin.FiberPlugin

/** RX DMA tag used to construct [[HostReq]] after DMA.  Filled from [[lauberhorn.net.PacketDesc]] */
case class RxDmaTag() extends Bundle {
  /** packet buffer address from allocator.  used to fill buffer in [[HostReq]] */
  val addr = PacketAddr()
  val ty = HostReqType()
  val data = HostReqData()
}

/**
  * Global packet buffer for payloads of RX and TX packets.  These compliment the decoded headers held in
  * [[lauberhorn.net.PacketDesc]].  Contains AXI-attached packet buffers (one for RX and one for TX) and an AXI DMA
  * engine.  [[DmaControlPlugin]] generates DMA commands from [[lauberhorn.net.PacketDesc]] to operate the DMA engine.
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
class PacketBuffer extends FiberPlugin {
  lazy val dc = host[DmaControlPlugin].logic
  lazy val ms = host[MacInterfaceService]

  // config for packetBufDmaMaster
  // we fix here and downstream should adapt
  lazy val axiConfig = Axi4Config(
    addressWidth = log2Up(PKT_BUF_SIZE),
    dataWidth = 512,
    idWidth = PKT_BUF_ID_WIDTH,
    useQos = false,
    useRegion = false,
  )

  lazy val dmaConfig = AxiDmaConfig(axiConfig, ms.axisConfig,
    tagWidth = RxDmaTag().getBitsWidth, lenWidth = PKT_BUF_LEN_WIDTH)

  // TX packet buffers located after all RX buffers, one "rounded mtu" per core
  PKT_BUF_TX_OFFSET.set(NUM_CORES * PKT_BUF_RX_SIZE_PER_CORE)

  val logic = during build new Area {
    val axiDma = new AxiDma(dmaConfig)

    // TX data goes to encoder pipeline
    axiDma.m_axis_read_data >> host[TxEncoderSourceService].packetSource

    // RX data from the streaming-mode decoder pipeline
    // may contain gaps at the beginning, align first
    val aligner = AxiStreamAligner(dmaConfig.axisConfig)
    host[RxDecoderSinkService].packetSink >> aligner.io.input
    axiDma.s_axis_write_data <-/< aligner.io.output

    axiDma.io.read_enable := True
    axiDma.io.write_enable := True
    axiDma.io.write_abort := False

    val axiMem = new AxiDpRam(axiConfig)
    axiMem.io.s_axi_a <> axiDma.io.m_axi

    // connect descriptors from [[DmaControlPlugin]]
    axiDma.s_axis_read_desc << dc.readDesc
    axiDma.m_axis_read_desc_status >> dc.readDescStatus
    axiDma.s_axis_write_desc << dc.writeDesc
    axiDma.m_axis_write_desc_status >> dc.writeDescStatus
  }
}
