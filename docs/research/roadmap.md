# Pending features and design decisions

## Go toward etcd and HOST_WORK — October 13

The [Go runtime design](../proposals/go-etcd.md) records the new direction: adapt Go, implement
HOST_WORK, validate memcached hash expansion, and run native Go null-RPC with
nested calls before porting Go Raft and full etcd/client functionality. The
current instruction is **documentation only; stop before implementation**.
No runtime, kernel, RTL, CI or board change accompanies this design record.

HOST_WORK is the new name for the proposed notification that completes an
assigned worker's RX wait to service host-side work. It is not DESCHED or a
direct wake inside `lh_yield()`. Allocation for an unassigned domain is a separate
requirement. The design retains fixed worker-core–endpoint affinity and records
the temporary native-demand dispatch matrix, receive oversubscription and packet
ownership limits. Native use does not create a sticky goroutine class: locality
may retain a G on ML, but it can move to M between transport operations.
It supersedes the earlier HOST_WAKE hold as future direction, not as permission
to implement now or as evidence any new interface exists. Dated records below
retain their original terminology and finite acceptance scope.

## Current status — October 8 consolidation

This summary reconciles the platform campaign through October 5 and Dandelion
results through October 8; no new CI query, board run or reservation check
accompanies this documentation pass. Detailed
historical sections below retain their original evidence and assumptions.
The completed correction/measurement campaign through `d21aef52` is approved
for consolidation into master by the October 8 closeout below.

| Area | Established evidence | Remaining claim or work |
| --- | --- | --- |
| Physical-worker addressing and cooperative DESCHED | Selected 8 KiB layout implemented; bounded models, targeted RTL and finite handoff/board checks passed. [Implementation ledger](../hardware/eci-implementation.md). | Full-system refinement and adversarial progress/isolation acceptance are not established. |
| Payload isolation and inactive-domain access | TX scrub/bounds, RX tail masking and multi-PA fault corrections implemented; pipeline 516112 passed all CI jobs and two cold-boot 100-RPC trials. [Correction record](eci-progress.md#builds-and-interpretation). | The original disclosure is a corrected defect, not an active campaign stop. Adversarial multi-domain board stress remains open. |
| 516232 idle activation | Module per-CPU storage corrected at `af0f64fe`; two fresh cold boots passed 200 measured + 32 warmup RPCs. [Diagnosis and correction](eci-progress.md#builds-and-interpretation). | Preserve the original failure capture; do not describe the board as still frozen by that failure or claim an independently confirmed upstream kernel bug. |
| GSYNC early NACK | 240 controlled RPCs passed; 160 permission-change GSYNCs, 107 overlapping one/two fully observed peer holds. Hold-dependent millisecond stalls disappeared. [Board evidence](eci-progress.md#corrected-module-early-nack-measurement--october-5). | Three simultaneously stalled peers and arbitrary-interrupt tail guarantees remain untested by this campaign. |
| Hot/cold benchmark | Cross-built Nix DPDK client, buffered runtime/kernel tracing, 1,000 requests per hot/cold × kernel-tracing on/off cohort; all 4,000 measured RPCs passed. Kernel-off median RTT: 12.24 us hot, 43.36 us cold. [Breakdowns, percentiles and figures](latency-measurement.md#corrected-module-516232-comparison--october-5). | One measured thread plus a separate four-thread flush domain is not four measured workers concurrently. Kernel tracing adds measured overhead; residual latency is not all wire time. |
| Hot CPU work | Cache-refill campaign and exact-binary selected-path instruction audit complete. [Static counts](hot-path-performance.md#historical-exact-binary-instruction-baseline--october-5): 1,865 instructions per nominal untraced iteration, 3,434 with buffered tracing. | Retired-instruction PMU counter was unreliable; static counts exclude rare retries/interrupts and do not measure cycles. |
| TX latency | 516232 TX hardware median 0.950 us, unchanged from 516112. | This comparison is not a controlled R12-versus-RA3 experiment and establishes no new TX reduction. |
| Timing | Latest tested 516232 report: WNS −0.102 ns, 893 failing setup endpoints. | Timing closure remains open; optimization remains paused. |

### Maintainer closeout and next application — October 8

The maintainer closed the dedicated multi-worker campaign at its recorded finite
coverage and authorized merging the completed correction/measurement branch.
Additional simultaneous four-worker, three-stalled-peer and application-driven
tail cases move into application validation; they are no longer prerequisites
for starting Dandelion. This is a scope decision, not evidence those cases passed.
The isolation/progress limitations in the table above remain explicit.

**HOST_WAKE remains on hold. Dandelion is paused; memcached is next, in a new
thread.** The bounded Dandelion campaign includes atomic/composition waterfalls,
32-way graph fan-out and the aligned vanilla 1×1 comparison. See the
[closeout and two deferred items](#dandelion-closeout-limited-scope-and-deferred-work--october-8).
Sharding acceptance, loaded multi-domain/overload cases and general recovery
remain open; they are not gates to starting memcached.
Do not introduce HOST_WAKE or change the address/DESCHED contract solely for
the next application; investigate concrete application dependencies first.

Complete kill/exit resource recovery, broader adversarial isolation/overload,
and full-system refinement remain open claims. Cold-path Linux scheduler
optimization and timing optimization remain deferred. Subsequent first-paper
applications and the [Caladan baseline comparison](caladan-enzian.md) continue, with memcached next; TCP/Protobuf and gRPC
retain their documented lower priority. Historical nested-runtime results are
recorded in the [merge disposition](../software/nested-rpc-blockers.md#merge-disposition--2026-09-23),
not a substitute for current Dandelion composition acceptance.

### Completed goal: Dandelion nested-composition waterfall — October 8

**Completed within the bounded October 8 scope.** Atomic/DAG/concurrent-chain
correctness passed; three 1,000-request cohorts were measured with one and four
resident workers, plus a four-worker tracing-off control. All 1,000 depth-2
requests showed three overlapping children after bounded allocation warmup.
Correlated FPGA/software waterfalls and launch/collection/return zooms are
available; cross-clock uncertainty is ±0.97 µs. See the
[results, provenance and limits](dandelion-measurement.md). This does not close
recovery, sharding, loaded multi-domain or full-paper evaluation claims.

The goal as requested was:

Reproduce Sven's evaluation compositions on real Enzian hardware and produce
high-resolution latency waterfalls: blocked RX → request arrival/launch →
deferral to a fiber → nested-call submission → parallel child execution →
result collection → final reply. Show each child on its own lane so overlapping
execution is visible and is not double-counted as serial latency.

Proceed in order: validate one atomic function with checked output and actual
worker placement; validate a composition through local FPGA loopback; then add
buffered timestamp instrumentation, measure, and plot. Correlate parent/child
identities, runtime/fiber events and matched FPGA traces, retaining clock
alignment uncertainty, missing intervals and tracing overhead. Dump software
records after the run. Reuse the hot/cold waterfall conventions, including the
RX wait beginning before request arrival, and preserve exact artifact provenance.
Build checks alone do not complete either hardware gate. HOST_WAKE remains on
hold; no timing or address/DESCHED redesign is part of this goal.

### Completed bounded follow-up: wide Dandelion fan-out

Exercise 32 independent matrix tasks and a sum reduction on all four workers,
including compute on the core whose parent composition is suspended. Reuse the
existing graph walker and its submit-until-capacity / await / resume mechanism;
do not substitute a benchmark-specific scheduler. Check the distinction between
outgoing-call admission and incoming loopback reply-context capacity before
claiming that a larger graph works. Plot worker lanes as well as parent/child
correlation. Two corrected requests now complete all 63 children each, reach
four concurrent executions, and demonstrate compute on the parent’s waiting
core. See the [wide-graph evidence](dandelion-measurement.md#wide-fan-out-and-temporary-udp-backpressure--october-8).
This bounded probe supplements the earlier three-child measurement cohorts.

The first 32-way probe on the old runtime failed: parent plus 15 children
occupied the 16 incoming reply contexts; receive-side rejection surfaces as
`SYSTEM_ERR`, and the old walker unwrapped a missing child result. The frozen
336,433,472-byte failure window is preserved under
`out/dandelion-resume/wide-failure-trace/` (SHA-256
`13cb7df01abfdc8fa09053f73c684d3c9f578ada30ef5570aad0e9cf9856c5b3`).
Dandelion `f95d33d` retains/requeues the rejected loopback shard; the platform
runtime preserves SYSTEM_ERR as future status 4. Other future errors drain accepted
calls and fail the composition. Native runtime status tests, AArch64/QEMU
capacity-policy tests, cross-build and image packaging passed. The first corrected
board probe handled four SYSTEM_ERR retries but still lost 13 completions between
host enqueue and scheduler queue admission (88 versus 75 descriptors), then
returned failure without aborting the server. Its 139,149,632-byte trace is saved
under `out/dandelion-resume/retry-failure-trace/`, SHA-256
`afdd4109dfc12155a0707a19d52e68440506988c47d9b87b4a55c5247723d43c`.

A process-wide runtime credit now retains the 16-call bound through software
consumption, including ready futures. Native tests cover full-table admission,
credit reuse and 32 concurrent submitters. This is a **temporary UDP/small-table
workaround**, not the intended architectural limit; see the
[table scaling and spill plan](../proposals/lookup-spill.md#completion-capacity-and-delivery).
Many current capacity restrictions should eventually be lifted. The corrected
bounded board gate passed on FPGA 516232; no new bitstream was needed.

### Dandelion closeout: limited scope and deferred work — October 8

The maintainer closes this round and pauses Dandelion. Continue with memcached
in a separate thread; do not launch more Dandelion measurements or implement the
items below until resumed. The full Dandelion paper benchmark suite is out of
scope for this PoC.

**Established results.** The 32-way graph runs 32 independent matrix tasks plus
31 reduction tasks, not 32 simultaneous hardware executions. Two checked
requests completed all 63 children each, with peak parallelism four on CPUs
44–47 and compute on the core whose parent fiber was waiting. RTTs were 86.678
and 86.296 ms; five busy replies were retried successfully. This is a two-sample
correctness/parallelism result, not a tail-latency distribution. The matched
hardware/software join covers 259 RX descriptors and 259 TX commits without
missing packets. See [wide-graph evidence and provenance](dandelion-measurement.md#wide-fan-out-and-temporary-udp-backpressure--october-8).

The other thread completed the [aligned atomic rerun and vanilla comparison](dandelion-baseline.md#aligned-atomic-rerun--october-8):
3,000 measured requests passed after aligning static worker linking, 8 MiB
contexts and function binaries. For prewarmed 1×1 on the port, mean/median RTT
was 2772.709/2771.143 µs; setup averaged 432.098 µs and process execution
2236.109 µs. Vanilla upstream process execution averaged 2145.418 µs: the
remaining engine gap is 4.2%. Setup plus execution account for about 96.2% of
port RTT. The aligned 2×2 port's mean RTT improved 29.7% over the old port,
almost entirely in process execution; linking and context-size changes were
combined, so this is not an isolated transport improvement. Upstream and port
cohorts differ in warmup and CPU placement, and the original upstream client
stops timing at HTTP headers whereas the port validates the full response.
There is **no controlled end-to-end speedup claim over vanilla upstream**.
The aligned atomic run used runtime `ce271135`; the wide-graph run used the
later bounded runtime. Their exact artifacts and tracing limits remain in the
two reports, rather than being treated as one matched campaign.

**Intended claim.** Demonstrate the compute/communication composition model,
measure reductions in communication/orchestration overhead under matched
conditions, and assess their significance for lightweight isolation. Process
sandbox costs dominate the measured implementation. A CHERI benefit would be a
component-level projection, not measured CHERI performance on Enzian. The
[paper's approximately 90 µs CHERI figure](https://arxiv.org/html/2505.01603v1#S7)
is a complete 1×1 invocation, including communication/data-handling components;
it is not a standalone isolation cost to which our transport cost can simply
be added. Replace comparable components without double counting, state platform
assumptions, and do not claim a quantified gain until that comparison exists.

Exactly two application-facing extensions are deferred:

1. **Dandelion messages beyond one MTU.** Fragment the BSON blob inside the
   existing small ONC RPC calls using XDR fields such as logical message ID,
   total length, chunk sequence/index and opaque chunk bytes. Reassemble in
   the receiving Dandelion adapter before BSON decoding and invocation. Cover
   requests, results and nested calls. This is a Dandelion-specific protocol
   over ONC RPC, not generic large ONC RPC messages, IP fragmentation or the
   byte-stream record-marking protocol. Keep the single-packet fast path.
   Retire individual chunk transactions promptly; keep logical message state
   in software rather than one persistent hardware context per chunk. Initial
   scope excludes retransmission; retain bounds/resource limits, duplicate
   handling and expiry, and fail incomplete messages without partial execution.
   “Arbitrarily long” means independent of the MTU within explicit resource
   bounds. UDP delivery and small-table restrictions remain first-PoC limits;
   see the [temporary workaround and scaling plan](../proposals/lookup-spill.md#completion-capacity-and-delivery).
2. **Communication functions expressed as nested RPC calls.** Map a communication
   node to asynchronous RPC submission, suspend its composition without holding
   a compute worker, then resume dependent nodes on completion. Demonstrate a
   representative compute → remote RPC → parallel compute → join composition.
   This preserves the programming pattern; it does not promise compatibility
   with arbitrary existing HTTP services or require reproducing every paper
   workload. Side-effecting communication must not inherit the local pure-task
   SYSTEM_ERR retry assumption indiscriminately.

These are scoped future capabilities, not implemented features or a requirement
to finish the full benchmark suite. Keep reliability, recovery and isolation
limitations explicit while evaluating the core programming-model and overhead
contribution.

Survey: 2026-09-08, base `b86c68c`. Maintainer priority: Dandelion and other first-paper applications come before TCP/Protobuf integration. Merge the finished protocol projects soon to limit drift; fully offloaded gRPC is a potential later paper. Other ordering below is a survey recommendation, not an approved schedule. Keep unresolved choices explicit; do not implement a proposal merely because it appears here.

The nested RPC integration is approved for master as of 2026-09-23. Its
[merge disposition and remaining tests](../software/nested-rpc-blockers.md#merge-disposition--2026-09-23)
record accepted timing limitations, the cross-process late-reply race to test,
and the startup workflow explanation. The earlier Dandelion validation hold is superseded by the October 8 direction
to resume the application on the integrated scheduler/critical-section design.

## Active coherence verification follow-up — October 2

The [three-layer model](../formal/model-structure.md#layers-and-boundaries) now separates
single-worker coherence, steady-state shared-resource progress, and scheduling.
Its generated DC/THX tables reproduce the missing RX write-service dependency;
public contracts and optional detailed products connect the layers. Remaining
modeling is broader CPU eviction/request races and independent VC ordering,
finite shared response/data resources, and justified full-product fairness.
These are distinct from the historical bounded scheduling completion milestone.

RX writebacks complete independently and preserve payload mutations in the
[private replay shadow](../architecture/hw-sw-interface.md#independent-rx-writeback-service).
[RX-return responses](hot-path-performance.md#hardware-reuse-the-rx-return-half-for-responses)
remain an open feasibility/performance study alongside
[application measurements](#application-performance-and-coupled-rxtx-analysis). Pipeline
[515802](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/515802)
built that fix at `a51139e` after pipeline 515796 failed fetching its prematurely
deleted branch. [Two cold-reset trials passed 200 RPCs](../../agents/hardware-state.md#rx-writeback-regression-evidence),
with trace-confirmed RX writeback completion. The
[30-minute / five-hour CI follow-up](../development/ci.md#scheduled-hardware-build-follow-up)
now retains pipeline refs until all jobs finish. That historical trial used the former address ABI. The current ECI source now
uses the selected physical-worker decoder and cooperative DESCHED contract.

## Next steps after the bounded modeling round

Implementation is integrated on `feat/cooperative-yield`, with generated
address arithmetic, physical-worker routing, owner validation and cooperative
kernel/runtime activation. See [evidence and remaining integration](../hardware/eci-implementation.md).
Pinned checks and pipeline 515944 passed. A kernel preemption-table indexing
fix built against its exact headers passed the baseline. Two cold boots now
pass finite contention at a 133.281505 ms RX hold, including 5,926 ordinary
handoffs with no scheduling IRQs and trace-confirmed three-peer overlap.
The old-image maximum-stall search stopped at the original isolation finding.
Corrected-build functional testing has since resumed; qualification of the stall
boundary under expanded contention remains open. One earlier baseline kernel
lockup remains unexplained. HOST_WAKE interface design and
implementation, deadline-kick integration, and complete kill/exit recovery are
explicitly outside this stage; step 4 below is later work.

The selected scheduling matrix and initial three-layer checks are complete.
Carry their assumptions into implementation as explicit service contracts and
local checks; full THX/RTL refinement and every larger model product are not
prerequisites for starting implementation. Close any newly exposed dependency
that invalidates those contracts before relying on the affected mechanism.
The existing-design RX-fix board trial has passed. The sequence below separates
the replacement integration from deferred follow-on work.

| Order | Work | Completion checkpoint |
| --- | --- | --- |
| 1 (complete, bounded regression) | Revalidate the simplified DC plus RX write sink on Enzian, retaining the existing router/address ABI. | Pipeline 515802 passed two cold-reset trials / 200 RPCs; a repeated-hash trace shows RX WDDA/LCIA and the next read. Broader stress remains in step 5. |
| 2 (implemented; expanded acceptance open) | Implement the router-free address path: selected 8 KiB physical-worker blocks, domain-qualified PAs and stable pre-mapped aliases. | HW decoding, generated headers, kmod mappings and runtime accesses agree; both parities, overflow, domain isolation and full response/invalidation addresses are tested. Monotonic domain codes never wrap or get reused across surviving coherence state. |
| 3 (implemented; expanded acceptance open) | Implement cooperative DESCHED/`lh_yield()` and the blocking DeschedInfo interface across NIC, kmod and runtime. | Mailbox identity/parity come from one committed response; transport fencing, reservations/claims and local activation meet the [implementation checklist](../software/scheduling-implementation-checks.md). Ordinary handoff needs no cache flush, activation commit write or scheduling IRQ. |
| 4 (deferred) | Design host-event activation and complete whole-domain recovery in a later round. | Neither interface design nor reusable-resource recovery is part of this stage. |
| 5 (partial) | Validate the integrated replacement with targeted actual-DCS RTL simulations and matched board stress. | Run the [whole-slice cases below](#targeted-dcs-rtl-simulation-after-replacement-rtl-is-available), stalled peers plus bypass, repeated handoffs, wakes and kill cleanup. Add model-derived assertions/event monitors as each component lands, rather than postponing all validation to this stage. Record finite observations separately from liveness assumptions. |
| 6 (microbenchmark baseline complete; applications open) | Integrate the first-paper applications and collect performance data, then evaluate coupled RX/TX where measurements justify it. | Reproducible functional workloads and baseline comparisons; quantify scheduling costs and application bottlenecks before selecting another transport optimization. |

Continue targeted modeling alongside these steps: competing CPU requests and
evictions, independent VC ordering, shared response/data-resource contention,
and the larger scheduling combinations listed below. Prioritize cases touched
by implementation or contradicting a service assumption. Keep layer checks
independent; extend detailed compositions when a cross-layer dependency warrants
it. Revisit formal refinement when stronger guarantees or observed discrepancies
justify it. Timing optimization remains paused.

### Application performance and coupled RX/TX analysis

Put the RX return-half study in the application evaluation round, after the
correct independent RX/TX baseline is running. Start with RPC workloads and
Dandelion, then the planned application portfolio in the
[paper evidence plan](paper-goals.md). The
[hot-path optimization note](hot-path-performance.md#hardware-reuse-the-rx-return-half-for-responses) describes possible
commands/status and responses carried by intentionally dirtying an RX CL.
This is an experiment to establish benefit, not a prerequisite for the address
or DESCHED work and not a selected replacement protocol.

- Investigate [software instructions and cache-line layout](hot-path-performance.md#software-and-cache-line-layout-questions)
  alongside this study, using the current contiguous borrowed-RX/replay layout
  and ownership contract. Separate copies, codecs, allocation and runtime work;
  deferred arguments/results still need owned storage.
- First measure response-size distributions, the fraction fitting in the usable
  return payload, handler time, and the share of latency/CPU work spent on TX.
- Compare the independent path with a coupled prototype on the same hardware,
  application semantics and offered loads: median/tail RPC latency, throughput,
  CPU work, coherence traffic and resource/backpressure effects. Include mixed
  small/large responses and fallback to the independent TX path.
- Before promoting the prototype, check message identity, validity, exactly-once
  consumption, preemption/kill cleanup and independent write service under stalls.
  A response depending on the next held RX must not reintroduce a service cycle.
- Keep or reject the optimization based on measured application benefit against
  implementation and protocol cost; retain artifacts and negative results too.

## Work supported by repository evidence

| Work | Current evidence / gap | Completion evidence to seek |
| --- | --- | --- |
| Scheduling robustness under pressure | `hw/src/lauberhorn/Scheduler.scala`: queue-full drop has buffer-free FIXME; fairness and non-retrying-core recovery TODOs | No buffer leak under saturation; bounded progress/recovery; documented policy and multi-process stress |
| Scalable service/process/session tables | `docs/hardware/lookup-spill.md` proposes hardware caches and typed software spill ownership | End-to-end admission, eviction, ownership and packet lifetime tests beyond table capacity |
| Nested RPC usable from applications | Managed C-runtime outcomes, futures and nested completion are implemented with historical generic board validation; this is not current application acceptance | Generic runtime/outcome checks passed in the historical nested campaign; application-specific acceptance and revalidation on the replacement scheduling ABI remain |
| Coherence progress and queue-pair placement | Goal 1 complete as a bounded modeling/layout milestone: selected 8 KiB physical-worker blocks, domain encoding and reproducible positive/negative models | Selected bounded Goal 2 matrix complete; replacement implementation and targeted DCS RTL validation below, followed by adversarial board traces |
| TCP and Protobuf integration | Finished external student projects in `../flavian-tcp` and `../elena-protobuf`; unmerged here | Adapt code/dependencies and validate host contracts after higher-priority applications; [follow-on roadmap](../proposals/grpc.md) |
| Data movement and decode extensions | Current C runtime performs XDR; generator TODO suggests coupled worker protocol | Chosen ABI and workloads; measured benefit; large-message strategy independently validated |
| Reproducible evaluation | Trace/latency tooling and descheduling microbenchmark exist; historical examples are not a full evaluation | [Paper evidence matrix](paper-goals.md), reproducible raw data and controls |
| Development consistency | Build/CI/manual instructions have drift; PCIe contains unfinished preemption | See [workflow](../development/workflow.md) and [validation](../development/validation.md); repair only within a separately scoped implementation task |

The kernel/control bypass and runtime-owned request spill planes are intentionally distinct in the spill proposal. Preserve that distinction when designing new descriptors; establish who frees packet storage and owns retry before adding a message type.

## Confirmed design intent (maintainer, 2026-09-08)

- Build the full RPC system on published 2F2F; demonstrate low latency and efficiency while retaining OS flexibility and security. [Paper scope and application portfolio](paper-goals.md).
- Primary baseline: [Caladan](caladan-enzian.md), selected October 15 as a closer match for runtime-managed core allocation and networking. The minimal Enzian port and validation are pending. Planned applications: Dandelion, RPC server, userspace NFS, memcached, and consensus such as etcd/Raft.
- `../sven-dandelion/{report,platform,dandelion}` contains the student report, mock platform/runtime extensions and Dandelion port. See [source map and limitations](dandelion.md); these are external reference trees, not merged support in this checkout.
- Worker cores are isolated and never shared with ordinary Linux tasks. RPC tasks run to finish. Ordinary idle RX cooperatively yields on DESCHED. Unsupported exits and fatal
  activation failures kill and fence the domain; full resource-recycling recovery
  and deadline activation remain deferred.
- TCP and Protobuf student platform/report trees are located and documented in [TCP](tcp.md) and [Protobuf](protobuf.md). Merge soon to avoid drift, at lower priority than Dandelion and the other first-paper applications. Fully offloaded gRPC is a possible subsequent paper.
- Updated 2026-09-30: physical-worker-owned QPs and the [8 KiB layout/domain encoding](../hardware/queue-pair-design.md#concrete-domain-qualified-pa-encoding) are selected for the router-free replacement. The implementation now selects physical-worker decoding; full acceptance is pending.

## Scheduling verification milestones (maintainer, 2026-09-30)

**Goal 1 is complete as the bounded modeling and layout milestone**, accepted to
proceed with Goal 2 modeling and implementation. The [verification report](../formal/resource-progress.md)
and [reproducible evidence](../../model/evidence/steady/nix-progress-evidence.json) record
what passed, the incomplete four-client run, and the remaining assumptions.
This disposition does not claim full hardware verification or whole-system HoL
freedom. [Goal 2 modeling](../formal/scheduling.md) now has modular
handoff/resource, reservation, thread-claim, host-wake, retirement and interrupt
contracts, with the original 26 baseline outcomes and stalled-worker extension checked.
The [race/checklist work](../software/scheduling-implementation-checks.md) adds
delayed wake acceptance/delivery, notification/ACK and fixed-pool reservation/claim cases;
local handoff sequencing is tracked as implementation obligations. The expanded
33-outcome Nix regression passed before the fixed-pool clarification. The updated
12-outcome focused suite passes; individual withdrawal and ordinary unavailable
dispatch are excluded. The later selected bounded matrix is complete as recorded
below; broader verification and implementation remain open.
A joined host-driven handoff checks the Goal 1 resource-entry predicate. The
[scheduling composition](../formal/scheduling.md#joined-scheduling-wake-and-transport) now connects
wake/claim races and kill to RX/TX cleanup; its selected bounded matrix
is merged into `master`. Endpoint freshness and concrete
control/transport correspondence remain assumptions. Goal 2 must establish the settled entry contract and preserve unaffected
workers' progress during scheduling transitions, reusing the resource model.

### Active Goal 2 workstreams and compaction handoff (2026-10-01)

**The selected bounded modeling round for items 1 and 2 is complete.** Their
broader/refinement extensions remain open; item 3 remains an explicit
hardware-service contract, and item 4 is implementation and validation.
The completed modeling branch is merged into `master`. The selected LCI-only endpoint and its
checks are recorded below. The [module review](../formal/model-structure.md#module-review-evidence-and-historical-sources)
consolidates cache/transport composition, removes UL from the active models and
separates source/configuration/evidence; see the [model index](../../model/README.md).
The new [scheduling composition](../formal/scheduling.md#joined-scheduling-wake-and-transport) joins the
focused wake/claim contracts with kill and transport. Eleven positive cases, eleven
expected faults, three reachability witnesses and all 33 module checks completed.
The committed-TX kill/handoff case with a recurring peer completed by checkpoint
continuation after its original timeout; the report distinguishes that result
from the 24 completed Nix case derivations. This permits the next implementation
round under the listed service assumptions; it does not mark Goal 2 or hardware
verification complete.

| Item | Remaining work | Status / completion evidence |
| --- | --- | --- |
| 1 | Connect kill completion to actual transport resources, including TX/overflow and packet/buffer cleanup. | The [kill/transport composition](../formal/kill-exit.md#resource-backed-kill-cleanup) now imports kill control, cached transport and response properties, adding TX control/overflow writeback and packet ownership. Separate RX, committed-TX and abandoned-TX cases include a stalled worker and live peer. Exact RTL correspondence, multiple closing workers and shared packet queues remain open. Initial-buffer bookkeeping is not a proof of safe pool reuse: enforce protocol release or kernel no-return plus access drain (H20). |
| 2 | Complete broader combined liveness checks and stalled-peer transition cases. | Selected bounded round complete in [SchedulingComposition](../formal/scheduling.md#joined-scheduling-wake-and-transport): A → B → A, generation/ACK races, fixed-pool claims, whole-domain close and RX/TX cleanup. Selected matrix: eleven positive cases, eleven expected faults and three reachability witnesses complete, including committed-TX cleanup with a recurring peer and indefinitely held RX. Larger combinations, closing B, multiple closing workers and transition-time full-duplex bypass remain outside this matrix. [Combined state invariants](../formal/resource-progress.md#directory-accounting-and-replacement) have bounded passing evidence; historical nonallocating combined temporal runs remain incomplete; the new auto-lock one-slot temporal runs complete and fail handoff/progress due to absent-target allocation. The new matrix bounds migration to A → B → A, notifications to two and TX to one control plus two overflow lines; it is not an unbounded proof. |
| 3 | Establish THX replacement and independent coherence-service guarantees. | The October 2 board trial exposed RX `WDD` without AXI write service; `a51139e` implements an independent write sink and passes directed RTL tests. Two cold-reset board trials now pass with trace-confirmed completion. The LCI-only ROM is selected; broader service guarantees remain open. Retain explicit directory-provisioning assumptions and establish CPU replacement initiation and independent delivery through THX/ECI/DCS shared resources. Neither matched cache capacity nor per-DCU input arbitration alone establishes end-to-end progress. |
| 4 | Implement the serialization checks, then validate actual DCS RTL and hardware. | Pending. Implement the [kmod/HW checklist](../software/scheduling-implementation-checks.md), including close versus claim/activation, kill issue/ack versus reassignment, callback/command lifetime and immutable MMIO responses. Run the whole-slice cases below and matched-artifact hardware tests. Existing targeted RTL checks are partial evidence, not completion of this workstream. |

Settled context: use the selected router-free 8 KiB worker blocks and monotonic
19-bit domain-code allocation without reuse. Prefix-reuse certification is deferred;
exhaustion must not wrap and driver reload must not reuse codes while coherence
state survives. Ordinary handoff still uses DESCHED/`lh_yield()` and one blocking
DeschedInfo read, with no kernel 2F2F CL access, ordinary cache flush or activation
commit write. Ordinary handoffs have no scheduling IRQ. Initial/idle activation uses a sticky
notification and kick IRQ; fatal-domain notification is separate.

#### Historical starting point: implementation after RX-fix board acceptance

Hardware validation used revision `2699f58`,
[pipeline 515621](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/515621).
Hardware job [2861585](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2861585)
succeeded in CI. This is the existing routed/addressed design with the simplified DC and UL removed,
not the scheduling/address pivot. The [October 2 matched board trial](../../agents/hardware-state.md#rx-writeback-regression-evidence)
passed driver/revision checks but failed RPC discovery. The frozen trace shows a
data-bearing HAKD for RX `0x100`, followed by `WDD` without `WDDA`/LCIA; the RX
AXI router blocks write channels. The [trace comparison](../hardware/eci-implementation.md#rx-writeback-and-permission-changes)
identifies the newly selected R12→RA3 permission change: earlier RX used
PSHA/SINV_H/no-data HAKD; exclusive RX now uses FLDX_EH/data-bearing HAKD. No
software store is required. The [actual-RTL reproducer](../development/validation.md#rx-writeback-regression-reproducer)
reproduced AW queueing with WREADY low; the production fix now drains and discards
RX writes independently, and its regression checks WDDA/LCIA and the pending read.
The [RX-fix follow-up](../../agents/hardware-state.md#rx-writeback-regression-evidence)
passed two cold-reset trials and directly observed RX completion. Both captures
are preserved. The address/DESCHED replacement is now implemented on `feat/cooperative-yield`.
See the current evidence ledger for its checks and pending board acceptance.

The [selected LCI-only ROM](../hardware/lauberhorn-rom.md) is now generated by
Nix from a dedicated Perl source in the DC submodule and consumed by real-DCU
simulation and the Vivado input bundle. It retains Jasmin's A22 equations, uses
RA3 for R12, and removes LC/F32 and UL. NIC UL emission is removed. Directory
miss-to-invalid writes are suppressed and clean HAKD uses outstanding F31 context.
The selected ROM has 14 states and 35 permitted transitions. The linked document
records the precise checks and limitations; the initial board failure at the RX
writeback boundary is resolved for the checked regression above.

The [LCI-only resource-model update](../formal/resource-progress.md#current-endpoint-semantics) now
composes LCIA release without UL with the existing scheduling modules. Focused
handoff/cache safety and liveness checks passed, as did combined state invariants
over 2,528,455 states. Full combined temporal verification remains open.

The [AXI response-contract checks](../formal/axi-response-contract.md) now cover
earlier/queued/buffered reads, stop fencing and shared response service: five
positive cases, seven deliberate faults and two witnesses. THX same-line response
ordering, independent service and exclusion of target reads behind the held
doorbell remain hardware obligations, not established guarantees. The [kill-tail composition](../formal/kill-exit.md#resource-backed-kill-cleanup) connects
resource cleanup to the existing kill control model. The [new composition](../formal/scheduling.md#joined-scheduling-wake-and-transport) now joins broader
wake, reservation and repeated-handoff liveness by reusing component properties
and adding cross-component control/transport relations. Its selected bounded matrix
is checked; the report lists larger combinations not checked and hardware
correspondence obligations (including control freshness, MMIO/IRQ ordering and
RX-loan reuse). Reconcile the composed resource model with this
selected endpoint; earlier auto-lock counterexamples and conditional nonallocating
runs are not a new proof for the integrated ROM. No automatic ICI eviction policy is introduced.
#### Historical auto-lock investigation — superseded by the selected LCI-only endpoint

The following auto-lock results remain historical motivation and regression
cases, not evidence that the selected non-locking endpoint has the same failure.

The [new directory RTL test](../hardware/lauberhorn-rom.md#historical-auto-lock-counterexamples-and-regression-coverage)
reaches a full-set absent-LCI stall from reset through R12/RDDA fills and a clean
V21 eviction, using actual TSU/tag RAM/controller/auto-lock ROM and a test XPM
primitive model. It does not force full/hit outputs. Full-set **hit** invalidation
works; its entry remains charged until UL. That UL frees a way and permits the
absent-target LCI to complete. The test checks unrelated entries survive.

The [TLA+ rerun](../hardware/lauberhorn-rom.md#bounded-model-evidence)
now finds the same problem after a from-empty worker/cache history (320 states)
and in complete one-slot combined scheduling checks (33,740 states). Safety
holds, but handoff completion and stable progress fail. Full-set hit and
independent-release recovery controls pass. This was a confirmed blocking condition in the auto-lock model, motivating the
subsequent LCI-only selection; it is not a current request to restore auto-lock.

The investigation originally proposed the following steps (historical):

1. Close the full NIC/runtime reachability gap: a fresh first RX does not itself
   invalidate the opposite parity. Check subsequent RX, overflow lines never
   read, retained old-domain lines, and preemption/kill against the actual engine.
The [existing DC bench audit](../hardware/lauberhorn-rom.md#upstream-auto-lock-bench-coverage)
finds an upstream `test_lci_stall_tsu_full` regression: it expects stalling and
supplies voluntary CPU evictions before retry. ICI has ROM transitions but no
current producer or explicit bench stimulus. A forced-eviction solution therefore
requires new control-path implementation and tests; it is not enabling an existing
controller feature.

2. Evaluate the non-locking control-path repair and response-fence contract;
   alternatively establish/enforce an auto-lock allocation guarantee for absent targets, or
   prove an independent release is always pending for the permitted sequences.
   Matched CPU/directory size does not by itself cover FPGA-created locks.
3. Then compose the guarantee with shared writeback/VC/credit service. The AXI
   gate model supplies a narrow candidate non-locking contract; extend it to the
   missing transition cases before selecting that protocol.

At that investigation stage, only trace instrumentation had changed in production
RTL. That status is superseded by the selected LCI-only ROM, UL removal and RX
write-service fix above. No forced-eviction policy or extra directory way is selected.

### Targeted DCS RTL simulation after replacement RTL is available

The main ECI suites use Scala `DcsAppMaster` in
[`NicSim.scala`](../../hw/test/src/lauberhorn/host/eci/NicSim.scala), abstracting
away the DCS RTL. Retain those fast suites, and add a separate targeted harness
that instantiates the actual DCS slice/DCU RTL with the replacement address path
once that RTL is available. Existing DCS sources alone do not constitute this
integration test. Cover:

- Slice read/write routing to DCUs: even/odd slices, scrambled color selection,
  selected worker/domain addresses, both control parities and all overflow lines;
  verify full address identity through responses and invalidations.
- Held reads with concurrent writes/invalidation traffic: stall one and
  then multiple workers while a peer and full-duplex bypass run; include retained TX/overflow responses alongside a held RX, and same-DCU versus independent-DCU traffic.
- Shared-path backpressure: exercise VC arbitration, read/write transaction
  managers, data-buffer gates, RTG occupancy, CDC and AXI queues; check whether a
  blocked DCU prevents another eligible DCU from admission or completion.
- Accounting and framing: check allocation/release, queue occupancy, beat counts,
  transaction identity and LCI/LCIA handshakes; map observed events to the model
  and turn its negative cases into directed sensitivity tests.

Record exact RTL/configuration revisions and bounded progress observations under
explicit environmental readiness assumptions. These simulations must exercise
DCS RTL rather than use `DcsAppMaster` as its substitute. They will test the
resource correspondence; finite passing traces still do not prove infinite-stall
liveness or undocumented ThunderX internals. Carry forward broader concurrent
model checks, THX credit assumptions and eventual board conformance separately.

## Remaining decisions

1. Complete expanded acceptance of the implemented physical-worker/domain design using the completed bounded scheduling models and the next-step sequence above. Establish concrete admission/resource guarantees and validate supported concurrency; the color-compatible seven-worker capacity is not yet a hardware progress guarantee.
2. Specify receive-critical-section versus whole-application timeout, timer start/reset points, kill granularity and safe resource/reply cleanup. Define service allocation fairness if a guarantee is needed; run-to-finish alone does not define it.
3. Validate the [managed outcome contract](../software/programming-model.md), including local reply forwarding for tail calls. Direct downstream-to-original-client replies remain separate protocol work. Implement [service-owned events/timers and resident-worker integration](../proposals/service-execution.md) in later runs.
4. Consensus targets a runtime-managed raft-rs embedding; memcached uses [native UDP cooperative workers](memcached.md) with bounded maintenance. Select NFS implementation, Caladan port/application revisions and resource budgets under the [Enzian baseline plan](caladan-enzian.md), target venue/cycle, and ordered feature milestones.
5. Choose the TCP/Protobuf merge order and follow-on gRPC scope without displacing first-paper applications. Any separate Linux/full-paper source trees still need identification if required; student reports are not the full-system paper source.

Keep maintainer intent separate from source-backed implementation status. Do not silently turn these remaining choices into requirements or reopen the settled isolated-worker policy.

The [control/retirement interface](../software/scheduling-control-interface.md)
now specifies one blocking DeschedInfo read per handoff, local userspace activation
and separate whole-domain close/quiescence/retirement. Combined race checking must
preserve response lifetime and pending wake ownership across close. Retained
old-domain cache generations now have explicit resource accounting in the
composition below; no ordinary kernel CL flush is specified.

[Retained cache and composed scheduling](../formal/resource-progress.md#directory-accounting-and-replacement)
now models full dirty sets, conflict replacement and delayed directory release,
then joins reservations, claims, wake ownership and whole-domain close. DCS waits
for CPU downgrades on a full set; concrete replacement-path independence is an
explicit hardware correspondence obligation, not an empty-cache assumption.

## Kill/exit follow-up (2026-10-01)

The [focused kill/exit check](../formal/kill-exit.md) separates
application stopping from PA lifetime reclamation. The first implementation allocates
domain codes monotonically without reuse; domain-code reuse certification is deferred.
The focused model checks uncooperative userspace, outstanding RX, IRQ ordering,
close/dispatch races and reference cleanup. It does not complete the larger
combined temporal or concrete resource/refinement obligations. The linked report
lists the remaining implementation and verification work.

## Directory-capacity finding (2026-10-01)

The [sequential replacement/delivery audit](../hardware/lauberhorn-rom.md#directory-pressure-and-the-nonlocking-contract)
found an absent-LCI/full-set progress counterexample in the corrected abstract
resource model and reproduced the controller/table's allocation dependency in
controlled RTL tests. Complete hardware-state reachability remains unestablished.
The selected address layout is unchanged. The earlier instruction to retain auto-lock was superseded by the selected
LCI-only endpoint. Item 3 now concerns independent service and the remaining
resource/refinement obligations, not restoring auto-lock. The new actual-directory test confirms the component-level
obstruction; the selected non-locking build now has focused real-DCU regression coverage. Early controller/arbiter/data-gate checks do not replace the planned
whole-slice routing tests or establish shared release-path progress.
