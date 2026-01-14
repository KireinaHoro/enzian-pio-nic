package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.{BigIntParser, IntRicherEndianAware}
import lauberhorn.Global.{ALLOC, NUM_CORES, NUM_WORKER_CORES, PID_WIDTH}
import lauberhorn.sim.CoreState.threadDbMtx
import lauberhorn.{AsSimBusMaster, NicEngine}
import org.scalatest.Assertions.fail
import spinal.core.sim.{SimMutex, simRandom, waitUntil}
import spinal.lib.BytesRicher

import scala.collection.mutable

object CoreState {
  val threadDbMtx = SimMutex(randomized = true)
}

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

  /** Configure the thread router to enable one mapping.  This is called in the ISR so must
    * be reentrant -- protect with lock */
  def switchToThread[B](thr: ThreadDef, bus: B)(implicit asMaster: AsSimBusMaster[B]): Unit = {
    threadDbMtx.lock()

    log(f"enabling thread prefix ${thr.prefix}%#x in router entry $cid")

    asMaster.write(bus, ALLOC.readBack("threadRouter")("ctrl", "addrPrefix"), thr.prefix.toBytesLE)
    asMaster.write(bus, ALLOC.readBack("threadRouter")("ctrl", "enabled"), 1.toBytesLE)

    asMaster.write(bus, ALLOC.readBack("threadRouter")("ctrl", "coreIdx"), cid.toBytesLE)

    threadDbMtx.unlock()
  }
}

case class ThreadDef(tid: Int, prefix: Int) {
  /** Track which core we are running on */
  var runningOn: Option[WorkerCoreState] = None
  var proc: ProcDef = null
}
case class ProcDef(pid: Int, threads: Seq[ThreadDef]) {
  def enableAt[B](idx: Int, bus: B)(implicit asMaster: AsSimBusMaster[B]) = {
    // activate process
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

  def canInterrupt: Boolean

  var currThread: Option[ThreadDef] = None
  override def log(msg: String) = {
    currThread match {
      case Some(thr) => println(s"[core $cid tid ${thr.tid}]\t$msg")
      case None => super.log(msg)
    }
  }

  override def switchToThread[B](thr: ThreadDef, bus: B)(implicit asMaster: AsSimBusMaster[B]) = {
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
    super.switchToThread(thr, bus)

    // impl-specific actions
    switchToThreadImpl(thr)
  }

  /** Implementation-specific thread enable action.  For example, the ECI
    * backend needs to program CL parity bits into HW. */
  protected def switchToThreadImpl(threadDef: ThreadDef): Unit

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
      val prefix = genNewId(threads.values.map(_.prefix).toSeq)
      val thr = ThreadDef(tid, prefix)
      threads(thr.tid) = thr
      thr
    }
    val pd = ProcDef(genNewId(processes.keys.toSeq), ts)
    processes(pd.pid) = pd

    pd.threads foreach { thr => thr.proc = pd }

    pd
  }

  /** Simulates behaviour of the Lauberhorn scheduler integration on Linux.
    *  - the bypass ISR runs a given handler "in kernel"
    *  - the worker ISR modifies the core states to convey the idea that
    *    a thread has been descheduled.
    *
    * The test benches should run simulation threads for each physical worker core
    * and consult [[WorkerCoreState]] to determine if the thread running has been
    * changed.
    */
  def genericIrqCb[B](bus: B, irq: Int, cid: Int)(implicit asMaster: AsSimBusMaster[B]): Unit = {
    val preemptRegBlock = ALLOC.readBack("preempt", blockIdx = cid)
    val cs = coreStates(cid)

    // no need to mask interrupt for worker: preemption won't send another interrupt
    // until we write to ACK
    if (!cs.enterISR()) return

    cs.log("disabling IRQ")
    asMaster.write(bus, preemptRegBlock("irqEn"), 0.toBytesLE)

    if (irq == 15) {
      assert(cid == 0, "bypass IRQ should only be sent to core 0")

      // call bypass handler
      cs.asInstanceOf[BypassCoreState].handler()
    } else if (irq == 8) {
      val wcs = cs.asInstanceOf[WorkerCoreState]

      // FIXME: possibly we never get a chance to interrupt
      cs.log("waiting until we can interrupt")
      waitUntil(wcs.canInterrupt)

      assert(cid >= 1, "worker IRQ should only be sent to worker cores")
      assert(cid < NUM_CORES, s"worker IRQ sent to core $cid, but only $NUM_WORKER_CORES workers exist")

      cs.log("reading preempt cmd")
      val ipiAckReg = asMaster.read(bus, preemptRegBlock("schedCmd"), 8).bytesToBigInt
      val ipiAck = new BigIntParser(ipiAckReg)

      val pidToSched = ipiAck.pop(PID_WIDTH)
      val killed     = ipiAck.pop(1) != 0

      // TODO: how to handle killing a process?
      assert(!killed, "kill preemption not implemented yet and should not happen")

      // select thread in process
      processes(pidToSched.toInt).threads.find(_.runningOn.isEmpty) match {
        case Some(thr) =>
          wcs.switchToThread(thr, bus)
        case None =>
          fail(s"PID#$pidToSched has all threads running, can't schedule more")
      }
    } else {
      fail(s"unknown IRQ $irq")
    }

    // in the Linux kernel, the next interrupt will not come in until we are out
    cs.exitISR()

    cs.log("re-enabling IRQ")
    asMaster.write(bus, preemptRegBlock("irqEn"), 1.toBytesLE)
  }
}
