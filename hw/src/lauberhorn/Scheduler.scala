package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import jsteward.blocks.misc.{LookupTable, RegBlockAlloc}
import lauberhorn.host.eci.{EciDecoupledRxTxProtocol, EciOwnership}
import lauberhorn.host.{HostReq, HostReqType, HostReqWithTrace, DeschedulingService}
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
  * Assignment-change intent between packet arrival and request delivery.
  * ECI executes ordinary descheduling only when the worker waits for RX;
  * it never interrupts a running handler.
  *
  *  - [[idle]]: select an unassigned worker
  *  - [[ready]]: select an idle worker or one waiting for RX
  *  - [[force]]: legacy kill intent; unsupported by the cooperative ECI path
  */
object DeschedCmdType extends SpinalEnum {
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

  case class PacketArrival() extends Bundle {
    val idx = ProcTblIdx
    val fill = UInt(log2Up(RX_PKTS_PER_PROC + 1) bits)
    val hostMsgId = UInt(HostMsgID.width bits)
  }

  def driveControl(bus: AxiLite4, alloc: RegBlockAlloc): Unit = {
    val busCtrl = AxiLite4SlaveFactory(bus)
    ctrl(busCtrl, alloc)
    stat(busCtrl, alloc)
  }

  def ctrl(busCtrl: BusSlaveFactory, alloc: RegBlockAlloc): Unit = {
    logic.procDb.update.setIdle()
    new DeadlineRegisters(busCtrl, alloc, logic.deadlines, logic.deadlineNow, logic.deadlineMinimumLead)
    if (host.list[EciOwnership].isEmpty) {
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
    /** Routable packet metadata accepted from the decoding pipeline. */
    val rxMeta = Stream(HostReqWithTrace())
    // Rejected descriptors still own their packet allocation and reply context.
    val discarded = Stream(HostReqWithTrace())

    val queueTp = host[TracePlugin].makePort("scheduler_queue", LauberhornTraceDma.NicHostInterfaceSlr)
    val coreTps = Seq.tabulate(NUM_WORKER_CORES)(idx =>
      host[TracePlugin].makePort(s"scheduler_core${idx + 1}", LauberhornTraceDma.NicHostInterfaceSlr))
    val timerTps = Seq.tabulate(NUM_PROCS.get + 1)(idx =>
      host[TracePlugin].makePort(s"scheduler_deadline$idx", LauberhornTraceDma.NicHostInterfaceSlr))
    val deadlineTps = Seq.tabulate(NUM_WORKER_CORES)(idx =>
      host[TracePlugin].makePort(s"scheduler_core${idx + 1}_deadline", LauberhornTraceDma.NicHostInterfaceSlr))

    /** Packet metadata issued to the downstream [[lauberhorn.host.DatapathPlugin]].
      *
      * Note that this is purely for the datapath and does not contain any scheduling information: switching processes
      * on a core is requested through the [[coreDesched]] interfaces.
      */
    val coreMeta = Seq.fill(NUM_WORKER_CORES)(Stream(HostReqWithTrace()))
    // Explicit withdrawal of an unaccepted descriptor during process stop.
    val coreCancel = Vec.fill(NUM_WORKER_CORES)(Bool())

    awaitBuild()

    /**
      * Request a core to switch to a different process.  Interaction with [[coreMeta]] happens in the following order:
      *  - hold requests in [[coreMeta]] (valid === False)
      *  - issue request on [[coreDesched]] (wait until ready && valid === True)
      *  - re-assign PID queue, allow requests to continue on [[coreMeta]]
      *
      * Will be stalled (ready === False) when a descheduling is in progress.
      */
    val coreDesched = host.list[DeschedulingService].map { ps =>
      val p = Stream(ps.DeschedReq())
      p.payload.setAsReg()
      p >> ps.deschedReq
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
    val watchdogLimits = Vec.fill(NUM_PROCS + 1)(
      if (host.list[EciOwnership].nonEmpty) U(0, 32 bits) else Reg(UInt(32 bits)) init 0)
    val watchdogKilled = Vec.fill(NUM_PROCS + 1)(RegInit(False))
    val watchdogNow = Counter(32 bits)
    watchdogNow.increment()
    val processMaximum = Vec.fill(NUM_PROCS + 1)(Reg(UInt(log2Up(NUM_WORKER_CORES + 1) bits)) init 0)
    val processEnabled = Vec.fill(NUM_PROCS + 1)(RegInit(False))
    val processPids = Vec.fill(NUM_PROCS + 1)(Reg(PID()))
    processPids.foreach(_.bits init U(0xffff))
    when(procDb.update.valid) {
      processEnabled(procDb.update.idx) := procDb.update.value.enabled && !watchdogKilled(procDb.update.idx)
      processPids(procDb.update.idx) := procDb.update.value.pid
      processMaximum(procDb.update.idx) := procDb.update.value.maxThreads
    }

    // A bound lifetime can publish before its worker pool becomes runnable.
    val deadlineNow = host[GlobalCSRPlugin].logic.status.cycles
    val deadlineMinimumLead = (BigInt(ClockDomain.current.frequency.getValue.toLong) * DEADLINE_MIN_LEAD_NS.get + 999999999) / 1000000000
    val deadlines = Seq.tabulate(NUM_PROCS.get + 1) { i =>
      val slot = new DomainDeadline(minimumLead = deadlineMinimumLead)
      slot.io.now := deadlineNow
      slot.io.claim := False
      host.list[EciOwnership].headOption match {
        case Some(ownership) =>
          slot.io.enabled := ownership.logic.domainBound(i) && !ownership.logic.faults.io.failed(i) && Bool(i != 0)
          slot.io.lifetime := ownership.logic.domainCodes(i).resized
        case None =>
          slot.io.enabled := processEnabled(i) && Bool(i != 0)
          slot.io.lifetime := processPids(i).bits.resized
      }
      val timerTp = timerTps(i)
      val publication = slot.io.command.valid && slot.io.accepted
      val expiry = slot.io.pending.rise()
      timerTp.trace("SchedulerDeadlineState", QueueIndex(U(i, log2Up(NUM_PROCS.get + 1) bits)),
        DeadlineAction(expiry ## publication),
        DeadlineGenerationLow((slot.io.generation + publication.asUInt).resize(32))) := expiry || publication
      slot
    }
    val deadlineRunnable = deadlines.map(_.io.runnable).asBits
    val packetDue = Vec.fill(NUM_PROCS + 1)(RegInit(False))
    val deadlineRequests = Bits(NUM_WORKER_CORES bits)
    deadlineRequests := 0
    val deadlineSelected = OHMasking.firstV2(deadlineRequests)

    val statistics = new Bundle {
      val pushed, dropped = Reg(UInt(REG_WIDTH bits)) init 0
      val watchdogControls, watchdogExpirations = Vec.fill(NUM_WORKER_CORES)(Reg(UInt(REG_WIDTH bits)) init 0)
      val popped, descheduled, dispatched = Vec.fill(NUM_WORKER_CORES)(Reg(UInt(REG_WIDTH bits)) init 0)
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
      // all start in IDLE -- desched request will move them away
      Reg(ProcTblIdx) init 0
    }

    val (pushLookup, pushResult, pushLookupCapacity) = procDb.makePort(PID(), HostReqWithTrace(),
      "rxPush", singleMatch = true) { (v, q, _) =>
      v.enabled && v.pid === q
    }

    def requestPid(req: HostReq): PID = new Composite(req, "requestPid") {
      val ret = PID()
      ret.bits.assignDontCare()
      switch (req.ty) {
        import HostReqType._
        is (nativeUdpRx) { ret := req.data.nativeUdp.pid }
        is (nativeTcpRx) { ret := req.data.nativeTcp.pid }
        is (deadline) { ret := req.data.deadline.pid }
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
        rxMeta.req.ty === HostReqType.nativeTcpRx || rxMeta.req.ty === HostReqType.nativeUdpRx || rxMeta.req.ty === HostReqType.oncRpcCallRx || rxMeta.req.ty === HostReqType.oncRpcReplyRx,
        "scheduler requires a routable request")
    }

    // Capture the admission event; allocation rechecks live state next cycle.
    val packetArrival = Reg(Flow(PacketArrival()))
    packetArrival.valid init False
    packetArrival.valid := False
    packetArrival.hostMsgId := invalidTraceId(HostMsgID.width)

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

      when (pushResult.fire && pushResult.matched && processEnabled(pushResult.idx) && !queueMetas(pushResult.idx).full) {
        packetArrival.valid := True
        packetArrival.idx := pushResult.idx
        packetArrival.fill := queueMetas(pushResult.idx).fill
        packetArrival.hostMsgId := pushResult.userData.hostMsgId
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
    val popReadValid = RegInit(False)
    val popReadOwner = Reg(ProcTblIdx) init 0
    // A pending read keeps its queue slot occupied until BRAM samples it.
    // Prevent a second reader taking the same head in the intervening cycle.
    val popReqPresent = popReqs.map(p => p.req &&
      !(popReadValid && p.queueIdx === popReadOwner)).asBits()
    val popWinner = OHMasking.firstV2(popReqPresent)
    val grantedPopReq = MuxOH(popWinner, popReqs.map(_.queueIdx))
    popReqs.zipWithIndex.foreach { case (request, i) => request.grant := popWinner(i) }
    // Select predecoded addresses directly; avoid priority encode -> queue-index
    // mux -> head mux on the readiness path.
    val selectedHead = MuxOH(popWinner, popReqs.map(p => queueMetas(p.queueIdx).head))

    // Register the address before BRAM, retaining the queue slot until the
    // read edge. This also prevents a simultaneous enqueue overwriting a freed
    // slot before it was read. The granted reader holds its process ownership.
    popReadValid := popReqPresent.orR
    when(popReqPresent.orR) { popReadOwner := grantedPopReq }
    val toPopReqAddr = RegNextWhen(selectedHead, popReqPresent.orR)
    val poppedReq = queueMem.readSync(toPopReqAddr, popReadValid)

    when(popReadValid) {
      assert(!queueMetas(popReadOwner).empty, "reserved queue head disappeared before read")
      assert(toPopReqAddr === queueMetas(popReadOwner).head, "reserved queue head changed before read")
    }
    popQ(popReadOwner) := popReadValid

    val reclaimPop = popReqs.last
    reclaimPop.req := False
    queueDiscard.setIdle()
    val reclaimCandidates = queueMetas.zipWithIndex.map { case (q, i) =>
      !processEnabled(i) && !q.empty && Bool(i != 0)
    }.asBits()
    val reclaimIdx = OHToUInt(OHMasking.firstV2(reclaimCandidates))
    reclaimPop.queueIdx := reclaimIdx
    val reclaimSaved = Reg(HostReqWithTrace())
    val reclaimOwner = Reg(ProcTblIdx) init 0
    val reclaimFsm = new StateMachine {
      val idle: State = new State with EntryPoint {
        whenIsActive {
          reclaimPop.req := reclaimCandidates.orR
          when(reclaimPop.req && reclaimPop.grant) { reclaimOwner := reclaimIdx; goto(readWait) }
        }
      }
      val readWait: State = new State { whenIsActive { goto(capture) } }
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
      host[OncRpcReplyDecoder].logic.admission,
      host[lauberhorn.net.udp.NativeUdpDecoder].logic.admission,
      host[lauberhorn.net.tcp.NativeTcpDecoder].logic.admission)
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

    // Actual old owner is retained through the entire descheduling handshake;
    // corePidMap reserves the next owner earlier to bound parallelism.
    val coreResident = Vec.fill(NUM_WORKER_CORES)(Reg(ProcTblIdx) init 0)
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

    // All normal reservations share one arbiter. Current owner includes a
    // reservation before handoff, so every grant rechecks the recipient limit.
    val allocation = new SchedulerAllocation(NUM_PROCS, NUM_WORKER_CORES, RX_PKTS_PER_PROC)
    allocation.io.enabled := processEnabled.asBits
    allocation.io.maximum := processMaximum
    allocation.io.fill := Vec(queueMetas.map(_.fill))
    allocation.io.owner := corePidMap
    allocation.io.available := 0 // only an idle dispatch FSM may reserve
    allocation.io.ready := coreMeta.map(_.ready).asBits
    allocation.io.deadlines := deadlineRunnable
    allocation.io.arrival.valid := packetArrival.valid
    allocation.io.arrival.payload := packetArrival.idx
    allocation.io.arrivalFill := packetArrival.fill
    val coreIdleMap = corePidMap.map(_ === 0).asBits
    val allocationGrant = allocation.io.grant
    val allocationPid = processPids(allocationGrant.slot)
    val allocationTrace = UInt(HostMsgID.width bits)
    allocationTrace := invalidTraceId(HostMsgID.width)
    when(allocationGrant.reason === AllocationReason.initialPacket ||
         allocationGrant.reason === AllocationReason.packetBacklog) {
      allocationTrace := packetArrival.hostMsgId
    }

    0 until NUM_WORKER_CORES foreach { idx => new Area {
      val coreTp = coreTps(idx)
      val deadlineTp = deadlineTps(idx)
      val toCore = coreMeta(idx)
      toCore.setIdle()
      coreCancel(idx) := False
      val coreDiscard = coreDiscards(idx)
      coreDiscard.setIdle()

      val popReq = popReqs(idx)
      popReq.req := False
      popReq.queueIdx := corePidMap(idx)
      val savedPoppedReq = Reg(HostReqWithTrace())

      val corePopQueueIdx = corePidMap(idx)
      // Sample the candidate independently of arbitration. If selected on this
      // edge, captureDeadline consumes that edge's generation even if software
      // supersedes the timer concurrently or on the following cycle.
      val deadlineGenerationAtSelection = RegNext(Vec(deadlines.map(_.io.generation))(corePopQueueIdx))
      val deadlinePidAtSelection = RegNext(processPids(corePopQueueIdx))
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
      watchdog.io.clear := coreDesched(idx).valid || (RegNext(coreDesched(idx).valid) init False)
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

      coreDesched(idx).valid := False
      // save the requested descheduling target until descheduling is actually done
      val savedDeschedIdx = Reg(ProcTblIdx)
      val savedDeschedPid = Reg(PID())
      val savedDeschedHostMsgId = Reg(UInt(HostMsgID.width bits)) init invalidTraceId(HostMsgID.width)

      val coreTd = CoreID(B(idx + 1, CoreID.width bits))

      val popFsm = new StateMachine {
        val idle: State = new State with EntryPoint {
          whenIsActive {
            allocation.io.available(idx) := corePopQueueIdx === 0 || processEnabled(corePopQueueIdx)
            when (corePopQueueIdx =/= 0 && !processEnabled(corePopQueueIdx)) {
              if (host.list[EciOwnership].isEmpty) {
              coreDesched(idx).kill := watchdogKilled(corePopQueueIdx)
              coreDesched(idx).pid.bits := 0
              coreDesched(idx).outOfIdle := False
              savedDeschedIdx := 0
              savedDeschedPid.bits := 0
              savedDeschedHostMsgId := invalidTraceId(HostMsgID.width)
              coreTp.trace("SchedulerDeschedCore",
                HostMsgID(invalidTraceId(HostMsgID.width)), ProcessID(U(0, PID_WIDTH bits)),
                PreviousProcessID(processPids(coreResident(idx)).bits),
                coreTd, DeschedCommand(DeschedCmdType.force.asBits),
                DeschedOutOfIdle(False), QueueIndex(corePopQueueIdx),
                QueueFill(queueMetas(corePopQueueIdx).fill))
              goto(desched)
              } // ECI fatal lifetimes retain their worker; no recovery handoff.
            } elsewhen (allocationGrant.valid && allocationGrant.core === idx) {
              // we are selected as the eviction target
              // Commit the reservation in the same cycle as the arbiter grant.
              coreDesched(idx).kill := False
              coreDesched(idx).pid := allocationPid

              // Only an idle reservation authorizes a scheduling kick.
              coreDesched(idx).outOfIdle := coreIdleMap(idx)

              corePidMap(idx) := allocationGrant.slot
              savedDeschedIdx := allocationGrant.slot
              savedDeschedPid := allocationPid
              savedDeschedHostMsgId := allocationTrace
              coreTp.trace("SchedulerDeschedCore",
                HostMsgID(allocationTrace),
                ProcessID(allocationPid.bits),
                PreviousProcessID(processPids(coreResident(idx)).bits),
                coreTd,
                DeschedCommand(Mux(coreIdleMap(idx), DeschedCmdType.idle, DeschedCmdType.ready).asBits),
                DeschedOutOfIdle(coreIdleMap(idx)),
                QueueIndex(allocationGrant.slot),
                QueueFill(queueMetas(allocationGrant.slot).fill))
              when(allocationGrant.reason === AllocationReason.deadline) {
                deadlineTp.trace("SchedulerDeadlineActivation", coreTd, QueueIndex(allocationGrant.slot))
              }
              goto(desched)
            } elsewhen (toCore.ready && processEnabled(corePopQueueIdx) && deadlineRunnable(corePopQueueIdx) &&
                        (!packetDue(corePopQueueIdx) || queueMetas(corePopQueueIdx).empty)) {
              deadlineRequests(idx) := True
              when(deadlineSelected(idx)) {
                for (slot <- 1 to NUM_PROCS.get) {
                  when(corePopQueueIdx === slot) { deadlines(slot).io.claim := True; packetDue(slot) := True }
                }
                deadlineTp.trace("SchedulerDeadlineSelected", coreTd, QueueIndex(corePopQueueIdx),
                  DeadlineGenerationLow(Vec(deadlines.map(_.io.generation))(corePopQueueIdx).resize(32)))
                goto(captureDeadline)
              }
            } elsewhen (toCore.ready && processEnabled(corePopQueueIdx) && !queueMetas(corePopQueueIdx).empty) {
              // core ready, we can ask for a request to be popped
              // XXX: this goes against the Stream semantics (https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/stream.html#semantics):
              //      "It is recommended that valid does not depend on ready at all":
              //      we will only assert valid, when a core is ready.
              //      not a combinatorial dependency, so we are still ok
              //      needed since we don't want to pop a request when core is not ready, since after popping one:
              //      - allow desched?  need to somehow put it back in the queue
              //      - not allow desched?  no chance in practice to desched then
              popReq.req := True
              when (popReq.grant) {
                packetDue(corePopQueueIdx) := False
                goto(popReadWait)
              }
            }
          }
        }
        val captureDeadline: State = new State {
          whenIsActive {
            savedPoppedReq.req.buffer.clearAll()
            savedPoppedReq.req.len.bits := 0
            savedPoppedReq.req.ty := HostReqType.deadline
            savedPoppedReq.req.data.deadline.generation := deadlineGenerationAtSelection.asBits
            savedPoppedReq.req.data.deadline.pid := deadlinePidAtSelection
            savedPoppedReq.hostMsgId := invalidTraceId(HostMsgID.width)
            goto(sendPoppedReq)
          }
        }
        val desched: State = new State {
          whenIsActive {
            coreDesched(idx).valid := True
            // mark core as in the destination process already;
            // if we wait until ACK, we might dispatch too many cores to the process
            corePidMap(idx) := savedDeschedIdx

            when (coreDesched(idx).ready) {
              coreResident(idx) := savedDeschedIdx
              inc(_.descheduled(idx))
              coreTp.trace("SchedulerProcessRun",
                HostMsgID(savedDeschedHostMsgId),
                ProcessID(savedDeschedPid.bits),
                coreTd)
              goto(idle)
            }
          }
        }
        val popReadWait: State = new State { whenIsActive { goto(popReqGranted) } }
        val popReqGranted: State = new State {
          whenIsActive {
            inc(_.popped(idx))
            // The registered read address reached BRAM in the previous
            // cycle; now its descriptor is ready.

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
              when(savedPoppedReq.req.ty === HostReqType.deadline) {
                goto(idle) // no packet allocation belongs to a deadline
              } otherwise {
                coreDiscard.valid := True
                when(coreDiscard.fire) { goto(idle) }
              }
            }

            when (toCore.fire) {
              // no guarantee that worker must accept request: they might de-assert ready due to a read timeout
              // have to wait until they try again (and re-assert ready)
              // TODO: what happens if the core went amok and never retried? Kill proc?

              when(savedPoppedReq.req.ty === HostReqType.deadline) {
                deadlineTp.trace("SchedulerDeadlineDelivered", coreTd, QueueIndex(corePopQueueIdx),
                  DeadlineGenerationLow(savedPoppedReq.req.data.deadline.generation.asUInt.resize(32)))
              }
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
