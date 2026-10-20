# Full systems paper: goals and evidence

The paper evaluates an OS-integrated RPC system built on published 2F2F.
The claims and experiment matrix are goals; completed evidence and unresolved
decisions are indexed in the [research roadmap](roadmap.md#evidence-and-remaining-claims).

## Starting claim

The coherent datapath/2F2F is published groundwork: [Rethinking Programmed I/O for Fast Devices, Cheap Cores, and Coherent Interconnects](https://arxiv.org/html/2409.08141v3). The [HotOS paper](https://arxiv.org/pdf/2501.10138) supplies the OS-integrated NIC direction. The full paper's contribution is an efficient, low-latency RPC **system built on 2F2F**, retaining the flexibility and security guarantees of OS ownership. Do not present 2F2F itself as the new contribution or infer system guarantees from transport measurements.

Worker cores are isolated from ordinary Linux tasks. Tasks run to finish; scheduling flexibility concerns allocation among RPC services and composition execution within that worker pool. A receive-critical-section kill timer or application timeout is intended, but exact timeout and recovery semantics remain open. This is not a design for arbitrary time-sliced handlers or sharing worker cores with ordinary Linux tasks.

## Planned applications and baseline

**Caladan and DPDK are explicit paper baselines.** **CS-A64 (Caladan Simple)**
is the AArch64 Caladan port using the simple scheduler and ConnectX-5 mlx5
directpath; **DPDK** is the polling transport/CPU-processing baseline. CS-A64
evaluates runtime-managed networking and core allocation, not a Shenango
simulation. Compare both with **LH** under matched workload semantics and
resource budgets. The [Caladan baseline](caladan-enzian.md) records finite ARM,
packet-path and shared-echo acceptance; controlled paper measurements and
memcached comparison remain pending. Conventional Linux RPC and static
Lauberhorn are additional controls. Full Caladan IAS/interference-control
claims are outside the selected scope.

**Native Lauberhorn memcached versus Caladan memcached is a main paper goal.**
Use the [Caladan baseline plan](caladan-enzian.md) and
[memcached application evidence](../applications/memcached.md) to define comparable application
versions, operation mixes, concurrency, skew and worker/core budgets. The existing
Linux-over-FPGA-bypass comparison is a separate control, not the Caladan baseline.

The [DPDK microbenchmark baseline](dpdk-baseline.md) uses ConnectX-5 polling
and the shared echo RPC software path to compare CPU-side networking with LH.
Its configured workers provide a transport/CPU-processing baseline; CS-A64
separately evaluates Caladan's runtime-managed scheduling. Both use the common
[hot measurement methodology](hot-measurement.md).

| Planned application | Purpose in the evaluation / integration starting point |
| --- | --- |
| RPC server | Basic request dispatch, payload/handler sweeps and multi-service load; current microbenchmarks are a starting point |
| Userspace NFS server | Stateful service and larger data movement; implementation/version and filesystem semantics to select |
| memcached | Main paper comparison: native Lauberhorn versus Caladan (CS-A64), covering key-value service latency, throughput and dynamic worker allocation under comparable workloads and resource budgets. Native bounded integration results exist; the controlled baseline comparison remains pending. |
| [Go Raft/etcd](../applications/etcd.md) | Chained peer RPCs and stateful coordination; proposed Go runtime integration first, with transport, durability and failure semantics still to define |

Application inclusion does not imply a completed port or evaluation.
[Dandelion is bracketed](../applications/dandelion.md): process-sandbox overhead
dominates enough that Lauberhorn cannot make a meaningful end-to-end difference.
Its retained results are outside the active paper goals.

## Follow-on scope

The finished [TCP](tcp.md) and [Protobuf](protobuf.md) student projects should be integrated soon to avoid drift, with lower priority than the active applications above. Fully offloaded gRPC is a potential later paper. See [protocol integration roadmap](../proposals/grpc.md); do not expand the first paper into that agenda by default.

## Proposed evidence plan

| Claim to test | Experiment and controls | Repository starting point |
| --- | --- | --- |
| Coherent delivery reduces host RPC overhead | Sweep payload and handler sizes; compare equivalent transports and report CPU work separately from end-to-end latency | `../microbenchmarks/`, `sw/core/eci/core.h` |
| Scheduling integration helps changing demand | More services than workers, bursts, idle-to-active transitions, skew, competing RPC services within isolated workers; report tail latency, throughput, core occupancy, fairness and transition cost | `Scheduler.scala`, `sw/kmod/sched.c`, `../microbenchmarks/client/bench_dpdk.c` |
| Using the RX return half improves application performance | After baseline application measurements, compare independent TX with a coupled RX-return prototype; measure fitting-response fraction, latency/tails, throughput, CPU work and coherence contention, including large-response fallback | [Evaluation and decision criteria](../proposals/rx-return-responses.md#evaluation-and-decision-criteria); [protocol design questions](hot-path-performance.md#hardware-reuse-the-rx-return-half-for-responses) |
| Benefits come from integration | Ablate dynamic scheduling, compare pinned service workers, separate coherent transport effects from scheduling effects | Same workload and hardware across configurations; implementations of controls still need auditing |
| Mechanism scales beyond tiny tables | Sweep services/processes/in-flight calls through capacity; account for spills, queueing, drops and hardware area/timing | [Lookup/spill proposal](../proposals/lookup-spill.md), [hardware limits](../hardware/implementation-status.md) |
| RPC composition is practical | Remote nested calls, concurrent continuations, reply reordering, saturation, local calls if supported | [Nested RPC contract](../software/nested-rpc.md), component simulations and application-specific evidence; bounded Dandelion functionality is outside the active paper goals |
| Coherence and isolation remain correct | Worker handoff at protocol boundaries, stale mappings, process exit, receive-critical-section timeout and overload; model plus directed simulation and board stress | `model/`, router/preemption RTL, ECI simulations |

Security/flexibility evidence must accompany latency: define the trusted NIC/kernel boundary, per-process mappings and registration authority, validate cross-process isolation and stale-pointer/ownership handling, and show resource reclamation on exit and timeout. Bound exhaustion effects across services. These are proof and test obligations for the system claim, not guarantees already demonstrated by the prototype. Run-to-finish does not promise bounded latency for an unbounded handler; the application timeout policy remains open.

## Measurement and correctness contracts

Use the [hot-path methodology](hot-measurement.md) and
[campaign workflow](../development/benchmark-campaign.md) for workload boundaries,
instrumentation controls, clock uncertainty, provenance and reproduction.
Separate wire latency, client RTT, CPU work and scheduler intervals. Measure
energy or CPU availability before claiming system-level savings from blocked loads.

The [formal topics](../formal/README.md) distinguish selected bounded results,
negative controls and environment assumptions from concrete RTL correspondence.
The legacy model and its historical outputs are not a proof of today's system.
Use [validation](../development/validation.md) for targeted checks and matched
board testing; finite acceptance does not establish full-system refinement or
unbounded progress. Complete recovery and adversarial isolation obligations
remain explicit in the [roadmap](roadmap.md#progress-isolation-and-recovery).

## Suggested paper structure

Problem and OS/NIC boundary → published 2F2F foundation → RPC programming/ownership model and scheduler mechanism → implementation and correctness → controlled evaluation → limitations and generalization. Make whole-system RPC efficiency, application composition, dynamic service allocation and OS isolation the core evidence. Quantify coherence traffic, FPGA resources/timing and bottlenecks to explain the implementation. CXL portability needs an explicit semantic mapping or experiment; ECI results alone do not establish it.
