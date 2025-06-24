package pionic.host.eci

import jsteward.blocks.eci._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
import pionic._
import pionic.net.ProtoDecoder
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType.RO
import Global._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

/**
  * Plumbing logic for DCS interfaces.  Performs the following connections:
  *  - packet data: global [[PacketBuffer]] to [[EciPioProtocol]] instances
  *  - packet descriptors (non-bypass): [[Scheduler]] to [[EciPioProtocol]] instances
  *    - bypass descriptors are directly passed in [[DmaControlPlugin]] already
  *  - DCS access for all [[EciPioProtocol]] instances
  *
  * Also implements host register access for everything that has control registers.
  *
  * Actual cache-line protocol logic is in classes that implement [[pionic.host.eci.EciPioProtocol]] (e.g.
  * [[EciDecoupledRxTxProtocol]]).
  */
class EciInterfacePlugin extends FiberPlugin {
  lazy val macIf = host[MacInterfaceService]
  lazy val csr = host[GlobalCSRPlugin]
  lazy val protos = host.list[EciPioProtocol]

  // bypass core does not have preemption control; add null to allow one loop later
  lazy val preempts = null +: host.list[EciPreemptionControlPlugin]

  HOST_REQ_WIDTH.set(64 * 8)

  // dcs_2_axi AXI config has ID width 7
  // - we have two DCS slices -> 8
  // - each core has RX and TX routers -> 9
  // - we have NUM_CORES cores
  PKT_BUF_ID_WIDTH.set(log2Up(NUM_CORES) + 9)

  val axiConfig = Axi4Config(
    // ECI address width
    addressWidth = EciDcsDefs.DS_ADDR_WIDTH,
    dataWidth = 512,
    idWidth = 7,
    useRegion = false,
    useQos = false,
  )

  val logic = during build new Area {
    val clockDomain = ClockDomain.current

    // even and odd in ALIASED addresses
    // Refer to Chapter 9.4 in CCKit
    val dcsOdd = slave(DcsInterface(axiConfig)) addTag ClockDomainTag(clockDomain)
    val dcsEven = slave(DcsInterface(axiConfig)) addTag ClockDomainTag(clockDomain)

    dcsOdd.axi.setName("s_axi_dcs_odd")
    dcsEven.axi.setName("s_axi_dcs_even")

    val dcsIntfs = Seq(dcsEven, dcsOdd)

    // muxed interface to ECI interrupt controller
    val ipiToIntc = master(Stream(EciIntcInterface()))
    val demuxedIpiIntfs = null +: Seq.fill(NUM_WORKER_CORES)(Stream(EciIntcInterface()))
    // FIXME: do we need to merge core masks?
    ipiToIntc << StreamArbiterFactory().roundRobin.on(demuxedIpiIntfs.tail)

    // assert dcs interfaces never drop valid when ready is low
    dcsIntfs foreach { dcs =>
      dcs.cleanMaybeInvReq.assertPersistence()
      dcs.unlockResp.assertPersistence()
    }

    val s_axil_ctrl = slave(AxiLite4(
      addressWidth = 44, dataWidth = 64,
    )) addTag ClockDomainTag(clockDomain)

    // connect CSR for global modules
    val csrCtrl = AxiLite4SlaveFactory(s_axil_ctrl)
    private val alloc = ALLOC.get("global")(0, 0x1000, REG_WIDTH / 8)(s_axil_ctrl.config.dataWidth)
    csr.readAndWrite(csrCtrl, alloc)
    host.list[ProtoDecoder[_]].foreach(_.driveControl(csrCtrl, alloc))
    host[ProfilerPlugin].logic.reportTimestamps(csrCtrl, alloc)
    host[Scheduler].driveControl(csrCtrl, alloc)
    host[DmaControlPlugin].logic.connectControl(csrCtrl, alloc)
    host[DmaControlPlugin].logic.reportStatistics(csrCtrl, alloc)

    // master nodes for access to packet buffer
    val memNode = host[PacketBuffer].logic.axiMem.io.s_axi_b
    val accessNodes = Seq.fill(NUM_CORES)(Axi4(memNode.config.copy(idWidth = 9)))
    Axi4CrossbarFactory()
      .addSlave(memNode, SizeMapping(0, PKT_BUF_SIZE))
      .addConnections(accessNodes.map(_ -> Seq(memNode)): _*)
      .build()

    // mux both DCS AXI masters to all cores
    // FIXME: post to host header generator
    val coreOffset = 0x20000
    val coreIdMask  = 0x7e0000
    val preemptMask = 0x010000
    val unitIdMask  = coreIdMask | preemptMask
    val unitIdShift = Integer.numberOfTrailingZeros(unitIdMask)

    // list of data path nodes with optionally the preemption control node
    val dcsNodes = Seq.tabulate(NUM_CORES) { idx =>
      val config = axiConfig.copy(
        // 2 masters, ID width + 1
        idWidth = 8,
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
          val unitIdx = ((unaliasedAddr & unitIdMask) >> unitIdShift).resize(log2Up(2 * NUM_CORES)).asUInt
          // demuxed into 2*numCores (INCLUDING non existent bypass preemption control)
          val ret = StreamDemux(chanLocator(dcs), unitIdx, 2 * NUM_CORES)
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
    val coresLci = Seq.fill(NUM_CORES)(Seq.fill(2)(Stream(EciCmdDefs.EciAddress)))
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
    val coresLcia = Seq.fill(NUM_CORES)(Seq.fill(2)(Stream(EciCmdDefs.EciAddress)))
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
    val coresUl = Seq.fill(NUM_CORES)(Seq.fill(2)(Stream(EciCmdDefs.EciAddress)))
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
    0 until NUM_CORES foreach { cid => new Area {
      // get all nodes to bind
      val (dcsNode, preemptNodeOption) = dcsNodes(cid)
      val Seq(dataLci, preemptLci) = coresLci(cid)
      val Seq(dataLcia, preemptLcia) = coresLcia(cid)
      val Seq(dataUl, preemptUl) = coresUl(cid)
      val proto = protos(cid)
      val preempt = preempts(cid)
      val ipiCtrl = demuxedIpiIntfs(cid)
      val memNode = accessNodes(cid)

      // bind DCS channels to datapath
      dataLci  << proto.lci
      dataUl   << proto.ul
      dataLcia >> proto.lcia

      if (cid != 0) {
        // worker cores get RX descriptors from scheduler
        // bypass core descriptor already connected by [[DmaControlPlugin]]
        host[Scheduler].logic.coreMeta(cid - 1) >> proto.hostRx
      }

      proto.driveDcsBus(dcsNode, memNode)
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
          preempt.logic.ipiToIntc >> ipiCtrl
      }
    }.setName("bindProtoToCoreCtrl")
    }
  }
}
