package lauberhorn.host.eci

import jsteward.blocks.axi.RichAxi4
import jsteward.blocks.eci.EciCmdDefs
import lauberhorn.Global.ECI_TX_BASE
import lauberhorn.{Global, PacketAddr, PacketLength}
import lauberhorn.host.HostReq
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

  /** Whether a preemption happened.  We need to drop [[savedControl]] on preemption to
    * prevent a leak, when the new thread attempts to read back the packet sent by the
    * old thread.
    */
  val doPreempt = in Bool()

  /** Address to put the outgoing packet payload in the packet buffer.  Captured
    * from [[lauberhorn.host.DatapathPlugin.hostTx]] */
  val txAddr = in(PacketAddr())

  /** Length of the packet to send, as captured in real time */
  val currInvLen = out(PacketLength())

  /** Pulse: all dirty CLs have been flushed */
  val invDone = in Bool()

  /** Pulse: the host just started a new request on a CL */
  val hostReq = out(Vec(Bool(), 2)).setAsReg().initZero()

  checkEciAxiCmd(dcsAxi)

  val dcsQ = dcsAxi.queue(8)

  val readCmd: Axi4Ar = Reg(dcsAxi.ar.payload.clone)
  val readAddr = readCmd.addr - ECI_TX_BASE.get
  val writeCmd: Axi4Aw = Reg(dcsAxi.aw.payload.clone)
  val writeAddr = writeCmd.addr - ECI_TX_BASE.get

  // initialization to avoid latches
  dcsQ.setBlocked()
  pktBufAxi.setIdle()
  txDesc.setIdle()

  // save the control separately to allow re-reads from host
  // packet data will be read from packet buffer
  val savedControl = Reg(Bits(512 bits)) init 0
  val aliasedHostCtrl = EciHostCtrlInfo()
  aliasedHostCtrl.assignFromBits(savedControl >> 1)
  currInvLen := aliasedHostCtrl.len
  
  when (doPreempt) {
    savedControl.clearAll()
  }

  // invalidation can finish before we enter waitInv, store it here
  val invFinished = Reg(Bool()) init False
  invFinished.setWhen(invDone)

  // offset and size to read from packet buffer, to serve CL fetch
  val pktBufReadOff = Reg(pktBufAxi.ar.addr.clone)

  // we read max 2 beats each round, will fit inside one AXI burst
  // NOTE: this counts number of BEATS left, not BYTES
  val pktBufReadBeats = Reg(UInt(2 bits))

  // offset and size to write to packet buffer
  // NOTE: len has same meaning in read
  val pktBufWriteOff = Reg(pktBufAxi.aw.addr.clone)
  val pktBufWriteBeats = Reg(UInt(2 bits))

  val writeFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsQ.aw.freeRun()
        when (dcsQ.aw.valid) {
          writeCmd := dcsQ.aw.payload
          goto(decodeCmd)
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
          pktBufWriteOff := (writeAddr - 0xc0).resized
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
        pktBufAxi.aw.len := (pktBufWriteBeats - 1).resized
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
        pktBufAxi.w << dcsQ.w

        when (pktBufAxi.w.fire && pktBufAxi.w.last) {
          goto(writePktBufResp)
        }
      }
    }
    val writePktBufResp: State = new State {
      whenIsActive {
        pktBufAxi.b >> dcsQ.b
        when (dcsQ.b.fire) {
          assert(pktBufAxi.b.isOKAY(), "error BRESP from packet buffer")
          goto(idle)
        }
      }
    }
  }
  writeFsm.build()

  val readFsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        dcsQ.ar.freeRun()
        hostReq.foreach(_ := False)
        invFinished.clear()
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
          pktBufReadOff := (readAddr - 0xc0).resized
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
          goto(readPktBuf)
        }
      }
    }
    val readPktBuf: State = new State {
      whenIsActive {
        // no need to filter read (as in DcsRxAxiRouter), since there's only
        // one tx buffer
        pktBufAxi.ar.valid := True
        pktBufAxi.ar.len := (pktBufReadBeats - 1).resized
        pktBufAxi.ar.addr := pktBufReadOff + txAddr.bits.resized
        pktBufAxi.ar.id := readCmd.id
        pktBufAxi.ar.setFullSize()
        pktBufAxi.ar.setBurstINCR()
        when (pktBufAxi.ar.ready) {
          goto(sendPktData)
        }
      }
    }
    val sendPktData: State = new State {
      whenIsActive {
        pktBufAxi.r >> dcsQ.r
        when (pktBufAxi.r.fire) {
          assert(pktBufAxi.r.isOKAY(), "error RRESP from packet buffer")
          assert(pktBufAxi.r.last === (pktBufReadBeats === 1), "packet buffer read last not match")
          when (pktBufAxi.r.last) {
            goto(idle)
          }
          pktBufReadBeats := pktBufReadBeats - 1
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
          // we are in the middle of a read for next CL -- serve read
          goto(sendDesc)
        }
      }
    }
  }
  readFsm.build()

  val readStateOut = out(readFsm.stateReg.clone)
  readStateOut := readFsm.stateReg
  val writeStateOut = out(writeFsm.stateReg.clone)
  writeStateOut := writeFsm.stateReg
}
