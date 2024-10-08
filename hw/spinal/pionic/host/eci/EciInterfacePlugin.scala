package pionic.host.eci

import jsteward.blocks.eci._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
import pionic._
import pionic.host.HostService
import pionic.net.ProtoDecoder
import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.misc.plugin._
import spinal.lib.bus.misc.SizeMapping

import scala.language.postfixOps
import scala.util.Random

/** Plumbing logic for DCS interfaces.  Actual cacheline protocol logic is in classes that implement [[pionic.host.eci.EciPioProtocol]]. */
class EciInterfacePlugin extends PioNicPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val cores = host.list[CoreControlPlugin]
  lazy val protos = host.list[EciPioProtocol]
  lazy val allocFactory = host[RegAlloc].f
  val retainer = Retainer()

  lazy val sizePerMtuPerDirection = (512 / 8) * 3 + roundMtu
  lazy val pktBufSizePerCore = c[Int]("pkt buf size per core")
  lazy val rxSizePerCore = pktBufSizePerCore - roundMtu
  lazy val txSizePerCore = roundMtu

  postConfig("max host desc size", 64, action = ConfigDatabase.Unique) // BYTES

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

    // even and odd in ALIASED addresses
    // Refer to Chapter 9.4 in CCKit
    val dcsOdd = slave(DcsInterface(axiConfig)) addTag ClockDomainTag(clockDomain)
    val dcsEven = slave(DcsInterface(axiConfig)) addTag ClockDomainTag(clockDomain)

    dcsOdd.axi.setName("s_axi_dcs_odd")
    dcsEven.axi.setName("s_axi_dcs_even")

    val dcsIntfs = Seq(dcsEven, dcsOdd)

    // assert dcs interfaces never drop valid when ready is low
    dcsIntfs foreach { dcs =>
      dcs.cleanMaybeInvReq.assertPersistence()
      dcs.unlockResp.assertPersistence()
    }

    val s_axil_ctrl = slave(AxiLite4(
      addressWidth = 44, dataWidth = 64,
    )) addTag ClockDomainTag(clockDomain)

    val csrCtrl = AxiLite4SlaveFactory(s_axil_ctrl)
    private val alloc = allocFactory("global")(0, 0x1000, regWidth / 8)(s_axil_ctrl.config.dataWidth)
    csr.readAndWrite(csrCtrl, alloc(_))

    // axi DMA traffic steered into each core's packet buffers
    val dmaNodes = Seq.fill(cores.length)(Axi4(axiConfig.copy(
      addressWidth = log2Up(pktBufSizePerCore - 1),
    )))
    Axi4CrossbarFactory()
      .addSlaves(dmaNodes.zipWithIndex map { case (node, idx) =>
        node -> SizeMapping(pktBufSizePerCore * idx, pktBufSizePerCore)
      }: _*)
      // FIXME: we could need an adapter here
      .addConnection(host[AxiDmaPlugin].packetBufDmaMaster -> dmaNodes)
      .build()

    // mux both DCS AXI masters to all cores
    val coreOffset = 0x10000
    postConfig("eci core offset", coreOffset, action = ConfigDatabase.Unique)

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
      .addConnections(dcsIntfs map { dcs =>
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
          // even addr -> odd VC, vice versa
          val dcsIdx = (~addrLocator(acmd.payload)(7)).asUInt

          // assemble ECI channel
          val chanStream = Stream(LclChannel())
          chanStream.translateFrom(acmd) { case (chan, data) =>
            chan.data := data
            chan.vc := dcsIdx.asBool ? B(oddVc) | B(evenVc)
            chan.size := 1
          }

          val ret = StreamDemux(chanStream, dcsIdx, 2).toSeq
        }.setName("demuxCoreCmds").ret
      }.transpose.zip(dcsIntfs) foreach { case (chan, dcs) => new Area {
        chanLocator(dcs) << StreamArbiterFactory().roundRobin.on(chan)
      }.setName("arbitrateIntoLcl")
      }
    }

    def bindLclChansToCoreResps(resps: Seq[Stream[EciWord]], hreqIdLocator: EciWord => Bits, addrLocator: EciWord => Bits, chanLocator: DcsInterface => Stream[LclChannel]): Unit = {
      dcsIntfs.map { dcs =>
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
      ret.payload.lci.opcode  := B("00001")
      ret.payload.lci.hreqId  := B(idx, EciCmdDefs.ECI_HREQID_WIDTH bits)
      ret.payload.lci.dmask   := B("1111")
      ret.payload.lci.ns      := True
      ret.payload.lci.rnode   := B("01")
      ret.payload.lci.address := addr.payload
      ret.payload.lci.xb1     := False
      ret.payload.lci.xb2     := B("2'x0")
      ret.payload.lci.xb3     := B("3'x0")

      ret.arbitrationFrom(addr)
    }.setName("bindLci").ret
    }, _.lci.address, 16, 17, _.cleanMaybeInvReq)

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
        ret.payload.ul.opcode  := B("00010")
        ret.payload.ul.address := addr.payload
        ret.payload.ul.xb19    := B("19'x0")

        ret.arbitrationFrom(addr)
      }.setName("bindUl").ret
    }, _.ul.address, 18, 19, _.unlockResp)

    // drive core control interface -- datapath per core
    cores lazyZip dmaNodes lazyZip dcsNodes lazyZip coresLci lazyZip coresLcia lazyZip coresUl lazyZip protos foreach { case ((c, dmaNode, dcsNode, lci), lcia, ul, proto) => new Area {
      val baseAddress = (1 + c.coreID) * 0x1000
      val alloc = allocFactory("core", c.coreID)(baseAddress, 0x1000, regWidth / 8)(s_axil_ctrl.config.dataWidth)
      val cio = c.logic.io

      // per-core packet buffer
      val rxNumWords = rxSizePerCore / (pktBufWordWidth / 8)
      val txNumWords = txSizePerCore / (pktBufWordWidth / 8)

      // FIXME: fix naming in lambda functions
      val rxPktBuffer = Mem(Bits(pktBufWordWidth bits), rxNumWords)
      val txPktBuffer = Mem(Bits(pktBufWordWidth bits), txNumWords)

      // packet data DMA into packet buffer
      val dmaBusCtrl = Axi4SlaveFactory(dmaNode.fullPipe())
      dmaBusCtrl.writeMemWordAligned(rxPktBuffer, 0)
      dmaBusCtrl.readSyncMemWordAligned(txPktBuffer, rxSizePerCore)

      lci  << proto.lci
      ul   << proto.ul
      lcia >> proto.lcia

      cio.hostTxAck     <-/< proto.hostTxAck
      cio.hostTx        >/-> proto.hostTx
      cio.hostRxAck <-/< proto.hostRxAck
      cio.hostRx    >/-> proto.hostRx
      cio.hostRxReq :=   proto.hostRxReq

      // CSR for the core
      c.logic.connectControl(csrCtrl, alloc(_))
      c.logic.reportStatistics(csrCtrl, alloc(_, _))

      proto.driveDcsBus(dcsNode, rxPktBuffer, txPktBuffer)
      proto.driveControl(csrCtrl, alloc(_))
    }.setName("bindProtoToCoreCtrl")
    }

    // control for the decoders
    host.list[ProtoDecoder[_]].foreach(_.driveControl(csrCtrl, alloc(_, _)))

    host[ProfilerPlugin].logic.reportTimestamps(csrCtrl, alloc(_, _))
  }
}
