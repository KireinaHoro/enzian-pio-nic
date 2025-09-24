package lauberhorn.host.eci

import jsteward.blocks.eci.EciCmdDefs
import lauberhorn._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4ReadOnlyErrorSlave, Axi4SlaveFactory, Axi4WriteOnlyErrorSlave}
import spinal.lib.fsm._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.bus.regif.AccessType.RO
import jsteward.blocks.misc.RegBlockAlloc
import jsteward.blocks.eci.EciIntcInterface
import lauberhorn.host.PreemptionService
import Global._
import spinal.lib.bus.amba4.axilite.AxiLite4Utils.AxiLite4Rich
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}

import scala.language.postfixOps

/**
  * Backing storage for preemption control cacheline.  Fits the following information to pass to host:
  *  - READY & BUSY bits
  */
case class PreemptionControlCl() extends Bundle {
  val busy = Bool()
  val ready = Bool()
}

/**
  * Extra information to pass to kernel.  This register will be read by kernel; the read also functions
  * as an ACK for the IPI.  Information included here:
  *  - RX & TX parity bits
  *  - whether the process was force-killed
  *  - next PID
  */
case class IpiAckReg() extends Bundle {
  // TODO: Mackerel def
  val rxParity, txParity = Bool()
  val killed = Bool()
  val xb5 = Bits(5 bits)
  val pid = PID()
}

object EciPreemptionControlPlugin {
  // called for driving the non-existent preemption control for core#0
  def bypassDriveControl(irqEn: Bool)(bus: AxiLite4, alloc: RegBlockAlloc) = {
    val busCtrl = AxiLite4SlaveFactory(bus)

    alloc("realCoreId")
    alloc("ipiAck", attr = RO, readSensitive = true)

    // generate IRQ enable reg for bypass
    val irqEnAddr = alloc("irqEn")
    busCtrl.driveAndRead(irqEn, irqEnAddr) init False
  }
}

/**
  * Preemption control plugin for ECI.  Interfaces with [[EciInterfacePlugin]] to issue interrupts to CPU as IPI.
  *
  * READY/BUSY flags are implemented in a **preemption control cacheline** that's normally pinned in the L2 cache.
  * We take parity flags as input, to return them inside this cacheline as well.
  */
class EciPreemptionControlPlugin(val coreID: Int) extends PreemptionService {
  withPrefix(s"worker_${coreID - 1}")

  lazy val preemptCritSecTimeout = host[EciInterfacePlugin].preemptCritSecTimeout

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc) = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    val ipiAckAddr = alloc("ipiAck", attr = RO, readSensitive = true)
    busCtrl.read(logic.ipiAck, ipiAckAddr)

    busCtrl.onRead(ipiAckAddr) {
      logic.ipiAckReadReq := True
    }

    busCtrl.readAndWrite(logic.realCoreId, alloc("realCoreId"))
    busCtrl.driveAndRead(logic.irqEn, alloc("irqEn")) init False
  }

  val requiredAddrSpace = 0x80

  // we have same address space view as the core control (for LCI/LCIA/UL, and AXI)
  val controlClAddr = 0x10000
  ECI_PREEMPT_CTRL_OFFSET.set(controlClAddr)

  assert(coreID != 0, "bypass core does not need preemption control!")

  val logic = during setup new Area {
    val proto = host.list[EciDecoupledRxTxProtocol].apply(coreID)

    // DCS interfaces
    val lci = Stream(EciCmdDefs.EciAddress)
    val lcia = Stream(EciCmdDefs.EciAddress)
    val ul = Stream(EciCmdDefs.EciAddress)

    lci.assertPersistence()
    ul.assertPersistence()

    // muxed interface to ECI interrupt controller
    val ipiToIntc = Stream(EciIntcInterface())

    lci.valid := False
    lci.payload := controlClAddr

    ul.payload := controlClAddr
    ul.valid := False

    lcia.setBlocked()
    when (lcia.valid) {
      assert(lcia.payload === controlClAddr, "LCIA does not match control address!")
    }

    val preemptCtrlCl = Reg(PreemptionControlCl())
    // CPU is not in critical section at the very start
    preemptCtrlCl.busy init False
    // We are not issuing a preemption request at the start
    preemptCtrlCl.ready init True

    assert(requiredAddrSpace >= preemptCtrlCl.getBitsWidth, "not all information fits in the preemption control CL")

    // Preemption request from scheduler.  When issued (valid === True), the scheduler
    // must have:
    // - blocked off new requests from entering the granted buffer (i.e. the
    //   datapath will not receive a new packet)
    // - updated queue mapping to pop from new PID queue
    //
    // When [[preemptReq]] is acknowledged (valid && ready === True), the kernel would have
    // signalled that they finished all steps and will immediately return to user space.
    // The scheduler can then allow new requests into the granted buffer.
    val preemptReq = Stream(PID())
    preemptReq.setBlocked()

    val ipiAck = Reg(IpiAckReg())
    val ipiAckReadReq = CombInit(False)

    awaitBuild()

    // Parity bits are served to the kernel in the preemption control cacheline.  They
    // are also accessible directly as register reads (exposed originally for debugging)
    // in [[EciDecoupledRxTxProtocol]]; exposing them in the ACK reg will save one extra
    // I/O roundtrip
    ipiAck.rxParity := proto.logic.rxCurrClIdx
    ipiAck.txParity := proto.logic.txCurrClIdx
    // TODO: drive killed with counter output
    ipiAck.killed := False
    ipiAck.xb5 := 0
    ipiAck.pid := preemptReq.payload

    ipiToIntc.cmd := 0
    // 8 to 15 are allowed
    // FIXME: should we use a different interrupt ID for killing a proc?
    ipiToIntc.intId := 8

    // affLvl0 is a bit mask, but we only send to one at a time
    // FIXME: can we eliminate this calculation? i.e. use a fixed core offset
    val coreIDWidth = log2Up(MAX_CORE_ID)
    val realCoreId = Reg(UInt(coreIDWidth bits)) init coreID
    ipiToIntc.affLvl0 := UIntToOh(realCoreId(3 downto 0))
    ipiToIntc.affLvl1 := realCoreId(coreIDWidth - 1 downto 4).asBits.resized
    ipiToIntc.valid := False

    val irqEn = Bool()

    // Preemption request to forward to the datapath.  Issued AFTER clearing READY bit
    // to ACK the pending packet (if any) and drop ctrl (& data, if any) CLs from L2 cache
    val rxProtoPreemptReq = proto.preemptReq
    rxProtoPreemptReq.setIdle()

    // Timer for CPU to exit critical section (unset BUSY), before the FPGA kills the
    // process (sets killed === True) before sending IPI
    val preemptTimer = Counter(REG_WIDTH bits)

    val fsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          preemptTimer.clear()
          // only start preemption when IRQ is enabled
          when (preemptReq.valid && irqEn) {
            lci.valid := True
            when (lci.ready) {
              goto(unsetReady)
            }
          }
        }
      }
      val unsetReady: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.valid) {
            preemptCtrlCl.ready := False
            goto(unlockCheckBusy)
          }
        }
      }
      val unlockCheckBusy: State = new State {
        whenIsActive {
          preemptTimer.increment()
          ul.valid := True
          when (ul.ready) {
            when (preemptCtrlCl.busy) {
              // busy when we unset ready
              when (preemptTimer >= preemptCritSecTimeout) {
                // timer has expired -- kill
                ipiAck.killed := True
                goto(issueIpi)
              } otherwise {
                // timer has not expired -- poll again
                goto(readBusyReq)
              }
            } otherwise {
              // busy already low but locked, we can issue IPI
              goto(issueIpi)
            }
          }
        }
      }
      val readBusyReq: State = new State {
        whenIsActive {
          preemptTimer.increment()
          lci.valid := True
          when (lci.ready) {
            goto(readBusy)
          }
        }
      }
      val readBusy: State = new State {
        whenIsActive {
          preemptTimer.increment()
          lcia.freeRun()
          when (lcia.valid) {
            goto(unlockCheckBusy)
          }
        }
      }
      val issueIpi: State = new State {
        whenIsActive {
          ipiToIntc.valid := True
          when (ipiToIntc.ready) {
            goto(ipiWaitAck)
          }
        }
      }
      val ipiWaitAck: State = new State {
        whenIsActive {
          when (ipiAckReadReq) {
            // we can only trigger data path preemption once we are sure we are
            // in the kernel, or the old user thread might have a chance to
            // corrupt the clean state (e.g. sneak covert data in)
            goto(preemptDataPath)
          }
        }
      }
      val preemptDataPath: State = new State {
        whenIsActive {
          rxProtoPreemptReq.valid := True
          when (rxProtoPreemptReq.ready) {
            // set ready, which the kernel will poll to be 1 before it returns
            goto(setReadyReq)
          }
        }
      }
      val setReadyReq: State = new State {
        whenIsActive {
          lci.valid := True
          when (lci.ready) {
            goto(setReady)
          }
        }
      }
      val setReady: State = new State {
        whenIsActive {
          lcia.freeRun()
          when (lcia.valid) {
            preemptCtrlCl.ready := True
            goto(unlockToIdle)
          }
        }
      }
      val unlockToIdle: State = new State {
        whenIsActive {
          ul.valid := True
          when (ul.ready) {
            preemptReq.ready := True
            goto(idle)
          }
        }
      }
    }
  }

  override def preemptReq: Stream[PID] = logic.preemptReq
  def driveDcsBus(bus: Axi4, lci: Stream[Bits], lcia: Stream[Bits], ul: Stream[Bits]): Unit = new Area {
    val busCtrl = Axi4SlaveFactory(bus)
    busCtrl.readAndWrite(logic.preemptCtrlCl, controlClAddr)

    lci  << logic.lci
    ul   << logic.ul
    lcia >> logic.lcia
  }.setCompositeName(this, "driveDcsBus")
}
