package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.host.eci.{EciDecoupledRxTxProtocol, EciOwnership}
import lauberhorn.host.{HostReq, HostReqType, HostReqWithTrace, PreemptionService}
import lauberhorn.Global._
import lauberhorn.net.invalidTraceId
import lauberhorn.net.oncrpc.{OncRpcCallRxMeta, OncRpcCallDecoder, OncRpcReplyDecoder}
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
    val hostMsgId = UInt(HostMsgID.width bits)
  }

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    ctrl(busCtrl, alloc)
    stat(busCtrl, alloc)
  }

  def ctrl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.procDb.update.setIdle()
    if (host.list[EciOwnership].isEmpty) {
    val wakeIndex = ProcTblIdx
    wakeIndex := 0
    val wakeAddr = alloc("ctrl", "Request a worker for internal deadline progress", "wake_idx", attr = AccessType.WO)
    busCtrl.write(wakeIndex, wakeAddr)
    busCtrl.onWrite(wakeAddr) {
      when(logic.processEnabled(wakeIndex)) { logic.softwarePending(wakeIndex) := True }
    }
    val watchdogLimit = UInt(32 bits)
    busCtrl.driveAndRead(watchdogLimit, alloc("ctrl", "Execution limit in cycles; zero disables",
      "watchdog_cycles")) init 0
    val watchdogIndex = ProcTblIdx
    watchdogIndex := 0
    val watchdogAddr = alloc("ctrl", "Configure watchdog before process workers start", "watchdog_idx", attr = AccessType.WO)
    busCtrl.write(watchdogIndex, watchdogAddr)
    busCtrl.onWrite(watchdogAddr) {
      assert(watchdogIndex =/= 0 && !watchdogLimit.msb)
      assert(!logic.corePidMap.map(_ === watchdogIndex).asBits.orR,
        "watchdog configuration changed after workers started")
      logic.watchdogLimits(watchdogIndex) := watchdogLimit
      logic.watchdogKilled(watchdogIndex) := False
    }

    }

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
      val draining = Bool()
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
      readbackPort.draining := logic.processDraining(logic.procDb.readbackIdx)
    }

    logic.statistics.elements.foreach {
      case (name, d: UInt) => busCtrl.read(d, alloc("stat", s"Stat $name", name, attr = RO))
      case (name, v: Vec[_]) => v.zipWithIndex.foreach { case (e, idx) =>
        val addr = alloc("stat", s"Per core stat $name",
          s"${name}_core${idx+1}", attr = RO)
        busCtrl.read(e, addr)
      }
      case _ =>
    }
  }

  val logic = during setup new Area {
    /** Packet metadata to accept from the decoding pipeline.  Must be a [[OncRpcCallRxMeta]] */
    val rxMeta = Stream(HostReqWithTrace())
    // Rejected descriptors still own their packet allocation and reply context.
    val discarded = Stream(HostReqWithTrace())

    val queueTp = host[TracePlugin].makePort("scheduler_queue", LauberhornTraceDma.NicHostInterfaceSlr)
    val coreTps = Seq.tabulate(NUM_WORKER_CORES)(idx =>
      host[TracePlugin].makePort(s"scheduler_core${idx + 1}", LauberhornTraceDma.NicHostInterfaceSlr))

    /** Packet metadata issued to the downstream [[lauberhorn.host.DatapathPlugin]].
      *
      * Note that this is purely for the datapath and does not contain any scheduling information: switching processes
      * on a core is requested through the [[corePreempt]] interfaces.
      */
    val coreMeta = Seq.fill(NUM_WORKER_CORES)(Stream(HostReqWithTrace()))
    // Explicit withdrawal of an unaccepted descriptor during process stop.
    val coreCancel = Vec.fill(NUM_WORKER_CORES)(Bool())

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
      val p = Stream(ps.PreemptReq())
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
    // The pipelined lookup can still contain a result from before disable.
    // Gate admission/pop with the current control state as well.
    val softwarePending = Vec.fill(NUM_PROCS + 1)(RegInit(False))
    val watchdogLimits = Vec.fill(NUM_PROCS + 1)(
      if (host.list[EciOwnership].nonEmpty) U(0, 32 bits) else Reg(UInt(32 bits)) init 0)
    val watchdogKilled = Vec.fill(NUM_PROCS + 1)(RegInit(False))
    val watchdogNow = Counter(32 bits)
    watchdogNow.increment()
    val processEnabled = Vec.fill(NUM_PROCS + 1)(RegInit(False))
    val processPids = Vec.fill(NUM_PROCS + 1)(Reg(PID()))
    processPids.foreach(_.bits init U(0xffff))
    when(procDb.update.valid) {
      processEnabled(procDb.update.idx) := procDb.update.value.enabled && !watchdogKilled(procDb.update.idx)
      processPids(procDb.update.idx) := procDb.update.value.pid
      when(!procDb.update.value.enabled) { softwarePending(procDb.update.idx) := False }
    }

    val statistics = new Bundle {
      val pushed, dropped = Reg(UInt(REG_WIDTH bits)) init 0
      val watchdogControls, watchdogExpirations = Vec.fill(NUM_WORKER_CORES)(Reg(UInt(REG_WIDTH bits)) init 0)
      val popped, preempted, dispatched = Vec.fill(NUM_WORKER_CORES)(Reg(UInt(REG_WIDTH bits)) init 0)
    }
    def inc(f: statistics.type => UInt): Unit = f(statistics) := f(statistics) + 1

    // per-process queues are in memory
    val queueMem = Mem(HostReqWithTrace(), totalPkts)

    case class QueueMetadata(off: Int, cap: Int)(idx: Int) extends Bundle {
      val offset, head, tail = MemAddr
      val capacity, fill = UInt(log2Up(RX_PKTS_PER_PROC + 1) bits)

      val enqueued, dequeued = UInt(REG_WIDTH bits)

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
        head init off
        tail init off
        fill init 0
        offset := off
        capacity := cap
        enqueued init 0
        dequeued init 0
      }

      private def advance(ptr: UInt, stat: UInt): UInt = {
        val newPtr = ptr.clone
        when (ptr + 1 === offset + capacity) {
          newPtr := offset
        } otherwise {
          newPtr := ptr + 1
        }
        ptr := newPtr
        stat := stat + 1
        newPtr
      }
      private def push() = advance(tail, enqueued)
      private def pop() = advance(head, dequeued)

      def pushOne(): Unit = {
        assert(!full, s"queue #$idx: trying to push one into a full queue")
        push()
        fill := fill + 1
      }

      def popOne(): Unit = {
        assert(!empty, s"queue #$idx: trying to pop one from an empty queue")
        pop()
        fill := fill - 1
      }

      def passOne(): Unit = {
        assert(!full && !empty, s"queue #$idx: can only pass one from non-empty, non-full queue")
        push()
        pop()
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

    host.list[EciOwnership].headOption.foreach { ownership =>
      for (slot <- 1 to NUM_PROCS.get) {
        when(ownership.logic.faults.io.failed(slot)) { processEnabled(slot) := False }
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
      v.enabled && v.maxThreads =/= 0 && !q.procEmptyMap(idx) && coreMap === 0
    }

    // always valid to select a process to drain
    drainLookup.query.procEmptyMap := queueMetas.zipWithIndex.map { case (q, i) =>
      q.empty && !softwarePending(i)
    }.asBits()
    drainLookup.query.corePidMap := corePidMap
    drainLookup.valid := True
    drainResult.ready := True

    // make sure no two cores will pick up the same queue to drain
    val drainProcCoreReq, drainProcCoreGrant = Bits(NUM_WORKER_CORES bits)
    drainProcCoreReq := 0
    drainProcCoreGrant := OHMasking.firstV2(drainProcCoreReq)
    val drainProcInProgress = Vec.fill(NUM_PROCS+1)(Reg(Bool()) init False)

    val (pushLookup, pushResult, pushLookupCapacity) = procDb.makePort(PID(), HostReqWithTrace(),
      "rxPush", singleMatch = true) { (v, q, _) =>
      v.enabled && v.pid === q
    }

    def requestPid(req: HostReq): PID = new Composite(req, "requestPid") {
      val ret = PID()
      ret.bits.assignDontCare()
      switch (req.ty) {
        import HostReqType._
        is (oncRpcCallRx) { ret := req.data.oncRpcCallRx.pid }
        is (oncRpcReplyRx) { ret := req.data.oncRpcReplyRx.pid }
        default { ret.bits := 0 }
      }
    }.ret

    pushLookup.translateFrom(rxMeta) { case (lk, meta) =>
      lk.query := requestPid(meta.req)
      lk.userData := meta
    }
    when (rxMeta.valid) {
      assert(
        rxMeta.req.ty === HostReqType.oncRpcCallRx || rxMeta.req.ty === HostReqType.oncRpcReplyRx,
        "scheduler only supports ONC-RPC call RX and reply RX requests")
    }

    val pushResultCoreMap = corePidMap.map(_ === pushResult.idx).asBits()
    val pushResultThrCount = CountOne(pushResultCoreMap)

    // preempt request to popping side
    val rxPreemptReq = Reg(Flow(PreemptCmd()))
    rxPreemptReq.valid := False
    rxPreemptReq.hostMsgId := invalidTraceId(HostMsgID.width)

    pushResult.ready := False
    val pushDiscard, queueDiscard = Stream(HostReqWithTrace())
    val coreDiscards = Seq.fill(NUM_WORKER_CORES)(Stream(HostReqWithTrace()))
    // Register each producer before arbitration as well: cancellation/queue
    // selection must not share a cycle with arbitration and owner capture.
    val discardInputs = (Seq(pushDiscard, queueDiscard) ++ coreDiscards).map(_.m2sPipe())
    val selectedDiscard = StreamArbiterFactory().roundRobin.on(discardInputs)
    val discardStage = new RecoveryStreamBuffer(HostReqWithTrace(), PID_WIDTH.get)
    discardStage.io.push << selectedDiscard
    discardStage.io.pushOwner := requestPid(selectedDiscard.req).bits
    discarded << discardStage.io.pop
    when(discarded.fire) { inc(_.dropped) }
    queueTp.trace("SchedulerRequestDropped",
      HostMsgID(discarded.hostMsgId),
      ProcessID(requestPid(discarded.req).bits)) := discarded.fire
    pushDiscard.valid := False
    pushDiscard.payload := pushResult.userData
    when (pushResult.valid) {
      // received packet: push into memory
      when (!pushResult.matched || !processEnabled(pushResult.idx) || queueMetas(pushResult.idx).full) {
        // the destination proc queue is full
        // since we don't have any queuing anywhere outside the scheduler, we have to drop the packet
        pushDiscard.valid := True
        pushResult.ready := pushDiscard.ready
      } otherwise {
        pushResult.ready := True
        // store at where the tail was
        queueMem.write(queueMetas(pushResult.idx).tail, pushResult.userData)

        // update pointers
        pushQ(pushResult.idx) := True

        inc(_.pushed)
        queueTp.trace("SchedulerRequestQueued",
          HostMsgID(pushResult.userData.hostMsgId),
          ProcessID(pushResult.value.pid.bits)) := True
      }

      // A new packet arrived, try to select a core to preempt, but do not block the RX process.
      when (pushResult.fire && pushResult.matched && processEnabled(pushResult.idx) && !queueMetas(pushResult.idx).full &&
            pushResultThrCount < pushResult.value.maxThreads) {
        rxPreemptReq.pid := pushResult.value.pid
        rxPreemptReq.idx := pushResult.idx
        rxPreemptReq.hostMsgId := pushResult.userData.hostMsgId

        // preempting as ready takes priority
        when (queueMetas(pushResult.idx).almostFull) {
          // queue almost full (V_arrival > V_consume, need to scale up)
          // in addition to preempting idle cores, also allow preempting a ready one
          rxPreemptReq.ty := PreemptCmdType.ready
          rxPreemptReq.valid := True
        } elsewhen (pushResultCoreMap === 0) {
          // no process assigned to this queue -- idle preempt
          rxPreemptReq.ty := PreemptCmdType.idle
          rxPreemptReq.valid := True
        }
      }

    }

    // each core can raise a pop request, to remove one packet from the queues.
    // a pop request is idx of the queue from which a request should be popped
    case class PopReq() extends Bundle {
      val req, grant = Bool()
      val queueIdx = ProcTblIdx
    }
    // One extra reader drains disabled process queues through the same allocator
    // return path as rejected incoming packets. Never reset queue pointers and
    // lose the packet allocations that they own.
    val popReqs = Seq.fill(NUM_WORKER_CORES + 1)(PopReq())
    popReqs.foreach { _.grant := False }
    val popReqPresent = popReqs.map(_.req).asBits()
    val grantedIdx = OHToUInt(OHMasking.firstV2(popReqPresent))
    val grantedPopReq = popReqs(grantedIdx).queueIdx
    popReqs(grantedIdx).grant := True

    val toPopReqAddr = queueMetas(grantedPopReq).head
    val poppedReq = queueMem.readSync(toPopReqAddr)

    popQ(grantedPopReq) := popReqPresent.orR

    val reclaimPop = popReqs.last
    reclaimPop.req := False
    reclaimPop.queueIdx := 0
    queueDiscard.setIdle()
    val reclaimCandidates = queueMetas.zipWithIndex.map { case (q, i) =>
      !processEnabled(i) && !q.empty && Bool(i != 0)
    }.asBits()
    val reclaimIdx = OHToUInt(OHMasking.firstV2(reclaimCandidates))
    val reclaimSaved = Reg(HostReqWithTrace())
    val reclaimOwner = Reg(ProcTblIdx) init 0
    val reclaimFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          reclaimPop.req := reclaimCandidates.orR
          reclaimPop.queueIdx := reclaimIdx
          when(reclaimPop.req && reclaimPop.grant) { reclaimOwner := reclaimIdx; goto(capture) }
        }
      }
      val capture: State = new State {
        whenIsActive {
          reclaimSaved := poppedReq
          goto(discard)
        }
      }
      val discard: State = new State {
        whenIsActive {
          queueDiscard.valid := True
          queueDiscard.payload := reclaimSaved
          when(queueDiscard.fire) { reclaimOwner := 0; goto(idle) }
        }
      }
    }
    reclaimFsm.build()

    // Ownership starts when decoding admits a descriptor, before DMA/payload
    // storage. Retain it through the pipelined process lookup even after disable.
    val admissions = Seq(host[OncRpcCallDecoder].logic.admission,
      host[OncRpcReplyDecoder].logic.admission)
    // DecoderSink has one FULL (two-record) stage for each RPC decoder,
    // DmaControl has one active record, and LookupTable exports its stage count.
    // One additional record covers a departure waiting in the accounting stage.
    val inTransitCapacity = 2 * admissions.size + 1 + pushLookupCapacity + 1
    val inTransitBusy = Vec.fill(NUM_PROCS + 1)(Bool())
    inTransitBusy(0) := False
    for(i <- 1 to NUM_PROCS.get) {
      val accounting = new RecoveryInFlight(inTransitCapacity, admissions.size)
      accounting.io.arrivals := admissions.map(a => a.valid && a.payload === processPids(i)).asBits
      accounting.io.departure := pushResult.fire && requestPid(pushResult.userData.req) === processPids(i)
      inTransitBusy(i) := accounting.io.busy
    }

    // Actual old owner is retained through the entire preemption handshake;
    // corePidMap reserves the next owner earlier to bound parallelism.
    val coreResident = Vec.fill(NUM_WORKER_CORES)(Reg(ProcTblIdx) init 0)
    for(i <- 1 to NUM_PROCS.get) {
      when((0 until NUM_WORKER_CORES).map(c => coreResident(c) === i && !corePreempt(c).valid).asBits.orR) {
        softwarePending(i) := False
      }
    }
    val processDraining = Vec.tabulate(NUM_PROCS + 1) { pid =>
      val busy = inTransitBusy(pid) || !queueMetas(pid).empty || reclaimOwner === pid ||
        discardStage.io.owners.map(o => o.valid && o.payload === processPids(pid).bits).asBits.orR ||
        discardInputs.map(d => d.valid && requestPid(d.req) === processPids(pid)).asBits.orR ||
        coreResident.map(_ === pid).asBits.orR || corePidMap.map(_ === pid).asBits.orR
      // Drain completion is meaningful only after admission is disabled. Keep
      // enabled processes busy so the status pipeline cannot expose an old idle
      // sample on the disable edge. Subsequent samples include immediate arrivals.
      // This cuts reply validation/admission out of the CSR selection cycle.
      RegNext(busy || processEnabled(pid)) init True
    }

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
      PreemptCmdType.ready -> Mux(coreIdleMap.orR, coreIdleMap, coreReadyMap),
      // TODO: when do we need the `force` command type?
      //       when we need to kill a running handler?
      default -> B(0),
    )
    val victimCoreMapSel = OHMasking.firstV2(victimCoreMap)

    0 until NUM_WORKER_CORES foreach { idx => new Area {
      val coreTp = coreTps(idx)
      val toCore = coreMeta(idx)
      toCore.setIdle()
      coreCancel(idx) := False
      val coreDiscard = coreDiscards(idx)
      coreDiscard.setIdle()

      val popReq = popReqs(idx)
      popReq.req := False
      popReq.queueIdx.assignDontCare()
      val savedPoppedReq = Reg(HostReqWithTrace())

      val corePopQueueIdx = corePidMap(idx)
      val execution = host.list[EciDecoupledRxTxProtocol].find(_.coreID == idx + 1)
      val watchdog = new ExecutionWatchdog()
      watchdog.io.now := watchdogNow.value
      watchdog.io.limit := RegNext(watchdogLimits(coreResident(idx))) init 0
      watchdog.io.enabled := False
      if (host.list[EciOwnership].isEmpty) {
        watchdog.io.enabled := RegNext(watchdogLimits(coreResident(idx)) =/= 0 && coreResident(idx) =/= 0) init False
      }
      // Register scheduler/TX events before the deadline register's enable.
      // Clear both immediately and for the following cycle, discarding events
      // from the previous owner rather than starting them on a new process.
      watchdog.io.clear := corePreempt(idx).valid || (RegNext(corePreempt(idx).valid) init False)
      val executionBegin = toCore.fire && toCore.req.ty === HostReqType.oncRpcCallRx
      val executionEnd = Bool()
      executionEnd := False
      watchdog.io.begin := RegNext(executionBegin) init False
      watchdog.io.end := RegNext(executionEnd) init False
      execution.foreach { proto =>
        when(proto.logic.execution.valid) {
          when(proto.logic.execution.payload(1)) { executionBegin := True }
          executionEnd := proto.logic.execution.payload(0)
        }
        when(proto.logic.execution.valid && proto.logic.executionOnly) {
          inc(_.watchdogControls(idx))
        }
      }
      when(watchdog.io.expired.rise()) {
        inc(_.watchdogExpirations(idx))
        coreTp.trace("SchedulerWatchdogExpired", CoreID(B(idx + 1, CoreID.width bits)),
          ProcessID(processPids(coreResident(idx)).bits))
      }
      when(watchdog.io.expired && coreResident(idx) =/= 0) {
        processEnabled(coreResident(idx)) := False
        watchdogKilled(coreResident(idx)) := True
      }

      corePreempt(idx).valid := False
      // save the requested preemption target until preemption is actually done
      val savedPreemptIdx = Reg(ProcTblIdx)
      val savedPreemptPid = Reg(PID())
      val savedPreemptHostMsgId = Reg(UInt(HostMsgID.width bits)) init invalidTraceId(HostMsgID.width)

      val coreTd = CoreID(B(idx + 1, CoreID.width bits))

      val popFsm = new StateMachine {
        val idle: State = new State with EntryPoint {
          whenIsActive {
            when (corePopQueueIdx =/= 0 && !processEnabled(corePopQueueIdx)) {
              if (host.list[EciOwnership].isEmpty) {
              corePreempt(idx).kill := watchdogKilled(corePopQueueIdx)
              corePreempt(idx).pid.bits := 0
              corePreempt(idx).outOfIdle := False
              savedPreemptIdx := 0
              savedPreemptPid.bits := 0
              savedPreemptHostMsgId := invalidTraceId(HostMsgID.width)
              coreTp.trace("SchedulerPreemptCore",
                HostMsgID(invalidTraceId(HostMsgID.width)), ProcessID(U(0, PID_WIDTH bits)),
                PreviousProcessID(processPids(coreResident(idx)).bits),
                coreTd, PreemptCommand(PreemptCmdType.force.asBits),
                PreemptOutOfIdle(False), QueueIndex(corePopQueueIdx),
                QueueFill(queueMetas(corePopQueueIdx).fill))
              goto(preempt)
              } // ECI fatal lifetimes retain their worker; no recovery handoff.
            } elsewhen (rxPreemptReq.valid && processEnabled(rxPreemptReq.idx) && victimCoreMapSel(idx)) {
              // we are selected as the eviction target
              // capture requested PID since it's a Flow and only valid for one cycle
              corePreempt(idx).kill := False
              corePreempt(idx).pid := rxPreemptReq.pid

              // Only an idle reservation authorizes a scheduling kick.
              corePreempt(idx).outOfIdle := coreIdleMap(idx)

              corePidMap(idx) := rxPreemptReq.idx
              savedPreemptIdx := rxPreemptReq.idx
              savedPreemptPid := rxPreemptReq.pid
              savedPreemptHostMsgId := rxPreemptReq.hostMsgId
              coreTp.trace("SchedulerPreemptCore",
                HostMsgID(rxPreemptReq.hostMsgId),
                ProcessID(rxPreemptReq.pid.bits),
                PreviousProcessID(processPids(coreResident(idx)).bits),
                coreTd,
                PreemptCommand(rxPreemptReq.ty.asBits),
                PreemptOutOfIdle(coreIdleMap(idx)),
                QueueIndex(rxPreemptReq.idx),
                QueueFill(queueMetas(rxPreemptReq.idx).fill))
              goto(preempt)
            } elsewhen (toCore.ready && processEnabled(corePopQueueIdx) && !queueMetas(corePopQueueIdx).empty) {
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
            } elsewhen (!rxPreemptReq.valid && (toCore.ready || coreIdleMap(idx)) &&
                        drainResult.idx =/= 0 && drainResult.valid && drainResult.matched &&
                        processEnabled(drainResult.idx) && !corePidMap.map(_ === drainResult.idx).asBits.orR) {
              // FIXME: can we simplify and merge this case with the RX preemption case?

              // When a core completely drained its queue, it needs to check if there are non-empty queues that
              // have no cores assigned.  This is needed to be work-efficient and prevent excessive latency for
              // the following case:
              // - process received a request when all cores are busy, couldn't preempt any core
              // - system later became not busy, but no new requests arrive for process
              // - if we don't scan inactive queues, the request will sit there indefinitely
              // TODO: fairness?

              // we are not popping from the queue here, only preempting;
              // so no need to check again if queue is empty
              corePreempt(idx).kill := False
              corePreempt(idx).pid := drainResult.value.pid
              corePreempt(idx).outOfIdle := coreIdleMap(idx)
              savedPreemptIdx := drainResult.idx
              savedPreemptPid := drainResult.value.pid
              savedPreemptHostMsgId := invalidTraceId(HostMsgID.width)
              drainProcCoreReq(idx) := True
              when (drainProcCoreGrant(idx) && !drainProcInProgress(drainResult.idx)) {
                corePidMap(idx) := drainResult.idx
                drainProcInProgress(drainResult.idx) := True
                coreTp.trace("SchedulerPreemptCore",
                  HostMsgID(invalidTraceId(HostMsgID.width)), // invalid in the drain case
                  ProcessID(drainResult.value.pid.bits),
                  PreviousProcessID(processPids(coreResident(idx)).bits),
                  coreTd,
                  PreemptCommand(PreemptCmdType.ready.asBits),
                  PreemptOutOfIdle(coreIdleMap(idx)),
                  QueueIndex(drainResult.idx),
                  QueueFill(queueMetas(drainResult.idx).fill))
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
              coreResident(idx) := savedPreemptIdx
              drainProcInProgress(savedPreemptIdx) := False
              inc(_.preempted(idx))
              coreTp.trace("SchedulerProcessRun",
                HostMsgID(savedPreemptHostMsgId),
                ProcessID(savedPreemptPid.bits),
                coreTd)
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
            toCore.valid := processEnabled(corePopQueueIdx)
            when(!processEnabled(corePopQueueIdx)) {
              coreCancel(idx) := True
              coreDiscard.payload := savedPoppedReq
              coreDiscard.valid := True
              when(coreDiscard.fire) { goto(idle) }
            }

            when (toCore.fire) {
              // no guarantee that worker must accept request: they might de-assert ready due to a read timeout
              // have to wait until they try again (and re-assert ready)
              // TODO: what happens if the core went amok and never retried? Kill proc?

              inc(_.dispatched(idx))
              coreTp.trace("SchedulerRequestDispatched",
                HostMsgID(savedPoppedReq.hostMsgId),
                ProcessID(requestPid(savedPoppedReq.req).bits),
                coreTd)
              goto(idle)
            }
          }
        }
      }
      popFsm.build()
    }.setCompositeName(this, s"core_$idx") }
  }
}
