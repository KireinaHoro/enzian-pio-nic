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
import scala.util.Random

/** Plumbing logic for DCS interfaces.  Actual cacheline protocol logic is in classes that implement [[pionic.eci.EciPioProtocol]]. */
class EciInterfacePlugin(implicit config: PioNicConfig) extends FiberPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val cores = host.list[CoreControlPlugin]
  lazy val protos = host.list[EciPioProtocol]
  val retainer = Retainer()

  val sizePerMtuPerDirection = (512 / 8) * 3 + config.roundMtu
  val rxSizePerCore = config.pktBufSizePerCore - config.roundMtu
  val txSizePerCore = config.roundMtu

  lazy val configWriter = host[ConfigWriter]

  // dcs_2_axi AXI config
  val axiConfig = Axi4Config(
    // ECI address width
    addressWidth = EciDcsDefs.DS_ADDR_WIDTH,
    dataWidth = 512,
    idWidth = 7,
    useRegion = false,
    useQos = false,
  )
  val pktBufWordWidth = axiConfig.dataWidth

  val logic = during build new Area {
    val clockDomain = ClockDomain.current
    val dcsClock = ClockDomain.external("dcsClock")

    // even and odd in ALIASED addresses
    // Refer to Chapter 9.4 in CCKit
    val dcsOdd = slave(DcsInterface(axiConfig)) addTag ClockDomainTag(dcsClock)
    val dcsEven = slave(DcsInterface(axiConfig)) addTag ClockDomainTag(dcsClock)

    dcsOdd.axi.setName("s_axi_dcs_odd")
    dcsEven.axi.setName("s_axi_dcs_even")

    val dcsCdcIntfs = Seq(dcsEven, dcsOdd) map { dcs =>
      new Composite(dcs, "cdc") {
        val ret = dcs.clone
        dcs.axi.cdc(dcsClock, clockDomain) >> ret.axi
        val lciFifo = SimpleAsyncFifo(ret.cleanMaybeInvReq, dcs.cleanMaybeInvReq, 2, clockDomain, dcsClock)
        val ulFifo = SimpleAsyncFifo(ret.unlockResp, dcs.unlockResp, 2, clockDomain, dcsClock)
        val lciaFifo = SimpleAsyncFifo(dcs.cleanMaybeInvResp, ret.cleanMaybeInvResp, 2, dcsClock, clockDomain)
      }.ret
    }

    // assert dcs interfaces never drop valid when ready is low
    dcsCdcIntfs foreach { dcs =>
      checkStreamValidDrop(dcs.cleanMaybeInvReq)
      checkStreamValidDrop(dcs.unlockResp)
    }

    val s_axil_ctrl = slave(AxiLite4(
      addressWidth = 44, dataWidth = 64,
    )) addTag ClockDomainTag(clockDomain)

    val csrCtrl = AxiLite4SlaveFactory(s_axil_ctrl)
    private val alloc = config.allocFactory("global")(0, 0x1000, config.regWidth / 8)(s_axil_ctrl.config.dataWidth)
    csr.readAndWrite(csrCtrl, alloc(_))

    // axi DMA traffic steered into each core's packet buffers
    val dmaNodes = Seq.fill(cores.length)(Axi4(axiConfig.copy(
      addressWidth = log2Up(config.pktBufSizePerCore - 1),
    )))
    Axi4CrossbarFactory()
      .addSlaves(dmaNodes.zipWithIndex map { case (node, idx) =>
        node -> SizeMapping(config.pktBufSizePerCore * idx, config.pktBufSizePerCore)
      }: _*)
      // FIXME: we could need an adapter here
      .addConnection(macIf.packetBufDmaMaster -> dmaNodes)
      .build()

    // mux both DCS AXI masters to all cores
    val coreOffset = 0x10000
    configWriter.postConfig("eci core offset", coreOffset)

    val dcsNodes = Seq.fill(cores.length)(Axi4(axiConfig.copy(
      // 2 masters, ID width + 1
      idWidth = axiConfig.idWidth + 1,
      addressWidth = log2Up(coreOffset - 1),
    )))
    Axi4CrossbarFactory()
      .addSlaves(dcsNodes.zipWithIndex map { case (node, idx) =>
        val sizePerCore = host.list[EciPioProtocol].apply(idx).sizePerCore
        assert(coreOffset >= sizePerCore, "core offset smaller than needed mem size per core")
        node -> SizeMapping(coreOffset * idx, sizePerCore)
      }: _*)
      .addConnections(dcsCdcIntfs map { dcs =>
        dcs.axi.remapAddr { a =>
          val byteOffset = a(6 downto 0)
          // optimization of DCS: only 256 GiB (38 bits) of the address space is used
          (EciCmdDefs.unaliasAddress(a.asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)) | byteOffset.resized).resized
        } -> dcsNodes
      }: _*)
      .build()

    def bindCoreCmdsToLclChans(cmds: Seq[Stream[EciWord]], addrLocator: EciWord => Bits, evenVc: Int, oddVc: Int, chanLocator: DcsInterface => Stream[LclChannel]): Unit = {
      cmds.zipWithIndex.map { case (cmd, idx) =>
        new Area {
          // core interfaces use UNALIASED addresses
          val acmd = cmd.mapPayloadElement(addrLocator) { a =>
            EciCmdDefs.aliasAddress(a.asUInt + coreOffset * idx)
          }
          // lowest 7 bits are byte offset
          val dcsIdx = addrLocator(acmd.payload)(7).asUInt

          // assemble ECI channel
          val chanStream = Stream(LclChannel())
          chanStream.translateFrom(acmd) { case (chan, data) =>
            chan.data := data
            chan.vc := dcsIdx.asBool ? B(oddVc) | B(evenVc)
            chan.size := 1
          }

          val ret = StreamDemux(chanStream, dcsIdx, 2).toSeq
        }.setName("demuxCoreCmds").ret
      }.transpose.zip(dcsCdcIntfs) foreach { case (chan, dcs) => new Area {
        chanLocator(dcs) << StreamArbiterFactory().roundRobin.on(chan)
      }.setName("arbitrateIntoLcl")
      }
    }

    def bindLclChansToCoreResps(resps: Seq[Stream[EciWord]], hreqIdLocator: EciWord => Bits, addrLocator: EciWord => Bits, chanLocator: DcsInterface => Stream[LclChannel]): Unit = {
      dcsCdcIntfs.map { dcs =>
        new Area {
          val chan = chanLocator(dcs)
          val coreIdx = hreqIdLocator(chan.data).asUInt.resize(log2Up(cores.length))
          val ret = StreamDemux(chanLocator(dcs), coreIdx, cores.length).toSeq
        }.setName("demuxLcl").ret
      }.transpose.zip(resps).zipWithIndex foreach { case ((chans, resp), idx) => new Area {
        val resps = chans.map { c =>
          new Composite(c) {
            // dcs use ALIASED addresses
            val unaliased = c.mapPayloadElement(cc => addrLocator(cc.data)) { a =>
              (EciCmdDefs.unaliasAddress(a) - coreOffset * idx).asBits
            }
            val ret = unaliased.translateWith(unaliased.data)
          }
        }
        resp << StreamArbiterFactory().roundRobin.on(resps.map(_.ret))
      }.setName("arbitrateIntoCoreCmds")
      }
    }

    // mux LCL request (LCI)
    assert(log2Up(cores.length) <= EciCmdDefs.ECI_HREQID_WIDTH, s"${cores.length} cores cannot fit inside hreq id of ${EciCmdDefs.ECI_HREQID_WIDTH}!")
    val coresLci = Seq.fill(cores.length)(Stream(EciCmdDefs.EciAddress))
    bindCoreCmdsToLclChans(coresLci.zipWithIndex.map { case (addr, idx) => new Area {
      val ret = Stream(EciWord())

      // generating a LCI -- refer to Table 7.9 of CCKit
      ret.payload.lci.opcode := B("00001")
      ret.payload.lci.hreqId := B(idx, EciCmdDefs.ECI_HREQID_WIDTH bits)
      ret.payload.lci.dmask := B("1111")
      ret.payload.lci.ns := True
      ret.payload.lci.rnode := B("01")
      ret.payload.lci.address := addr.payload

      ret.payload.lci.xb1 := False
      ret.payload.lci.xb2 := B(0, 2 bits)
      ret.payload.lci.xb3 := B(0, 3 bits)

      ret.arbitrationFrom(addr)
    }.setName("bindLci").ret
    }, _.lci.address, 17, 16, _.cleanMaybeInvReq)

    // demux LCL response (LCIA)
    val coresLcia = Seq.fill(cores.length)(Stream(EciCmdDefs.EciAddress))
    bindLclChansToCoreResps(coresLcia.map { lcia =>
      new Area {
        val ret = Stream(EciWord())

        lcia.payload := ret.payload.lcia.address
        lcia.arbitrationFrom(ret)
      }.setName("bindLcia").ret
    }, _.lcia.hreqId, _.lcia.address, _.cleanMaybeInvResp)

    // mux LCL unlock response
    val coresUl = Seq.fill(cores.length)(Stream(EciCmdDefs.EciAddress))
    bindCoreCmdsToLclChans(coresUl.map { addr =>
      new Area {
        val ret = Stream(EciWord())

        // generating a UL -- refer to Table 7.11 of CCKit
        ret.payload.ul.opcode := B("00010")
        ret.payload.ul.address := addr.payload

        ret.payload.ul.xb19 := B(0, 19 bits)

        ret.arbitrationFrom(addr)
      }.setName("bindUl").ret
    }, _.ul.address, 19, 18, _.unlockResp)

    // drive core control interface -- datapath per core
    cores lazyZip dmaNodes lazyZip dcsNodes lazyZip coresLci lazyZip coresLcia lazyZip coresUl lazyZip protos foreach { case ((c, dmaNode, dcsNode, lci), lcia, ul, proto) => new Area {
      val baseAddress = (1 + c.coreID) * 0x1000
      val alloc = config.allocFactory("control", c.coreID)(baseAddress, 0x1000, config.regWidth / 8)(s_axil_ctrl.config.dataWidth)
      val cio = c.logic.ctrl.io

      // per-core packet buffer
      val rxNumWords = rxSizePerCore / (pktBufWordWidth / 8)
      val txNumWords = txSizePerCore / (pktBufWordWidth / 8)

      // FIXME: fix naming in lambda functions
      val rxPktBuffer = Mem(Bits(pktBufWordWidth bits), rxNumWords)
      val txPktBuffer = Mem(Bits(pktBufWordWidth bits), txNumWords)

      // packet data DMA into packet buffer
      // TODO: datapath pipeline attach point (accelerators, rpc request decode, etc.)
      val dmaBusCtrl = Axi4SlaveFactory(dmaNode.fullPipe())
      dmaBusCtrl.writeMemWordAligned(rxPktBuffer, 0)
      dmaBusCtrl.readSyncMemWordAligned(txPktBuffer, rxSizePerCore)

      proto.driveDcsBus(dcsNode, rxPktBuffer, txPktBuffer)

      lci << proto.lci
      ul << proto.ul
      lcia >> proto.lcia

      cio.hostTxAck <-/< proto.hostTxAck
      cio.hostTx >/-> proto.hostTx
      cio.hostRxNextAck <-/< proto.hostRxNextAck
      cio.hostRxNext >/-> proto.hostRxNext
      cio.hostRxNextReq := proto.hostRxNextReq

      // CSR for the core
      c.logic.ctrl.connectControl(csrCtrl, alloc(_))
      c.logic.ctrl.reportStatistics(csrCtrl, alloc(_, _))
    }.setName("bindProtoToCoreCtrl")
    }
  }
}
