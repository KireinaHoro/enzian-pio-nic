package pionic.host.eci

import jsteward.blocks.eci._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
import jsteward.blocks.misc.RegAllocatorFactory.allocToGeneric
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
import spinal.lib.bus.regif.AccessType.RO

import scala.language.postfixOps
import scala.util.Random

/** Plumbing logic for DCS interfaces.  Actual cacheline protocol logic is in classes that implement [[pionic.host.eci.EciPioProtocol]]. */
class EciInterfacePlugin extends PioNicPlugin with HostService {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val cores = host.list[CoreControlPlugin]
  lazy val protos = host.list[EciPioProtocol]
  // bypass core does not have preemption control
  lazy val preempts = null +: host.list[EciPreemptionControlPlugin]
  val retainer = Retainer()

  lazy val sizePerMtuPerDirection = (512 / 8) * 3 + roundMtu
  lazy val pktBufSizePerCore = c[Int]("pkt buf size per core")
  lazy val rxSizePerCore = pktBufSizePerCore - roundMtu
  lazy val txSizePerCore = roundMtu

  postConfig("host desc size", 64*8, action = ConfigDatabase.Unique) // BYTES

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
    private val alloc = c.f("global")(0, 0x1000, regWidth / 8)(s_axil_ctrl.config.dataWidth)
    csr.readAndWrite(csrCtrl, alloc)

    // axi DMA traffic steered into each core's packet buffers
    val dmaNodes = Seq.fill(numCores)(Axi4(axiConfig.copy(
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
    val coreOffset = 0x20000
    postConfig("eci core offset", coreOffset, action = ConfigDatabase.Unique)

    val coreIdMask  = 0x7e0000
    val preemptMask = 0x010000
    val unitIdMask  = coreIdMask | preemptMask
    val unitIdShift = Integer.numberOfTrailingZeros(unitIdMask)

    // list of data path nodes with optionally the preemption control node
    val dcsNodes = Seq.tabulate(numCores) { idx =>
      val config = axiConfig.copy(
        // 2 masters, ID width + 1
        idWidth = axiConfig.idWidth + 1,
        addressWidth = log2Up(coreOffset - 1),
      )

      // no preemption control node for bypass core
      (Axi4(config), Option.when(idx != 0)(Axi4(config)))
    }
    Axi4CrossbarFactory()
      .addSlaves(dcsNodes.zipWithIndex flatMap { case ((dataNode, preemptNodeOption), idx) =>
        val dataPathSize = host.list[EciPioProtocol].apply(idx).sizePerCore
        val preemptSize = if (idx != 0) {
          host.list[EciPreemptionControlPlugin].apply(idx - 1).requiredAddrSpace
        } else 0
        val sizePerCore = dataPathSize + preemptSize
        assert(coreOffset >= sizePerCore, "core offset smaller than needed mem size per core (plus preempt control)")

        Seq(dataNode -> SizeMapping(coreOffset * idx, dataPathSize)) ++
          preemptNodeOption.map(_ -> SizeMapping(coreOffset * idx + dataPathSize, preemptSize)).toSeq
      }: _*)
      .addConnections(dcsIntfs map { dcs =>
        dcs.axi.remapAddr { a =>
          val byteOffset = a(6 downto 0)
          // optimization of DCS: only 256 GiB (38 bits) of the address space is used
          (EciCmdDefs.unaliasAddress(a.asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)) | byteOffset.resized).resized
        } -> dcsNodes.flatMap { case (d, p) => Seq(d) ++ p.toSeq }
      }: _*)
      .build()

    // takes flattened list of LCI endpoints (incl. non-existent preemption control for bypass core)
    def bindCoreCmdsToLclChans(cmds: Seq[Stream[EciWord]], addrLocator: EciWord => Bits, evenVc: Int, oddVc: Int, chanLocator: DcsInterface => Stream[LclChannel]): Unit = {
      cmds.zipWithIndex.map { case (cmd, uidx) =>
        new Area {
          // core interfaces use UNALIASED addresses
          val acmd = cmd.mapPayloadElement(addrLocator) { a =>
            EciCmdDefs.aliasAddress(a.asUInt + coreOffset * (uidx / 2))
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

    // takes flattened list of LCI endpoints (incl. non-existent preemption control for bypass core)
    def bindLclChansToCoreResps(resps: Seq[Stream[EciWord]], addrLocator: EciWord => Bits, chanLocator: DcsInterface => Stream[LclChannel]): Unit = {
      dcsIntfs.map { dcs =>
        new Area {
          val chan = chanLocator(dcs)
          val unaliasedAddr = EciCmdDefs.unaliasAddress(addrLocator(chan.data)).asBits
          val unitIdx = ((unaliasedAddr & unitIdMask) >> unitIdShift).resize(log2Up(2 * numCores)).asUInt
          // demuxed into 2*numCores (INCLUDING non existent bypass preemption control)
          val ret = StreamDemux(chanLocator(dcs), unitIdx, 2 * numCores)
        }.setName("demuxLcl").ret
      }.transpose.zip(resps).zipWithIndex foreach { case ((chans, resp), uidx) => new Area {
        val resps = chans.map { c =>
          new Composite(c) {
            // dcs use ALIASED addresses
            val unaliased = c.mapPayloadElement(cc => addrLocator(cc.data)) { a =>
              (EciCmdDefs.unaliasAddress(a) - coreOffset * (uidx / 2)).asBits
            }
            val ret = unaliased.translateWith(unaliased.data)
          }
        }
        resp << StreamArbiterFactory().roundRobin.on(resps.map(_.ret))
      }.setName("arbitrateIntoCoreCmds")
      }
    }

    // mux LCL request (LCI)
    val coresLci = Seq.fill(numCores)(Seq.fill(2)(Stream(EciCmdDefs.EciAddress)))
    bindCoreCmdsToLclChans(coresLci.flatten.zipWithIndex.map { case (addr, uidx) => new Area {
      val ret = Stream(EciWord())

      // generating a LCI -- refer to Table 7.9 of CCKit
      ret.payload.lci.opcode  := B("00001")
      ret.payload.lci.hreqId  := B(uidx % 2) // 0 from datapath, 1 from preemption control
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
    val coresLcia = Seq.fill(numCores)(Seq.fill(2)(Stream(EciCmdDefs.EciAddress)))
    bindLclChansToCoreResps(coresLcia.flatten.zipWithIndex.map { case (lcia, uidx) =>
      new Area {
        val ret = Stream(EciWord())

        lcia.payload := ret.payload.lcia.address
        lcia.arbitrationFrom(ret)

        when (ret.fire) {
          assert(ret.payload.lcia.hreqId === B(uidx % 2), "source of LCIA does not match LCI")
        }
      }.setName("bindLcia").ret
    }, _.lcia.address, _.cleanMaybeInvResp)

    // mux LCL unlock response
    val coresUl = Seq.fill(numCores)(Seq.fill(2)(Stream(EciCmdDefs.EciAddress)))
    bindCoreCmdsToLclChans(coresUl.flatten.map { addr =>
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
    cores lazyZip dmaNodes lazyZip dcsNodes lazyZip coresLci lazyZip coresLcia lazyZip coresUl lazyZip protos lazyZip preempts foreach { case (((c, dmaNode, (dcsNode, preemptNodeOption), lci), lcia, ul, proto), preempt) => new Area {
      val baseAddress = (1 + c.coreID) * 0x1000
      val alloc = host[pionic.ConfigDatabase].f("core", c.coreID)(baseAddress, 0x1000, regWidth / 8)(s_axil_ctrl.config.dataWidth)
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

      val Seq(dataLci, preemptLci) = lci
      val Seq(dataLcia, preemptLcia) = lcia
      val Seq(dataUl, preemptUl) = ul

      dataLci  << proto.lci
      dataUl   << proto.ul
      dataLcia >> proto.lcia

      cio.hostTxAck     <-/< proto.hostTxAck
      cio.hostTx        >/-> proto.hostTx
      cio.hostRxAck <-/< proto.hostRxAck
      cio.hostRx    >/-> proto.hostRx
      cio.hostRxReq :=   proto.hostRxReq

      // CSR for the core
      c.logic.connectControl(csrCtrl, alloc)
      c.logic.reportStatistics(csrCtrl, alloc)

      proto.driveDcsBus(dcsNode, rxPktBuffer, txPktBuffer)
      proto.driveControl(csrCtrl, alloc)

      preemptNodeOption match {
        case None =>
          preemptLci.setIdle()
          preemptUl.setIdle()
          preemptLcia.setBlocked()
          assert(preempt == null)
          // tie down preemption request for bypass
          proto.preemptReq.setIdle()

          // XXX: still allocate ipiAck for bypass core due to allocator limitation
          alloc("ipiAck", attr = RO, readSensitive = true)
        case Some(pn) =>
          preempt.driveDcsBus(pn, preemptLci, preemptLcia, preemptUl)
          preempt.driveControl(csrCtrl, alloc)
      }
    }.setName("bindProtoToCoreCtrl")
    }

    // control for the decoders
    host.list[ProtoDecoder[_]].foreach(_.driveControl(csrCtrl, alloc))

    host[ProfilerPlugin].logic.reportTimestamps(csrCtrl, alloc)
  }
}
