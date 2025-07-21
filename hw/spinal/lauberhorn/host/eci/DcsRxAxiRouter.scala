package lauberhorn.host.eci

import jsteward.blocks.axi.RichAxi4
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

  // do not read from the packet buffer if current CL served with NACK
  val noReadPktBuf = Reg(Bool()) init False
  val lastPktBufSlot = Reg(PacketAddr())

  // offset and length to read from the pkt buffer
  val pktBufReadOff = Reg(pktBufAxi.ar.addr.clone)

  // we read max 2 beats each round, will fit inside one AXI burst
  // NOTE: this counts number of BEATS left, not BYTES
  val pktBufReadLen = Reg(pktBufAxi.ar.len.clone)

  // timer for blocking requests
  val blockTimer = Counter(blockCycles.getWidth bits)

  // buffered first half CL for responding to host reloads on the same CL
  val savedControl = Reg(Bits(512 bits)) init 0
  val loadedFirstControl = Reg(Bool()) init False

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
        when (readCmd.addr === 0x0 || readCmd.addr === 0x80) {
          // host reading the first half CL in either first or second CL
          pktBufReadOff := 0x0
          pktBufReadLen := 1

          val reqCl = (readCmd.addr === 0x80).asUInt
          hostReq(reqCl) := True

          // the very first read request needs to pop a descriptor, even if it's reading the
          // same CL as current
          // afterwards, reading the opposite CL will have triggered popping a descriptor
          when (reqCl === currCl && loadedFirstControl) {
            goto(sendDesc)
          } otherwise {
            goto(waitDesc)
          }
        } otherwise {
          // host reading second half of some CL
          // XXX: we assume the first half-CL is always read first
          //      so we can ignore any state change and just serve packet buffer contents
          pktBufReadOff := (readCmd.addr - 0xc0).resized
          pktBufReadLen := 2
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
          savedControl := EciHostCtrlInfo.packFrom(rxDesc.payload) ## rxDesc.valid

          // when a packet is present, read from slot in packet buffer
          when (rxDesc.valid) {
            lastPktBufSlot := rxDesc.buffer.addr
          }

          // can the following requests read from the packet buffer?
          noReadPktBuf := !rxDesc.valid

          when (!loadedFirstControl) {
            // no need to wait for invalidating opposite CL for first load
            loadedFirstControl := True
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
        // send first beat, could be NACK
        dcsQ.r.data := savedControl
        dcsQ.r.valid := True
        dcsQ.r.setOKAY()
        dcsQ.r.last := False
        dcsQ.r.id := readCmd.id
        when (dcsQ.r.ready) {
          nackSent := !savedControl(0)
          goto(readPktBuf)
        }
      }
    }
    val readPktBuf: State = new State {
      whenIsActive {
        when (noReadPktBuf) {
          // do not send AXI request
          goto(sendData)
        } otherwise {
          // send read request to pkt buf axi
          pktBufAxi.ar.valid := True
          pktBufAxi.ar.len := pktBufReadLen - 1
          pktBufAxi.ar.addr := pktBufReadOff + lastPktBufSlot.bits.resized
          pktBufAxi.ar.id := readCmd.id
          pktBufAxi.ar.setFullSize()
          pktBufAxi.ar.setBurstINCR()
          when(pktBufAxi.ar.ready) {
            goto(sendData)
          }
        }
      }
    }
    val sendData: State = new State {
      whenIsActive {
        when (noReadPktBuf) {
          // return dummy result for packet buffer fetch
          dcsQ.r.valid := True
          dcsQ.r.data := B(0)
          dcsQ.r.id := readCmd.id
          dcsQ.r.setOKAY()
          dcsQ.r.last := pktBufReadLen === 1
        } otherwise {
          pktBufAxi.r.ready := dcsQ.r.ready
          dcsQ.r.payload := pktBufAxi.r.payload
          dcsQ.r.valid := pktBufAxi.r.valid
        }

        when (dcsQ.r.fire) {
          when (pktBufReadLen === 1) {
            assert(dcsQ.r.last, "no more packet buffer to read but last not set")
            goto(idle)
          }
          pktBufReadLen := pktBufReadLen - 1
        }
      }
    }
  }
}