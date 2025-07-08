package pionic

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import jsteward.blocks.misc.RegBlockAlloc
import pionic.host.{HostReq, HostReqOncRpcCall, HostReqType, PreemptionService}
import pionic.Global._
import spinal.lib.misc.database.Element.toValue
import spinal.lib.bus.regif.AccessType
import spinal.lib.bus.regif.AccessType.RO
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

import scala.language.postfixOps

/** Type for PIDs. */
case class PID() extends Bundle {
  override def clone = PID()

  val bits = UInt(PID_WIDTH bits)
}

/** Command from host to create a process in the scheduler. */
case class ProcessDef() extends Bundle {
  val enabled = Bool()
  /** PID of process on the CPU -- not necessarily corresponding to actual process IDs on Linux */
  val pid = PID()
  /** maximum number of threads that the process is allowed to run on */
  val maxThreads = UInt(log2Up(NUM_WORKER_CORES + 1) bits)
}

/**
  * Level of urgency on preempting a core.  Core preemption is triggered on packet arrival (otherwise a non-cooperative
  * core can never be preempted), but actually executed in the pop logic to serialize with blocking request delivery.
  * This command is used to convey intention between the two parts.
  *
  *  - [[idle]]: only preempt, when the core is in the IDLE process (i.e. no services)
  *  - [[ready]]: preempt, when the core is stuck in a read (i.e. not currently processing a request); can also
  *    preempt a core that is in idle.
  *  - [[force]]: kill a running process due to timeout
  */
object PreemptCmdType extends SpinalEnum {
  val idle, ready, force = newElement()
}

/**
  * Top class of Lauberhorn's scheduler, as a plugin.
  *
  * Takes a [[pionic.host.HostReq]] from the decoding pipeline and extracts the (PID, funcPtr) pair.  Determines to
  * which core the packet should go to based on queue occupancy.  Only supports [[OncRpcCallMetadata]] for now.  See the
  * [[https://unlimited.ethz.ch/spaces/sgnetoswiki/pages/216442761/Lauberhorn+Fast+RPC+on+Enzian#Lauberhorn(FastRPConEnzian)-(FPGA-side)schedulerarchitecture wiki page]]
  * for the detailed architecture.
  *
  * Interfaces with [[pionic.host.DatapathService]] instances to deliver the dispatched [[pionic.host.HostReq]].
  *
  * Note: to allow accurate decisions based on queue capacity, this is the only component in the system that queues packets.
  */
class Scheduler extends FiberPlugin {
  lazy val csr = host[GlobalCSRPlugin].logic.get
  lazy val totalPkts = RX_PKTS_PER_PROC * NUM_PROCS

  def MemAddr = UInt(log2Up(totalPkts) bits)
  def ProcTblIdx = UInt(log2Up(NUM_PROCS+1) bits)

  case class PreemptCmd() extends Bundle {
    val ty = PreemptCmdType()
    val pid = PID()
    val idx = ProcTblIdx
  }

  def driveControl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    // max number of threads per process (degree of parallelism)
    val procDefPort = ProcessDef()
    procDefPort.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("schedCtrl", s"proc_$name", attr = AccessType.WO))
    }
    
    val procDefIdx = ProcTblIdx
    procDefIdx := 0
    val procDefIdxAddr = alloc("schedCtrl", "proc_idx", attr = AccessType.WO)
    busCtrl.write(procDefIdx, procDefIdxAddr)
    busCtrl.onWrite(procDefIdxAddr) {
      // the IDLE process should not be changed
      when (procDefIdx =/= 0) {
        logic.procDefs(procDefIdx) := procDefPort
        assert(procDefPort.maxThreads <= NUM_WORKER_CORES.get, "process has more threads than available worker cores")
      } otherwise {
        report("attempting to modify the IDLE process", FAILURE)
      }
    }
  }

  def reportStatistics(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    // read-back port for SW to inspect programmed procs
    val readbackPort = Reg(new ProcessDef {
      val queueFill = UInt(REG_WIDTH bits)
    })
    readbackPort.elements.foreach { case (name, field) =>
      busCtrl.read(field, alloc("schedStats", s"readback_$name", attr = AccessType.RO))
    }

    val readbackIdx = ProcTblIdx
    readbackIdx := 0
    val readbackIdxAddr = alloc("schedStats", "readback_idx", attr = AccessType.WO)
    busCtrl.write(readbackIdx, readbackIdxAddr)
    busCtrl.onWrite(readbackIdxAddr) {
      readbackPort.assignSomeByName(logic.procDefs(readbackIdx))
      readbackPort.queueFill := logic.queueMetas(readbackIdx).fill.resized
    }

    logic.statistics.elements.foreach { case (name, data) =>
      data match {
        case d: UInt => busCtrl.read(d, alloc("schedStats", name, attr = RO))
        case v: Vec[_] => v.zipWithIndex foreach { case (s, idx) =>
          // FIXME: allocate this in the per-core space
          busCtrl.read(s, alloc("schedStats", s"${name}_core$idx", attr = RO))
        }
      }
    }
  }

  val logic = during setup new Area {
    /** Packet metadata to accept from the decoding pipeline.  Must be a [[pionic.net.OncRpcCallMetadata]] */
    val rxMeta = Stream(HostReq())

    /** Packet metadata issued to the downstream [[pionic.host.DatapathPlugin]].
      *
      * Note that this is purely for the datapath and does not contain any scheduling information: switching processes
      * on a core is requested through the [[corePreempt]] interfaces.
      */
    val coreMeta = Seq.fill(NUM_WORKER_CORES)(Stream(HostReq()))

    awaitBuild()

    /**
      * Request a core to switch to a different process.  Interaction with [[coreMeta]] happens in the following order:
      *  - hold requests in [[coreMeta]] (valid === False)
      *  - issue request on [[corePreempt]] (wait until ready && valid === True)
      *  - re-assign PID queue, allow requests to continue on [[coreMeta]]
      *
      * Will be stalled (ready === False) when a preemption is in progress.
      */
    val corePreempt = host.list[PreemptionService].map { ps =>
      val p = Stream(PID())
      p.payload.setAsReg()
      p >> ps.preemptReq
      p
    }

    // one per-process queue for every entry in procDefs
    // all cores come out of reset with idx 0 in this table:
    // table entry 0 is a special "IDLE" process
    val procDefs = Vec.fill(NUM_PROCS+1) {
      val ret = Reg(ProcessDef())
      ret.enabled init False
      ret.maxThreads init U(NUM_WORKER_CORES)
      ret.pid.bits init U(0xffff)
      ret
    }

    val statistics = new Bundle {
      val pushed, dropped = Reg(UInt(REG_WIDTH bits)) init 0
      val popped, preempted, dispatched = Vec.fill(NUM_WORKER_CORES)(Reg(UInt(REG_WIDTH bits)) init 0)
    }
    def inc(f: statistics.type => UInt): Unit = f(statistics) := f(statistics) + 1

    // per-process queues are in memory
    val queueMem = Mem(HostReq(), totalPkts)

    case class QueueMetadata()(off: UInt, cap: UInt) extends Bundle {
      val offset, head, tail = MemAddr
      val capacity, fill = UInt(log2Up(RX_PKTS_PER_PROC + 1) bits)

      // XXX: offset and capacity must be UInt, since we need to mux to select one
      offset := off
      capacity := cap

      // use signalCache to prevent storing these derived signals as registers
      def full = signalCache(this, "full") {
        (fill === capacity).setCompositeName(this, "full")
      }
      def empty = signalCache(this, "empty") {
        (head === tail && (!full || capacity === 0)).setCompositeName(this, "empty")
      }
      def almostFull = signalCache(this, "almostFull") {
        // TODO: more flexible metric
        (2 * fill >= capacity).setCompositeName(this, "almostFull")
      }

      // actions called on a reg to mutate
      def initEmpty: Unit = {
        head init offset
        tail init offset
        fill init 0
        offset := off
        capacity := cap
      }

      private def advance(ptr: UInt): UInt = {
        val newPtr = ptr.clone
        when (ptr + 1 === offset + capacity) {
          newPtr := offset
        } otherwise {
          newPtr := ptr + 1
        }
        ptr := newPtr
        newPtr
      }

      def pushOne(): Unit = {
        assert(!full, "trying to push one into a full queue")
        advance(tail)
        fill := fill + 1
      }

      def popOne(): Unit = {
        assert(!empty, "trying to pop one from an empty queue")
        advance(head)
        fill := fill - 1
      }

      def passOne(): Unit = {
        assert(!full && !empty, "can only pass one from non-empty, non-full queue")
        advance(head)
        advance(tail)
      }
    }
    val queueMetas = Vec.tabulate(NUM_PROCS+1) { idx =>
      val offset = if (idx == 0) 0 else (idx-1) * RX_PKTS_PER_PROC
      val capacity: Int = if (idx == 0) 0 else RX_PKTS_PER_PROC
      val ret = Reg(QueueMetadata()(offset, capacity))
      ret.initEmpty
      ret
    }

    // update pointers centrally
    val pushQ, popQ = Seq.fill(NUM_PROCS+1)(False)
    (pushQ zip popQ zipWithIndex) foreach { case ((push, pop), idx) =>
      switch (push ## pop) {
        is (B("01")) { queueMetas(idx).popOne()  }
        is (B("10")) { queueMetas(idx).pushOne() }
        is (B("11")) { queueMetas(idx).passOne() }
      }
    }

    // such that the waveform shows the actual header, not a union
    val rxOncRpcCall: Stream[HostReqOncRpcCall] = rxMeta.map { meta =>
      meta.data.oncRpcCall
    }
    // process ID to select which queue the incoming packet goes into
    val rxProcSelOh = procDefs.map { pd =>
      pd.enabled && pd.pid === rxOncRpcCall.pid
    }.asBits()

    when (rxMeta.valid) {
      // decoder pipeline should have filtered out packets that do not belong to an enabled process
      assert(CountOne(rxProcSelOh) === 1, "not exactly one proc can handle a packet")
      assert(rxMeta.ty === HostReqType.oncRpcCall, "scheduler does not support other req types yet")
    }

    // FIXME: this will become too wide when we register more processes
    val rxProcTblIdx = OHToUInt(rxProcSelOh)
    val rxProcDef = procDefs(rxProcTblIdx)

    // used to find a non-empty queue, when a core has drained its queue
    val drainProcSelOh = procDefs.zipWithIndex.map { case (pd, idx) =>
      pd.enabled && !queueMetas(idx).empty
    }.asBits()
    val drainProcTblIdx = OHToUInt(drainProcSelOh)
    val drainProcDef = procDefs(drainProcTblIdx)

    // map of which process is running on which core
    val corePidMap = Vec.fill(NUM_WORKER_CORES) {
      // all start in IDLE -- preempt request will move them away
      Reg(ProcTblIdx) init 0
    }

    // core map for the rx process
    val rxProcCoreMap = corePidMap.map(_ === rxProcTblIdx).asBits()
    val rxProcCurrThrCount = CountOne(rxProcCoreMap)

    // preempt request to popping side
    val rxPreemptReq = Reg(Flow(PreemptCmd()))
    rxPreemptReq.valid := False

    rxOncRpcCall.setBlocked()
    when (rxOncRpcCall.valid) {
      // received packet: push into memory
      when (queueMetas(rxProcTblIdx).full) {
        // the destination proc queue is full
        // since we don't have any queuing anywhere outside the scheduler, we have to drop the packet
        inc(_.dropped)
      } otherwise {
        // store at where the tail was
        queueMem.write(queueMetas(rxProcTblIdx).tail, rxMeta.payload)

        // update pointers
        pushQ(rxProcTblIdx) := True

        inc(_.pushed)
      }

      // A new packet arrived, try to select a core to preempt, but do not block the RX process.
      when (rxProcCurrThrCount < rxProcDef.maxThreads) {
        rxPreemptReq.pid := rxOncRpcCall.pid
        rxPreemptReq.idx := rxProcTblIdx

        // preempting as ready takes priority
        when (queueMetas(rxProcTblIdx).almostFull) {
          // queue almost full (V_arrival > V_consume, need to scale up)
          // preempt a non-idle, ready core
          rxPreemptReq.ty := PreemptCmdType.ready
          rxPreemptReq.valid := True
        } elsewhen (rxProcCoreMap === 0) {
          // no process assigned to this queue -- idle preempt
          rxPreemptReq.ty := PreemptCmdType.idle
          rxPreemptReq.valid := True
        }
      }

      // we either pushed the packet or dropped it, ack
      rxOncRpcCall.ready := True
    }

    // each core can raise a pop request, to remove one packet from the queues
    val popReqs = Seq.fill(NUM_WORKER_CORES)(Stream(MemAddr))
    val arbitratedPopReq = StreamArbiterFactory().roundRobin.on(popReqs)
    val poppedReq = queueMem.readSync(arbitratedPopReq.payload)
    arbitratedPopReq.ready := True

    // victim core selection, based on preemption type
    val coreIdleMap = Seq.tabulate(NUM_WORKER_CORES) { cid =>
      // idle preemption: a core that is
      // - running the IDLE process, and
      // - a preemption is not underway
      corePidMap(cid) === 0 && !corePreempt(cid).valid
    }.asBits()
    val coreReadyMap = Seq.tabulate(NUM_WORKER_CORES) { cid =>
      // ready preemption: a core that is
      // - running another PID, and
      // - running some proc but ready (i.e. no request ongoing), and
      // - a preemption is not underway
      (corePidMap(cid) =/= rxPreemptReq.idx && coreMeta(cid).ready) && !corePreempt(cid).valid
    }.asBits()

    val victimCoreMap = rxPreemptReq.ty.mux(
      PreemptCmdType.idle -> coreIdleMap,
      PreemptCmdType.ready -> (coreReadyMap | coreIdleMap),
      // TODO: when do we need the `force` command type?
      //       when we need to kill a running handler?
      default -> B(0),
    )
    val victimCoreMapSel = OHMasking.firstV2(victimCoreMap)

    0 until NUM_WORKER_CORES foreach { idx => new Area {
      val toCore = coreMeta(idx)
      toCore.setIdle()

      val popReq = popReqs(idx)
      popReq.setIdle()

      val corePopQueueIdx = corePidMap(idx)

      corePreempt(idx).valid := False
      // save the requested preemption target until preemption is actually done
      val savedPreemptIdx = Reg(ProcTblIdx)

      val popFsm = new StateMachine {
        val idle: State = new State with EntryPoint {
          whenIsActive {
            when (rxPreemptReq.valid && victimCoreMapSel(idx)) {
              // we are selected as the eviction target
              // capture requested PID since it's a Flow and only valid for one cycle
              corePreempt(idx).payload := rxPreemptReq.pid
              savedPreemptIdx := rxPreemptReq.idx
              goto(preempt)
            } elsewhen (toCore.ready && !queueMetas(corePopQueueIdx).empty) {
              // core ready, we can ask for a request to be popped
              // XXX: this goes against the Stream semantics (https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html#semantics):
              //      "It is recommended that valid does not depend on ready at all":
              //      we will only assert valid, when a core is ready.
              //      not a combinatorial dependency, so we are still ok
              //      needed since we don't want to pop a request when core is not ready, since after popping one:
              //      - allow preempt?  need to somehow put it back in the queue
              //      - not allow preempt?  no chance in practice to preempt then
              popReq.payload := queueMetas(corePopQueueIdx).head
              popReq.valid := True
              when(popReq.ready) {
                // queue mem read granted.  readSync has one cycle latency

                // we could have a race condition between cores
                // must update queue pointers immediately after granted
                popQ(corePopQueueIdx) := True
                inc(_.popped(idx))

                goto(readPoppedReq)
              }
            } elsewhen (toCore.ready && corePopQueueIdx =/= drainProcTblIdx) {
              // When a core completely drained its queue, it needs to check if there are non-empty queues that
              // have no cores assigned.  This is needed to be work-efficient and prevent excessive latency for
              // the following case:
              // - process received a request when all cores are busy, couldn't preempt any core
              // - system later became not busy, but no new requests arrive for process
              // - if we don't scan inactive queues, the request will sit there indefinitely
              // TODO: fairness?

              // XXX: two cores can see the same queue at the same time
              //      this will only result in them all preempted to that queue, should be ok
              corePreempt(idx).payload := drainProcDef.pid
              savedPreemptIdx := drainProcTblIdx
              goto(preempt)
            }
          }
        }
        val preempt: State = new State {
          whenIsActive {
            corePreempt(idx).valid := True
            when (corePreempt(idx).ready) {
              corePidMap(idx) := savedPreemptIdx

              inc(_.preempted(idx))
              goto(idle)
            }
          }
        }
        val readPoppedReq: State = new State {
          whenIsActive {
            // issue the popped request to core
            toCore.payload := poppedReq
            toCore.valid := True

            when (toCore.ready) {
              // no guarantee that worker must accept request: they might de-assert ready due to a read timeout
              // have to wait until they try again (and re-assert ready)
              // TODO: what happens if the core went amok and never retried? Kill proc?

              inc(_.dispatched(idx))
              goto(idle)
            }
          }
        }
      }
    }.setName(s"sched_rx_pop_core$idx") }
  }
}
