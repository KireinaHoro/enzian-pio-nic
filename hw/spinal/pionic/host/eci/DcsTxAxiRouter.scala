package pionic.host.eci

import pionic.{Global, PacketAddr, PacketLength}
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
  */
case class DcsTxAxiRouter(dcsConfig: Axi4Config,
                          pktBufConfig: Axi4Config,
                         ) extends Component {
  assert(dcsConfig.dataWidth == 512, "only supports 512b bus from DCS AXI interface")

  /** Outgoing TX descriptors to the encoder pipeline. */
  val txDesc = master(Stream(HostReq()))

  /** Per-core AXI interface from DCS (demuxed; shared with [[DcsRxAxiRouter]]).
    *
    * Note that writes from the host still carries a "valid" lowest bit, to use
    * the same datatype on the CPU.  This bit carries no meaning and will be discarded
    */
  val dcsAxi = slave(Axi4(dcsConfig))

  /** Forwarded requests to global packet buffer (starts at 0) */
  val pktBufAxi = master(Axi4(pktBufConfig.copy(idWidth = dcsConfig.idWidth)))

  /** Current control cache line index.  Used to check if the host evicted the current
    * cache line (e.g. due to a capacity conflict).  Responds to reloads of the current
    * CL with the buffer captured so far.  */
  val currCl = in UInt(1 bit)

  /** Address to put the outgoing packet payload in the packet buffer.  Captured
    * from [[pionic.host.DatapathPlugin.hostTx]] */
  val txAddr = in(PacketAddr())

  /** Length of the packet to send, as captured in real time */
  val currInvLen = out(PacketLength())

  /** Pulse: all dirty CLs have been flushed */
  val invDone = in Bool()

  /** Pulse: the host just started a new request on a CL */
  val hostReq = out Vec(Bool(), 2)

  checkEciAxiCmd(dcsAxi)

  val dcsAr = dcsAxi.ar.queue(8)
  val dcsR = dcsAxi.r

  val dcsAw = dcsAxi.aw.queue(8)
  val dcsW = dcsAxi.w
  val dcsB = dcsAxi.b

  val readCmd: Axi4Ar = Reg(dcsAxi.ar.payload.clone)
  val writeCmd: Axi4Aw = Reg(dcsAxi.aw.payload.clone)

  // initialization to avoid latches
  hostReq.foreach(_ := False)
  dcsAxi.setBlocked()
  pktBufAxi.setIdle()

  // buffer to assemble an outgoing descriptor from host
  val savedControl = Reg(Bits(512 bits)) init 0
  val aliasedHostCtrl = EciHostCtrlInfo()
  aliasedHostCtrl.assignFromBits(savedControl >> 1)
  currInvLen := aliasedHostCtrl.len

  // offset and size to read from packet buffer, to serve CL fetch
  val pktBufReadOff = Reg(pktBufAxi.ar.addr.clone)
  // we read max 2 beats each round, will fit inside one AXI burst
  val pktBufReadLen = Reg(pktBufAxi.ar.len.clone)

  // offset and size to write to packet buffer
  val pktBufWriteOff = Reg(pktBufAxi.aw.addr.clone)
  val pktBufWriteLen = Reg(pktBufAxi.aw.len.clone)

  val writeFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsAw.freeRun()
        when (dcsAw.valid) {
          writeCmd := dcsAw.payload
          goto(decodeCmd)
        }
      }
    }
    val decodeCmd: State = new State {
      whenIsActive {
        when (writeCmd.addr === currCl * 0x80) {
          pktBufWriteOff := 0x0
          pktBufWriteLen := 0x40
          goto(recvPartialDesc)
        } elsewhen (writeCmd.addr === (1 - currCl) * 0x80) {
          report("write cannot happen on the inactive CL")
        } otherwise {
          pktBufWriteOff := (writeCmd.addr - 0xc0).resized
          pktBufWriteLen := 0x80
          goto(writePktBufCmd)
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
        pktBufAxi.aw.len := pktBufWriteLen - 1
        pktBufAxi.aw.addr := pktBufWriteOff + txAddr.bits.resized
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
          pktBufWriteLen := pktBufWriteLen - 64
          when (pktBufWriteLen === 0) {
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

  val readFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsAr.freeRun()
        when (dcsAr.valid) {
          readCmd := dcsAr.payload
          goto(decodeCmd)
        }
      }
    }
    val decodeCmd: State = new State {
      whenIsActive {
        when (readCmd.addr === 0x0 || readCmd.addr === 0x80) {
          pktBufReadOff := 0x0
          pktBufReadLen := 0x40

          val reqCl = (readCmd.addr === 0x80).asUInt
          hostReq(reqCl) := True

          when (reqCl === currCl) {
            goto(sendPartialDesc)
          } otherwise {
            // host reading opposite cache line; protocol will invalidate all cache
            // lines before we can send the descriptor to encoders
            when (invDone) {
              goto(transmitDesc)
            }
          }
        } otherwise {
          // accessing packet buffer via overflow cachelines
          pktBufReadOff := (readCmd.addr - 0xc0).resized
          pktBufReadLen := 0x80

          goto(readPktBufCmd)
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
        pktBufAxi.ar.len := pktBufReadLen - 1
        pktBufAxi.ar.addr := pktBufReadOff + txAddr.bits.resized
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
          pktBufReadLen := pktBufReadLen - 64
          when (pktBufReadLen === 0) {
            assert(dcsR.last, "no more packet buffer to read but last not set")
            goto(idle)
          }
        }
      }
    }
    val transmitDesc: State = new State {
      whenIsActive {
        // send assembled descriptor to encoder pipeline
        // the packet buffer has been fully written from invalidation
        txDesc.valid := True

        // one bit reserved for valid in host side Mackerel file to allow reusing RX HostReq
        val ctrl = EciHostCtrlInfo()
        ctrl.assignFromBits(savedControl >> 1)
        ctrl.unpackTo(txDesc.payload, txAddr)

        when (txDesc.ready) {
          savedControl := 0

          // we are in the middle of a read for next CL -- serve read
          goto(sendPartialDesc)
        }
      }
    }
  }
}
