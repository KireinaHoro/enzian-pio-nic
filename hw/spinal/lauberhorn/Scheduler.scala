package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.host.{HostReq, HostReqOncRpcCallRx, HostReqType, PreemptionService}
import lauberhorn.Global._
import lauberhorn.net.oncrpc.OncRpcCallRxMeta
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory}
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
  * Takes a [[lauberhorn.host.HostReq]] from the decoding pipeline and extracts the (PID, funcPtr) pair.  Determines to
  * which core the packet should go to based on queue occupancy.  Only supports [[OncRpcCallRxMeta]] for now.  See the
  * [[https://unlimited.ethz.ch/spaces/sgnetoswiki/pages/216442761/Lauberhorn+Fast+RPC+on+Enzian#Lauberhorn(FastRPConEnzian)-(FPGA-side)schedulerarchitecture wiki page]]
  * for the detailed architecture.
  *
  * Interfaces with [[lauberhorn.host.DatapathService]] instances to deliver the dispatched [[lauberhorn.host.HostReq]].
  *
  * Note: to allow accurate decisions based on queue capacity, this is the only component in the system that queues packets.
  */
class Scheduler extends FiberPlugin {
  lazy val totalPkts = RX_PKTS_PER_PROC * NUM_PROCS

  def MemAddr = UInt(log2Up(totalPkts) bits)
  def ProcTblIdx = UInt(log2Up(NUM_PROCS+1) bits)

  case class PreemptCmd() extends Bundle {
    val ty = PreemptCmdType()
    val pid = PID()
    val idx = ProcTblIdx
  }

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    ctrl(busCtrl, alloc)
    stat(busCtrl, alloc)
  }

  def ctrl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.procDb.update.setIdle()

    // max number of threads per process (degree of parallelism)
    logic.procDb.update.value.elements.foreach { case (name, field) =>
      busCtrl.drive(field, alloc("ctrl", s"Process table update $name",
        s"proc_$name", attr = AccessType.WO))
    }

    val procDefIdx = ProcTblIdx
    procDefIdx := 0
    val procDefIdxAddr = alloc("ctrl", s"Index of process to update",
      "proc_idx", attr = AccessType.WO)
    busCtrl.write(procDefIdx, procDefIdxAddr)
    busCtrl.onWrite(procDefIdxAddr) {
      logic.procDb.update.valid := True
      logic.procDb.update.idx := procDefIdx

      assert(logic.procDb.update.value.maxThreads <= NUM_WORKER_CORES.get,
        "process has more threads than available worker cores")
      // the IDLE process should not be changed
      assert(procDefIdx =/= 0, "attempting to modify the IDLE process")
    }
  }

  def stat(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    // read-back port for SW to inspect programmed procs
    val readbackPort = Reg(new ProcessDef {
      val queueFill = UInt(REG_WIDTH bits)
    })
    readbackPort.elements.foreach { case (name, field) =>
      busCtrl.read(field, alloc("stat", s"Process table readback $name",
        s"readback_$name", attr = AccessType.RO))
    }

    val readbackIdxAddr = alloc("stat", "Index of process to read back",
      "readback_idx", attr = AccessType.WO)
    logic.procDb.readbackIdx := 0
    busCtrl.write(logic.procDb.readbackIdx, readbackIdxAddr)
    busCtrl.onWrite(readbackIdxAddr) {
      readbackPort.assignSomeByName(logic.procDb.readback)
      readbackPort.queueFill := logic.queueMetas(logic.procDb.readbackIdx).fill.resized
    }

    logic.statistics.elements.foreach {
      case (name, d: UInt) => busCtrl.read(d, alloc("stat", s"Stat $name", name, attr = RO))
      case (name, v: Vec[_]) => v.zipWithIndex.foreach { case (e, idx) =>
        val addr = alloc("coreStat", s"Per core stat $name",
          s"${name}_core${idx+1}", attr = RO)
        busCtrl.read(e, addr)
      }
      case _ =>
    }
  }

  val logic = during setup new Area {
    /** Packet metadata to accept from the decoding pipeline.  Must be a [[OncRpcCallRxMeta]] */
    val rxMeta = Stream(HostReq())

    /** Packet metadata issued to the downstream [[lauberhorn.host.DatapathPlugin]].
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
    val procDb = LookupTable(ProcessDef(), NUM_PROCS+1) { v =>
      v.enabled init False
      v.maxThreads init U(NUM_WORKER_CORES)
      v.pid.bits init U(0xffff)
    }

    val statistics = new Bundle {
      val pushed, dropped = Reg(UInt(REG_WIDTH bits)) init 0
      val popped, preempted, dispatched = Vec.fill(NUM_WORKER_CORES)(Reg(UInt(REG_WIDTH bits)) init 0)
    }
    def inc(f: statistics.type => UInt): Unit = f(statistics) := f(statistics) + 1

    // per-process queues are in memory
    val queueMem = Mem(HostReq(), totalPkts)

    case class QueueMetadata(off: UInt, cap: UInt)(idx: Int) extends Bundle {
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
        assert(!full, s"queue #$idx: trying to push one into a full queue")
        advance(tail)
        fill := fill + 1
      }

      def popOne(): Unit = {
        assert(!empty, s"queue #$idx: trying to pop one from an empty queue")
        advance(head)
        fill := fill - 1
      }

      def passOne(): Unit = {
        assert(!full && !empty, s"queue #$idx: can only pass one from non-empty, non-full queue")
        advance(head)
        advance(tail)
      }
    }
    val queueMetas = Vec.tabulate(NUM_PROCS+1) { idx =>
      val offset = if (idx == 0) 0 else (idx-1) * RX_PKTS_PER_PROC
      val capacity: Int = if (idx == 0) 0 else RX_PKTS_PER_PROC
      val ret = Reg(QueueMetadata(offset, capacity)(idx))
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

    // map of which process is running on which core
    val corePidMap = Vec.fill(NUM_WORKER_CORES) {
      // all start in IDLE -- preempt request will move them away
      Reg(ProcTblIdx) init 0
    }

    // used to find a non-empty queue that has no cores, when a core has drained its queue
    case class DrainQuery() extends Bundle {
      val procEmptyMap = Bits(NUM_PROCS+1 bits)
      val corePidMap = Vec.fill(NUM_WORKER_CORES)(ProcTblIdx)
    }

    val (drainLookup, drainResult, _) = procDb.makePort(DrainQuery(), NoData(), "drain") { (v, q, idx) =>
      val coreMap = q.corePidMap.map(_ === idx).asBits()
      v.enabled && !q.procEmptyMap(idx) && coreMap === 0
    }

    // always valid to select a process to drain
    drainLookup.query.procEmptyMap := queueMetas.map(_.empty).asBits()
    drainLookup.query.corePidMap := corePidMap
    drainLookup.valid := True
    drainResult.ready := True

    // make sure no two cores will pick up the same queue to drain
    val drainProcCoreReq, drainProcCoreGrant = Bits(NUM_WORKER_CORES bits)
    drainProcCoreReq := 0
    drainProcCoreGrant := OHMasking.firstV2(drainProcCoreReq)
    val drainProcInProgress = Vec.fill(NUM_PROCS+1)(Reg(Bool()) init False)

    val (pushLookup, pushResult, _) = procDb.makePort(PID(), HostReq(),
      "rxPush", singleMatch = true) { (v, q, _) =>
      v.enabled && v.pid === q
    }

    pushLookup.translateFrom(rxMeta) { case (lk, meta) =>
      lk.query := meta.data.oncRpcCallRx.pid
      lk.userData := meta
    }
    when (rxMeta.valid) {
      assert(rxMeta.ty === HostReqType.oncRpcCall, "scheduler does not support other req types yet")
    }

    val pushResultCoreMap = corePidMap.map(_ === pushResult.idx).asBits()
    val pushResultThrCount = CountOne(pushResultCoreMap)

    // preempt request to popping side
    val rxPreemptReq = Reg(Flow(PreemptCmd()))
    rxPreemptReq.valid := False

    pushResult.ready := False
    when (pushResult.valid) {
      // received packet: push into memory
      when (queueMetas(pushResult.idx).full) {
        // the destination proc queue is full
        // since we don't have any queuing anywhere outside the scheduler, we have to drop the packet
        inc(_.dropped)
      } otherwise {
        // store at where the tail was
        queueMem.write(queueMetas(pushResult.idx).tail, pushResult.userData)

        // update pointers
        pushQ(pushResult.idx) := True

        inc(_.pushed)
      }

      // A new packet arrived, try to select a core to preempt, but do not block the RX process.
      when (pushResultThrCount < pushResult.value.maxThreads) {
        rxPreemptReq.pid := pushResult.value.pid
        rxPreemptReq.idx := pushResult.idx

        // preempting as ready takes priority
        when (queueMetas(pushResult.idx).almostFull) {
          // queue almost full (V_arrival > V_consume, need to scale up)
          // preempt a non-idle, ready core
          rxPreemptReq.ty := PreemptCmdType.ready
          rxPreemptReq.valid := True
        } elsewhen (pushResultCoreMap === 0) {
          // no process assigned to this queue -- idle preempt
          rxPreemptReq.ty := PreemptCmdType.idle
          rxPreemptReq.valid := True
        }
      }

      // we either pushed the packet or dropped it, ack
      pushResult.ready := True
    }

    // each core can raise a pop request, to remove one packet from the queues.
    // a pop request is idx of the queue from which a request should be popped
    case class PopReq() extends Bundle {
      val req, grant = Bool()
      val queueIdx = ProcTblIdx
    }
    val popReqs = Seq.fill(NUM_WORKER_CORES)(PopReq())
    popReqs.foreach { _.grant := False }
    val popReqPresent = popReqs.map(_.req).asBits()
    val grantedIdx = OHToUInt(OHMasking.firstV2(popReqPresent))
    val grantedPopReq = popReqs(grantedIdx).queueIdx
    popReqs(grantedIdx).grant := True

    val toPopReqAddr = queueMetas(grantedPopReq).head
    val poppedReq = queueMem.readSync(toPopReqAddr)

    popQ(grantedPopReq) := popReqPresent.orR

    // victim core selection, based on preemption type
    val coreIdleMap = Seq.tabulate(NUM_WORKER_CORES) { cid =>
      // idle preemption: a core that is
      // - running the IDLE process, and
      // - a preemption is not underway
      corePidMap(cid) === 0
    }.asBits()
    val coreReadyMap = Seq.tabulate(NUM_WORKER_CORES) { cid =>
      // ready preemption: a core that is
      // - running another PID, and
      // - running some proc but ready (i.e. no request ongoing), and
      // - a preemption is not underway
      corePidMap(cid) =/= rxPreemptReq.idx && coreMeta(cid).ready
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
      popReq.req := False
      popReq.queueIdx.assignDontCare()
      val savedPoppedReq = Reg(HostReq())

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
              popReq.queueIdx := corePopQueueIdx
              popReq.req := True
              when (popReq.grant) {
                goto(popReqGranted)
              }
            } elsewhen (toCore.ready && drainResult.idx =/= 0 && drainResult.valid) {
              // When a core completely drained its queue, it needs to check if there are non-empty queues that
              // have no cores assigned.  This is needed to be work-efficient and prevent excessive latency for
              // the following case:
              // - process received a request when all cores are busy, couldn't preempt any core
              // - system later became not busy, but no new requests arrive for process
              // - if we don't scan inactive queues, the request will sit there indefinitely
              // TODO: fairness?

              // we are not popping from the queue here, only preempting;
              // so no need to check again if queue is empty
              corePreempt(idx).payload := drainResult.value.pid
              savedPreemptIdx := drainResult.idx
              drainProcCoreReq(idx) := True
              when (drainProcCoreGrant(idx) && !drainProcInProgress(drainResult.idx)) {
                drainProcInProgress(drainResult.idx) := True
                goto(preempt)
              }
            }
          }
        }
        val preempt: State = new State {
          whenIsActive {
            corePreempt(idx).valid := True
            // mark core as in the destination process already;
            // if we wait until ACK, we might dispatch too many cores to the process
            corePidMap(idx) := savedPreemptIdx

            when (corePreempt(idx).ready) {
              drainProcInProgress(savedPreemptIdx) := False
              inc(_.preempted(idx))
              goto(idle)
            }
          }
        }
        val popReqGranted: State = new State {
          whenIsActive {
            inc(_.popped(idx))
            // if our pop request is granted, read happened on that cycle;
            // now we have the popped request ready

            // need to save popped req: timeout might happen in popReqCheckGrant, resulting in
            // the core not ready any more; we need to hold the same request instead of switching
            // to another request
            savedPoppedReq := poppedReq
            goto(sendPoppedReq)
          }
        }
        val sendPoppedReq: State = new State {
          whenIsActive {
            // issue the popped request to core
            toCore.payload := savedPoppedReq
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
    }.setCompositeName(this, s"core_$idx") }
  }
}
