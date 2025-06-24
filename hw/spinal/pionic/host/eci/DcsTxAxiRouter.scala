package pionic.host.eci

import pionic.Global
import pionic.host.HostReq
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
  * @param txBufBase base address of the TX buffer slot for this core
  */
case class DcsTxAxiRouter(axiConfig: Axi4Config,
                          txBufBase: Int,
                         ) extends Component {
  assert(axiConfig.dataWidth == 512, "only supports 512b bus from DCS AXI interface")

  /** Outgoing TX descriptors to the encoder pipeline. */
  val txDesc = master(Stream(HostReq()))

  /** Per-core AXI interface from DCS (demuxed; shared with [[DcsRxAxiRouter]]).
    *
    * Note that writes from the host still carries a "valid" lowest bit, to use
    * the same datatype on the CPU.  This bit carries no meaning and will be discarded
    */
  val dcsAxi = slave(Axi4(axiConfig))

  /** Forwarded requests to global packet buffer (starts at 0) */
  val pktBufAxi = master(Axi4(axiConfig.copy(useId = false)))

  /** Current control cache line index.  Used to check if the host evicted the current
    * cache line (e.g. due to a capacity conflict).  Responds to reloads of the current
    * CL with the buffer captured so far.  */
  val currCl = in UInt(1 bit)

  /** Pulse: the host just started a new request on a CL */
  val hostReq = out Vec(Bool(), 2)

  checkEciAxiCmd(dcsAxi)

  val dcsAr = dcsAxi.ar.queue(8)
  val dcsR = dcsAxi.r

  val dcsAw = dcsAxi.aw.queue(8)
  val dcsW = dcsAxi.w
  val dcsB = dcsAxi.b

  val currCmd: Axi4Ax = Reg(dcsAxi.aw.payload.clone)
  val currIsRead = Reg(Bool())

  // initialization to avoid latches
  hostReq.foreach(_ := False)
  dcsAxi.setBlocked()
  pktBufAxi.setIdle()

  // buffer to assemble an outgoing descriptor from host
  val savedControl = Reg(Bits(512 bits)) init 0

  // offset and size to read/write from packet buffer, to serve CL fetch
  val pktBufOff = Reg(dcsAxi.ar.addr.clone)
  val pktBufLen = Reg(UInt(log2Up(Global.ROUNDED_MTU) bits))

  val fsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsAr.freeRun()
        dcsAw.freeRun()

        when (dcsAr.valid) {
          currCmd := dcsAr.payload
          currIsRead := True
        } elsewhen (dcsAw.valid) {
          currCmd := dcsAw.payload
          currIsRead := False
        }
        goto(decodeCmd)
      }
    }
    val decodeCmd: State = new State {
      whenIsActive {
        when (currCmd.addr === 0x0 || currCmd.addr === 0x80) {
          pktBufOff := 0x0
          pktBufLen := 0x40

          when (currIsRead) {
            when (currCmd.addr === (1 - currCl) * 0x80) {
              // reading opposite CL, clear saved control half CL
              hostReq(1 - currCl) := True
              savedControl := 0
            }
            goto(sendPartialDesc)
          } otherwise {
            // write request can only happen on the current cache line
            assert(currCmd.addr === currCl * 0x80, "write cannot happen on the inactive CL")
            goto(recvPartialDesc)
          }
        } otherwise {
          // accessing packet buffer via overflow cachelines
          pktBufOff := currCmd.addr - 0xc0
          pktBufLen := 0x80

          when (currIsRead) {
            goto(readPktBufCmd)
          } otherwise {
            goto(writePktBufCmd)
          }
        }
      }
    }
    val sendPartialDesc: State = new State {
      whenIsActive {
        dcsR.data := savedControl
        dcsR.valid := True
        dcsR.setOKAY()
        dcsR.last := False
        when (dcsR.ready) {
          // send first half cache line length of packet buffer
          goto(readPktBufCmd)
        }
      }
    }
    val readPktBufCmd: State = new State {
      whenIsActive {
        pktBufAxi.ar.valid := True
        pktBufAxi.ar.len := pktBufLen - 1
        pktBufAxi.ar.addr := pktBufOff + txBufBase
        pktBufAxi.ar.setFullSize()
        pktBufAxi.ar.setBurstINCR()
        when (pktBufAxi.ar.ready) {
          goto(readPktBufData)
        }
      }
    }
    val readPktBufData: State = new State {
      whenIsActive {
        pktBufAxi.r.ready := dcsR.ready
        dcsR.payload := pktBufAxi.r.payload
        dcsR.valid := pktBufAxi.r.valid

        when (dcsR.fire) {
          pktBufLen := pktBufLen - 64
          when (pktBufLen === 0) {
            assert(dcsR.last, "no more packet buffer to read but last not set")
            goto(idle)
          }
        }
      }
    }
    val recvPartialDesc: State = new State {
      whenIsActive {
        // the host is writing a control CL: capture write into control CL
        dcsW.ready := True
        when (dcsW.valid) {
          assert(!dcsW.last, "not receiving the last beat yet but last is set")
          savedControl.subdivideIn(8 bits) zip
            dcsW.data.subdivideIn(8 bits) zip
            dcsW.strb.asBools foreach { case ((buf, byte), en) =>
            when (en) { buf := byte }
          }

          goto(writePktBufCmd)
        }
      }
    }
    val writePktBufCmd: State = new State {
      whenIsActive {
        pktBufAxi.aw.valid := True
        pktBufAxi.aw.len := pktBufLen - 1
        pktBufAxi.aw.addr := pktBufOff + txBufBase
        pktBufAxi.aw.setFullSize()
        pktBufAxi.aw.setBurstINCR()
        when (pktBufAxi.aw.ready) {
          goto(writePktBufData)
        }
      }
    }
    val writePktBufData: State = new State {
      whenIsActive {
        pktBufAxi.w.valid := dcsW.valid
        pktBufAxi.w.data := dcsW.data
        pktBufAxi.w.strb := dcsW.strb
        pktBufAxi.w.last := dcsW.last
        dcsW.ready := pktBufAxi.w.ready

        when (dcsW.fire) {
          pktBufLen := pktBufLen - 64
          when (pktBufLen === 0) {
            assert(dcsW.last, "no more packet buffer to write but last not set")
            goto(writePktBufResp)
          }
        }
      }
    }
    val writePktBufResp: State = new State {
      whenIsActive {
        pktBufAxi.b.ready := dcsB.ready
        dcsB.valid := pktBufAxi.b.valid
        dcsB.setOKAY()
        when (dcsB.fire) {
          assert(pktBufAxi.b.isOKAY(), "error response from packet buffer")
          goto(idle)
        }
      }
    }
  }
}
