package lauberhorn.host.eci

import jsteward.blocks.axi.RichAxi4
import jsteward.blocks.eci.EciCmdDefs
import lauberhorn.Global.ECI_TX_BASE
import lauberhorn.{Global, PacketAddr}
import lauberhorn.host.{HostReq, HostReqType}
import lauberhorn.Global.{HOST_REQ_TY_WIDTH, PKT_BUF_LEN_WIDTH}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm._

import scala.language.postfixOps

/** For the decoupled RX/TX protocol, route incoming AXI request from the DCS for TX to:
  *  - capture TX control metadata sent by the host
  *  - write data to global packet buffer, sitting in a fixed slot
  *
  * Same address layout on the DCS AXI interface as [[DcsRxAxiRouter]].
  *
  * @param axiConfig AXI parameters of upstream and downstream nodes
  */
case class DcsTxAxiRouter(dcsConfig: Axi4Config,
                          pktBufConfig: Axi4Config,
                         ) extends Component {
  assert(dcsConfig.dataWidth == 512, "only supports 512b bus from DCS AXI interface")
  assert(pktBufConfig.dataWidth == 512, "only supports 512b bus from pkt buffer AXI interface")

  /** Outgoing TX descriptors to the encoder pipeline. */
  val txDesc = master(Stream(HostReq()))
  txDesc.assertPersistence()
  val txResult = slave(Flow(Bits(2 bits)))
  val resultPending = RegInit(False)
  val resultStatus = Reg(Bits(2 bits)) init 0
  when (txResult.valid) {
    resultPending := True
    resultStatus := txResult.payload
  }

  /** Per-core AXI interface from DCS (demuxed; shared with [[DcsRxAxiRouter]]). */
  val dcsAxi = slave(Axi4(dcsConfig))

  /** Forwarded requests to global packet buffer (starts at 0) */
  val pktBufAxi = master(Axi4(pktBufConfig.copy(idWidth = dcsConfig.idWidth)))

  /** Current control cache line index.  Used to check if the host evicted the current
    * cache line (e.g. due to a capacity conflict).  Responds to reloads of the current
    * CL with the buffer captured so far.  */
  val currCl = in UInt(1 bit)

  /** Whether a preemption happened.  We need to drop [[savedControl]] on preemption to
    * prevent a leak, when the new thread attempts to read back the packet sent by the
    * old thread.
    */
  val doPreempt = in Bool()
  val quiescent = out Bool()

  /** Address to put the outgoing packet payload in the packet buffer.  Captured
    * from [[lauberhorn.host.DatapathPlugin.hostTx]] */
  val txAddr = in(PacketAddr())

  /** Pulse: all dirty CLs have been flushed */
  val invDone = in Bool()

  /** Pulse: the host just started a new request on a CL */
  val hostReq = out(Vec(Bool(), 2)).setAsReg().initZero()

  checkEciAxiCmd(dcsAxi)

  val dcsQ = dcsAxi.queue(8)

  val readCmd: Axi4Ar = Reg(dcsAxi.ar.payload.clone)
  val readAddr = readCmd.addr(12 downto 0) - ECI_TX_BASE.get
  val writeCmd: Axi4Aw = Reg(dcsAxi.aw.payload.clone)
  val writeAddr = writeCmd.addr(12 downto 0) - ECI_TX_BASE.get

  // initialization to avoid latches
  dcsQ.setBlocked()
  pktBufAxi.setIdle()
  txDesc.setIdle()

  // save the control separately to allow re-reads from host
  // packet data will be read from packet buffer
  val savedControl = Reg(Bits(512 bits)) init 0
  // Control may be evicted before the doorbell. Retain its payload independently
  // of overflow aliases, then restore it after the control invalidation completes.
  val savedInline = Reg(Bits(512 bits)) init 0
  val inlinePending = RegInit(False)
  val inlineCommitted = RegInit(False)
  val invalidLayout = Bool()
  val execution = master(Flow(Bits(2 bits)))
  val executionOnly = out Bool()
  executionOnly := txDesc.ty === HostReqType.executionControl
  val executionType = txDesc.ty === HostReqType.oncRpcReplyTx ||
    txDesc.ty === HostReqType.oncRpcCallTx || txDesc.ty === HostReqType.nativeUdpTx || executionOnly
  execution.valid := txDesc.fire && executionType && !invalidLayout
  execution.payload := executionType ? savedControl(HOST_REQ_TY_WIDTH.get + PKT_BUF_LEN_WIDTH.get + 1 downto HOST_REQ_TY_WIDTH.get + PKT_BUF_LEN_WIDTH.get) | B(0, 2 bits)
  val aliasedHostCtrl = EciHostTxCtrlInfo()
  aliasedHostCtrl.assignFromBits(savedControl)
  val payloadLines = out UInt(4 bits)
  val controlOnly = aliasedHostCtrl.ty === HostReqType.executionControl
  val neededLines = (aliasedHostCtrl.len.bits.resize(17) + 127) >> 7
  payloadLines := neededLines.resized
  when(aliasedHostCtrl.inlinePayload || controlOnly) { payloadLines := 0 }
  when(neededLines > EciAddressMap.overflow_lines && !aliasedHostCtrl.inlinePayload && !controlOnly) {
    payloadLines := EciAddressMap.overflow_lines
  }
  invalidLayout := (aliasedHostCtrl.inlinePayload && aliasedHostCtrl.len.bits > 64) ||
    aliasedHostCtrl.len.bits > Global.ROUNDED_MTU.get
  when(invDone.rise(False) && aliasedHostCtrl.inlinePayload) {
    inlinePending := True
    inlineCommitted := True
  }

  when (doPreempt) {
    savedControl.clearAll()
    savedInline.clearAll()
    inlineCommitted := False
    inlinePending := False
  }

  // invalidation can finish before we enter waitInv, store it here
  val invFinished = Reg(Bool()) init False
  invFinished.setWhen(invDone)

  val pktBufReadOff = Reg(pktBufAxi.ar.addr.clone)

  // we read max 2 beats each round, will fit inside one AXI burst
  // NOTE: this counts number of BEATS left, not BYTES
  val pktBufReadBeats = Reg(UInt(2 bits))

  // offset and size to write to packet buffer
  // NOTE: len has same meaning in read
  val pktBufWriteOff = Reg(pktBufAxi.aw.addr.clone)
  val pktBufWriteBeats = Reg(UInt(2 bits))

  // Ownership changes only after old traffic has drained. Clear the physical
  // allocation before servicing any successor TX access (including partial
  // writes); this also protects DMA reads of bytes the successor never wrote.
  val scrubPending = RegInit(False)
  val scrubOffset = Reg(pktBufAxi.aw.addr.clone) init 0
  when(doPreempt) { scrubPending := True; scrubOffset := 0 }
  val txBytes = Global.ROUNDED_MTU.get
  require(txBytes % 64 == 0)

  val writeFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsQ.aw.ready := !scrubPending && !inlinePending && !doPreempt
        when(scrubPending) { goto(scrubCmd) }
          .elsewhen(inlinePending) { goto(inlineCmd) }
        when (dcsQ.aw.fire) {
          writeCmd := dcsQ.aw.payload
          goto(decodeCmd)
        }
      }
    }
    val inlineCmd: State = new State {
      whenIsActive {
        pktBufAxi.aw.valid := True
        pktBufAxi.aw.addr := txAddr.bits.resized
        pktBufAxi.aw.len := 0
        pktBufAxi.aw.id := 0
        pktBufAxi.aw.setFullSize()
        pktBufAxi.aw.setBurstINCR()
        when(pktBufAxi.aw.fire) { goto(inlineData) }
      }
    }
    val inlineData: State = new State {
      whenIsActive {
        pktBufAxi.w.valid := True
        pktBufAxi.w.data := savedInline
        pktBufAxi.w.strb.setAll()
        pktBufAxi.w.last := True
        when(pktBufAxi.w.fire) { goto(inlineResp) }
      }
    }
    val inlineResp: State = new State {
      whenIsActive {
        pktBufAxi.b.ready := True
        when(pktBufAxi.b.fire) {
          assert(pktBufAxi.b.isOKAY(), "TX inline restore failed")
          inlinePending := False
          goto(idle)
        }
      }
    }
    val scrubCmd: State = new State {
      whenIsActive {
        pktBufAxi.aw.valid := True
        pktBufAxi.aw.addr := txAddr.bits.resized + scrubOffset
        pktBufAxi.aw.len := 0
        pktBufAxi.aw.id := 0
        pktBufAxi.aw.setFullSize()
        pktBufAxi.aw.setBurstINCR()
        when(pktBufAxi.aw.fire) { goto(scrubData) }
      }
    }
    val scrubData: State = new State {
      whenIsActive {
        pktBufAxi.w.valid := True
        pktBufAxi.w.data := 0
        pktBufAxi.w.strb.setAll()
        pktBufAxi.w.last := True
        when(pktBufAxi.w.fire) { goto(scrubResp) }
      }
    }
    val scrubResp: State = new State {
      whenIsActive {
        pktBufAxi.b.ready := True
        when(pktBufAxi.b.fire) {
          assert(pktBufAxi.b.isOKAY(), "TX scrub failed")
          when(scrubOffset === txBytes - 64) {
            scrubPending := False
            goto(idle)
          } otherwise {
            scrubOffset := scrubOffset + 64
            goto(scrubCmd)
          }
        }
      }
    }
    val decodeCmd: State = new State {
      whenIsActive {
        assert(writeCmd.len === 1, "only support two-beat writes from DCS")

        when (writeAddr === currCl * 0x80) {
          pktBufWriteOff := 0x0
          pktBufWriteBeats := 1
          goto(saveDesc)
        } elsewhen (writeAddr === (1 - currCl) * 0x80) {
          report("write cannot happen on the inactive CL", FAILURE)
        } otherwise {
          pktBufWriteOff := (writeAddr - 0x100).resized
          pktBufWriteBeats := 2
          goto(writePktBufCmd)
        }
      }
    }

    def captureWrite(dest: Bits) = {
      dest.subdivideIn(8 bits) zip
        dcsQ.w.data.subdivideIn(8 bits) zip
        dcsQ.w.strb.asBools foreach { case ((buf, byte), en) =>
        when (en) { buf := byte }
      }
    }
    val saveDesc: State = new State {
      whenIsActive {
        // the host is writing a control CL: capture write into control CL
        dcsQ.w.ready := True
        when (dcsQ.w.valid) {
          assert(!dcsQ.w.last, "not expecting last yet")
          when (pktBufWriteBeats === 1) {
            captureWrite(savedControl)
          }

          goto(writePktBufCmd)
        }
      }
    }
    val writePktBufCmd: State = new State {
      whenIsActive {
        pktBufAxi.aw.valid := True
        pktBufAxi.aw.len := 0
        // Redirect any out-of-range beat to this slot with zero strobes,
        // never into the neighboring allocation.
        pktBufAxi.aw.addr := txAddr.bits.resized
        when(pktBufWriteOff < txBytes) {
          pktBufAxi.aw.addr := pktBufWriteOff + txAddr.bits.resized
        }
        pktBufAxi.aw.id := writeCmd.id
        pktBufAxi.aw.setFullSize()
        pktBufAxi.aw.setBurstINCR()
        when (pktBufAxi.aw.ready) {
          goto(writePktBufData)
        }
      }
    }
    val writePktBufData: State = new State {
      whenIsActive {
        pktBufAxi.w << dcsQ.w
        pktBufAxi.w.last.allowOverride
        pktBufAxi.w.last := True
        when(pktBufWriteOff >= txBytes ||
            ((writeAddr === 0 || writeAddr === 0x80) && !aliasedHostCtrl.inlinePayload) ||
            (writeAddr >= 0x100 && inlineCommitted)) {
          pktBufAxi.w.strb := 0
        }

        when (pktBufAxi.w.fire) {
          when(writeAddr === 0 || writeAddr === 0x80) { captureWrite(savedInline) }
          assert(dcsQ.w.last === (pktBufWriteBeats === 1))
          goto(writePktBufResp)
        }
      }
    }
    val writePktBufResp: State = new State {
      whenIsActive {
        pktBufAxi.b >> dcsQ.b
        when(pktBufWriteBeats =/= 1) {
          dcsQ.b.valid := False
          pktBufAxi.b.ready := True
        }
        when (pktBufAxi.b.fire) {
          assert(pktBufAxi.b.isOKAY(), "error BRESP from packet buffer")
          when(pktBufWriteBeats === 1) { goto(idle) } otherwise {
            pktBufWriteBeats := pktBufWriteBeats - 1
            pktBufWriteOff := pktBufWriteOff + 64
            goto(writePktBufCmd)
          }
        }
      }
    }
  }
  writeFsm.build()

  val readFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsQ.ar.ready := !scrubPending && !doPreempt
        hostReq.foreach(_ := False)
        invFinished.clear()
        when (dcsQ.ar.fire) {
          readCmd := dcsQ.ar.payload
          goto(decodeCmd)
        }
      }
    }
    val decodeCmd: State = new State {
      whenIsActive {
        when (readAddr === 0x0 || readAddr === 0x80) {
          pktBufReadOff := 0x0
          pktBufReadBeats := 1

          val reqCl = (readAddr === 0x80).asUInt
          hostReq(reqCl) := True

          when (reqCl === currCl) {
            goto(sendDesc)
          } otherwise {
            // host dummy-reading opposite cache line; protocol will invalidate all cache
            // lines before we can send the descriptor to encoders.  we need to serve
            // a dummy data
            goto(waitInv)
          }
        } otherwise {
          // accessing packet buffer via overflow cachelines
          pktBufReadOff := (readAddr - 0x100).resized
          pktBufReadBeats := 2

          goto(readPktBuf)
        }
      }
    }
    val waitInv: State = new State {
      whenIsActive {
        when (invFinished) {
          goto(transmitDesc)
        }
      }
    }
    val sendDesc: State = new State {
      whenIsActive {
        dcsQ.r.data := savedControl
        dcsQ.r.valid := True
        dcsQ.r.setOKAY()
        dcsQ.r.id := readCmd.id
        dcsQ.r.last := False
        when (dcsQ.r.ready) {
          when(aliasedHostCtrl.inlinePayload) { goto(sendInline) } otherwise { goto(sendPadding) }
        }
      }
    }
    val readPktBuf: State = new State {
      whenIsActive {
        // Bound each beat so an out-of-range read cannot reach the
        // adjacent physical worker's allocation.
        pktBufAxi.ar.valid := True
        pktBufAxi.ar.len := 0
        pktBufAxi.ar.addr := pktBufReadOff + txAddr.bits.resized
        pktBufAxi.ar.id := readCmd.id
        pktBufAxi.ar.setFullSize()
        pktBufAxi.ar.setBurstINCR()
        when (pktBufAxi.ar.ready) { goto(sendPktData) }
        when(pktBufReadOff >= txBytes) {
          pktBufAxi.ar.valid := False
          goto(sendPadding)
        }
      }
    }
    val sendPadding: State = new State {
      whenIsActive {
        dcsQ.r.valid := True
        dcsQ.r.data := 0
        dcsQ.r.id := readCmd.id
        dcsQ.r.setOKAY()
        dcsQ.r.last := pktBufReadBeats === 1
        when(dcsQ.r.fire) {
          pktBufReadBeats := pktBufReadBeats - 1
          when(pktBufReadBeats === 1) { goto(idle) }
        }
      }
    }
    val sendInline: State = new State {
      whenIsActive {
        dcsQ.r.valid := True
        dcsQ.r.data := savedInline
        dcsQ.r.id := readCmd.id
        dcsQ.r.setOKAY()
        dcsQ.r.last := True
        when(dcsQ.r.fire) { goto(idle) }
      }
    }
    val sendPktData: State = new State {
      whenIsActive {
        pktBufAxi.r >> dcsQ.r
        dcsQ.r.last.allowOverride
        dcsQ.r.last := pktBufReadBeats === 1
        when (pktBufAxi.r.fire) {
          assert(pktBufAxi.r.isOKAY(), "error RRESP from packet buffer")
          assert(pktBufAxi.r.last, "expected single-beat packet buffer read")
          when (pktBufReadBeats === 1) { goto(idle) } otherwise {
            pktBufReadOff := pktBufReadOff + 64
            goto(readPktBuf)
          }
          pktBufReadBeats := pktBufReadBeats - 1
        }
      }
    }
    val transmitDesc: State = new State {
      whenIsActive {
        // send assembled descriptor to encoder pipeline
        // the packet buffer has been fully written from invalidation
        txDesc.valid := !inlinePending

        val ctrl = EciHostTxCtrlInfo()
        ctrl.assignFromBits(savedControl)
        ctrl.unpackTo(txDesc.payload, txAddr)
        when(invalidLayout) {
          // Use the DMA boundary's ordinary oversized-descriptor rejection.
          // Preserve the known type and physical slot so rejection acknowledges
          // this transaction without triggering an unsupported-type domain fault.
          txDesc.buffer.size.bits := Global.ROUNDED_MTU.get + 1
        }

        when (txDesc.fire) {
          // we are in the middle of a read for next CL -- serve read
          goto(waitResult)
        }
      }
    }
    val waitResult: State = new State {
      whenIsActive {
        when (resultPending) {
          resultPending := False
          inlineCommitted := False
          // Preserve the acknowledgement across voluntary eviction/reload.
          savedControl := resultStatus.resize(512)
          goto(sendDesc)
        }
      }
    }
  }
  readFsm.build()
  quiescent := readFsm.isActive(readFsm.idle) && writeFsm.isActive(writeFsm.idle) &&
    !dcsQ.ar.valid && !dcsQ.aw.valid && !scrubPending && !inlinePending
  when(doPreempt) { assert(quiescent, "TX scrub requires drained old-owner traffic") }

  val readStateOut = out(readFsm.stateReg.clone)
  readStateOut := readFsm.stateReg
  val writeStateOut = out(writeFsm.stateReg.clone)
  writeStateOut := writeFsm.stateReg
}
