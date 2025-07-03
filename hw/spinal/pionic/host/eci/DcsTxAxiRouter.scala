package pionic.host.eci

import jsteward.blocks.axi.RichAxi4
import pionic.Global.ECI_TX_BASE
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
  txDesc.assertPersistence()

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
  val hostReq = out(Vec(Bool(), 2)).setAsReg()

  checkEciAxiCmd(dcsAxi)

  val dcsQ = dcsAxi.queue(8)

  val readCmd: Axi4Ar = Reg(dcsAxi.ar.payload.clone)
  val readAddr = readCmd.addr - ECI_TX_BASE.get
  val writeCmd: Axi4Aw = Reg(dcsAxi.aw.payload.clone)
  val writeAddr = writeCmd.addr - ECI_TX_BASE.get

  hostReq.foreach(_ init False)

  // initialization to avoid latches
  dcsQ.setBlocked()
  pktBufAxi.setIdle()
  txDesc.setIdle()

  // buffer to assemble an outgoing descriptor from host
  val savedControl = Reg(Bits(512 bits)) init 0
  val aliasedHostCtrl = EciHostCtrlInfo()
  aliasedHostCtrl.assignFromBits(savedControl >> 1)
  currInvLen := aliasedHostCtrl.len

  // invalidation can finish before we enter waitInv, store it here
  val invFinished = Reg(Bool()) init False
  invFinished.setWhen(invDone)

  // offset and size to read from packet buffer, to serve CL fetch
  val pktBufReadOff = Reg(pktBufAxi.ar.addr.clone)

  // we read max 2 beats each round, will fit inside one AXI burst
  // NOTE: this counts number of BEATS left, not BYTES
  val pktBufReadLen = Reg(pktBufAxi.ar.len.clone)

  // offset and size to write to packet buffer
  // NOTE: len has same meaning in read
  val pktBufWriteOff = Reg(pktBufAxi.aw.addr.clone)
  val pktBufWriteLen = Reg(pktBufAxi.aw.len.clone)

  val writeFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsQ.aw.freeRun()
        invFinished.clear()
        hostReq.foreach(_ := False)
        when (dcsQ.aw.valid) {
          writeCmd := dcsQ.aw.payload
          goto(decodeCmd)
        }
      }
    }
    val decodeCmd: State = new State {
      whenIsActive {
        when (writeAddr === currCl * 0x80) {
          pktBufWriteOff := 0x0
          pktBufWriteLen := 1
          goto(recvPartialDesc)
        } elsewhen (writeAddr === (1 - currCl) * 0x80) {
          report("write cannot happen on the inactive CL")
        } otherwise {
          pktBufWriteOff := (writeAddr - 0xc0).resized
          pktBufWriteLen := 2
          goto(writePktBufCmd)
        }
      }
    }
    val recvPartialDesc: State = new State {
      whenIsActive {
        // the host is writing a control CL: capture write into control CL
        dcsQ.w.ready := True
        when (dcsQ.w.valid) {
          assert(!dcsQ.w.last, "not receiving the last beat yet but last is set")
          savedControl.subdivideIn(8 bits) zip
            dcsQ.w.data.subdivideIn(8 bits) zip
            dcsQ.w.strb.asBools foreach { case ((buf, byte), en) =>
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
        pktBufAxi.w.valid := dcsQ.w.valid
        pktBufAxi.w.data := dcsQ.w.data
        pktBufAxi.w.strb := dcsQ.w.strb
        pktBufAxi.w.last := dcsQ.w.last
        dcsQ.w.ready := pktBufAxi.w.ready

        when (dcsQ.w.fire) {
          when (pktBufWriteLen === 1) {
            assert(dcsQ.w.last, "no more packet buffer to write but last not set")
            goto(writePktBufResp)
          }
          pktBufWriteLen := pktBufWriteLen - 1
        }
      }
    }
    val writePktBufResp: State = new State {
      whenIsActive {
        pktBufAxi.b.ready := dcsQ.b.ready
        dcsQ.b.valid := pktBufAxi.b.valid
        dcsQ.b.setOKAY()
        dcsQ.b.id := writeCmd.id
        when (dcsQ.b.fire) {
          assert(pktBufAxi.b.isOKAY(), "error response from packet buffer")
          goto(idle)
        }
      }
    }
  }

  val readFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsQ.ar.freeRun()
        when (dcsQ.ar.valid) {
          readCmd := dcsQ.ar.payload
          goto(decodeCmd)
        }
      }
    }
    val decodeCmd: State = new State {
      whenIsActive {
        when (readAddr === 0x0 || readAddr === 0x80) {
          pktBufReadOff := 0x0
          pktBufReadLen := 1

          val reqCl = (readAddr === 0x80).asUInt
          hostReq(reqCl) := True

          when (reqCl === currCl) {
            goto(sendPartialDesc)
          } otherwise {
            // host reading opposite cache line; protocol will invalidate all cache
            // lines before we can send the descriptor to encoders
            when (invFinished) {
              goto(transmitDesc)
            }
          }
        } otherwise {
          // accessing packet buffer via overflow cachelines
          pktBufReadOff := (readAddr - 0xc0).resized
          pktBufReadLen := 2

          goto(readPktBufCmd)
        }
      }
    }
    val sendPartialDesc: State = new State {
      whenIsActive {
        dcsQ.r.data := savedControl
        dcsQ.r.valid := True
        dcsQ.r.setOKAY()
        dcsQ.r.id := readCmd.id
        dcsQ.r.last := False
        when (dcsQ.r.ready) {
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
        pktBufAxi.ar.id := readCmd.id
        pktBufAxi.ar.setFullSize()
        pktBufAxi.ar.setBurstINCR()
        when (pktBufAxi.ar.ready) {
          goto(readPktBufData)
        }
      }
    }
    val readPktBufData: State = new State {
      whenIsActive {
        pktBufAxi.r.ready := dcsQ.r.ready
        dcsQ.r.payload := pktBufAxi.r.payload
        dcsQ.r.valid := pktBufAxi.r.valid

        when (dcsQ.r.fire) {
          when (pktBufReadLen === 1) {
            assert(dcsQ.r.last, "no more packet buffer to read but last not set")
            goto(idle)
          }
          pktBufReadLen := pktBufReadLen - 1
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
