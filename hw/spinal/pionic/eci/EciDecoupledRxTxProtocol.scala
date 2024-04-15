package pionic.eci

import jsteward.blocks.axi.RichAxi4
import jsteward.blocks.eci.EciCmdDefs
import pionic.{ConfigWriter, GlobalCSRPlugin, PacketLength, PioNicConfig, checkStreamValidDrop}
import spinal.core._
import spinal.core.fiber.Handle._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4SlaveFactory}
import spinal.lib.bus.misc.{SingleMapping, SizeMapping}
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

case class PacketCtrlInfo()(implicit config: PioNicConfig) extends Bundle {
  override def clone = PacketCtrlInfo()

  val size = PacketLength()

  // plus one for readStreamBlockCycles
  assert(getBitsWidth + 1 <= 512, "packet info larger than half a cacheline")
}

class EciDecoupledRxTxProtocol(coreID: Int)(implicit val config: PioNicConfig) extends FiberPlugin with EciPioProtocol {
  withPrefix(s"core_$coreID")

  lazy val csr = host[GlobalCSRPlugin].logic
  lazy val numOverflowCls = (host[EciInterfacePlugin].sizePerMtuPerDirection / EciCmdDefs.ECI_CL_SIZE_BYTES - 1).toInt
  lazy val pktBufWordNumBytes = host[EciInterfacePlugin].pktBufWordWidth / 8
  lazy val overflowCountWidth = log2Up(numOverflowCls)

  // map at aligned address to eliminate long comb paths
  lazy val txOffset = 0x4000

  lazy val configWriter = host[ConfigWriter]
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
    val descOffset = config.pktBufSizePerCore * coreID

    val rxSize = host[EciInterfacePlugin].rxSizePerCore
    val txSize = host[EciInterfacePlugin].txSizePerCore

    // remap packet data: first cacheline inline packet data -> second one
    // such that we have continuous address when handling packet data
    val busCtrl = Axi4SlaveFactory(bus.fullPipe()).remapAddress { addr =>
      addr.mux(
        U(0x40) -> U(0xc0),
        U(txOffset + 0x40) -> U(txOffset + 0xc0),
        default -> addr
      )
    }

    hostRxNextReq := logic.rxReqs.reduce(_ || _)
    val blockCycles = Vec(CombInit(csr.ctrl.rxBlockCycles), 2)
    Seq(0, 1) foreach { idx => new Area {
      val rxCtrlAddr = idx * 0x80
      busCtrl.onReadPrimitive(SingleMapping(rxCtrlAddr), false, null) {
        logic.rxReqs(idx).set()
        // only allow read request for a new packet to proceed when we are idle (no inv pending, etc.)
        // this covers both NACK and actual packets
        when (!logic.rxFsm.isActive(logic.rxFsm.idle) && idx =/= logic.rxCurrClIdx.asUInt) {
          busCtrl.readHalt()
        }
      }

      // we latch the rx stream during any possible host reload replays
      val needRepeat = logic.rxFsm.isActive(logic.rxFsm.gotPacket) || logic.rxFsm.isActive(logic.rxFsm.noPacket)
      val latched = RegNextWhen(logic.rxPacketCtrl(idx),
        // latch exactly when the first read happened, to prevent valid going high in between
        logic.rxFsm.isActive(logic.rxFsm.idle) && busCtrl.isReading(rxCtrlAddr)).asFlow
      val repeated = StreamMux(needRepeat.asUInt, Seq(logic.rxPacketCtrl(idx), latched.toStream)) setName "repeated"

      // disable block cycles when we are in repeat
      when (needRepeat) {
        blockCycles(logic.rxCurrClIdx.asUInt) := U(0).resized
      }

      // readStreamBlockCycles report timeout on last beat of stream, but we need to issue it after the entire reload is finished
      val streamTimeout = Bool()
      val bufferedStreamTimeout = Reg(Bool()) init False
      bufferedStreamTimeout.setWhen(streamTimeout)
      busCtrl.readStreamBlockCycles(repeated, rxCtrlAddr, blockCycles(idx), streamTimeout)
      busCtrl.onRead(0xc0) {
        logic.rxNackTriggerInv.setWhen((bufferedStreamTimeout | streamTimeout)
          // do not trigger inv when we got a packet right at the timeout
          && !logic.rxFsm.isActive(logic.rxFsm.gotPacket))
        bufferedStreamTimeout.clear()
      }

      val txCtrlAddr = txOffset + idx * 0x80
      busCtrl.onReadPrimitive(SingleMapping(txCtrlAddr), false, null) {
        logic.txReqs(idx).set()
        // only allow write load request when we are idle
        when (!logic.txFsm.isActive(logic.txFsm.idle) && idx =/= logic.txCurrClIdx.asUInt) {
          busCtrl.readHalt()
        }
      }

      busCtrl.driveStream(logic.txPacketCtrl(idx), txCtrlAddr)
      busCtrl.read(logic.savedTxCtrl, txCtrlAddr)
    }.setCompositeName(this, "driveBusCtrl")
    }

    val rxBufMapping = SizeMapping(descOffset, rxSize)

    // cpu not supposed to modify rx packet data, so omitting write
    // memOffset is in memory words (64B)
    val memOffset = rxBufMapping.removeOffset(logic.savedHostRx.addr.bits) >> log2Up(pktBufWordNumBytes)
    busCtrl.readSyncMemWordAligned(rxPktBuffer, 0xc0,
      memOffset = memOffset.resized,
      mappingLength = config.roundMtu)

    // tx buffer always start at 0
    // allow reloading from the packet buffer for partial flush due to voluntary invalidations
    busCtrl.readWriteSyncMemWordAligned(txPktBuffer, txOffset + 0xc0,
      mappingLength = config.roundMtu)
  }.setName("driveDcsBus")

  val logic = during build new Area {
    val rxCurrClIdx = Reg(Bool()) init False
    val txCurrClIdx = Reg(Bool()) init False

    assert(txOffset >= host[EciInterfacePlugin].sizePerMtuPerDirection, "tx offset does not allow one MTU for rx")

    configWriter.postConfig("eci rx base", 0)
    configWriter.postConfig("eci rx overflow", 0x100)
    configWriter.postConfig("eci tx base", txOffset)
    configWriter.postConfig("eci tx overflow", txOffset + 0x100)

    // corner case: when nack comes in after a long packet, this could be delivered before all LCIs for packets
    // finish issuing
    // ASSUMPTION: two control cachelines will never trigger NACK invalidate, thus shared
    val rxNackTriggerInv = Reg(Bool()) init False

    val rxReqs = Vec(False, 2)
    val txReqs = Vec(False, 2)

    lci.setIdle()
    lci.valid.setAsReg()
    lci.payload.setAsReg()
    checkStreamValidDrop(lci)

    val ulFlow = Flow(ul.payload.clone).setIdle()
    val ulOverflow = Bool()
    // max number of inflight ULs: overflow CLs + 2 ctrl CLs
    ul << ulFlow.toStream(ulOverflow).queue(numOverflowCls + 2)
    assert(
      assertion = !ulOverflow,
      message = s"UL flow overflow",
      severity = FAILURE
    )

    lcia.setBlocked()

    hostRxNextAck.setIdle()
    hostTx.setBlocked()
    hostTxAck.setIdle()

    val rxOverflowInvIssued, rxOverflowInvAcked = Counter(overflowCountWidth bits)
    val rxOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))
    val txOverflowInvIssued, txOverflowInvAcked = Counter(overflowCountWidth bits)
    val txOverflowToInvalidate = Reg(UInt(overflowCountWidth bits))

    val savedHostRx = RegNextWhen(hostRxNext.payload, hostRxNext.fire)

    val rxFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        onEntry {
          rxNackTriggerInv.clear()
        }
        whenIsActive {
          rxOverflowToInvalidate.clearAll()
          rxOverflowInvAcked.clear()
          rxOverflowInvIssued.clear()

          when (hostRxNext.fire) {
            rxOverflowToInvalidate := packetSizeToNumOverflowCls(hostRxNext.payload.size.bits)
            goto(gotPacket)
          }
          when (rxNackTriggerInv) {
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
            hostRxNextAck.payload := savedHostRx
            hostRxNextAck.valid := True
            when (hostRxNextAck.fire) {
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
    val ctrlInfo = PacketCtrlInfo()
    ctrlInfo.size := hostRxNext.payload.size
    val rxCtrlInfoStream = hostRxNext.translateWith(ctrlInfo)
    val rxPacketCtrl = StreamDemux(rxCtrlInfoStream, rxCurrClIdx.asUInt, 2) setName "rxPacketCtrl"

    val txPacketCtrl = Vec(Stream(Bits(512 bits)), 2)
    when (txPacketCtrl.map(_.fire).reduce(_ || _)) {
      // pop hostTx to honour the protocol
      hostTx.freeRun()
    }

    val selectedTxCtrl = StreamMux(txCurrClIdx.asUInt, txPacketCtrl)
    val savedTxCtrl = selectedTxCtrl.toReg()
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

            // we should've latched ctrl in savedTxCtrl
            txOverflowToInvalidate := packetSizeToNumOverflowCls(savedTxCtrl.asUInt)
            when (txOverflowToInvalidate > 0) {
              goto(invalidatePacketData)
            } otherwise {
              goto(tx)
            }
          }
        }
      }
      val invalidatePacketData: State = new State {
        whenIsActive {
          when(txOverflowInvIssued < txOverflowToInvalidate) {
            lci.payload := overflowIdxToAddr(txOverflowInvIssued, isTx = true)
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
          hostTxAck.payload.bits := savedTxCtrl.asUInt.resized
          hostTxAck.valid := True

          when (hostTxAck.fire) {
            txCurrClIdx.toggleWhen(True)
            goto(idle)
          }
        }
      }
    }
  }
}
