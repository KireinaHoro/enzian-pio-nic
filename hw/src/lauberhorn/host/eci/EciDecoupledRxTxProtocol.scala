package lauberhorn.host.eci

import jsteward.blocks.eci.{EciCmdDefs, EciIntcInterface}
import jsteward.blocks.misc.RegBlockAlloc
import jsteward.blocks.axi._
import lauberhorn._
import lauberhorn.host.DatapathPlugin
import lauberhorn.host.eci.EciDecoupledRxTxProtocol.emittedMackerel
import spinal.core._
import spinal.core.fiber.Handle._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4CrossbarFactory}
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
import spinal.lib.bus.misc.{BusSlaveFactory, SizeMapping}
import spinal.lib.bus.regif.AccessType.{RO, RC, RW, WO}
import spinal.lib.fsm._
import Global._

import scala.language.postfixOps
import scala.math.BigInt.int2bigInt

class EciDecoupledRxTxProtocol(coreID: Int) extends DatapathPlugin(coreID) with EciPioProtocol {
  val isBypass = coreID == 0
  lazy val debug = host[DebugPlugin]

  // potentially RX and TX can run at the same time (for bypass)
  val rxTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_rx", LauberhornTraceDma.NicHostInterfaceSlr)
  val txTp = during setup host[TracePlugin].makePort(s"datapath_core${coreID}_tx", LauberhornTraceDma.NicHostInterfaceSlr)

  if (isBypass) {
    withPrefix("proto_bypass")
  } else {
    withPrefix(s"proto_worker_${coreID - 1}")
  }

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc) = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    // TODO: emit constants type for FSM state value
    busCtrl.read(logic.rxFsm.stateReg, alloc("stat", subName = "rxFsmState", attr = RO,
      desc = "state of the RX state machine (raw value)"))
    busCtrl.read(logic.txFsm.stateReg, alloc("stat", subName = "txFsmState", attr = RO,
      desc = "state of the TX state machine (raw value)"))

    busCtrl.readAndWrite(logic.txCurrClIdx, alloc("ctrl", subName = "txCurrClIdx", attr = RW,
      desc = "parity (next CL to write) of the TX state machine"))
    busCtrl.readAndWrite(logic.rxCurrClIdx, alloc("ctrl", subName = "rxCurrClIdx", attr = RW,
      desc = "parity (next CL to read) of the RX state machine"))

    debug.postDebug(s"core${coreID}_rxFsm_state", logic.rxFsm.stateReg)
    debug.postDebug(s"core${coreID}_txFsm_state", logic.txFsm.stateReg)
    debug.postDebug(s"core${coreID}_rxClIdx", logic.rxCurrClIdx)
    debug.postDebug(s"core${coreID}_txClIdx", logic.txCurrClIdx)

    if (isBypass) {
      busCtrl.read(logic.bypassIrqArea.irqFsm.stateReg,
        alloc("stat", subName = "irqFsmState", attr = RO, desc = "state of the bypass IRQ state machine (raw value)"))
      busCtrl.read(logic.bypassIrqArea.issued.value,
        alloc("stat", subName = "irqsIssued", attr = RO, desc = "number of bypass IRQs issued"))
      busCtrl.read(logic.bypassIrqArea.acked.value,
        alloc("stat", subName = "irqsAcked", attr = RO, desc = "number of bypass IRQs acknowledged by ISR"))
      busCtrl.read(logic.bypassIrqArea.assumed.value,
        alloc("stat", subName = "irqsAssumed", attr = RO, desc = "number of bypass IRQs assumed"))

      busCtrl.driveAndRead(logic.bypassIrqArea.waitAckTimeout,
        alloc("ctrl", subName = "waitAckTimeout", attr = RW,
          desc = "cycles before we assume an SGI is lost")) init 10000 // 50 us

      debug.postDebug(s"core${coreID}_irqFsm_state", logic.bypassIrqArea.irqFsm.stateReg)
    }

    busCtrl.read(logic.numRetired.value,
      alloc("stat", subName = "numRetired", attr = RO, desc = "number of retired requests"))
    busCtrl.read(logic.numReq.value,
      alloc("stat", subName = "numReq", attr = RO, desc = "number of requests observed"))
    busCtrl.read(logic.numNack.value,
      alloc("stat", subName = "numNack", attr = RO, desc = "number of NACKs observed"))
    busCtrl.read(logic.numPreempted.value,
      alloc("stat", subName = "numPreempted", attr = RO, desc = "times this worker has been preempted"))
  }

  // called for driving the non-existent preemption control for core#0
  def driveBypassIrqCtrl(bus: AxiLite4, alloc: RegBlockAlloc) = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    alloc("realCoreId", desc = "Actual core ID serving requests for this context")
    val schedCmdAddr = alloc("schedCmd", attr = RC, readSensitive = true,
      desc = "Preemption command for worker core (all zero for bypass)",
      ty =
        """
          |{
          |  next_pid   32 "Next PID to schedule";
          |  killed     1  "Previously running process is killed";
          |  _          31 rsvd;
          |}
          |""".stripMargin)
    busCtrl.read(U(0), schedCmdAddr)

    // generate IRQ enable reg for bypass
    val irqEnAddr = alloc("irqEn", desc = "Enable IRQ to this core")
    busCtrl.driveAndRead(logic.irqEn, irqEnAddr) init False

    // ACK might come in, when irqFsm is not in waitAck
    when (logic.irqEn.rise()) {
      logic.irqAck := True
    }

    PreemptionControlCl().addMackerel()
  }

  lazy val overflowCountWidth = log2Up(numOverflowCls)

  // two control half CLs, one extra first word half CL, one MTU
  // CL#0: [ control | first data (aliased) ]
  // CL#1: [ control | first data (aliased) ]
  // CL#2...: [ rest data ]
  lazy val sizePerMtuPerDirection = (512 / 8) * 3 + ROUNDED_MTU
  lazy val numOverflowCls = (sizePerMtuPerDirection / EciCmdDefs.ECI_CL_SIZE_BYTES - 1).toInt

  // map at aligned address to eliminate long comb paths
  val txOffset = 0x8000
  val sizePerCore = 2 * txOffset

  private def packetSizeToNumOverflowCls(s: UInt): UInt = {
    val clSize = EciCmdDefs.ECI_CL_SIZE_BYTES
    ((s <= 64) ? U(0) | ((s - 64 + clSize - 1) / clSize)).resize(overflowCountWidth)
  }

  private def overflowIdxToAddr(idx: UInt, isTx: Boolean = false): Bits = {
    val offset = if (isTx) U(txOffset) else U(0)
    // two control cachelines before overflow
    ((idx + 2) * EciCmdDefs.ECI_CL_SIZE_BYTES + offset).asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)
  }

  private def ctrlToAddr(currIdx: UInt, isTx: Boolean = false): Bits = {
    (currIdx * EciCmdDefs.ECI_CL_SIZE_BYTES + (if (isTx) U(txOffset) else U(0))).asBits.resize(EciCmdDefs.ECI_ADDR_WIDTH)
  }

  def makeAccessPorts(dcsConfig: Axi4Config, memConfig: Axi4Config): (Seq[(Axi4, SizeMapping)], Seq[Axi4]) = new Area {
    // RX router
    val rxRouter = DcsRxAxiRouter(dcsConfig, memConfig)

    // No need to halt the stream here during preemption: host can't be reading
    // when preemption happens, since it is out of the critical region where a
    // read can happen.
    rxRouter.rxDesc << hostRx
    rxRouter.currCl := logic.rxCurrClIdx.asUInt
    rxRouter.invDone := logic.rxInvDone
    rxRouter.doPreempt := preemptReq.valid
    logic.rxSentNack := rxRouter.nackSent
    logic.rxReqs := rxRouter.hostReq

    // TX router
    val txRouter = DcsTxAxiRouter(dcsConfig, memConfig)
    txRouter.txDesc >> hostTxAck
    txRouter.currCl := logic.txCurrClIdx.asUInt
    txRouter.txAddr := logic.savedTxAddr.addr
    txRouter.invDone := logic.txInvDone
    txRouter.doPreempt := preemptReq.valid
    logic.txInvLen := txRouter.currInvLen
    logic.txReqs := txRouter.hostReq

    if (isBypass) {
      // bypass core will have non-blocking poll of cachelines
      // this will allow NAPI-based Linux driver implementation
      rxRouter.blockCycles := 0
    } else {
      // We use normal block cycles for all worker cores.
      // No need to disable this on preemption: no read request will be in progress
      // when the preemption request comes from [[EciPreemptionControlPlugin]]
      rxRouter.blockCycles := host[EciInterfacePlugin].rxBlockCycles
    }

    debug.postDebug(s"core${coreID}_rxRouter_state", rxRouter.stateOut)
    debug.postDebug(s"core${coreID}_txRouter_read_state", txRouter.readStateOut)
    debug.postDebug(s"core${coreID}_txRouter_write_state", txRouter.writeStateOut)

    val ret = (Seq(
      (rxRouter.dcsAxi, SizeMapping(0, txOffset)),
      (txRouter.dcsAxi, SizeMapping(txOffset, txOffset)),
    ), Seq(rxRouter.pktBufAxi, txRouter.pktBufAxi))
  }.setCompositeName(this, "makeAccessPorts").ret

  def preemptReq = logic.preemptReq

  val logic = during setup new Area {
    // The kernel will update these on thread resume
    val rxCurrClIdx = Reg(Bool()) init False
    val txCurrClIdx = Reg(Bool()) init False

    // Preemption request from [[EciPreemptionControlPlugin]].  This will only come
    // after the thread is out of the ready/busy critical section and spinning on !ready
    val preemptReq = Event

    // invalidation done for routers
    val rxInvDone = Bool()
    val txInvDone = Bool()

    // the router sent back a NACK
    val rxSentNack = Bool()

    ECI_RX_BASE.set(0)
    ECI_TX_BASE.set(txOffset)
    ECI_OVERFLOW_OFFSET.set(0x100)
    ECI_NUM_OVERFLOW_CL.set(numOverflowCls)

    val irqOut = isBypass generate Stream(EciIntcInterface())
    val irqEn = isBypass generate Bool()
    val irqAck = isBypass generate RegInit(False)

    val numRetired, numReq, numNack, numPreempted = Counter(REG_WIDTH bits)

    awaitBuild()

    assert(txOffset >= sizePerMtuPerDirection, "tx offset does not allow one MTU for rx")

    val rxReqs = Vec(Bool(), 2)

    // A read from the CPU to the opposite CL to fetch a new request.
    val rxTriggerNew = rxReqs(1 - rxCurrClIdx.asUInt)

    val txReqs = Vec(Bool(), 2)

    lci.setIdle()
    lci.valid.setAsReg() init False
    lci.payload.setAsReg()
    lci.assertPersistence()
    preemptReq.setBlocked()

    rxInvDone := False
    txInvDone := False

    val ulFlow = Flow(EciCmdDefs.EciAddress).setIdle()
    val ulOverflow = Bool()
    // max number of inflight ULs: overflow CLs + 2 ctrl CLs
    ul << ulFlow.toStream(ulOverflow).queue(numOverflowCls + 2)
    assert(
      assertion = !ulOverflow,
      message = s"UL flow overflow",
      severity = FAILURE
    )

    lcia.setBlocked()
    def recvLciaSendUl(counter: Counter = null): Unit = {
      lcia.freeRun()
      when (lcia.fire) {
        ulFlow.payload := lcia.payload
        ulFlow.valid := True
        if (counter != null)
          counter.increment()
      }
    }

    hostRxAck.setIdle()
    hostTx.setBlocked()

    val rxOverflowInvIssued, rxOverflowInvAcked = Counter(overflowCountWidth bits)
    val rxOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))
    val rxSlotToFree = Reg(PacketBufDesc())

    val rxSlotCaptured = hostRx.toFlowFire.map(_.buffer).toReg()
    val rxSlotCapturedValid = Reg(Bool()).setWhen(hostRx.fire).init(False)

    val txOverflowInvIssued, txOverflowInvAcked = Counter(overflowCountWidth bits)
    val txOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))

    // trace data
    val coreTd = CoreID(B(coreID))
    val rxClTd = Seq(coreTd, CacheLineIndex(rxCurrClIdx))
    val txClTd = Seq(coreTd, CacheLineIndex(txCurrClIdx))
    val rxOverflowTd = Seq(coreTd, OverflowCount(rxOverflowToInvalidate))
    val txOverflowTd = Seq(coreTd, OverflowCount(txOverflowToInvalidate))

    val rxFsm = new StateMachine {
      def handlePreempt() = {
        when (preemptReq.valid) {
          assert(!rxReqs.orR, "critical section violation: no read is allowed during preemption")
          preemptReq.ready := True
          numPreempted.increment()
          goto(waitHostRead)
        }
      }
      val waitHostRead: State = new State with EntryPoint {
        whenIsActive {
          // Wait for the very first read from host:
          // - after boot
          // - after preemption
          // In other cases, first read for the next request IS the one triggering the switch,
          // so we'd always end in hostIssuedRead
          when (rxReqs(rxCurrClIdx.asUInt)) {
            assert(!preemptReq.valid, "critical section violation: no preemption is allowed during read")
            rxTp.trace("EciRxReadStart", rxClTd: _*)
            goto(hostReadPending)
          } otherwise { handlePreempt() }
        }
      }
      val hostReadPending: State = new State {
        whenIsActive {
          when (rxSlotCapturedValid) {
            // A packet arrived in time.  Save the buffer that we sent to host and wait until
            // we need to invalidate the descriptor AND overflow data
            rxOverflowToInvalidate := packetSizeToNumOverflowCls(rxSlotCaptured.size.bits)
            rxSlotToFree := rxSlotCaptured
            rxSlotCapturedValid := False
            numReq.increment()
            rxTp.trace("EciRxDescSent", rxOverflowTd: _*)
            goto(repeatDesc)
          } elsewhen (rxSentNack) {
            // No packet arrived in time, the router delivered a NACK
            rxOverflowToInvalidate := 0
            rxSlotToFree.clearAll()
            numNack.increment()
            rxTp.trace("EciRxNackSent", coreTd)
            goto(repeatDesc)
          } otherwise { handlePreempt() }
        }
      }
      val repeatDesc: State = new State {
        whenIsActive {
          // We got the first read.  The router repeats until the
          // CPU acks the descriptor (or NACK) by reading the opposite CL
          when (rxTriggerNew) {
            rxTp.trace("EciRxReadNew", rxClTd: _*)
            when (rxSlotToFree.size.bits === 0) {
              // nothing to free or invalidate, just invalidate this NACK
              goto(invalidateCtrl)
            } otherwise {
              goto(freeSlot)
            }
          } otherwise { handlePreempt() }
        }
      }
      val freeSlot: State = new State {
        whenIsActive {
          hostRxAck.payload := rxSlotToFree
          hostRxAck.valid := True
          when (hostRxAck.fire) {
            rxOverflowInvAcked.clear()
            rxOverflowInvIssued.clear()
            when (rxOverflowToInvalidate > 0) {
              rxTp.trace("EciRxDataLciStart", rxOverflowTd: _*)
              goto(invalidatePacketData)
            } otherwise {
              rxTp.trace("EciRxFreeSlot", rxOverflowTd: _*)
              goto(invalidateCtrl)
            }
          }
        }
      }
      val invalidatePacketData: State = new State {
        whenIsActive {
          when (rxOverflowInvIssued.valueNext < rxOverflowToInvalidate) {
            lci.payload := overflowIdxToAddr(rxOverflowInvIssued.valueNext)
            lci.valid := True
          }

          when (lci.fire) {
            rxOverflowInvIssued.increment()
            when (rxOverflowInvIssued.valueNext === rxOverflowToInvalidate) {
              rxTp.trace("EciRxDataLciDone", rxOverflowTd: _*)
              goto(waitDataLciaDone)
            }
          }

          recvLciaSendUl(rxOverflowInvAcked)
        }
      }
      val waitDataLciaDone: State = new State {
        whenIsActive {
          recvLciaSendUl(rxOverflowInvAcked)

          when (rxOverflowInvAcked === rxOverflowToInvalidate) {
            rxTp.trace("EciRxDataLciaUlDone", rxOverflowTd: _*)
            goto(invalidateCtrl)
          }
        }
      }
      val invalidateCtrl: State = new State {
        whenIsActive {
          lci.payload := ctrlToAddr(rxCurrClIdx.asUInt)
          lci.valid := True
          when(lci.fire) {
            lci.valid := False
            rxTp.trace("EciRxCtrlInvalidate", rxClTd: _*)
            goto(waitInvResp)
          }
        }
      }
      val waitInvResp: State = new State {
        whenIsActive {
          recvLciaSendUl(numRetired)

          when (lcia.fire) {
            // always toggle, even if NACK was sent
            rxCurrClIdx.toggleWhen(True)

            // Invalidation is triggered by reading opposite
            rxInvDone := True
            rxTp.trace("EciRxCtrlUnlocked", rxClTd: _*)
            goto(hostReadPending)
          }
        }
      }
    }

    when (txReqs.reduce(_ || _)) {
      // pop hostTx to honour the protocol
      hostTx.freeRun()
    }
    val savedTxAddr = hostTx.asFlow.toReg()
    val txInvLen = PacketLength()
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          when (txReqs(txCurrClIdx.asUInt)) {
            txTp.trace("EciTxAcquire", txClTd: _*)
            goto(waitPacket)
          }
        }
      }
      val waitPacket: State = new State {
        whenIsActive {
          txOverflowToInvalidate.clearAll()
          when (txReqs(1 - txCurrClIdx.asUInt)) {
            // invalidate control first to know how many overflows do we need to invalidate
            txTp.trace("EciTxCommitRead", txClTd: _*)
            goto(invalidateCtrl)
          }
        }
      }
      val invalidateCtrl: State = new State {
        whenIsActive {
          lci.payload := ctrlToAddr(txCurrClIdx.asUInt, isTx = true)
          lci.valid := True
          when(lci.fire) {
            lci.valid := False
            txTp.trace("EciTxCtrlInvalidate", txClTd: _*)
            goto(waitInvResp)
          }
        }
      }
      val waitInvResp: State = new State {
        whenIsActive {
          recvLciaSendUl()

          when (lcia.fire) {
            // we should've latched tx descriptor in savedTxDesc
            val toInvalidate = packetSizeToNumOverflowCls(txInvLen.bits)
            txOverflowToInvalidate := toInvalidate
            when (toInvalidate > 0) {
              txOverflowInvIssued.clear()
              txOverflowInvAcked.clear()
              txTp.trace("EciTxDataLciStart", coreTd, OverflowCount(toInvalidate))
              goto(invalidatePacketData)
            } otherwise {
              txTp.trace("EciTxCtrlUnlocked", txClTd: _*)
              goto(tx)
            }
          }
        }
      }
      val invalidatePacketData: State = new State {
        whenIsActive {
          when(txOverflowInvIssued.valueNext < txOverflowToInvalidate) {
            lci.payload := overflowIdxToAddr(txOverflowInvIssued.valueNext, isTx = true)
            lci.valid := True
          }

          when (lci.fire) {
            txOverflowInvIssued.increment()
            when (txOverflowInvIssued.valueNext === txOverflowToInvalidate) {
              txTp.trace("EciTxDataLciDone", txOverflowTd: _*)
              goto(waitDataLciaDone)
            }
          }

          recvLciaSendUl(txOverflowInvAcked)
        }
      }
      val waitDataLciaDone: State = new State {
        whenIsActive {
          recvLciaSendUl(txOverflowInvAcked)

          when (txOverflowInvAcked === txOverflowToInvalidate) {
            txTp.trace("EciTxDataLciaUlDone", txOverflowTd: _*)
            goto(tx)
          }
        }
      }
      val tx: State = new State {
        whenIsActive {
          // allow router to send tx descriptor
          txInvDone := True

          when (hostTxAck.fire) {
            txCurrClIdx.toggleWhen(True)
            txTp.trace("EciTxSubmit", txClTd: _*)
            goto(idle)
          }
        }
      }
    }

    // make rx and tx fsm states available to top for debug
    rxFsm.build()
    txFsm.build()

    // if this is the bypass core, emit IRQ when the RX queue is not empty
    val bypassIrqArea: Area {
      val issued, acked, assumed : Counter
      val waitAckTimeout: UInt
      val irqFsm: StateMachine
    } = isBypass generate new Composite(this, "irqGen") {
      irqOut.setIdle()

      val issued, acked, assumed = Counter(REG_WIDTH bits)

      // SGIs seem to be lossy -- avoid deadlock.
      // Timeout waiting for ACK, configurable from SW.
      val waitCount = Counter(REG_WIDTH bits)
      val waitAckTimeout = UInt(REG_WIDTH bits)

      // Edge-triggered interrupt.
      //
      // VC12 SGI seems to have some kind of rate limit: if we send too fast, we get
      // stuck and nothing gets through any more.  Hence we use edge-triggered
      // semantics, even though NAPI in Linux prefers level-triggered interrupts.
      val irqFsm = new StateMachine {
        val idle: State = new State with EntryPoint {
          whenIsActive {
            when (hostRx.isStall && irqEn) {
              goto(sendIrq)
            }
          }
        }
        val sendIrq: State = new State {
          whenIsActive {
            irqOut.valid   := True
            irqOut.affLvl0 := 1   // always send to core 0
            irqOut.affLvl1 := 0
            irqOut.cmd     := 0
            irqOut.intId   := 15  // use 15 for bypass interrupts
            when (irqOut.ready) {
              goto(waitAck)
              issued.increment()
            }
          }
        }
        val waitAck: State = new State {
          whenIsActive {
            waitCount.increment()
            when (irqAck || waitCount.value >= waitAckTimeout) {
              when (irqAck) {
                irqAck := False
                acked.increment()
              } otherwise {
                waitCount.clear()
                assumed.increment()
              }
              goto(idle)
            }
          }
        }
      }
      irqFsm.build()
    }
  }

  during build {
    if (!emittedMackerel) {
      EciHostCtrlInfo().addMackerel
      emittedMackerel = true
    }
  }
}

object EciDecoupledRxTxProtocol {
  var emittedMackerel = false
}
