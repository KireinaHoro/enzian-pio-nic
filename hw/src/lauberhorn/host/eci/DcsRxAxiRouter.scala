package lauberhorn.host.eci

import jsteward.blocks.axi.RichAxi4
import jsteward.blocks.eci.EciCmdDefs
import lauberhorn.PacketAddr
import lauberhorn.Global._
import lauberhorn.host.HostReq
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm._

import scala.language.postfixOps

/**
  * For the decoupled RX/TX protocol, route incoming AXI request from the DCS for RX to:
  *  - read RX control metadata provided by scheduler to a specific core;
  *    - return NACK after configurable timeout
  *  - reroute to global packet buffer, connected via AXI.
  *
  * Reads to the RX control metadata will update cache line parity states, to implement 2F2F-style
  * cache line ping-ponging.  Extra signals will be emitted, to help implement the full 2F2F protocol:
  *  - read 0x0 /0x80           : start of request
  *  - read 0x40/0xc0 && timeout: NACK reply sent, skip sending ACK to scheduler
  *
  * Address mapping:
  *  - 0x 0 - 0x 40: control header #1
  *  - 0x40 - 0x 80: packet buffer 0x0 - 0x40 (aliased)
  *  - 0x80 - 0x c0: control header #2
  *  - 0xc0 - 0x100: packet buffer 0x0 - 0x40 (aliased)
  *  - 0x100+      : contiguous payload byte zero for lengths >64
  * Control payload halves are zero for lengths >64.
  *
  * @param axiConfig AXI parameters of upstream and downstream nodes
  */
case class DcsRxAxiRouter(dcsConfig: Axi4Config, pktBufConfig: Axi4Config) extends Component {
  assert(dcsConfig.dataWidth == 512, "only supports 512b bus from DCS AXI interface")
  assert(pktBufConfig.dataWidth == 512, "only supports 512b bus from pkt buffer AXI interface")
  assert(PKT_BUF_RX_SIZE_PER_CORE % 64 == 0, "pkt buffer size (B) should be multiple of 64")

  /** Incoming RX descriptors from scheduler.
    *
    * Note: [[rxDesc.ready]] carries special meaning.  The scheduler will not present a
    * request (i.e. assert [[rxDesc.valid]]), unless ready is high.  The router module
    * should not wait for valid as a trigger.
    */
  val rxDesc = slave(Stream(HostReq()))
  val rxTraceId = in UInt(lauberhorn.HostMsgID.width bits)
  // Only unaccepted descriptors may be cancelled. The scheduler retains their
  // allocation and returns it through its discard path; accepted ones drain here.
  val cancelRxDesc = in Bool()
  val stalled = RegNext(rxDesc.isStall) init False
  val previousDesc = RegNext(rxDesc.payload)
  when(stalled && !cancelRxDesc) {
    assert(rxDesc.valid && rxDesc.payload === previousDesc,
      "RX descriptor changed without acceptance or explicit cancellation")
  }
  when(cancelRxDesc) { assert(!rxDesc.valid) }

  /** Number of cycles to block for before returning NACK. */
  val blockCycles = in UInt(REG_WIDTH bits)

  // One application-clock pulse: use the timeout path only while waiting for
  // a descriptor. Do not retain the notification for a later RX request.
  val gsyncRelease = in Bool()

  /** Pulse: the host just started a new request on a CL */
  val hostReq = out(Vec(Bool(), 2))

  /** Current control cache line index.  Used to determine if the host is reading
    * the same CL, for example due to a conflict miss
    */
  val currCl = in UInt(1 bit)

  /** Whether a preemption happened.  We need to drop [[savedControl]] and [[noReadPktBuf]]
    * on preemption to prevent a leak, when the new thread attempts to pretend that it is replaying
    * the old request.
    */
  val doPreempt = in Bool()
  val yieldRequested = in Bool()
  val fatalRelease = in Bool()
  val quiescent = out Bool()

  /** Pulse: host is reading the next CL and the protocol has just finished invalidating the current CL.
    * We can now unblock the read on the next CL.
    */
  val invDone = in Bool()

  /** Pulse: just sent back a NACK.  Will be repeated if the host reloaded the same CL.  */
  val nackSent = out Bool()

  /** Per-core AXI interface from DCS (already demuxed by ECI interface) */
  val dcsAxi = slave(Axi4(dcsConfig))

  /** Forwarded requests to global packet buffer (starts at 0) */
  val pktBufAxi = master(Axi4(pktBufConfig.copy(idWidth = dcsConfig.idWidth)))

  checkEciAxiCmd(dcsAxi)

  // ad-hoc queue size to not block AR channel
  val dcsQ = dcsAxi.queue(8)

  hostReq.foreach(_ := False)

  // initialization to avoid latches
  nackSent := False
  rxDesc.setBlocked()
  dcsQ.ar.setBlocked()
  dcsQ.r.setIdle()
  pktBufAxi.setIdle()

  // An exclusive RX load can return data on invalidation even without a CPU
  // store. Complete those writes independently of the read FSM: the next RX
  // read may be waiting for the LCIA which itself waits for this write's B.
  // Preserve payload mutations in the per-packet replay shadow below.
  val writeActive = Reg(Bool()) init False
  val writeSecondBeat = Reg(Bool()) init False
  val writeResponse = Reg(Bool()) init False
  val writeId = Reg(dcsQ.aw.id.clone)
  // Borrowed payloads may be mutated by parsers. Preserve returned bytes for
  // conflict-miss replay until this packet retires; never write them to packet
  // storage or interpret descriptor-field stores as commands.
  val shadowWords = 4 + EciAddressMap.overflow_lines * 2
  val shadow = Mem(Bits(512 bits), shadowWords)
  val shadowMask = Vec.fill(shadowWords)(Reg(Bits(64 bits)) init 0)
  val writeWord = Reg(UInt(log2Up(shadowWords) bits))
  val writeInRange = Reg(Bool())
  val clearShadow = Bool()
  clearShadow := False
  dcsQ.aw.ready := !writeActive && !writeResponse
  val writeOffset = dcsQ.aw.addr(12 downto 0) - EciAddressMap.rx_control
  when(dcsQ.aw.fire) {
    writeId := dcsQ.aw.id
    writeWord := (writeOffset >> 6).resized
    writeInRange := (writeOffset >> 6) < shadowWords
    writeActive := True
    writeSecondBeat := False
  }
  dcsQ.w.ready := writeActive
  val shadowWrite = dcsQ.w.fire && writeInRange && writeWord < shadowWords &&
    (writeWord === 1 || writeWord === 3 || writeWord >= 4)
  shadow.write(writeWord, dcsQ.w.data, shadowWrite, dcsQ.w.strb)
  when(shadowWrite) { shadowMask(writeWord) := shadowMask(writeWord) | dcsQ.w.strb }
  when(dcsQ.w.fire) {
    writeWord := writeWord + 1
    assert(dcsQ.w.last === writeSecondBeat, "RX writeback must contain two beats")
    when(writeSecondBeat) {
      writeActive := False
      writeResponse := True
    } otherwise {
      writeSecondBeat := True
    }
  }
  dcsQ.b.valid := writeResponse
  dcsQ.b.id := writeId
  dcsQ.b.setOKAY()
  when(dcsQ.b.fire) { writeResponse := False }

  // Count at the external boundary, including AWs still inside the queue and
  // responses held under backpressure. AW queue (8) + one active transaction.
  val pendingWrites = Reg(UInt(4 bits)) init 0
  when(dcsAxi.aw.fire =/= dcsAxi.b.fire) {
    when(dcsAxi.aw.fire) { pendingWrites := pendingWrites + 1 }
      .otherwise { pendingWrites := pendingWrites - 1 }
  }
  when(dcsAxi.b.fire) { assert(pendingWrites =/= 0) }

  // command saved from DCS in AR
  val readCmd: Axi4Ar = Reg(dcsAxi.ar.payload.clone)
  val readOffset = readCmd.addr(12 downto 0) - EciAddressMap.rx_control

  // packet buffer slot that the descriptor carried
  val currentPktBuf = Reg(new Bundle {
    val addr = PacketAddr()
    val validBytes = UInt(PKT_BUF_LEN_WIDTH bits)
  })
  currentPktBuf.validBytes init 0
  val prefixBytes = Reg(UInt(PKT_BUF_LEN_WIDTH bits)) init 0
  val prefixData = Reg(Bits(512 bits)) init 0
  val payloadBytes = currentPktBuf.validBytes + prefixBytes
  val firstBody = Reg(Bits(512 bits)) init 0
  val secondBody = Reg(Bits(512 bits)) init 0
  val sourceOffset = Reg(pktBufAxi.ar.addr.clone)
  val sourceShift = Reg(UInt(6 bits))
  val sourceSecond = RegInit(False)

  // packet buffer read dimensions, as decoded from DCS AXI request
  // length will be filtered to fit to packet buffer slot
  val pktBufReadOff = Reg(pktBufAxi.ar.addr.clone)
  val pktBufReadBeats = Reg(UInt(2 bits)) // we will read at most 2 beats at a time

  // timer for blocking requests
  val blockTimer = Counter(blockCycles.getWidth bits)

  // buffer control descriptor for responding to host reloads
  val savedControl = Reg(Flow(Bits(512 bits)))
  savedControl.valid init False

  when(clearShadow || doPreempt) { shadowMask.foreach(_ := 0) }
  val newPayload = RegInit(False)
  when (doPreempt) {
    newPayload := False
    currentPktBuf.validBytes := 0
    prefixBytes := 0
    prefixData := 0
    savedControl.valid := False
  }

  // invalidation can finish before we enter waitInv, store it here
  val invFinished = Reg(Bool()) init False
  invFinished.setWhen(invDone)

  val fsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsQ.ar.freeRun()
        blockTimer.clear()
        invFinished.clear()
        when (dcsQ.ar.valid) {
          readCmd := dcsQ.ar.payload
          goto(decodeAr)
        }
      }
    }
    val decodeAr: State = new State {
      whenIsActive {
        assert(readCmd.len === 1, "only support two-beat reads from DCS")

        when (readOffset === 0x0 || readOffset === 0x80) {
          // host reading a control CL
          pktBufReadOff   := 0x0
          pktBufReadBeats := 1

          val reqCl = (readOffset === 0x80).asUInt
          hostReq(reqCl) := True

          // the very first read request needs to pop a descriptor, even if it's reading the
          // same CL as current;
          // after that, only reading the opposite CL will have triggered popping a descriptor;
          // reading the same CL will result in the CL replayed
          when (reqCl === currCl && savedControl.valid) {
            goto(sendDesc)
          } otherwise {
            goto(waitDesc)
          }
        } otherwise {
          // host reading an overflow CL
          pktBufReadOff := (readOffset - 0x100).resized
          pktBufReadBeats := 2
          when(payloadBytes <= 64) { goto(sendDummyData) } otherwise { goto(readPktBuf) }
        }
      }
    }
    val waitDesc: State = new State {
      whenIsActive {
        // block up to blockCycles
        blockTimer.increment()
        rxDesc.ready := !yieldRequested && !fatalRelease
        when (blockTimer >= blockCycles || rxDesc.valid || yieldRequested || fatalRelease || gsyncRelease) {
          // one of two outcomes:
          // - timer expired or cancelled: respond NACK
          //   - will drop rxDesc.ready
          // - got a descriptor: respond with descriptor
          newPayload := True
          savedControl.payload := B(0, 512 bits)
          when(rxDesc.fire) {
            savedControl.payload := EciHostRxCtrlInfo.packFrom(rxDesc.payload, rxTraceId) ## True
          } elsewhen(yieldRequested && !fatalRelease) {
            savedControl.payload(1) := True // valid=0, PREEMPT=1, no payload
          }

          // when a packet is present, read from slot in packet buffer
          when (rxDesc.fire) {
            currentPktBuf.addr := rxDesc.buffer.addr
            // Allocation size includes stale padding; only DMA-written bytes
            // from this packet may be returned to its owner.
            currentPktBuf.validBytes := rxDesc.len.bits
            prefixBytes := EciHostRxCtrlInfo.prefixLength(rxDesc.payload)
            prefixData := EciHostRxCtrlInfo.prefixData(rxDesc.payload)
          } otherwise {
            // clear buffer length so we don't leak the previous
            // packet (already freed) on a NACK
            currentPktBuf.validBytes := 0
            prefixBytes := 0
            prefixData := 0
          }

          when (!savedControl.valid) {
            // no need to wait for invalidating opposite CL for first load
            savedControl.valid := True
            goto(sendDesc)
          } otherwise {
            goto(waitInv)
          }
        }
      }
    }
    val waitInv: State = new State {
      whenIsActive {
        when (invFinished) {
          goto(sendDesc)
        }
      }
    }
    val sendDesc: State = new State {
      whenIsActive {
        assert(savedControl.valid, "saved CL not valid")
        when(newPayload) { clearShadow := True; newPayload := False }
        dcsQ.r.data := savedControl.payload
        dcsQ.r.valid := True
        dcsQ.r.setOKAY()
        dcsQ.r.last := False
        dcsQ.r.id := readCmd.id
        when (dcsQ.r.ready) {
          nackSent := !savedControl.payload(0)
          when(payloadBytes <= 64) { goto(readPktBuf) } otherwise { goto(sendDummyData) }
        }
      }
    }
    // Assemble the logical host stream from a metadata prefix and aligned
    // packet-buffer words. Prefix bytes never occupy host descriptor fields.
    val readPktBuf: State = new State {
      whenIsActive {
        firstBody := 0
        secondBody := 0
        sourceSecond := False
        val relative = pktBufReadOff - prefixBytes.resized
        sourceOffset := relative & U(~BigInt(63) & ((BigInt(1) << sourceOffset.getWidth) - 1), sourceOffset.getWidth bits)
        sourceShift := relative.resize(6)
        when(pktBufReadOff === 0) { sourceOffset := 0; sourceShift := 0 }
        when(pktBufReadOff >= payloadBytes) { goto(sendDummyData) } otherwise { goto(readBody) }
      }
    }
    val readBody: State = new State {
      whenIsActive {
        when(sourceOffset >= currentPktBuf.validBytes) {
          when(!sourceSecond) { goto(nextBody) } otherwise { goto(sendPktData) }
        } otherwise {
          pktBufAxi.ar.valid := True
          pktBufAxi.ar.len := 0
          pktBufAxi.ar.addr := sourceOffset + currentPktBuf.addr.bits.resized
          pktBufAxi.ar.id := readCmd.id
          pktBufAxi.ar.setFullSize()
          pktBufAxi.ar.setBurstINCR()
          when(pktBufAxi.ar.fire) { goto(receiveBody) }
        }
      }
    }
    val receiveBody: State = new State {
      whenIsActive {
        pktBufAxi.r.ready := True
        when(pktBufAxi.r.fire) {
          assert(pktBufAxi.r.isOKAY() && pktBufAxi.r.last)
          when(sourceSecond) { secondBody := pktBufAxi.r.data; goto(sendPktData) }
            .otherwise { firstBody := pktBufAxi.r.data; goto(nextBody) }
        }
      }
    }
    val nextBody: State = new State {
      whenIsActive {
        when(pktBufReadOff === 0 || sourceShift === 0) { goto(sendPktData) }
          .otherwise { sourceSecond := True; sourceOffset := sourceOffset + 64; goto(readBody) }
      }
    }
    def finishBeat(): Unit = {
      when(dcsQ.r.fire) {
        when(pktBufReadBeats === 1) { goto(idle) } otherwise {
          pktBufReadOff := pktBufReadOff + 64
          pktBufReadBeats := pktBufReadBeats - 1
          goto(readPktBuf)
        }
      }
    }
    val sendDummyData: State = new State {
      whenIsActive {
        dcsQ.r.valid := True
        dcsQ.r.data := 0
        dcsQ.r.id := readCmd.id
        dcsQ.r.setOKAY()
        dcsQ.r.last := pktBufReadBeats === 1
        finishBeat()
      }
    }
    val sendPktData: State = new State {
      whenIsActive {
        dcsQ.r.valid := True
        dcsQ.r.data := ((secondBody ## firstBody) |>> (sourceShift * 8)).resize(512)
        when(pktBufReadOff === 0) {
          dcsQ.r.data := (firstBody |<< (prefixBytes * 8)).resize(512) | prefixData
        }
        val word = UInt(log2Up(shadowWords) bits)
        word := ((readOffset >> 6) + (2 - pktBufReadBeats)).resized
        val replay = shadow.readAsync(word)
        for(byte <- 0 until 64) {
          when(word < shadowWords && shadowMask(word)(byte)) {
            dcsQ.r.data(byte * 8, 8 bits) := replay(byte * 8, 8 bits)
          }
          when(pktBufReadOff + byte >= payloadBytes) { dcsQ.r.data(byte * 8, 8 bits) := 0 }
        }
        dcsQ.r.id := readCmd.id
        dcsQ.r.setOKAY()
        dcsQ.r.last := pktBufReadBeats === 1
        finishBeat()
      }
    }

  }
  fsm.build()
  quiescent := fsm.isActive(fsm.idle) && !dcsQ.ar.valid &&
    pendingWrites === 0 && !dcsAxi.aw.valid && !dcsAxi.w.valid

  val stateOut = out(fsm.stateReg.clone)
  stateOut := fsm.stateReg
}
