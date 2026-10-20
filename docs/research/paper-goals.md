# Full systems paper: goals and evidence

Survey: 2026-09-08, base `b86c68c`. The system objective, application portfolio, baseline and scheduling scope below are maintainer-confirmed. Experiments are proposals; venue/cycle and exact application versions remain unspecified.

## Evaluation status — October 8 consolidation

The [current evidence index](roadmap.md#current-status--october-8-consolidation)
records completed hot/cold DPDK microbenchmarks, GSYNC mitigation and CPU-work
analysis. The [Dandelion campaign](dandelion-measurement.md) adds checked
atomic/composition execution and correlated waterfalls, including three parallel
children on real hardware. These are bounded results, not the full paper
evaluation: loaded four-worker tails, broader application workloads, Caladan controls,
recovery and broader adversarial acceptance remain open. HOST_WAKE/events and
complete resource recovery are still follow-on implementation work. The
experiment matrix below is an evidence plan, not a list of completed features.

## Starting claim

The coherent datapath/2F2F is published groundwork: [Rethinking Programmed I/O for Fast Devices, Cheap Cores, and Coherent Interconnects](https://arxiv.org/html/2409.08141v3). The [HotOS paper](https://arxiv.org/pdf/2501.10138) supplies the OS-integrated NIC direction. The full paper's contribution is an efficient, low-latency RPC **system built on 2F2F**, retaining the flexibility and security guarantees of OS ownership. Do not present 2F2F itself as the new contribution or infer system guarantees from transport measurements.

Worker cores are isolated from ordinary Linux tasks. Tasks run to finish; scheduling flexibility concerns allocation among RPC services and composition execution within that worker pool. A receive-critical-section kill timer or application timeout is intended, but exact timeout and recovery semantics remain open. This is not a design for arbitrary time-sliced handlers or sharing worker cores with ordinary Linux tasks.

## Planned applications and baseline

**Caladan is the selected primary baseline, labeled SH-A64 for the AArch64 port alongside DPDK and LH.** Follow the [current Enzian port plan](caladan-enzian.md): ConnectX-5 networking, the simple scheduler, and bounded hot, core-reclamation and memcached experiments. Pin implementation, application versions and resource budgets; ARM port and measurement acceptance remain pending. Conventional Linux RPC and static Lauberhorn can serve as additional controls. Keep application semantics and load generation comparable. Full Caladan interference-control claims are outside scope.

The [DPDK microbenchmark baseline](dpdk-baseline.md) uses ConnectX-5 polling
and the shared echo RPC software path to compare CPU-side networking with LH.
Its configured workers provide a transport/CPU-processing baseline; SH-A64
separately evaluates Caladan's runtime-managed scheduling. Both use the common
[hot measurement methodology](hot-measurement.md).

| Planned application | Purpose in the evaluation / integration starting point |
| --- | --- |
| Dandelion | Bounded Enzian composition/four-worker and aligned atomic measurements complete; paused. Scope is programming-model support and communication overhead, with lightweight-isolation projections distinguished from measurements. [Closeout and two deferred extensions](roadmap.md#dandelion-closeout-limited-scope-and-deferred-work--october-8). |
| RPC server | Basic request dispatch, payload/handler sweeps and multi-service load; current microbenchmarks are a starting point |
| Userspace NFS server | Stateful service and larger data movement; implementation/version and filesystem semantics to select |
| memcached | Next application, in a new thread: key-value service, mixed operations, concurrency and skew; port remains planned |
| Consensus, e.g. etcd/Raft | Chained peer RPCs and stateful coordination; exact implementation and durability/failure semantics to select |

These are planned applications, not claims that five ports run on the current FPGA. The historical Dandelion student report evaluates a software mock. The newer Enzian reports establish bounded implementation results, not a controlled end-to-end speedup over vanilla Dandelion or measured CHERI gains.

## Follow-on scope

The finished [TCP](tcp.md) and [Protobuf](protobuf.md) student projects should be integrated soon to avoid drift, with lower priority than Dandelion and the other applications above. Fully offloaded gRPC is a potential later paper. See [protocol integration roadmap](grpc-roadmap.md); do not expand the first paper into that agenda by default.

## Proposed evidence plan

| Claim to test | Experiment and controls | Repository starting point |
| --- | --- | --- |
| Coherent delivery reduces host RPC overhead | Sweep payload and handler sizes; compare equivalent transports and report CPU work separately from end-to-end latency | `../microbenchmarks/`, `sw/core/eci/core.h` |
| Scheduling integration helps changing demand | More services than workers, bursts, idle-to-active transitions, skew, competing RPC services within isolated workers; report tail latency, throughput, core occupancy, fairness and transition cost | `Scheduler.scala`, `sw/kmod/sched.c`, `../microbenchmarks/client/bench_dpdk.c` |
| Using the RX return half improves application performance | After baseline application measurements, compare independent TX with a coupled RX-return prototype; measure fitting-response fraction, latency/tails, throughput, CPU work and coherence contention, including large-response fallback | [Evaluation placement and decision criteria](roadmap.md#application-performance-and-coupled-rxtx-analysis); [protocol design questions](../hardware/rx-writeback.md) |
| Benefits come from integration | Ablate dynamic scheduling, compare pinned service workers, separate coherent transport effects from scheduling effects | Same workload and hardware across configurations; implementations of controls still need auditing |
| Mechanism scales beyond tiny tables | Sweep services/processes/in-flight calls through capacity; account for spills, queueing, drops and hardware area/timing | `docs/hardware/lookup-spill.md`, hardware status doc |
| RPC composition is practical | Remote nested calls, concurrent continuations, reply reordering, saturation, local calls if supported | `hw/test/src/lauberhorn/host/eci/OncRpcNestedSim.scala`; generic runtime completion has historical board validation; application composition on the replacement scheduling ABI remains separate acceptance |
| Coherence and isolation remain correct | Worker handoff at protocol boundaries, stale mappings, process exit, receive-critical-section timeout and overload; model plus directed simulation and board stress | `model/`, router/preemption RTL, ECI simulations |

Security/flexibility evidence must accompany latency: define the trusted NIC/kernel boundary, per-process mappings and registration authority, validate cross-process isolation and stale-pointer/ownership handling, and show resource reclamation on exit and timeout. Bound exhaustion effects across services. These are proof and test obligations for the system claim, not guarantees already demonstrated by the prototype. Run-to-finish does not promise bounded latency for an unbounded handler; the application timeout policy remains open.

## Measurement rules

- Use `docs/research/latency-measurement.md` and the actual plotting implementation together. Client RPC wall time, CMAC-to-CMAC service time, and blocking receive duration are different measurements.
- Do not assign the residual latency bucket entirely to the wire. It includes uninstrumented client/kernel/network work and correlation gaps.
- CPU/FPGA alignment and directional ECI timing include estimates. State the alignment method and timestamp overhead; retain raw captures and generated trace map.
- Narrow trace IDs wrap; correlate using order, time, core and event relationships. Never join solely by numeric ID.
- Existing latency examples are historical captures, not fresh results or a throughput/scheduling evaluation. Report provenance: git and submodule commits, bitstream/header pairing, kernel, worker/CPU configuration, load generator, offered load, payloads, duration, repetitions, correctness/drop counts and instrumentation settings.
- Measure energy or CPU availability before claiming that blocked loads save either at system level.

## Correctness scope

`model/legacy/tla/lauberhorn.tla` models caches, FPGA handlers, services, kernel state and core assignment. Toolbox configurations include type, cache-line exclusion, preemption and isolation invariants plus temporal properties. Checked-in `MC.out` files report historical successful model checks; they are not a proof that today's RTL refines that model. Reconcile model assumptions with decoupled RX/TX, routing, nested RPC and DCU progress, and rerun a recorded configuration before making new correctness claims.

## Suggested paper structure

Problem and OS/NIC boundary → published 2F2F foundation → RPC programming/ownership model and scheduler mechanism → implementation and correctness → controlled evaluation → limitations and generalization. Make whole-system RPC efficiency, application composition, dynamic service allocation and OS isolation the core evidence. Quantify coherence traffic, FPGA resources/timing and bottlenecks to explain the implementation. CXL portability needs an explicit semantic mapping or experiment; ECI results alone do not establish it.
