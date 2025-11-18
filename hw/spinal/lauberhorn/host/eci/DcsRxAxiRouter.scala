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
  *  - 0x100+      : packet buffer 0x40+
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
  rxDesc.assertPersistence()

  /** Number of cycles to block for before returning NACK. */
  val blockCycles = in UInt(REG_WIDTH bits)

  /** Pulse: the host just started a new request on a CL */
  val hostReq = out(Vec(Bool(), 2)).setAsReg()

  /** Current control cache line index.  Used to determine if the host is reading
    * the same CL, for example due to a conflict miss
    */
  val currCl = in UInt(1 bit)

  /** Whether a preemption happened.  We need to drop [[savedControl]] and [[noReadPktBuf]]
    * on preemption to prevent a leak, when the new thread attempts to pretend that it is replaying
    * the old request.
    */
  val doPreempt = in Bool()

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

  hostReq.foreach(_ init False)

  // initialization to avoid latches
  nackSent := False
  rxDesc.setBlocked()
  dcsQ.setBlocked()
  pktBufAxi.setIdle()

  // command saved from DCS in AR
  val readCmd: Axi4Ar = Reg(dcsAxi.ar.payload.clone)

  // packet buffer slot that the descriptor carried
  val currentPktBuf = Reg(new Bundle {
    val addr = PacketAddr()
    val numBeats = UInt(PKT_BUF_LEN_WIDTH - 5 bits) // divided by 64
  })
  currentPktBuf.numBeats init 0

  // packet buffer read dimensions, as decoded from DCS AXI request
  // length will be filtered to fit to packet buffer slot
  val pktBufReadOff = Reg(pktBufAxi.ar.addr.clone)
  val pktBufReadBeats = Reg(UInt(2 bits)) // we will read at most 2 beats at a time

  // timer for blocking requests
  val blockTimer = Counter(blockCycles.getWidth bits)

  // buffer control descriptor for responding to host reloads
  val savedControl = Reg(Flow(Bits(512 bits)))
  savedControl.valid init False

  when (doPreempt) {
    currentPktBuf.numBeats := 0
    savedControl.valid := False
  }

  // invalidation can finish before we enter waitInv, store it here
  val invFinished = Reg(Bool()) init False
  invFinished.setWhen(invDone)

  val fsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        hostReq.foreach(_ := False)
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

        when (readCmd.addr === 0x0 || readCmd.addr === 0x80) {
          // host reading a control CL
          pktBufReadOff   := 0x0
          pktBufReadBeats := 1

          val reqCl = (readCmd.addr === 0x80).asUInt
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
          pktBufReadOff := (readCmd.addr - 0xc0).resized
          pktBufReadBeats := 2
          goto(readPktBuf)
        }
      }
    }
    val waitDesc: State = new State {
      whenIsActive {
        // block up to blockCycles
        blockTimer.increment()
        rxDesc.ready := True
        when (blockTimer >= blockCycles || rxDesc.valid) {
          // one of two outcomes:
          // - timer expired or cancelled: respond NACK
          //   - will drop rxDesc.ready
          // - got a descriptor: respond with descriptor
          savedControl.payload := EciHostCtrlInfo.packFrom(rxDesc.payload) ## rxDesc.valid

          // when a packet is present, read from slot in packet buffer
          when (rxDesc.valid) {
            currentPktBuf.addr := rxDesc.buffer.addr
            // buffer size is the actual length of packet --
            // calculate size of actual packet buffer
            currentPktBuf.numBeats := rxDesc.buffer.size.bits >> 5
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
        dcsQ.r.data := savedControl.payload
        dcsQ.r.valid := True
        dcsQ.r.setOKAY()
        dcsQ.r.last := False
        dcsQ.r.id := readCmd.id
        when (dcsQ.r.ready) {
          nackSent := !savedControl.payload(0)
          goto(readPktBuf)
        }
      }
    }
    val readPktBuf: State = new State {
      whenIsActive {
        val startBeatNum = pktBufReadOff >> 5
        val startInside = startBeatNum < currentPktBuf.numBeats
        val endInside = startBeatNum + pktBufReadBeats <= currentPktBuf.numBeats
        // packet buffer slots should be aligned, so we don't have
        // to save partial dummy data
        assert(!(startInside && !endInside), "packet buffer slot not aligned properly!")

        when (!startInside) {
          // trying to read beyond current packet buffer slot, return dummy data
          goto(sendDummyData)
        } otherwise {
          // send read request to pkt buf axi
          pktBufAxi.ar.valid := True
          pktBufAxi.ar.len := (pktBufReadBeats - 1).resized
          pktBufAxi.ar.addr := pktBufReadOff + currentPktBuf.addr.bits.resized
          pktBufAxi.ar.id := readCmd.id
          pktBufAxi.ar.setFullSize()
          pktBufAxi.ar.setBurstINCR()
          when(pktBufAxi.ar.ready) {
            goto(sendPktData)
          }
        }
      }
    }
    val sendDummyData: State = new State {
      whenIsActive {
        dcsQ.r.valid := True
        dcsQ.r.data := B(0)
        dcsQ.r.id := readCmd.id
        dcsQ.r.setOKAY()
        dcsQ.r.last := pktBufReadBeats === 1
        when (dcsQ.r.ready) {
          when (pktBufReadBeats === 1) {
            goto(idle)
          }
          pktBufReadBeats := pktBufReadBeats - 1
        }
      }
    }
    val sendPktData: State = new State {
      whenIsActive {
        pktBufAxi.r >> dcsQ.r
        when (pktBufAxi.r.fire) {
          assert(pktBufAxi.r.isOKAY(), "packet buffer AXI resp not OKAY")
          assert(pktBufAxi.r.last === (pktBufReadBeats === 1), "packet buffer AXI last not match")
          when (pktBufAxi.r.last) {
            goto(idle)
          }
          pktBufReadBeats := pktBufReadBeats - 1
        }
      }
    }
  }
  fsm.build()

  val stateOut = out(fsm.stateReg.clone)
  stateOut := fsm.stateReg
}