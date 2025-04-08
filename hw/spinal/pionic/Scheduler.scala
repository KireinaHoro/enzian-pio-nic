package pionic

import pionic.net.OncRpcCallMetadata
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import jsteward.blocks.misc.RegBlockAlloc
import spinal.lib.bus.regif.AccessType
import spinal.lib.fsm._

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
  lazy val csr = host[GlobalCSRPlugin].logic.get

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
    val coreMeta = Seq.fill(numWorkerCores)(Stream(OncRpcCallMetadata()))

    /**
      * Request a core to switch to a different process.  Interaction with [[coreMeta]] happens in the following order:
      *  - hold requests in [[coreMeta]] (valid === False)
      *  - issue request on [[corePreempt]] (wait until ready && valid === True)
      *  - re-assign PID queue, allow requests to continue on [[coreMeta]]
      *
      * Will be stalled (ready === False) when a preemption is in progress.
      */
    val corePreempt = Seq.fill(numWorkerCores)(Stream(PID()))
    
    // one per-process queue for every entry in procDefs
    // since all cores start with idx 0 in this table, table entry 0 should be a special "IDLE" process
    val procDefs = Vec.fill(numProcs+1) {
      val ret = Reg(ProcessDef())
      ret.enabled init False
      ret.maxThreads init numWorkerCores
      ret
    }
    
    awaitBuild()
    
    // per-process queues are in memory
    val pktsPerProc = c[Int]("max rx pkts in flight per process")
    val queueMem = Mem(OncRpcCallMetadata(), numProcs * pktsPerProc)

    def MemAddr = UInt(log2Up(pktsPerProc * numProcs) bits)

    case class QueueMetadata(offset: Int, capacity: Int)(implicit c: ConfigDatabase) extends Bundle {
      val head, tail = MemAddr
      val full = Bool()

      def empty = head === tail && (!full || Bool(capacity == 0))
      def bound = offset + capacity - 1

      // actions called on a reg to mutate
      def initEmpty: Unit = {
        head init offset
        tail init offset
        full init capacity == 0
      }

      private def advance(ptr: UInt): UInt = {
        val newPtr = ptr.clone
        when (ptr === bound) {
          newPtr := offset
        } otherwise {
          newPtr := ptr + 1
        }
        ptr := newPtr
        newPtr
      }

      def pushOne(): Unit = {
        assert(!full, "trying to push one into a full queue")
        full := advance(tail) === head
      }

      def popOne(): Unit = {
        assert(!empty, "trying to pop one from an empty queue")
        advance(head)
        full := False
      }
    }
    val queueMetas = Vec.tabulate(numProcs+1) { idx =>
      val offset = if (idx == 0) 0 else (idx-1) * pktsPerProc
      val capacity = if (idx == 0) 0 else pktsPerProc
      val ret = Reg(QueueMetadata(offset, capacity))
      ret.initEmpty
      ret
    }

    // process ID to select which queue the incoming packet goes into
    val procSelOh = procDefs.map { pd =>
      pd.pid === rxMeta.pid
    }.asBits()
    val procTblIdx = OHToUInt(procSelOh)
    val rxProcDef = procDefs(procTblIdx)
    val rxQueueMeta = queueMetas(procTblIdx)

    // report scheduler packet drop
    val rxSchedDrop = CombInit(False)
    csr.status.rxSchedDroppedCount := Counter(regWidth bits, rxSchedDrop)

    rxMeta.setBlocked()
    when (rxMeta.valid) {
      // received packet: push into memory
      when (rxQueueMeta.full) {
        // must drop packet since destination is full
        rxSchedDrop := True
      } otherwise {
        // store at where the tail was
        queueMem.write(rxQueueMeta.tail, rxMeta.payload)

        // update pointers
        rxQueueMeta.pushOne()
      }

      // TODO: new packet arrived, select a core to preempt:
      //       - no process assigned to this queue, and there are idle cores available
      //       - queue almost full (V_arrival > V_consume, need to scale up)

      // we either pushed the packet or dropped it, ack
      rxMeta.ready := True
    }

    // map of which process is running on which core
    val corePidMap = Vec.fill(numWorkerCores) {
      val ret = Reg(PID())
      // all start in IDLE -- preempt request will move them away
      ret.bits init 0
    }

    // each core can raise a pop request, to remove one packet from the queues
    val popReqs = Seq.fill(numWorkerCores)(Stream(MemAddr))
    val arbitratedPopReq = StreamArbiterFactory().roundRobin.on(popReqs)
    val poppedReq = queueMem.readSync(arbitratedPopReq.payload)
    arbitratedPopReq.ready := True

    0 until numWorkerCores foreach { idx => new Area {
      val toCore = coreMeta(idx)
      toCore.setIdle()

      val popReq = popReqs(idx)
      popReq.setIdle()

      val queueIdx = corePidMap(idx)
      val queueMeta = queueMetas(queueIdx)

      val popFsm = new StateMachine {
        val idle: State = new State with EntryPoint {
          whenIsActive {
            when(toCore.ready && !queueMeta.empty) {
              // we can ask for a request to be popped
              popReq.payload := queueMeta.head
              popReq.valid := True
              when(popReq.ready) {
                // queue mem read granted.  readSync has one cycle latency
                goto(readPoppedReq)
              }
            }
          }
        }
        val readPoppedReq: State = new State {
          whenIsActive {
            // write popped request to core
            toCore.payload := poppedReq
            toCore.valid := True

            // update queue pointers
            queueMeta.popOne()

            goto(idle)
          }
        }
      }
    } }
  }
}