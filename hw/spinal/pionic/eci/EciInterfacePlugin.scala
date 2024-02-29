package pionic.eci

import jsteward.blocks.eci.{DcsInterface, EciCmdDefs, EciDcsDefs, EciWord}
import jsteward.blocks.axi._
import pionic._
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.misc.plugin._
import spinal.lib.bus.misc.SizeMapping

import scala.language.postfixOps

case class PacketCtrlInfo()(implicit config: PioNicConfig) extends Bundle {
  val valid = Bool()
  val size = PacketLength()

  assert(getBitsWidth <= 512, "packet info larger than half a cacheline")
}

class EciInterfacePlugin(implicit config: PioNicConfig) extends FiberPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val cores = host.list[CoreControlPlugin]
  val retainer = Retainer()

  // dcs_2_axi AXI config
  val axiConfig = Axi4Config(
    // ECI address width
    addressWidth = EciDcsDefs.DS_ADDR_WIDTH,
    dataWidth = 512,
    idWidth = 4,
  )

  val logic = during setup new Area {
    val dcsOdd = DcsInterface(axiConfig)
    val dcsEven = DcsInterface(axiConfig)

    val s_ctrl_axil = slave(AxiLite4(addressWidth = 44, dataWidth = 64))

    awaitBuild()

    val csrCtrl = AxiLite4SlaveFactory(s_ctrl_axil)
    private val alloc = config.allocFactory("global")(0, 0x1000, config.regWidth / 8)(s_ctrl_axil.config.dataWidth)
    csr.readAndWrite(csrCtrl, alloc(_))

    // axi DMA write steered into each core's packet buffer
    val coreNodes = Seq.fill(cores.length)(Axi4(axiConfig))
    Axi4CrossbarFactory()
      .addSlaves(coreNodes.zipWithIndex map { case (node, idx) =>
        node -> SizeMapping(config.pktBufSizePerCore * idx, config.pktBufSizePerCore)
      }: _*)
      // FIXME: we could need an adapter here
      .addConnection(macIf.packetBufDmaMaster -> coreNodes)
      .build()

    // mux both DCS AXI masters to all cores
    val cachelineInBytesPerCore = roundUp((512 / 8) + config.roundMtu, EciCmdDefs.ECI_CL_SIZE_BYTES)
    val dcsNodes = Seq.fill(cores.length)(Axi4(axiConfig.copy(
      // 2 masters, ID width + 1
      idWidth = axiConfig.idWidth + 1,
    )))
    Axi4CrossbarFactory()
      .addSlaves(dcsNodes.zipWithIndex map { case (node, idx) =>
        node -> SizeMapping(cachelineInBytesPerCore * idx, cachelineInBytesPerCore)
      }: _*)
      .addConnections(Seq(dcsOdd, dcsEven) map { dcs =>
        dcs.axi.remapAddr { a =>
          // optimization of DCS: only 256 GiB (38 bits) of the address space is used
          EciCmdDefs.unaliasAddress(a.asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)).resized
        } -> dcsNodes
      }: _*)
      .build()

    // drive core control interface -- datapath per core
    cores lazyZip coreNodes lazyZip dcsNodes foreach { case (c, dmaNode, dcsNode) =>
      val baseAddress = (1 + c.coreID) * 0x1000
      val alloc = config.allocFactory("coreControl", c.coreID)(baseAddress, 0x1000, config.regWidth / 8)(s_ctrl_axil.config.dataWidth)
      val cio = c.logic.ctrl.io

      // per-core packet buffer
      val wordWidth = axiConfig.dataWidth
      val numWords = config.pktBufSizePerCore / (wordWidth / 8)
      val pktBuffer = Mem(Bits(wordWidth bits), numWords)

      val dmaBusCtrl = Axi4SlaveFactory(dmaNode)
      dmaBusCtrl.readSyncMemWordAligned(pktBuffer, 0)
      dmaBusCtrl.writeMemWordAligned(pktBuffer, 0)

      // TODO: datapath pipeline attach point (accelerators, rpc request decode, etc.)

      val dcsBusCtrl = Axi4SlaveFactory(dcsNode.remapAddr { a =>
        // remap packet data in first control cacheline to second
        (a === 0x40) ? U(0xc0) | a
      })

      // double buffering for packet metadata
      val rxPacketCtrl = Vec.fill(2)(PacketCtrlInfo())

      // generate control -- first half of first two cachelines
      dcsBusCtrl.read(rxPacketCtrl(0), 0)
      dcsBusCtrl.read(rxPacketCtrl(1), 0x80)

      // load packet data
      dcsBusCtrl.readSyncMemWordAligned(pktBuffer, 0xc0)

      // CSR for the core
      c.logic.ctrl.connectControl(csrCtrl, alloc(_))
      c.logic.ctrl.reportStatistics(csrCtrl, alloc(_, _))
    }
  }
}