package pionic.host.eci

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
  * @param descType type of packet descriptor from scheduler
  * @param axiConfig AXI parameters of upstream and downstream nodes
  * @param pktBufSizePerCore size in bytes of per-core buffer (inside global packet buffer)
  * @param regWidth width of register (for `blockCycles`)
  */
case class DcsRxAxiRouter[T <: Data](descType: HardType[Stream[T]],
                                     axiConfig: Axi4Config,
                                     pktBufSizePerCore: Int,
                                     regWidth: Int,
                                    ) extends Component {
  assert(axiConfig.dataWidth == 512, "only supports 512b bus from DCS AXI interface")
  assert(pktBufSizePerCore % 64 == 0, "pkt buffer size (B) should be multiple of 64")

  /** Incoming RX descriptors from scheduler. */
  val rxDesc = slave(descType())

  /** Number of cycles to block for before returning NACK. */
  val blockCycles = in UInt(regWidth bits)

  /** Pulse: just started a new request on a CL */
  val hostReq  = out Vec(Bool(), 2)

  /** Pulse: just sent back a NACK */
  val nackSent = out Bool()

  /** Per-core AXI interface from DCS (already demuxed by ECI interface) */
  val dcsAxi = slave(Axi4(axiConfig))

  /** Forwarded requests to global packet buffer (starts at 0) */
  val pktBufAxi = master(Axi4(axiConfig.copy(useId = false)))

  when (dcsAxi.ar.valid) {
    // must match that of desc_to_axi/axi_rd_cl.sv
    // 128B INCR bursts (2 beats on the 512b bus)
    assert(dcsAxi.ar.size  === U("110"),     "only support 64B in each beat")
    assert(dcsAxi.ar.burst === B("01"),      "only support INCR bursts")
    assert(dcsAxi.ar.len   === 1,            "only support 2 beats in each burst")
    assert(dcsAxi.ar.addr(6 downto 0) === 0, "only support 128B-aligned addresses")
  }

  // ad-hoc queue size to not block AR channel
  val dcsAr = dcsAxi.ar.queue(8)
  val dcsR = dcsAxi.r

  // initialization to avoid latches
  hostReq.foreach(_ := False)
  nackSent := False
  dcsAr.setBlocked()
  dcsR.setIdle()
  pktBufAxi.ar.setIdle()
  pktBufAxi.r.setBlocked()
  rxDesc.setBlocked()

  // command saved from DCS in AR
  val dcsCmd: Axi4Ar = Reg(dcsAxi.ar.payload.clone)

  // result of parsing address
  val controlID = Reg(Bits(1 bit))

  // where and how much do we need to read from the pkt buffer
  val pktBufReadAddr = Reg(dcsAxi.ar.addr.clone)
  val pktBufReadLen = Reg(UInt(log2Up(pktBufSizePerCore) bits))

  // timer for blocking requests
  val blockTimer = Counter(blockCycles.getWidth bits)

  val fsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsAr.freeRun()
        blockTimer.clear()
        when (dcsAr.valid) {
          dcsCmd := dcsAr.payload
          goto(decodeAr)
        }
      }
    }
    val decodeAr: State = new State {
      whenIsActive {
        dcsAr.ready := True

        when (dcsCmd.addr === 0x0 || dcsCmd.addr === 0x80) {
          controlID := dcsCmd.addr === 0x80
          pktBufReadAddr := 0x0
          pktBufReadLen := 0x40
          hostReq(controlID.asUInt) := True
          goto(waitDesc)
        } otherwise {
          pktBufReadAddr := dcsCmd.addr - 0xc0
          pktBufReadLen := 0x80
          goto(readPktBuf)
        }
      }
    }
    val waitDesc: State = new State {
      whenIsActive {
        // block up to blockCycles
        blockTimer.increment()
        when (blockTimer >= blockCycles || rxDesc.valid) {
          // timer expired or cancelled, respond NACK, or
          // got request
          goto(sendDesc)
        }
      }
    }
    val sendDesc: State = new State {
      whenIsActive {
        // send first beat, could be NACK
        dcsR.data := rxDesc.payload ## rxDesc.valid
        dcsR.valid := True
        when (dcsR.ready) {
          rxDesc.ready := True
          nackSent := !rxDesc.valid
          goto(readPktBuf)
        }
      }
    }
    val readPktBuf: State = new State {
      whenIsActive {
        // send read request to pkt buf axi
        pktBufAxi.ar.valid := True
        pktBufAxi.ar.len   := pktBufReadLen - 1
        pktBufAxi.ar.addr  := pktBufReadAddr
        pktBufAxi.ar.burst := B("01")
        pktBufAxi.ar.size  := B("110")
        when (pktBufAxi.ar.ready) {
          goto(sendData)
        }
      }
    }
    val sendData: State = new State {
      whenIsActive {
        pktBufAxi.r.ready := dcsR.ready
        dcsR.payload := pktBufAxi.r.payload
        when (pktBufAxi.r.fire) {
          pktBufReadLen := pktBufReadLen - 64
          when (pktBufReadLen === 0) {
            goto(idle)
          }
        }
      }
    }
  }
}