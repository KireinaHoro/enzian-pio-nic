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

  val hostIfCtrl = during setup new Area {
    // Coalesced one-shot notification from the system-clock GSYNC monitor.
    val gsyncRelease = in Bool()
    gsyncRelease.setName("gsyncRelease")
    val rxBlockCycles = Reg(UInt(REG_WIDTH bits)) init 10000
  }

  val coreOffset = EciAddressMap.block_bytes
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
    ipiToIntc << StreamArbiterFactory(s"${getName()}_ipiCmdMux").roundRobin.on(demuxedIpiIntfs :+ host[EciOwnership].logic.faultIrq)

    // assert dcs interfaces never drop valid when ready is low
    dcsIntfs foreach { dcs =>
      dcs.cleanMaybeInvReq.assertPersistence()
    }

    val s_axil_ctrl = slave(AxiLite4(
      addressWidth = 44, dataWidth = REG_WIDTH,
    )) addTag ClockDomainTag(clockDomain)

    var ctrlBlockStart = 0
    val ctrlBlockSize = 0x100
    val ctrlAxiLiteNodes = mutable.ListBuffer[(AxiLite4, SizeMapping)]()
    def drive(func: (AxiLite4, RegBlockAlloc) => Unit, blockName: String, idx: Int = 0, blockSize: Int = ctrlBlockSize) = {
      val alloc = ALLOC.get(blockName, idx)(ctrlBlockStart, blockSize, REG_WIDTH / 8)()

      val node = AxiLite4(s_axil_ctrl.config)
      ctrlAxiLiteNodes += node -> SizeMapping(ctrlBlockStart, blockSize)
      ctrlBlockStart += blockSize

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

    drive(host[Scheduler].driveControl, "sched", blockSize = 0x200)
    drive(host[DmaControlPlugin].logic.driveControl, "dma")
    drive(host[EciOwnership].driveControl, "ownership")
    drive(host[BypassCmdSink].driveControl, "bypassSink")

    drive({ (bus, alloc) =>
      val busCtrl = AxiLite4SlaveFactory(bus)
      busCtrl.readAndWrite(hostIfCtrl.rxBlockCycles, alloc("rxBlockCycles",
        desc = "Timeout before a NACK is returned for RX (in cycles)"))
    }, "hostIf")

    // master nodes for access to packet buffer
    val memNode = host[PacketBuffer].logic.axiMem.io.s_axi_b

    val routedConfig = axiConfig.copy(arUserWidth = axiConfig.addressWidth,
      awUserWidth = axiConfig.addressWidth)
    // Endpoints receive the full accepted address; low bits select transport lines.
    val dcsNodeConfig = routedConfig.copy(idWidth = 8)

    val unaliasedDcsAxi = dcsIntfs map { dcs =>
      dcs.axi.remapAddr { a =>
        val byteOffset = a(6 downto 0)
        // optimization of DCS: only 256 GiB (38 bits) of the address space is used
        (EciCmdDefs.unaliasAddress(a.asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)).asUInt | byteOffset.resized).resized
      }
    }
    val translatedDcsAxi = unaliasedDcsAxi zip host[EciOwnership].logic.ingress map { case (ua, ingress) =>
      ua >> ingress.io.input
      ingress.io.routed
    }

    val lclEndpointsPerCore = 2
    def lclEndpointCoreIdx(unitIdx: Int): Int = unitIdx / lclEndpointsPerCore
    def lclEndpointHreqId(unitIdx: Int): Int = unitIdx % lclEndpointsPerCore

    // One obligation per transport line. Records retain the full address until
    // matching LCIA, including when arbitration or DCS completion is delayed.
    val obligations = Seq.fill(NUM_CORES * lclEndpointsPerCore) {
      Vec.fill(2 + EciAddressMap.overflow_lines) {
        val record = Reg(Flow(Bits(EciCmdDefs.ECI_ADDR_WIDTH bits)))
        record.valid init False
        record
      }
    }
    def obligationIndex(address: Bits): UInt =
      (address(11 downto 7).asUInt - (EciAddressMap.rx_control / 128)).resize(4)

    /** Bind the LCI commands from the 2F2F state machines to the odd and even DCS channels.  Takes a flattened
      * list of LCI endpoints (incl. non-existent preemption control for bypass core).
      *
      * This implements a mux-demux instead of a full crossbar; refer to [[ClLclPort]] for the rationale.
      */
    def bindCoreCmdsToLclChans(cmds: Seq[Stream[EciWord]], addrLocator: EciWord => Bits, evenVc: Int, oddVc: Int, chanLocator: DcsInterface => Stream[EciChannel]): Unit = {
      // Capture full addresses before arbitration. Ownership cannot retag a
      // stalled command, and remains held until its matching LCIA completes.
      val muxed = StreamArbiterFactory("EciInterfacePlugin_logic_lciFromCore_mux")
        .roundRobin.on(cmds.zipWithIndex.map { case (cmd, unitIdx) =>
          val core = lclEndpointCoreIdx(unitIdx)
          val full = cmd.mapPayloadElement(addrLocator) { offset =>
            if (core == 0) offset else host[EciOwnership].logic.fullAddress(core - 1, offset)
          }.m2sPipe()
          val index = obligationIndex(addrLocator(full.payload))
          when(full.fire) {
            assert(index < 2 + EciAddressMap.overflow_lines, "LCI outside transport lines")
            assert(!obligations(unitIdx)(index).valid, "duplicate outstanding LCI")
            obligations(unitIdx)(index).valid := True
            obligations(unitIdx)(index).payload := addrLocator(full.payload)
          }
          full
        })

      // pack into ECI channel
      val chan = Stream(EciChannel())
      chan.translateFrom(muxed) { case (c, fr) =>
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

      // LCIA routing depends only on its immutable physical address, never on
      // the current owner. Old-domain cleanup must reach the same endpoint.
      val decoded = EciAddressDecoder(NUM_WORKER_CORES.get)
      decoded.io.address := addrLocator(muxed.payload).asUInt.resized
      decoded.io.owners := host[EciOwnership].logic.owners
      decoded.io.enabled := host[EciOwnership].logic.enabled
      val core = UInt(log2Up(NUM_CORES) bits)
      core := decoded.io.worker.resize(core.getWidth) + 1
      when(decoded.io.bypass) { core := 0 }
      val endpoint = (core * lclEndpointsPerCore + muxed.payload.lcia.hreqId.asUInt)
        .resize(log2Up(lclEndpointsPerCore * NUM_CORES))
      val unmuxed = StreamDemux(muxed, endpoint, lclEndpointsPerCore * NUM_CORES)
      when(muxed.valid) {
        assert(decoded.io.known || decoded.io.bypass, "unknown LCIA physical endpoint")
        assert(muxed.payload.lcia.hreqId.asUInt < lclEndpointsPerCore, "unexpected LCIA hreqId")
      }
      unmuxed.zip(resps).zipWithIndex.foreach { case ((fr, resp), unitIdx) =>
        val address = addrLocator(fr.payload)
        val index = obligationIndex(address)
        when(fr.fire) {
          assert(index < 2 + EciAddressMap.overflow_lines, "LCIA outside transport lines")
          assert(obligations(unitIdx)(index).valid &&
            obligations(unitIdx)(index).payload === address, "LCIA has no matching full-address obligation")
          obligations(unitIdx)(index).valid := False
        }
        resp << fr.mapPayloadElement(addrLocator) { address =>
          address(12 downto 0).resize(address.getWidth)
        }
      }
    }

    val coresLcl = Seq.fill(NUM_CORES)(Seq.fill(lclEndpointsPerCore)(DcsAppLclInterface()))

    // mux LCL request (LCI)
    bindCoreCmdsToLclChans(coresLcl.flatten.map(_.lci).zipWithIndex.map { case (addr, uidx) => new Area {
      val ret = Stream(EciWord())

      // generating a LCI -- refer to Table 7.9 of CCKit
      ret.payload.lci.opcode  := B("00001")
      ret.payload.lci.hreqId  := B(lclEndpointHreqId(uidx), EciCmdDefs.ECI_HREQID_WIDTH bits)
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
    bindLclChansToCoreResps(coresLcl.flatten.map(_.lcia).zipWithIndex.map { case (lcia, uidx) =>
      new Area {
        val ret = Stream(EciWord())

        lcia.payload := ret.payload.lcia.address
        lcia.arbitrationFrom(ret)

        when (ret.fire) {
          assert(ret.payload.lcia.hreqId === B(lclEndpointHreqId(uidx), EciCmdDefs.ECI_HREQID_WIDTH bits), "source of LCIA does not match LCI")
        }
      }.setCompositeName(this, "bindLcia").ret
    }, _.lcia.address, _.cleanMaybeInvResp)

    val allSlaveNodes = mutable.ListBuffer[(Axi4, SizeMapping)]()
    val allMasterNodes = mutable.ListBuffer[Axi4]()

    // drive core control interface -- datapath per core
    0 until NUM_CORES foreach { cid => new Area {
      val Seq(dataRxLcl, dataTxLcl) = coresLcl(cid)
      val proto = protos(cid)
      val preempt = preempts(cid)
      val ipiCtrl = demuxedIpiIntfs(cid)

      val baseAddress = (1 + cid) * 0x1000

      // bind DCS channels to datapath
      dataRxLcl << proto.rxLcl
      dataTxLcl << proto.txLcl

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
        drive(preempt.driveControl, "preempt", cid)
        preempt.logic.ipiToIntc >> ipiCtrl

      } else {
        assert(preempt == null)
        // tie down preemption request for bypass
        proto.preemptReq.setIdle()
        val bypass = proto.asInstanceOf[EciDecoupledRxTxProtocol]
        bypass.logic.yieldRequested := False
        bypass.logic.fatalRelease := False
        bypass.logic.externalQuiet := True

        // bypass core generates interrupt to host that signifies non-empty queue
        val bypassProto = proto.asInstanceOf[EciDecoupledRxTxProtocol]
        bypassProto.logic.irqOut >> ipiCtrl
        drive(bypassProto.driveBypassIrqCtrl, "bypassIrq")
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
    val dcsXbar = new AxiCrossbar(routedConfig,
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
    dcsXbar.m_axi zip allSlaveNodes foreach { case (mp, (endpoint, _)) =>
      endpoint.ar.arbitrationFrom(mp.ar)
      endpoint.ar.payload := mp.ar.payload
      endpoint.ar.addr.allowOverride
      endpoint.ar.addr := mp.ar.user.asUInt
      endpoint.aw.arbitrationFrom(mp.aw)
      endpoint.aw.payload := mp.aw.payload
      endpoint.aw.addr.allowOverride
      endpoint.aw.addr := mp.aw.user.asUInt
      endpoint.w << mp.w
      mp.r << endpoint.r
      mp.b << endpoint.b
    }

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
