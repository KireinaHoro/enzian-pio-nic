package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.eci.sim.{DcsAppMaster, IpiSlave}
import jsteward.blocks.misc.sim.{IntRicherEndianAware, BigIntRicher}
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

  def baseAddr: Long = if (td.tid == -1) 0L else
    EciAddressMap.encode(NUM_WORKER_CORES.get, td.runningOn.get.cid - 1, td.proc.domainCode).toLong

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
    .workspaceName(getClass.getSimpleName)
    .compile(lauberhorn.GenEngineVerilog.engine(numWorkerCores, "eci"))

  var schedulingIrqs = 0
  var domainFaultHandler: () => Unit = () => fail("unexpected fatal domain interrupt")
  private val contextReadMutex = SimMutex()
  // Test/debug readback only: the managed application has no metadata query API.
  def requestXid(csr: AxiLite4Master, token: BigInt): Int = {
    contextReadMutex.lock()
    val regs = ALLOC.readBack("OncRpcReplyEncoder")
    csr.write(regs("stat", "context_read_idx"), (token.toInt & (NUM_SESSIONS.get - 1)).toBytesLE)
    val active = csr.read(regs("stat", "context_active"), 8).bytesToBigInt
    val actual = csr.read(regs("stat", "context_token"), 8).bytesToBigInt
    assert(active == 1 && actual == token, s"invalid live context $token: $active/$actual")
    val xid = csr.read(regs("stat", "context_xid"), 8).bytesToBigInt.toInt
    contextReadMutex.unlock()
    xid
  }

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val eciIf = dut.host[EciInterfacePlugin].logic.get
    dut.host[EciInterfacePlugin].hostIfCtrl.gsyncRelease #= false
    val csrMaster = AxiLite4Master(eciIf.s_axil_ctrl, dut.clockDomain)
    val dcsAppMaster = DcsAppMaster(eciIf.dcsEven, eciIf.dcsOdd, dut.clockDomain,
      readGetsExclusive = true)

    IpiSlave(eciIf.ipiToIntc, dut.clockDomain) { case (coreId, intId) =>
      println(s"Received IRQ #$intId for core $coreId")
      if (intId == 8) schedulingIrqs += 1
      if (intId == 9) { assert(coreId == 0); domainFaultHandler() }
      else genericIrqCb(csrMaster, cid = coreId, irq = intId)
    }

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup

    dut.clockDomain.forkStimulus(hzToLong(200 MHz), sleepDuration = 100, resetCycles = 0)

    csrMaster.write(ALLOC.readBack("bypassIrq")("irqEn"), 1.toBytesLE)
    CSRSim.csrSanityChecks(csrMaster, rxBlockCycles)

    val bypassThread = ThreadDef(-1) // kmod actually uses 0 for bypass
    threads(-1) = bypassThread

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
    def switchToThreadImpl(threadDef: ThreadDef, rx: Int, tx: Int): Unit = {
      val etd = getEciThreadData(threadDef.tid)
      etd.rxNextCl = rx
      etd.txNextCl = tx
    }
    def yieldAssignment(): Unit = cooperativeYield(csrMaster, cid)
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
  def tryReadPacketDesc(dcsMaster: DcsAppMaster, tid: Int, maxTries: Int = 20)(implicit dut: NicEngine): TailRec[Option[(EciHostCtrlInfoSim, RxPayloadDesc)]] = {
    if (maxTries == 0) done(None)
    else {
      val etd = getEciThreadData(tid)
      val descheduled = !hasAssignment(tid)
      if (descheduled) {
        return done(None)
      }

      val coreBase = etd.baseAddr
      val clAddr = etd.rxNextCl * 0x80 + ECI_RX_BASE.get + coreBase
      val pldDesc = RxPayloadDesc(etd.rxNextCl, coreBase + ECI_RX_BASE.get)
      etd.log(f"Reading packet desc at $clAddr%#x, $maxTries times left...")
      // read ctrl in first
      val control = dcsMaster.read(clAddr, 64).bytesToBigInt
      // always toggle cacheline
      etd.flipRx()
      val ret = if ((control & 3) == 2) {
        assert(tid != -1, "DESCHED on bypass")
        etd.td.runningOn.get.asInstanceOf[EciWorkerCoreState].yieldAssignment()
        done(None)
      } else if ((control & 1) == 0) {
        sleepCycles(20)

        tailcall(tryReadPacketDesc(dcsMaster, tid, maxTries - 1))
      } else {
        // got packet!


        done(Some((EciHostCtrlInfoSim.fromBigInt(control >> 1), pldDesc)))
      }

      ret
    }
  }

  /** Test-side assignment guard; there is no transport control cache line. */
  def hasAssignment(tid: Int): Boolean = tid == -1 || threads(tid).runningOn.nonEmpty

  def readPayload(dcsMaster: DcsAppMaster, pldDesc: RxPayloadDesc, len: Int): List[Byte] = {
    if (len == 0) Nil
    else dcsMaster.read(if (len <= 64) pldDesc.firstReadAddr else pldDesc.overflowAddr, len)
  }

  /** Send one descriptor, optionally with a tail payload. */
  def txSendSingle(dcsMaster: DcsAppMaster, txDesc: EciHostCtrlInfoSim, toSend: List[Byte], tid: Int, expectedStatus: Int = 1, forceOverflow: Boolean = false, reservationCapacity: Int = 0, dirtyReservation: Boolean = false)(implicit dut: NicEngine): Unit = {
    assert(hasAssignment(tid), "TX requires a current assignment")
    val etd = getEciThreadData(tid)
    val coreBase = etd.baseAddr
    def clAddr = etd.txNextCl * 0x80 + ECI_TX_BASE.get + coreBase

    etd.log(f"sending packet with desc $txDesc, writing packet desc to $clAddr%#x...")
    // Legacy test constructors carry RPC prefix bytes separately; the wire
    // representation now places the complete argument stream in one region.
    val bytes = txDesc match {
      case r: TxOncRpcReplySim => spinal.core.sim.SimBigIntPimper(r.args).toBytes(ONCRPC_INLINE_BYTES * 8).toList.take(r.len) ++ toSend
      case r: TxOncRpcCallSim => spinal.core.sim.SimBigIntPimper(r.args).toBytes(ONCRPC_NESTED_CALL_INLINE_BYTES * 8).toList.take(r.len) ++ toSend
      case _ => toSend
    }
    val capacity = Seq(bytes.length, scala.math.min(txDesc.len, MTU.get), reservationCapacity).max
    val inline = capacity <= 64 && !forceOverflow && !txDesc.isInstanceOf[TxExecutionSim]
    val control = txDesc.toTxDesc.toArray
    control(2) = ((control(2) & 0x7f) | (if (inline) 0x80 else 0)).toByte
    dcsMaster.write(clAddr, control.toList)
    if (capacity > 0) dcsMaster.write(
      if (inline) clAddr + 0x40 else ECI_TX_BASE.get + ECI_OVERFLOW_OFFSET + coreBase, bytes ++ (if (dirtyReservation) List.fill(capacity - bytes.length)(0xa5.toByte) else Nil))

    // trigger a read on the next cacheline to actually send the packet
    etd.log(f"sent packet at $clAddr%#x")

    etd.flipTx()
    val status = dcsMaster.read(clAddr, 1).head & 0xff
    assert(status == expectedStatus, s"TX admission status $status, expected $expectedStatus")

  }
}
