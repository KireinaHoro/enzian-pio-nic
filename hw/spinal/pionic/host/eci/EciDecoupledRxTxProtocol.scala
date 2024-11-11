package pionic.host.eci

import jsteward.blocks.axi.RichAxi4
import jsteward.blocks.eci.EciCmdDefs
import jsteward.blocks.misc.RegBlockAlloc
import pionic._
import spinal.core._
import spinal.core.fiber.Handle._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4AwUnburstified, Axi4SlaveFactory}
import spinal.lib.bus.misc.{BusSlaveFactory, SingleMapping, SizeMapping}
import spinal.lib.bus.regif.AccessType.RO
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps
import scala.math.BigInt.int2bigInt

class EciDecoupledRxTxProtocol(coreID: Int) extends EciPioProtocol {
  withPrefix(s"core_$coreID")

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc) = {
    // TODO: do we actually need resync?
    //       we need to drain all pending ULs, etc., rather complicated
    // val r = busCtrl.driveAndRead(logic.resync, alloc("eciResync")) init false
    // r.clearWhen(r)

    busCtrl.read(logic.rxFsm.stateReg, alloc("rxFsmState", attr = RO))
    busCtrl.read(logic.rxCurrClIdx, alloc("rxCurrClIdx", attr = RO))

    busCtrl.read(logic.txFsm.stateReg, alloc("txFsmState", attr = RO))
    busCtrl.read(logic.txCurrClIdx, alloc("txCurrClIdx", attr = RO))
  }
  lazy val csr = host[GlobalCSRPlugin].logic
  lazy val pktBufWordNumBytes = host[EciInterfacePlugin].pktBufWordWidth / 8
  lazy val overflowCountWidth = log2Up(numOverflowCls)

  // map at aligned address to eliminate long comb paths
  val txOffset = 0x8000

  val sizePerCore = 2 * txOffset

  var writeCmd: Stream[Fragment[Axi4AwUnburstified]] = null
  var txRwPort: MemReadWritePort[_] = null

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

  def driveDcsBus(bus: Axi4, rxPktBuffer: Mem[Bits], txPktBuffer: Mem[Bits]): Unit = new Area {
    // address offset for this core in CoreControl descriptors
    val descOffset = c[Int]("pkt buf size per core") * coreID

    val rxSize = host[EciInterfacePlugin].rxSizePerCore
    val txSize = host[EciInterfacePlugin].txSizePerCore

    // remap packet data: first cacheline inline packet data -> second one
    // such that we have continuous address when handling packet data
    val busCtrl = Axi4SlaveFactory(bus.fullPipe(), cmdPipeline = StreamPipe.FULL, addrRemapFunc = { addr =>
      addr.mux(
        U(0x40) -> U(0xc0),
        U(txOffset + 0x40) -> U(txOffset + 0xc0),
        default -> addr
      )
    })

    val blockCycles = Vec(CombInit(csr.ctrl.rxBlockCycles), 2)
    Seq(0, 1) foreach { idx => new Composite(this, s"driveBusCtrl_cl$idx") {
      val rxCtrlAddr = idx * 0x80
      busCtrl.onReadPrimitive(SingleMapping(rxCtrlAddr), false, null) {
        logic.rxReqs(idx).set()
      }

      // we need to freeze the read control cacheline when we are in noPacket
      // to avoid the following corner case:
      // - L2 issues reload, no packet => rxFsm == noPacket
      // - core didn't have a chance to read NACK, L2 evicted
      // - packet arrived while we are in noPacket
      // - L2 reload, core sees valid = 1, packet delivered
      // - packet NOT consumed yet since we didn't exit from gotPacket
      // - core reads again, packet delivered AGAIN
      // we still cannot do full voluntary reload idempotency check, since packet
      // will be DMA'ed into packet buffer anyways -- difficult to control from here
      // TODO: can we fabricate a test case for this?
      val rxHostCtrlInfo = Reg(Stream(EciHostCtrlInfo()))
      val rxDesc = logic.demuxedRxDescs(idx).map(EciHostCtrlInfo.packFrom)
      rxHostCtrlInfo.valid init False
      when (!logic.rxFsm.isActive(logic.rxFsm.noPacket)) {
        rxHostCtrlInfo.valid := rxDesc.valid
        rxHostCtrlInfo.payload := rxDesc.payload
      }
      rxDesc.ready := logic.rxFsm.isExiting(logic.rxFsm.gotPacket)

      // readStreamBlockCycles report timeout on last beat of stream, but we need to issue it after the entire reload is finished
      val streamTimeout = Bool()
      val bufferedStreamTimeout = Reg(Bool()) init False
      bufferedStreamTimeout.setWhen(streamTimeout)
      busCtrl.readStreamBlockCycles(rxHostCtrlInfo, rxCtrlAddr, blockCycles(idx), streamTimeout)
      busCtrl.onRead(0xc0) {
        logic.rxNackTriggerInv.setWhen((bufferedStreamTimeout | streamTimeout)
          // do not trigger inv when we got a packet right at the timeout
          && !logic.rxFsm.isActive(logic.rxFsm.gotPacket))
        bufferedStreamTimeout.clear()
      }

      val txCtrlAddr = txOffset + idx * 0x80
      busCtrl.onReadPrimitive(SingleMapping(txCtrlAddr), false, null) {
        logic.txReqs(idx).set()
        // only allow load request from writing when we are idle
        when (!logic.txFsm.isActive(logic.txFsm.idle) && idx =/= logic.txCurrClIdx.asUInt) {
          busCtrl.readHalt()
        }
      }

      // host sends out TxHostCtrlInfo without buffer information
      busCtrl.driveStream(logic.txHostCtrlInfo(idx), txCtrlAddr)
      // we need to read this again for partial reloads to have the same CL content
      busCtrl.read(logic.savedTxHostCtrl, txCtrlAddr)
    }
    }

    val rxBufMapping = SizeMapping(descOffset, rxSize)

    // cpu not supposed to modify rx packet data, so omitting write
    // memOffset is in memory words (64B)
    val memOffset = rxBufMapping.removeOffset(logic.selectedRxDesc.buffer.addr.bits) >> log2Up(pktBufWordNumBytes)
    busCtrl.readSyncMemWordAligned(rxPktBuffer, 0xc0,
      memOffset = memOffset.resized,
      mappingLength = roundMtu)

    // tx buffer always start at 0
    // allow reloading from the packet buffer for partial flush due to voluntary invalidations
    txRwPort = busCtrl.readWriteSyncMemWordAligned(txPktBuffer, txOffset + 0xc0,
      mappingLength = roundMtu)

    writeCmd = busCtrl.writeCmd
  }.setName(s"driveDcsBus_core$coreID")

  lazy val numOverflowCls = (host[EciInterfacePlugin].sizePerMtuPerDirection / EciCmdDefs.ECI_CL_SIZE_BYTES - 1).toInt

  val logic = during build new Area {
    val rxCurrClIdx = Reg(Bool()) init False
    val txCurrClIdx = Reg(Bool()) init False

    assert(txOffset >= host[EciInterfacePlugin].sizePerMtuPerDirection, "tx offset does not allow one MTU for rx")

    postConfig("eci rx base", 0)
    postConfig("eci tx base", txOffset)
    postConfig("eci overflow offset", 0x100)
    postConfig("eci num overflow cl", numOverflowCls)

    // corner case: when nack comes in after a long packet, this could be delivered before all LCIs for packets
    // finish issuing
    // ASSUMPTION: two control cachelines will never trigger NACK invalidate, thus shared
    val rxNackTriggerInv = Reg(Bool()) init False

    val rxReqs = Vec(False, 2)
    val txReqs = Vec(False, 2)

    lci.setIdle()
    lci.valid.setAsReg()
    lci.payload.setAsReg()
    lci.assertPersistence()

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

    hostRxAck.setIdle()
    hostTx.setBlocked()
    hostTxAck.setIdle()

    val rxOverflowInvIssued, rxOverflowInvAcked = Counter(overflowCountWidth bits)
    val rxOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))
    val txOverflowInvIssued, txOverflowInvAcked = Counter(overflowCountWidth bits)
    val txOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))

    val demuxedRxDescs = StreamDemux(hostRx, rxCurrClIdx.asUInt, 2) setName "demuxedRxDescs"

    // latch accepted host rx packet for:
    // - generating hostRxAck
    // - driving mem offset for packet buffer load
    val selectedRxDesc = demuxedRxDescs(rxCurrClIdx.asUInt)

    // read start is when request for the selected CL is active
    hostRxReq := RegInit(False).setWhen(rxReqs(rxCurrClIdx.asUInt))

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        onEntry {
          rxNackTriggerInv.clear()
          hostRxReq.clear()
        }
        whenIsActive {
          rxOverflowToInvalidate.clearAll()
          rxOverflowInvAcked.clear()
          rxOverflowInvIssued.clear()

          when (hostRx.valid) {
            rxOverflowToInvalidate := packetSizeToNumOverflowCls(hostRx.get.buffer.size.bits)
            goto(gotPacket)
          } elsewhen (rxNackTriggerInv) {
            goto(noPacket)
          }
        }
      }
      val noPacket: State = new State {
        whenIsActive {
          when (rxReqs(1 - rxCurrClIdx.asUInt)) {
            goto(invalidateCtrl)
          }
        }
      }
      val gotPacket: State = new State {
        whenIsActive {
          when (rxReqs(1 - rxCurrClIdx.asUInt)) {
            hostRxAck.payload := selectedRxDesc.buffer
            hostRxAck.valid := True
            when (hostRxAck.fire) {
              when (rxOverflowToInvalidate > 0) {
                goto(invalidatePacketData)
              } otherwise {
                goto(invalidateCtrl)
              }
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
          }

          lcia.freeRun()
          when (lcia.fire) {
            // unlock immediately since we guarantee that the CPU only loads the overflow cachelines after control
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            rxOverflowInvAcked.increment()
          }

          // count till all overflow cachelines are invalidated
          when (rxOverflowInvAcked === rxOverflowToInvalidate) {
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
            goto(waitInvResp)
          }
        }
      }
      val waitInvResp: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.fire) {
            // FIXME: should we check the response address?

            // immediately unlock so that we can see later load requests
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            // always toggle, even if NACK was sent
            rxCurrClIdx.toggleWhen(True)

            goto(idle)
          }
        }
      }
    }

    val txHostCtrlInfo = Vec(Stream(EciHostCtrlInfo()), 2)
    when (txHostCtrlInfo.map(_.fire).reduceBalancedTree(_ || _)) {
      // pop hostTx to honour the protocol
      hostTx.freeRun()
    }
    val savedTxAddr = hostTx.toFlowFire.toReg()

    val selectedTxHostCtrl = StreamMux(txCurrClIdx.asUInt, txHostCtrlInfo)
    val savedTxHostCtrl = selectedTxHostCtrl.toReg()
    val txFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          when (txReqs(txCurrClIdx.asUInt)) { goto(waitPacket) }
        }
      }
      val waitPacket: State = new State {
        whenIsActive {
          txOverflowInvIssued.clear()
          txOverflowInvAcked.clear()
          txOverflowToInvalidate.clearAll()
          when (txReqs(1 - txCurrClIdx.asUInt)) {
            // invalidate control first to know how many overflows do we need to invalidate
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
            goto(waitInvResp)
          }
        }
      }
      val waitInvResp: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.fire) {
            // immediately unlock
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            // we should've latched tx descriptor in savedTxDesc
            val toInvalidate = packetSizeToNumOverflowCls(savedTxHostCtrl.len.bits)
            txOverflowToInvalidate := toInvalidate
            when (toInvalidate > 0) {
              goto(invalidatePacketData)
            } otherwise {
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
          }

          lcia.freeRun()
          when (lcia.fire) {
            // unlock immediately
            ulFlow.payload := lcia.payload
            ulFlow.valid := True

            txOverflowInvAcked.increment()
          }

          when (txOverflowInvAcked === txOverflowToInvalidate) {
            goto(tx)
          }
        }
      }
      val tx: State = new State {
        whenIsActive {
          savedTxHostCtrl.unpackTo(hostTxAck.get)
          hostTxAck.get.buffer.addr := savedTxAddr.addr
          hostTxAck.valid := True

          when (hostTxAck.fire) {
            txCurrClIdx.toggleWhen(True)
            goto(idle)
          }
        }
      }
    }

    // make rx and tx fsm states available to top for debug
    rxFsm.build()
    txFsm.build()
    host[DebugPlugin].postDebug(s"rxFsm_${coreID}_stateReg", rxFsm.stateReg)
    host[DebugPlugin].postDebug(s"rxFsm_${coreID}_currClIdx", rxCurrClIdx)
    host[DebugPlugin].postDebug(s"txFsm_${coreID}_stateReg", txFsm.stateReg)
    host[DebugPlugin].postDebug(s"txFsm_${coreID}_currClIdx", txCurrClIdx)
  }

  // FIXME: mackerel does not support:
  //  - fragmenting large fields, or
  //  - declaring arrays inside datatypes
  //  until one of these is implemented, do not emit datatype definition
  during build EciHostCtrlInfo().addMackerel
}
