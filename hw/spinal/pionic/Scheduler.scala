package pionic

import pionic.net.OncRpcCallMetadata
import spinal.core._
import spinal.lib._
import pionic.PioNicPlugin
import spinal.core.fiber.Fiber.awaitBuild
import spinal.lib.bus.misc.BusSlaveFactory
import jsteward.blocks.misc.RegBlockAlloc
import spinal.lib.bus.regif.AccessType

/** Type for PIDs. */
case class PID()(implicit c: ConfigDatabase) extends Bundle {
  override def clone = PID()

  val bits = UInt(Widths.pidw bits)
}

/** Command from host to create a process in the scheduler. */
case class ProcessDef()(implicit c: ConfigDatabase) extends Bundle {
  val enabled = Bool()
  /** PID of process on the CPU -- not necessarily corresponding to actual process IDs on Linux */
  val pid = PID()
  /** maximum number of threads that the process is allowed to run on */
  val maxThreads = Bits(log2Up(c[Int]("num cores")) bits)
}

/**
  * Top class of Lauberhorn's scheduler, as a plugin.
  *
  * Takes a [[OncRpcCallMetadata]] from the decoding pipeline and extracts the (PID, funcPtr) pair.  See
  * [[https://unlimited.ethz.ch/spaces/sgnetoswiki/pages/216442761/Lauberhorn+Fast+RPC+on+Enzian#Lauberhorn(FastRPConEnzian)-(FPGA-side)schedulerarchitecture wiki page]]
  * for the detailed architecture.
  *
  * Note: to allow accurate decisions based on queue capacity, the [[Scheduler]] has to be the only component in the
  * system that queues packets -- all existing queuing e.g. inside [[CoreControlPlugin]] needs to be eliminated.
  */
class Scheduler extends PioNicPlugin {
  lazy val numProcs = c[Int]("num processes")
  
  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    // max number of threads per process (degree of parallelism)
    val procDefPort = ProcessDef()
    procDefPort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("sched", s"proc_$name", attr = AccessType.WO))
    }
    
    val procDefIdx = UInt(log2Up(numProcs) bits)
    procDefIdx := 0
    val procDefIdxAddr = alloc("sched", "proc_idx", attr = AccessType.WO)
    busCtrl.write(procDefIdx, procDefIdxAddr)
    busCtrl.onWrite(procDefIdxAddr) {
      // record process parallelism degree in table
      logic.procDefs(procDefIdx) := procDefPort
    }
  }

  val logic = during setup new Area {
    /** Packet metadata to accept from the decoding pipeline.  Must be a [[pionic.net.OncRpcCallMetadata]] */
    val rxMeta = Stream(OncRpcCallMetadata())

    /** Packet metadata issued to the downstream [[CoreControlPlugin]].  Note that this does not contain any scheduling
      * information -- switching processes on a core is requested through the [[corePreempt]] interfaces. */
    val coreMeta = Vec(Stream(OncRpcCallMetadata()), numWorkerCores)

    /**
      * Request a core to switch to a different process.  Interaction with [[coreMeta]] happens in the following order:
      *  - hold requests in [[coreMeta]] (valid === False)
      *  - issue request on [[corePreempt]] (wait until ready && valid === True)
      *  - re-assign PID queue, allow requests to continue on [[coreMeta]]
      *
      * Will be stalled (ready === False) when a preemption is in progress.
      */
    val corePreempt = Vec(Stream(PID()), numWorkerCores)
    
    // one per-process queue for every entry in procDefs
    val procDefs = Vec.fill(numProcs)(Reg(ProcessDef()))
    
    awaitBuild()
    
    // per-process queues are in memory
    val pktsPerProc = c[Int]("max rx pkts in flight per process")
    val queueMem = Mem(OncRpcCallMetadata(), numProcs * pktsPerProc)

    case class QueuePointers()(implicit c: ConfigDatabase) extends Bundle {
      def MemAddr = UInt(log2Up(pktsPerProc * numProcs) bits)
      val head, tail = MemAddr
    }
    val queuePtrs = Vec.tabulate(numProcs) { idx =>
      val ret = Reg(QueuePointers())
      val initHead = idx * pktsPerProc
      ret.head init initHead
      ret.tail init initHead
      ret
    }
    
    // receive packet and push into memory
    rxMeta.setBlocked()
    when (rxMeta.valid) {
      
    }
    
    // select first non-empty queue to grant packet  
    val queueNonEmpty = queuePtrs.map { ptr =>
      ptr.head =/= ptr.tail
    }

  }
}