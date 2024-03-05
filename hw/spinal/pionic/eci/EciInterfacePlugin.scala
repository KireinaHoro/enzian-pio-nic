package pionic.eci

import jsteward.blocks.eci._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
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

  val logic = during build new Area {
    // even and odd in ALIASED addresses
    // Refer to Chapter 9.4 in CCKit
    val dcsOdd = DcsInterface(axiConfig)
    val dcsEven = DcsInterface(axiConfig)

    val s_ctrl_axil = slave(AxiLite4(addressWidth = 44, dataWidth = 64))

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

    def bindCoreCmdsToLclChans(cmds: Seq[Stream[EciWord]], addrLocator: EciWord => Bits, evenVc: Int, oddVc: Int, chanLocator: DcsInterface => Stream[LclChannel]): Unit = {
      cmds.zipWithIndex.map { case (cmd, idx) =>
        // core interfaces use UNALIASED addresses
        val acmd = cmd.mapPayloadElement(addrLocator)(a => EciCmdDefs.aliasAddress(a.asUInt))
        // lowest 7 bits are byte offset
        val dcsIdx = addrLocator(acmd.payload)(7).asUInt

        // assemble ECI channel
        val chanStream = Stream(LclChannel())
        chanStream.translateFrom(acmd) { case (chan, data) =>
          chan.data := data
          chan.vc := dcsIdx.asBool ? B(oddVc) | B(evenVc)
          chan.size := 1
        }

        StreamDemux(chanStream, dcsIdx, 2).toSeq
      }.transpose.zip(Seq(dcsEven, dcsOdd)) foreach { case (chan, dcs) =>
        chanLocator(dcs) << StreamArbiterFactory().roundRobin.on(chan)
      }
    }

    def bindLclChansToCoreResps(resps: Seq[Stream[EciWord]], hreqIdLocator: EciWord => Bits, addrLocator: EciWord => Bits, chanLocator: DcsInterface => Stream[LclChannel]): Unit = {
      Seq(dcsEven, dcsOdd).map { dcs =>
        val chan = chanLocator(dcs)
        val coreIdx = hreqIdLocator(chan.data).asUInt.resize(log2Up(cores.length))
        StreamDemux(chanLocator(dcs), coreIdx, cores.length).toSeq
      }.transpose.zip(resps) foreach { case (chans, resp) =>
        val resps = chans.map { c =>
          // dcs use ALIASED addresses
          val unaliased = c.mapPayloadElement(cc => addrLocator(cc.data))(a => EciCmdDefs.unaliasAddress(a).asBits)
          unaliased.translateWith(unaliased.data)
        }
        resp << StreamArbiterFactory().roundRobin.on(resps)
      }
    }

    // mux LCL request (LCI)
    assert(log2Up(cores.length) <= EciCmdDefs.ECI_HREQID_WIDTH, s"${cores.length} cores cannot fit inside hreq id of ${EciCmdDefs.ECI_HREQID_WIDTH}!")
    val coresLci = Seq.fill(cores.length)(Stream(EciCmdDefs.EciAddress))
    bindCoreCmdsToLclChans(coresLci.zipWithIndex.map { case (addr, idx) =>
      val ret = Stream(EciWord())

      // generating a LCI -- refer to Table 7.9 of CCKit
      ret.payload.lci.opcode := B("00001")
      ret.payload.lci.hreqId := B(idx, EciCmdDefs.ECI_HREQID_WIDTH bits)
      ret.payload.lci.dmask := B("1111")
      ret.payload.lci.ns := True
      ret.payload.lci.rnode := B("01")
      ret.payload.lci.address := addr.payload
      ret.arbitrationFrom(addr)

      ret
    }, _.lci.address, 17, 16, _.cleanMaybeInvReq)

    // demux LCL response (LCIA)
    val coresLcia = Seq.fill(cores.length)(Stream(EciCmdDefs.EciAddress))
    bindLclChansToCoreResps(coresLcia.map { lcia =>
      val ret = Stream(EciWord())

      lcia.payload := ret.payload.lcia.address
      lcia.arbitrationFrom(ret)

      ret
    }, _.lcia.hreqId, _.lcia.address, _.cleanMaybeInvResp)

    // mux LCL unlock response
    val coresUl = Seq.fill(cores.length)(Stream(EciCmdDefs.EciAddress))
    bindCoreCmdsToLclChans(coresUl.map { addr =>
      val ret = Stream(EciWord())

      // generating a UL -- refer to Table 7.11 of CCKit
      ret.payload.ul.opcode := B("00010")
      ret.payload.ul.address := addr.payload
      ret.arbitrationFrom(addr)

      ret
    }, _.ul.address, 19, 18, _.unlockResp)

    // drive core control interface -- datapath per core
    cores lazyZip coreNodes lazyZip dcsNodes lazyZip coresLci lazyZip coresLcia lazyZip coresUl foreach { case ((c, dmaNode, dcsNode, lci), lcia, ul) =>
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
      rxPacketCtrl.assignDontCare()

      // generate control -- first half of first two cachelines
      dcsBusCtrl.read(rxPacketCtrl(0), 0)
      dcsBusCtrl.read(rxPacketCtrl(1), 0x80)

      // load packet data
      dcsBusCtrl.readSyncMemWordAligned(pktBuffer, 0xc0)

      // TODO: generate ECI reqs
      lci.setIdle()
      ul.setIdle()
      lcia.setBlocked()

      // TODO: drive core controls
      cio.hostTxAck.setIdle()
      cio.hostTx.setBlocked()
      cio.hostRxNextAck.setIdle()
      cio.hostRxNext.setBlocked()
      cio.hostRxNextReq := False

      // CSR for the core
      c.logic.ctrl.connectControl(csrCtrl, alloc(_))
      c.logic.ctrl.reportStatistics(csrCtrl, alloc(_, _))
    }
  }
}