package pionic.host.eci

import pionic.{ConfigDatabase, PacketAddr}
import pionic.host.HostReq
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
case class DcsRxAxiRouter(axiConfig: Axi4Config)(implicit c: ConfigDatabase) extends Component {
  assert(axiConfig.dataWidth == 512, "only supports 512b bus from DCS AXI interface")
  assert(c.rxPktBufSizePerCore % 64 == 0, "pkt buffer size (B) should be multiple of 64")

  /** Incoming RX descriptors from scheduler.
    *
    * Note: [[rxDesc.ready]] carries special meaning.  The scheduler will not present a
    * request (i.e. assert [[rxDesc.valid]]), unless ready is high.  The router module
    * should not wait for valid as a trigger.
    */
  val rxDesc = slave(Stream(HostReq()))

  /** Number of cycles to block for before returning NACK. */
  val blockCycles = in UInt(c[Int]("reg width") bits)

  /** Pulse: the host just started a new request on a CL */
  val hostReq = out Vec(Bool(), 2)

  /** Current control cache line index.  Used to determine if the host is reading
    * the same CL, for example due to a conflict miss
    */
  val currCl = in UInt(1 bit)

  /** Pulse: just sent back a NACK.  Will be repeated if the host reloaded the same CL.  */
  val nackSent = out Bool()

  /** Per-core AXI interface from DCS (already demuxed by ECI interface) */
  val dcsAxi = slave(Axi4(axiConfig))

  /** Forwarded requests to global packet buffer (starts at 0) */
  val pktBufAxi = master(Axi4(axiConfig.copy(useId = false)))

  checkEciAxiCmd(dcsAxi)

  // ad-hoc queue size to not block AR channel
  val dcsAr = dcsAxi.ar.queue(8)
  val dcsR = dcsAxi.r

  // initialization to avoid latches
  hostReq.foreach(_ := False)
  nackSent := False
  rxDesc.setBlocked()
  dcsAxi.setBlocked()
  pktBufAxi.setIdle()

  // command saved from DCS in AR
  val readCmd: Axi4Ar = Reg(dcsAxi.ar.payload.clone)

  // do not read from the packet buffer if current CL served with NACK
  val noReadPktBuf = Reg(Bool()) init False
  val lastPktBufSlot = Reg(PacketAddr())

  // offset and length to read from the pkt buffer
  val pktBufReadOff = Reg(dcsAxi.ar.addr.clone)
  val pktBufReadLen = Reg(UInt(log2Up(c.rxPktBufSizePerCore) bits))

  // timer for blocking requests
  val blockTimer = Counter(blockCycles.getWidth bits)

  // buffered first half CL for responding to host reloads on the same CL
  val savedControl = Reg(Bits(512 bits)) init 0

  val fsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsAr.freeRun()
        blockTimer.clear()
        when (dcsAr.valid) {
          readCmd := dcsAr.payload
          goto(decodeAr)
        }
      }
    }
    val decodeAr: State = new State {
      whenIsActive {
        when (readCmd.addr === 0x0 || readCmd.addr === 0x80) {
          // host reading the first half CL in either first or second CL
          pktBufReadOff := 0x0
          pktBufReadLen := 0x40

          when (readCmd.addr === 0x80 || readCmd.addr === 0x0) {
            when (readCmd.addr === (1 - currCl) * 0x80) {
              // reading opposite CL, try popping a descriptor
              hostReq(1 - currCl) := True
            }
            goto(waitDesc)
          }
        } otherwise {
          // host reading second half of some CL
          // XXX: we assume the first half-CL is always read first
          //      so we can ignore any state change and just serve packet buffer contents
          pktBufReadOff := readCmd.addr - 0xc0
          pktBufReadLen := 0x80
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
          goto(sendDesc)
        }
      }
    }
    val sendDesc: State = new State {
      whenIsActive {
        // send first beat, could be NACK
        dcsR.data := savedControl
        dcsR.valid := True
        dcsR.setOKAY()
        dcsR.last := False
        when (dcsR.ready) {
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
          pktBufAxi.ar.addr := pktBufReadOff + lastPktBufSlot.bits
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
          dcsR.valid := True
          dcsR.data := B(0)
          dcsR.setOKAY()
          dcsR.last := pktBufReadLen === 64
        } otherwise {
          pktBufAxi.r.ready := dcsR.ready
          dcsR.payload := pktBufAxi.r.payload
          dcsR.valid := pktBufAxi.r.valid
        }

        when (dcsR.fire) {
          pktBufReadLen := pktBufReadLen - 64
          when (pktBufReadLen === 0) {
            assert(dcsR.last, "no more packet buffer to read but last not set")
            goto(idle)
          }
        }
      }
    }
  }
}