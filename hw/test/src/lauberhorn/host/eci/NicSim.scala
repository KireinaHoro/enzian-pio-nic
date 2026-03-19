package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.eci.sim.{DcsAppMaster, IpiSlave}
import jsteward.blocks.misc.sim.IntRicherEndianAware
import lauberhorn.Global._
import lauberhorn._
import lauberhorn.sim._
import spinal.core.sim._
import spinal.core.{BigIntToSInt => _, BigIntToUInt => _, _}
import spinal.lib._
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master

import scala.collection.mutable
import scala.language.postfixOps
import scala.util.control.TailCalls._

class EciThreadData(val td: ThreadDef) {
  /** 2F2F protocol parity bits */
  var rxNextCl, txNextCl = 0

  def flipRx() = rxNextCl = 1 - rxNextCl
  def flipTx() = txNextCl = 1 - txNextCl

  def baseAddr = td.prefix * ECI_CORE_OFFSET

  def log(msg: String) = td.runningOn match {
    case Some(cs) => cs.log(msg)
    case None => println(s"[thread ${td.tid}]\t$msg")
  }
}

trait NicSim extends DutSimFunSuite[NicEngine]
    with DbFactory
    with TimestampSuiteFactory
    with GenericHostCPUModel {
  // NUM_CORES in Database only available inside test context
  def numWorkerCores = 4

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL -Wno-INITIALDLY")
    // wb2axip flags
    .addSimulatorFlag("-Wno-SIDEEFFECT")
    .workspaceName("eci")
    .compile(lauberhorn.GenEngineVerilog.engine(numWorkerCores, "eci"))

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val eciIf = dut.host[EciInterfacePlugin].logic.get
    val csrMaster = AxiLite4Master(eciIf.s_axil_ctrl, dut.clockDomain)
    val dcsAppMaster = DcsAppMaster(eciIf.dcsEven, eciIf.dcsOdd, dut.clockDomain)

    IpiSlave(eciIf.ipiToIntc, dut.clockDomain) { case (coreId, intId) =>
      println(s"Received IRQ #$intId for core $coreId")
      genericIrqCb(csrMaster, cid = coreId, irq = intId)
    }

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup

    dut.clockDomain.forkStimulus(hzToLong(200 MHz), sleepDuration = 100, resetCycles = 0)

    // enable IRQ for all cores
    0 until NUM_CORES foreach { cid =>
      csrMaster.write(ALLOC.readBack("preempt", cid)("irqEn"), 1.toBytesLE)
    }
    CSRSim.csrSanityChecks(csrMaster, rxBlockCycles)

    val bypassThread = ThreadDef(-1, 0) // kmod actually uses 0 for bypass
    threads(-1) = bypassThread
    bypassCore.switchToThread(bypassThread, csrMaster)

    0 until NUM_WORKER_CORES foreach { wcid =>
      setWorkerCore(wcid, new EciWorkerCoreState(wcid+1, csrMaster))
    }

    eciThreadDataMap.clear()

    (csrMaster, axisMaster, axisSlave, dcsAppMaster)
  }

  /** Set up the DUT and create the DCS and AXI-Lite simulation bus masters and arm an IRQ handler.
   * Calls into [[GenericHostCPUModel]] to simulate software state changes in the kernel.
   */
  /** In addition to [[commonDutSetup]], assert that the TX interface is inactive during RX-only tests.  */
  def rxDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (csrMaster, axisMaster, _, dcsMaster) = commonDutSetup(rxBlockCycles)

    // enable rx already for normal tests -- there is a separate test to see
    // if the rx drop all switch is effective
    csrMaster.write(ALLOC.readBack("macIf")("ctrl", "rxDropAll"), 0.toBytesLE)

    (csrMaster, axisMaster, dcsMaster)
  }

  /** Extra data for each [[ThreadDef]] with ECI-related states, notably CL parity bits */
  val eciThreadDataMap = mutable.HashMap[ThreadDef, EciThreadData]()
  def getEciThreadData(tid: Int) = {
    val td = threads(tid)
    eciThreadDataMap.getOrElseUpdate(td, new EciThreadData(td))
  }

  class EciWorkerCoreState(val cid: Int, csrMaster: AxiLite4Master) extends WorkerCoreState {
    var inCriticalRegion = false

    var canInterrupt = true

    def switchToThreadImpl(threadDef: ThreadDef): Unit = {
      assert(!inCriticalRegion, s"core $cid in critical region of thread ${currThread.get.tid}, cannot switch!")

      val etd = getEciThreadData(threadDef.tid)

      log(s"setting thread parity (RX:${etd.rxNextCl} TX:${etd.txNextCl}) in worker")

      // write our CL parity bits into hardware
      csrMaster.write(ALLOC.readBack("worker", blockIdx = cid)("ctrl", "rxCurrClIdx"), etd.rxNextCl.toBytesLE)
      csrMaster.write(ALLOC.readBack("worker", blockIdx = cid)("ctrl", "txCurrClIdx"), etd.txNextCl.toBytesLE)
    }
  }

  case class RxPayloadDesc(parity: Int, base: Long) {
    val firstReadAddr = base + parity * 0x80 + 0x40
    val overflowAddr = base + ECI_OVERFLOW_OFFSET
  }

  /** Attempt to read one packet descriptor.
    *
    * In the ECI implementation, the first half of the control cacheline is the packet descriptor, while
    * the second half is the inlined payload (if any).  The second return value is to be passed to [[readPayload]].
    */
  def tryReadPacketDesc(dcsMaster: DcsAppMaster, tid: Int, maxTries: Int = 20, exitCS: Boolean = true)(implicit dut: NicEngine): TailRec[Option[(EciHostCtrlInfoSim, RxPayloadDesc)]] = {
    if (maxTries == 0) done(None)
    else {
      val etd = getEciThreadData(tid)
      val coreBase = etd.baseAddr

      val descheduled = !enterCriticalSection(dcsMaster, tid)
      if (descheduled) {
        return done(None)
      }

      val clAddr = etd.rxNextCl * 0x80 + coreBase
      val pldDesc = RxPayloadDesc(etd.rxNextCl, coreBase)
      etd.log(f"Reading packet desc at $clAddr%#x, $maxTries times left...")
      // read ctrl in first
      val control = dcsMaster.read(clAddr, 64).bytesToBigInt
      // always toggle cacheline
      etd.flipRx()
      val ret = if ((control & 1) == 0) {
        sleepCycles(20)
        // we didn't get a packet, exit critical section here irrespective of exitCS
        exitCriticalSection(dcsMaster, tid)

        // pass exitCS so the retry can skip exitCS if needed
        tailcall(tryReadPacketDesc(dcsMaster, tid, maxTries - 1, exitCS))
      } else {
        // got packet!
        if (exitCS) {
          // should only be used, when there's no intention to check the packet data in overflow
          exitCriticalSection(dcsMaster, tid)
        }

        done(Some((EciHostCtrlInfoSim.fromBigInt(control >> 1), pldDesc)))
      }

      ret
    }
  }

  /** Enter the critical-section for preemption for a specific worker thread.
    *
    * This function is called directly inside RX/TX datapath functions ([[tryReadPacketDesc]]
    * and [[txSendSingle]]), also for bypass.  [[tid]] will be -1 for bypass accesses.
    *
    * Returns false when the TID is no longer running.  The outer core loop should then try again
    * under the new thread.
    */
  def enterCriticalSection(dcsMaster: DcsAppMaster, tid: Int, maxAttempts: Int = 20)(implicit dut: NicEngine): Boolean = {
    if (tid != -1) {
      val etd = getEciThreadData(tid)
      def wcs = etd.td.runningOn.get.asInstanceOf[EciWorkerCoreState]

      etd.log(s"entering critical section...")
      val coreBase = etd.baseAddr
      val preemptCtrlAddr = coreBase + ECI_PREEMPT_CTRL_OFFSET

      // CAS READY/BUSY to enter critical region
      var done = false
      var attempts = 0
      while (!done) {
        // if the CPU is no longer running our thread:
        if (etd.td.runningOn.isEmpty) {
          etd.log("we got descheduled")
          return false
        }

        assert(attempts < maxAttempts, s"failed to enter critical section for $maxAttempts times!")
        etd.log(s"reading BUSY/READY (attempt #$attempts)...")

        wcs.canInterrupt = false
        val busyReady = dcsMaster.read(preemptCtrlAddr, 1, doInvIdemptCheck = false).head
        wcs.canInterrupt = true

        assert((busyReady & 0x1) == 0, "BUSY already high!")
        if ((busyReady & 0x2) != 0) {
          // READY is set, set BUSY

          wcs.canInterrupt = false
          done = dcsMaster.casByte(preemptCtrlAddr, busyReady, busyReady | 0x1)
          wcs.canInterrupt = true

          if (!done) {
            etd.log("CAS failed, retrying...")
          }
        } else {
          // otherwise READY is 0, try again
          etd.log("READY is 0, retrying...")

          // rescheduling might be under way
          wcs.waitUser()
        }
        attempts += 1
      }

      etd.log("in critical section")
      wcs.inCriticalRegion = true
      true
    } else true
  }

  def exitCriticalSection(dcsMaster: DcsAppMaster, tid: Int): Unit = {
    if (tid != -1) {
      val etd = getEciThreadData(tid)
      etd.log("exiting critical section...")
      val coreBase = etd.baseAddr
      val preemptCtrlAddr = coreBase + ECI_PREEMPT_CTRL_OFFSET

      // CAS unset BUSY to exit critical region
      // need CAS, otherwise might overwrite READY that the FPGA might have just cleared
      var done = false
      while (!done) {
        val busyReady = dcsMaster.read(preemptCtrlAddr, 1, doInvIdemptCheck = false).head
        assert((busyReady & 0x1) != 0, "BUSY not high!")
        done = dcsMaster.casByte(preemptCtrlAddr, busyReady, busyReady & ~0x1)
        if (!done) {
          etd.log("CAS failed, retrying...")
        }
      }

      etd.log("out of critical section")
      etd.td.runningOn.get.asInstanceOf[EciWorkerCoreState].inCriticalRegion = false
    }
  }
  def readPayload(dcsMaster: DcsAppMaster, pldDesc: RxPayloadDesc, len: Int): List[Byte] = {
    // calculate where are the overflow cachelines
    val firstReadSize = Math.min(len, 64)
    println(s"Reading payload in control CL: $firstReadSize bytes")
    var data = dcsMaster.read(pldDesc.firstReadAddr, firstReadSize)
    if (len > 64) {
      val overflowLen = len - 64
      println(s"Reading payload in overflow CL: $overflowLen bytes")
      data ++= dcsMaster.read(pldDesc.overflowAddr, overflowLen)
    }
    data
  }

  /** Send one descriptor, optionally with a tail payload. */
  def txSendSingle(dcsMaster: DcsAppMaster, txDesc: EciHostCtrlInfoSim, toSend: List[Byte], tid: Int, skipCS: Boolean = false)(implicit dut: NicEngine): Unit = {
    val etd = getEciThreadData(tid)
    val coreBase = etd.baseAddr
    def clAddr = etd.txNextCl * 0x80 + ECI_TX_BASE.get + coreBase

    if (!skipCS) {
      // since we didn't implement killing a process yet, we should never get descheduled during TX
      val descheduled = !enterCriticalSection(dcsMaster, tid)
      assert(!descheduled, "should never get descheduled during TX")
    }

    etd.log(f"sending packet with desc $txDesc, writing packet desc to $clAddr%#x...")
    dcsMaster.write(clAddr, txDesc.toTxDesc)

    val firstWriteSize = if (toSend.size > 64) 64 else toSend.size
    dcsMaster.write(clAddr + 0x40, toSend.take(firstWriteSize))
    if (toSend.size > 64) {
      val overflowAddr = ECI_TX_BASE.get + ECI_OVERFLOW_OFFSET + coreBase
      dcsMaster.write(overflowAddr, toSend.drop(firstWriteSize))
    }

    // trigger a read on the next cacheline to actually send the packet
    etd.log(f"sent packet at $clAddr%#x")

    etd.flipTx()
    dcsMaster.read(clAddr, 1)

    if (!skipCS) {
      exitCriticalSection(dcsMaster, tid)
    }
  }
}
