package lauberhorn.host.eci

import jsteward.blocks.eci._
import jsteward.blocks.axi._
import jsteward.blocks.misc._
import lauberhorn._
import lauberhorn.net.{Decoder, DecoderSink}
import spinal.core._
import spinal.lib._
import spinal.lib.StreamPipe.FULL
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.misc.SizeMapping
import Global._
import lauberhorn.host.BypassCmdSink
import lauberhorn.net.ip.IpEncoder
import lauberhorn.net.oncrpc.OncRpcReplyEncoder
import spinal.lib.bus.amba4.axilite.AxiLite4Utils.AxiLite4Rich
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps
import scala.collection.mutable

/**
  * Plumbing logic for DCS interfaces.  Performs the following connections:
  *  - packet data: global [[PacketBuffer]] to [[EciPioProtocol]] instances
  *  - packet descriptors (non-bypass): [[Scheduler]] to [[EciPioProtocol]] instances
  *    - bypass descriptors are directly passed in [[DmaControlPlugin]] already
  *  - DCS access for all [[EciPioProtocol]] instances
  *
  * Also implements host register access for everything that has control registers.
  *
  * Actual cache-line protocol logic is in classes that implement [[lauberhorn.host.eci.EciPioProtocol]] (e.g.
  * [[EciDecoupledRxTxProtocol]]).
  */
class EciInterfacePlugin extends FiberPlugin {
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
    idWidth = EciDcsDefs.MAX_DCU_ID_WIDTH,
    useRegion = false,
    useQos = false,
  )

  lazy val preemptCritSecTimeout = Reg(UInt(REG_WIDTH bits)) init 100000 // 400 us @ 250 MHz
  lazy val rxBlockCycles = Reg(UInt(REG_WIDTH bits)) init 10000

  val coreOffset = 0x20000
  ECI_CORE_OFFSET.set(coreOffset)

  val logic = during setup new Area {
    val clockDomain = ClockDomain.current

    // even and odd in ALIASED addresses
    // Refer to Chapter 9.4 in CCKit
    val dcsOdd = slave(DcsInterface(axiConfig)) addTag ClockDomainTag(clockDomain)
    val dcsEven = slave(DcsInterface(axiConfig)) addTag ClockDomainTag(clockDomain)

    dcsOdd.axi.setName("s_axi_dcs_odd")
    dcsEven.axi.setName("s_axi_dcs_even")

    val dcsIntfs = Seq(dcsEven, dcsOdd)
    awaitBuild()

    // muxed interface to ECI interrupt controller
    val ipiToIntc = master(Stream(EciIntcInterface()))
    val demuxedIpiIntfs = Seq.fill(NUM_CORES)(Stream(EciIntcInterface()))
    // FIXME: do we need to merge core masks?
    ipiToIntc << StreamArbiterFactory(s"${getName()}_ipiCmdMux").roundRobin.on(demuxedIpiIntfs)

    // assert dcs interfaces never drop valid when ready is low
    dcsIntfs foreach { dcs =>
      dcs.cleanMaybeInvReq.assertPersistence()
      dcs.unlockResp.assertPersistence()
    }

    val s_axil_ctrl = slave(AxiLite4(
      addressWidth = 44, dataWidth = REG_WIDTH,
    )) addTag ClockDomainTag(clockDomain)

    var ctrlBlockStart = 0
    val ctrlBlockSize = 0x100
    val ctrlAxiLiteNodes = mutable.ListBuffer[(AxiLite4, SizeMapping)]()
    def drive(func: (AxiLite4, RegBlockAlloc) => Unit, blockName: String, idx: Int = 0) = {
      val alloc = ALLOC.get(blockName, idx)(ctrlBlockStart, ctrlBlockSize, REG_WIDTH / 8)()

      val node = AxiLite4(s_axil_ctrl.config)
      ctrlAxiLiteNodes += node -> SizeMapping(ctrlBlockStart, ctrlBlockSize)
      ctrlBlockStart += ctrlBlockSize

      func(node, alloc)
    }

    drive({ (bus, alloc) =>
      host[GlobalCSRPlugin].readAndWrite(AxiLite4SlaveFactory(bus), alloc)
    }, "global")
    drive(host[MacInterfaceService].driveControl, "macIf")
    drive(host[DecoderSink].driveControl, "decoderSink")
    host.list[Decoder[_]].foreach { pd => drive(pd.driveControl, pd.decoderName) }

    drive(host[IpEncoder].driveControl, "IpEncoder")
    drive(host[OncRpcReplyEncoder].driveControl, "OncRpcReplyEncoder")

    drive(host[Scheduler].driveControl, "sched")
    drive(host[DmaControlPlugin].logic.driveControl, "dma")
    drive(host[EciThreadClRouter].driveControl, "threadRouter")
    drive(host[BypassCmdSink].driveControl, "bypassSink")

    drive({ (bus, alloc) =>
      val busCtrl = AxiLite4SlaveFactory(bus)
      busCtrl.readAndWrite(preemptCritSecTimeout, alloc("preemptCritSecTimeout",
        desc = "Timeout before preemption victim process is killed (in cycles)"))
      busCtrl.readAndWrite(rxBlockCycles, alloc("rxBlockCycles",
        desc = "Timeout before a NACK is returned for RX (in cycles)"))
    }, "hostIf")

    // master nodes for access to packet buffer
    val memNode = host[PacketBuffer].logic.axiMem.io.s_axi_b

    val coreIdMask  = 0x7e0000
    val preemptMask = 0x010000
    val unitIdMask  = coreIdMask | preemptMask
    val unitIdShift = Integer.numberOfTrailingZeros(unitIdMask)

    // list of data path nodes with optionally the preemption control node
    val dcsNodeConfig = axiConfig.copy(
      // 2 masters, ID width + 1
      idWidth = 8,
      // truncate address here so that the core protocol modules wouldn't have to subtract the core offset
      addressWidth = log2Up(coreOffset - 1),
    )

    val unaliasedDcsAxi = dcsIntfs map { dcs =>
      dcs.axi.remapAddr { a =>
        val byteOffset = a(6 downto 0)
        // optimization of DCS: only 256 GiB (38 bits) of the address space is used
        (EciCmdDefs.unaliasAddress(a.asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)).asUInt | byteOffset.resized).resized
      }
    }
    val translatedDcsAxi = unaliasedDcsAxi zip host[EciThreadClRouter].logic.axi map { case (ua, rp) =>
      ua >> rp.axiFromDcs
      rp.axiToProto
    }

    /** Bind the LCI/UL commands from the 2F2F state machines to the odd and even DCS channels.  Takes a flattened
      * list of LCI endpoints (incl. non-existent preemption control for bypass core).
      *
      * This implements a mux-demux instead of a full crossbar; refer to [[ClLclPort]] for the rationale.
      */
    def bindCoreCmdsToLclChans(cmds: Seq[Stream[EciWord]], addrLocator: EciWord => Bits, evenVc: Int, oddVc: Int, chanLocator: DcsInterface => Stream[EciChannel], isUl: Boolean = false): Unit = {
      val chanName = if (isUl) "ul" else "lci"
      val routerPort = host[EciThreadClRouter].logic.lcl
      val (toRouter, fromRouter) = if (isUl) {
        (routerPort.ulFromProto, routerPort.ulToDcs)
      } else {
        (routerPort.lciFromProto, routerPort.lciToDcs)
      }

      // first, mux all core commands together
      val muxed = StreamArbiterFactory(s"EciInterfacePlugin_logic_${chanName}FromCore_mux")
        .roundRobin.on(cmds.zipWithIndex.map { case (cmd, unitIdx) =>
          // core commands came in without the core offset
          cmd.mapPayloadElement(addrLocator) { a =>
            (a.asUInt + coreOffset * (unitIdx / 2)).asBits
          }
        })

      // translate this muxed channel
      muxed >> toRouter

      // pack into ECI channel
      val chan = Stream(EciChannel())
      chan.translateFrom(fromRouter) { case (c, fr) =>
        c.data := fr.mapElement(addrLocator)(EciCmdDefs.aliasAddress)
        c.vc := ~addrLocator(chan.data)(7) ? B(oddVc) | B(evenVc)
        c.size := 1
      }

      // unmux the translated channel
      val dcsIdx = (chan.vc === B(oddVc)).asUInt
      dcsIntfs zip StreamDemux(chan, dcsIdx, 2) foreach { case (dcs, fr) =>
        chanLocator(dcs) << fr
      }
    }

    /** Bind the LCIA responses from the DCS channels to the 2F2F state machines.  Takes a flattened
      * list of LCIA endpoints (incl. non-existent preemption control for bypass core).
      *
      * This implements a mux-demux instead of a full crossbar; refer to [[ClLclPort]] for the rationale.
      */
    def bindLclChansToCoreResps(resps: Seq[Stream[EciWord]], addrLocator: EciWord => Bits, chanLocator: DcsInterface => Stream[EciChannel]): Unit = {
      val routerPort = host[EciThreadClRouter].logic.lcl
      val (toRouter, fromRouter) = (routerPort.lciaFromDcs, routerPort.lciaToProto)

      // first, mux all dcs responses together
      val muxed = StreamArbiterFactory(s"EciInterfacePlugin_logic_lciaFromDcs_mux")
        .roundRobin.on(dcsIntfs map { dcs =>
          // cut LCIA ready dependency on valid
          val chan = chanLocator(dcs).pipelined(FULL)
          // dcs use ALIASED addresses
          val unaliased = chan.mapPayloadElement(cc => addrLocator(cc.data))(EciCmdDefs.unaliasAddress)

          // convert to EciWord (dropping extra stuff)
          unaliased.translateWith(unaliased.data)
        })

      // translate this muxed response channel
      muxed >> toRouter

      // unmux the translated channel
      val translatedAddr = addrLocator(fromRouter.payload)
      val unitIdx = ((translatedAddr & unitIdMask) >> unitIdShift).resize(log2Up(2 * NUM_CORES)).asUInt
      val unmuxed = StreamDemux(fromRouter, unitIdx, 2 * NUM_CORES)

      // subtract core offset and connect to cores
      (unmuxed zip resps).zipWithIndex foreach { case ((fr, resp), unitIdx) =>
        resp << fr.mapPayloadElement(addrLocator) { a =>
          (a.asUInt - coreOffset * (unitIdx / 2)).asBits
        }
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
    }.setCompositeName(this, "bindLci").ret
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
      }.setCompositeName(this, "bindLcia").ret
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
      }.setCompositeName(this, "bindUl").ret
    }, _.ul.address, 18, 19, _.unlockResp, isUl = true)

    val allSlaveNodes = mutable.ListBuffer[(Axi4, SizeMapping)]()
    val allMasterNodes = mutable.ListBuffer[Axi4]()

    // drive core control interface -- datapath per core
    0 until NUM_CORES foreach { cid => new Area {
      val Seq(dataLci, preemptLci) = coresLci(cid)
      val Seq(dataLcia, preemptLcia) = coresLcia(cid)
      val Seq(dataUl, preemptUl) = coresUl(cid)
      val proto = protos(cid)
      val preempt = preempts(cid)
      val ipiCtrl = demuxedIpiIntfs(cid)

      val baseAddress = (1 + cid) * 0x1000

      // bind DCS channels to datapath
      dataLci  << proto.lci
      dataUl   << proto.ul
      dataLcia >> proto.lcia

      if (cid != 0) {
        // worker cores get RX descriptors from scheduler
        // bypass core descriptor already connected by [[DmaControlPlugin]]
        host[Scheduler].logic.coreMeta(cid - 1) >> proto.hostRx
      }

      drive(proto.driveControl, "worker", cid)

      val (slaveNodesWithMapping, masterNodes) = proto.makeAccessPorts(dcsNodeConfig, memNode.config)
      allMasterNodes.appendAll(masterNodes)

      slaveNodesWithMapping.foreach { case (sn, map) =>
        allSlaveNodes.append((sn, map.copy(base = map.base + coreOffset * cid)))
      }
      if (cid != 0) {
        // also add preempt node
        val preemptNode = Axi4(dcsNodeConfig)
        val preemptSize = host.list[EciPreemptionControlPlugin].apply(cid - 1).requiredAddrSpace
        val preemptMapping = SizeMapping(coreOffset * cid + proto.sizePerCore, preemptSize)

        preempt.driveDcsBus(preemptNode, preemptLci, preemptLcia, preemptUl)
        drive(preempt.driveControl, "preempt", cid)
        preempt.logic.ipiToIntc >> ipiCtrl

        allSlaveNodes.append((preemptNode, preemptMapping))
      } else {
        preemptLci.setIdle()
        preemptUl.setIdle()
        preemptLcia.setBlocked()
        assert(preempt == null)
        // tie down preemption request for bypass
        proto.preemptReq.setIdle()

        // bypass core generates interrupt to host that signifies non-empty queue
        val bypassProto = proto.asInstanceOf[EciDecoupledRxTxProtocol]
        bypassProto.logic.irqOut >> ipiCtrl
        drive(bypassProto.driveBypassIrqCtrl, "preempt", cid)
      }
    }.setCompositeName(this, "bindProtoToCoreCtrl")
    }

    // No need to use full crossbar with parallelism since DCS routers issue
    // request one at a time
    Axi4CrossbarFactory()
      .addSlave(memNode, SizeMapping(0, PKT_BUF_SIZE.get))
      .addConnections(allMasterNodes.map(_.fullPipe() -> Seq(memNode)).toSeq: _*)
      .build()

    val numMasters = translatedDcsAxi.length
    val numConcurrentReqs = NUM_WORKER_CORES.get + 2 // every worker has one thread, bypass has RX and TX
    val dcsXbar = new AxiCrossbar(axiConfig,
      translatedDcsAxi.map { _ => AxiCrossbarSlaveConfig(
        concurrentOps = numConcurrentReqs,
        threads = numConcurrentReqs,
      ) },
      allSlaveNodes.map { case (_, sm) => AxiCrossbarMasterConfig(
        regions = Seq(sm),
        readFromSlaves = Seq.fill(numMasters)(true),
        writeFromSlaves = Seq.fill(numMasters)(true),
        concurrentOps = 1,  // Rx/Tx router as well as preemption control can handle one concurrent request
      ) }.toSeq,
    )
    dcsXbar.s_axi zip translatedDcsAxi foreach { case (sp, m) => m >> sp }
    dcsXbar.m_axi zip allSlaveNodes foreach { case (mp, (s, _)) => mp >> s }

    // connect all AXI-Lite nodes
    val fullNodes = ctrlAxiLiteNodes.map { case (n, sm) =>
      val nr = n.fromAxi()
      val ret = Axi4(nr.config)
      ret.fullPipe() >> nr
      ret -> sm
    }.toSeq
    Axi4CrossbarFactory()
      .addSlaves(fullNodes: _*)
      .addConnections(s_axil_ctrl.toAxi().fullPipe() -> fullNodes.map(_._1))
      .build()
  }
}
