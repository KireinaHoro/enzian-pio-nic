package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.{BigIntParser, IntRicherEndianAware}
import lauberhorn.Global.{ALLOC, NUM_CORES, NUM_WORKER_CORES, PID_WIDTH}
import lauberhorn.{AsSimBusMaster, NicEngine}
import spinal.core.sim.{simRandom, waitUntil}
import spinal.lib.BytesRicher

import scala.collection.mutable

trait CoreState {
  def cid: Int

  var inISR = false
  def log(msg: String) = println(s"[core $cid] $msg")
  def enterISR() = {
    assert(!inISR, s"core $cid already in kernel!")
    inISR = true
    log("entering kernel")
  }
  def exitISR() = {
    assert(inISR, s"core $cid not in kernel!")
    inISR = false
    log("exiting kernel")
  }

  /** Configure thread router. */
  def switchToThread[B](thr: ThreadDef, bus: B)(implicit asMaster: AsSimBusMaster[B]) = {
    asMaster.write(bus, ALLOC.readBack("threadRouter")("ctrl", "addrPrefix"), thr.prefix.toBytesLE)
    asMaster.write(bus, ALLOC.readBack("threadRouter")("ctrl", "enabled"), 1.toBytesLE)

    asMaster.write(bus, ALLOC.readBack("threadRouter")("ctrl", "coreIdx"), cid.toBytesLE)
  }
}

case class ThreadDef(tid: Int, prefix: Int) {
  /** Track which core we are running on */
  var runningOn: Option[WorkerCoreState] = None
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

  var currThread: Option[ThreadDef] = None
  override def switchToThread[B](thr: ThreadDef, bus: B)(implicit asMaster: AsSimBusMaster[B]) = {
    assert(thr.tid != -1, "can't enable the bypass thread on a worker core")
    thr.runningOn = Some(this)

    currThread match {
      case Some(curr) =>
        assert(curr != thr, s"asking core $cid to switch to an already running thread (TID ${thr.tid})!")
        curr.runningOn = None
      case None =>
    }

    println(s"[core $cid] switching to thread ${thr.tid}")

    currThread = Some(thr)
    super.switchToThread(thr, bus)

    // impl-specific actions
    switchToThreadImpl(thr)
  }

  /** Implementation-specific thread enable action.  For example, the ECI
    * backend needs to program CL parity bits into HW. */
  protected def switchToThreadImpl(threadDef: ThreadDef): Unit

  /** Wait until the core is running a given thread ID. */
  def waitUser(tid: Int) = waitUntil(!inISR && currThread.exists(_.tid == tid))
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

  val threads = mutable.HashMap[Int, ThreadDef]()

  val processes = mutable.HashMap[Int, ProcDef]()
  def mkRandomProc(maxThreads: Int) = {
    val threads = Seq.fill(maxThreads) {
      // FIXME: possible collision
      val thr = ThreadDef(simRandom.nextInt(65536), simRandom.nextInt(65536))
      threads(thr.tid) = thr
      thr
    }
    val pd = ProcDef(simRandom.nextInt(65536), threads)
    processes(pd.pid) = pd

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
    val cs = coreStates(cid)
    cs.enterISR()

    val preemptRegBlock = ALLOC.readBack("preempt", blockIdx = cid)

    // disable interrupt
    asMaster.write(bus, preemptRegBlock("irqEn"), 0.toBytesLE)

    if (irq == 15) {
      assert(cid == 0, "bypass IRQ should only be sent to core 0")

      // call bypass handler
      bypassCore.asInstanceOf[BypassCoreState].handler()
    } else if (irq == 8) {
      assert(cid >= 1, "worker IRQ should only be sent to worker cores")
      assert(cid < NUM_CORES, s"worker IRQ sent to core $cid, but only $NUM_WORKER_CORES workers exist")

      // ACK interrupt
      val ipiAckReg = asMaster.read(bus, preemptRegBlock("ipiAck"), 8).bytesToBigInt
      val ipiAck = new BigIntParser(ipiAckReg)

      val pidToSched = ipiAck.pop(PID_WIDTH)
      val killed     = ipiAck.pop(1) != 0

      // TODO: how to handle killing a process?
      assert(!killed, "kill preemption not implemented yet and should not happen")

      // select thread in process
      processes(pidToSched.toInt).threads.find(_.runningOn.isEmpty) match {
        case Some(thr) =>
          cs.asInstanceOf[WorkerCoreState].switchToThread(thr, bus)
        case None =>
          fail(s"PID#$pidToSched has all threads running, can't schedule more")
      }
    } else {
      fail(s"unknown IRQ $irq")
    }

    // re-enable interrupt
    asMaster.write(bus, preemptRegBlock("irqEn"), 0.toBytesLE)
    cs.exitISR()
  }
}