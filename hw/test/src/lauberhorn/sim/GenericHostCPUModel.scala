package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.{BigIntParser, IntRicherEndianAware}
import lauberhorn.Global.{ALLOC, NUM_CORES, NUM_WORKER_CORES, PID_WIDTH}
import lauberhorn.{AsSimBusMaster, NicEngine}
import org.scalatest.Assertions.fail
import spinal.core.sim.{SimMutex, simRandom, waitUntil}
import spinal.lib.BytesRicher

import scala.collection.mutable

trait CoreState {
  def cid: Int

  var inISR = false
  def log(msg: String) = println(s"[core $cid]\t$msg")

  /** Enter the ISR. */
  def enterISR(): Boolean = {
    if (inISR) {
      log("already in kernel! skipping ISR")
      false
    } else {
      inISR = true
      log("entering kernel")
      true
    }
  }

  /** Finish ISR. */
  def exitISR() = {
    assert(inISR, s"core $cid not in kernel!")
    log("exiting kernel")
    inISR = false
  }

}

case class ThreadDef(tid: Int) {
  /** Track which core we are running on */
  var runningOn: Option[WorkerCoreState] = None
  var proc: ProcDef = null
}
case class ProcDef(pid: Int, threads: Seq[ThreadDef]) {
  var domainCode = -1
  var slot = -1
  def enableAt[B](idx: Int, bus: B)(implicit asMaster: AsSimBusMaster[B]) = {
    assert(domainCode == -1, "domain pool is immutable")
    val ownership = ALLOC.readBack("ownership")
    domainCode = asMaster.read(bus, ownership("allocateDomain"), 8).bytesToBigInt.toInt
    assert(domainCode < (1 << 19))
    slot = idx
    asMaster.write(bus, ownership("domainCode"), domainCode.toBytesLE)
    asMaster.write(bus, ownership("domainSlot"), idx.toBytesLE)
    // Publish the fully initialized, waiting pool; packet allocation starts it.
    asMaster.write(bus, ALLOC.readBack("sched")("ctrl", "proc_pid"), pid.toBytesLE)
    asMaster.write(bus, ALLOC.readBack("sched")("ctrl", "proc_maxThreads"), threads.length.toBytesLE)
    asMaster.write(bus, ALLOC.readBack("sched")("ctrl", "proc_enabled"), 1.toBytesLE)

    asMaster.write(bus, ALLOC.readBack("sched")("ctrl", "proc_idx"), idx.toBytesLE)

    println(f"Enabled PID#$pid%#x with ${threads.length} threads @ table idx $idx")
  }
}

/** Models the CPU-side states of a worker core.  [[cid]] starts from 1
  * as 0 is reserved for the bypass core. */
trait WorkerCoreState extends CoreState {
  assert(cid >= 1, "worker core should have ID > 1")
  assert(cid <= NUM_CORES, s"worker core ID $cid exceeds maximum of $NUM_CORES")

  val dispatchMutex = SimMutex()

  var currThread: Option[ThreadDef] = None
  override def log(msg: String) = {
    currThread match {
      case Some(thr) => println(s"[core $cid tid ${thr.tid}]\t$msg")
      case None => super.log(msg)
    }
  }

  def switchToThread(thr: ThreadDef, rx: Int, tx: Int): Unit = {
    assert(thr.tid != -1, "can't enable the bypass thread on a worker core")
    thr.runningOn = Some(this)

    currThread match {
      case Some(curr) =>
        assert(curr != thr, s"asking core $cid to switch to an already running thread (TID ${thr.tid})!")
        curr.runningOn = None
      case None =>
    }

    log(s"switching to thread ${thr.tid}")

    currThread = Some(thr)

    // impl-specific actions
    switchToThreadImpl(thr, rx, tx)
  }

  /** Install the authoritative activation parities before transport access. */
  protected def switchToThreadImpl(threadDef: ThreadDef, rx: Int, tx: Int): Unit

  /** Wait until the core is running any user thread. */
  def waitUser() = waitUntil(!inISR && currThread.nonEmpty)
}

/** Models the bypass core.  In SW this is not a dedicated core, but the interrupt
  * context of core 0.
  *
  * Must use the [[handler]] function instead of waiting on [[inISR]]!  The handler
  * will directly finish and re-enable IRQ if [[handler]] returned immediately */
trait BypassCoreState extends CoreState {
  val cid = 0

  def handler(): Unit
}

trait GenericHostCPUModel { this: DutSimFunSuite[NicEngine] =>
  // A SimMutex holds simulation threads and must never be shared by suites.
  private val threadDbMtx = SimMutex(randomized = true)
  // needed since NUM_WORKER_CORES is only available in test context
  def numWorkerCores: Int
  def numCores = numWorkerCores + 1

  val coreStates = mutable.Seq.fill[CoreState](numCores)(null)
  def workerCore(wcid: Int) = coreStates(wcid + 1).asInstanceOf[WorkerCoreState]
  def bypassCore = coreStates.head.asInstanceOf[BypassCoreState]
  def setBypassCore(bc: BypassCoreState) = coreStates(0) = bc
  def setWorkerCore(wcid: Int, wc: WorkerCoreState) = coreStates(wcid + 1) = wc

  // set dummy bypass core handler
  setBypassCore { () =>
    println("Bypass handler not initialized, ignoring interrupt")
  }

  val threads = mutable.HashMap[Int, ThreadDef]()
  val processes = mutable.HashMap[Int, ProcDef]()
  def genNewId(existing: Seq[Int]): Int =
    LazyList.continually(simRandom.nextInt(65536)).find(id => !existing.contains(id)).get

  def mkRandomProc(maxThreads: Int) = {
    val ts = Seq.fill(maxThreads) {
      val tid = genNewId(threads.keys.toSeq)
      val thr = ThreadDef(tid)
      threads(thr.tid) = thr
      thr
    }
    val pd = ProcDef(genNewId(processes.keys.toSeq), ts)
    processes(pd.pid) = pd

    pd.threads foreach { thr => thr.proc = pd }

    pd
  }

  /** Both kernel entry paths consume one snapshot with serialized readers. */
  private def activate[B](bus: B, wcs: WorkerCoreState, outgoing: Option[ThreadDef] = None)(implicit asMaster: AsSimBusMaster[B]): Unit = {
    val info = asMaster.read(bus, ALLOC.readBack("preempt", wcs.cid)("preemptInfo"), 8).bytesToBigInt
    val domain = (info & ((BigInt(1) << 19) - 1)).toInt
    val slot = ((info >> 19) & 31).toInt
    val rx = ((info >> 24) & 1).toInt
    val tx = ((info >> 25) & 1).toInt
    val proc = processes.values.find(p => p.slot == slot && p.domainCode == domain).get
    threadDbMtx.lock()
    outgoing.foreach(_.runningOn = None)
    if (outgoing.nonEmpty) wcs.currThread = None
    threadDbMtx.unlock()
    var claimed = false
    while (!claimed) {
      waitUntil(proc.threads.exists(_.runningOn.isEmpty))
      threadDbMtx.lock()
      proc.threads.find(_.runningOn.isEmpty).foreach { thr =>
        wcs.switchToThread(thr, rx, tx)
        claimed = true
      }
      threadDbMtx.unlock()
    }
  }

  def cooperativeYield[B](bus: B, cid: Int)(implicit asMaster: AsSimBusMaster[B]): Unit = {
    val wcs = coreStates(cid).asInstanceOf[WorkerCoreState]
    wcs.dispatchMutex.lock()
    assert(wcs.enterISR())
    // Match the kernel: relinquishment is acknowledged by the read, then the
    // old thread is published before waiting for a destination claim.
    activate(bus, wcs, wcs.currThread)
    wcs.exitISR()
    wcs.dispatchMutex.unlock()
  }

  def genericIrqCb[B](bus: B, irq: Int, cid: Int)(implicit asMaster: AsSimBusMaster[B]): Unit = {
    if (irq == 15) {
      assert(cid == 0)
      val regs = ALLOC.readBack("bypassIrq")
      val bypass = bypassCore
      if (!bypass.enterISR()) return
      asMaster.write(bus, regs("irqEn"), 0.toBytesLE)
      bypass.handler()
      bypass.exitISR()
      asMaster.write(bus, regs("irqEn"), 1.toBytesLE)
    } else if (irq == 8) {
      val wcs = coreStates(cid).asInstanceOf[WorkerCoreState]
      wcs.dispatchMutex.lock()
      // Duplicate or delayed kicks cannot cause another side-effecting read.
      if (wcs.currThread.isEmpty &&
          asMaster.read(bus, ALLOC.readBack("preempt", cid)("idleNotification"), 8).bytesToBigInt != 0) {
        assert(wcs.enterISR())
        activate(bus, wcs)
        wcs.exitISR()
      }
      wcs.dispatchMutex.unlock()
    } else fail(s"unexpected interrupt $irq on core $cid")
  }
}
