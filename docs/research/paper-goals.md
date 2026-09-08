# Full systems paper: goals and evidence

Survey: 2026-09-08, base `b86c68c`. The system objective, application portfolio, baseline and scheduling scope below are maintainer-confirmed. Experiments are proposals; venue/cycle and exact application versions remain unspecified.

## Starting claim

The coherent datapath/2F2F is published groundwork: [Rethinking Programmed I/O for Fast Devices, Cheap Cores, and Coherent Interconnects](https://arxiv.org/html/2409.08141v3). The [HotOS paper](https://arxiv.org/pdf/2501.10138) supplies the OS-integrated NIC direction. The full paper's contribution is an efficient, low-latency RPC **system built on 2F2F**, retaining the flexibility and security guarantees of OS ownership. Do not present 2F2F itself as the new contribution or infer system guarantees from transport measurements.

Worker cores are isolated from ordinary Linux tasks. Tasks run to finish; scheduling flexibility concerns allocation among RPC services and composition execution within that worker pool. A receive-critical-section kill timer or application timeout is intended, but exact timeout and recovery semantics remain open. This is not a design for arbitrary time-sliced handlers or sharing worker cores with ordinary Linux tasks.

## Planned applications and baseline

**Demikernel is the selected primary baseline.** Select and record its backend, version, supported platform, workload adapter and resource budget before comparison; no baseline integration was established in this survey. Conventional Linux RPC and static Lauberhorn can serve as additional controls. Keep application semantics and client/load generation comparable; disclose CPU/NIC differences where identical hardware is infeasible.

| Planned application | Purpose in the evaluation / integration starting point |
| --- | --- |
| Dandelion | Composition graphs and nested calls; tail-call design is deferred pending later work; student mock-based port and runtime in `../sven-dandelion/`, described in [Dandelion reference](dandelion.md) |
| RPC server | Basic request dispatch, payload/handler sweeps and multi-service load; current microbenchmarks are a starting point |
| Userspace NFS server | Stateful service and larger data movement; implementation/version and filesystem semantics to select |
| memcached | Key-value service, mixed operations, concurrency and skew; port remains planned |
| Consensus, e.g. etcd/Raft | Chained peer RPCs and stateful coordination; exact implementation and durability/failure semantics to select |

These are planned applications, not claims that five ports run on the current FPGA. The Dandelion student report evaluates a software mock; its results do not establish real-ECI speedups.

## Follow-on scope

The finished [TCP](tcp.md) and [Protobuf](protobuf.md) student projects should be integrated soon to avoid drift, with lower priority than Dandelion and the other applications above. Fully offloaded gRPC is a potential later paper. See [protocol integration roadmap](grpc-roadmap.md); do not expand the first paper into that agenda by default.

## Proposed evidence plan

| Claim to test | Experiment and controls | Repository starting point |
| --- | --- | --- |
| Coherent delivery reduces host RPC overhead | Sweep payload and handler sizes; compare equivalent transports and report CPU work separately from end-to-end latency | `sw/apps/microbenchmarks/`, `sw/core/eci/core.h` |
| Scheduling integration helps changing demand | More services than workers, bursts, idle-to-active transitions, skew, competing RPC services within isolated workers; report tail latency, throughput, core occupancy, fairness and transition cost | `Scheduler.scala`, `sw/kmod/sched.c`, `sw/apps/microbenchmarks/client/run_desched_bench.py` |
| Benefits come from integration | Ablate dynamic scheduling, compare pinned service workers, separate coherent transport effects from scheduling effects | Same workload and hardware across configurations; implementations of controls still need auditing |
| Mechanism scales beyond tiny tables | Sweep services/processes/in-flight calls through capacity; account for spills, queueing, drops and hardware area/timing | `docs/hardware/lookup-spill.md`, hardware status doc |
| RPC composition is practical | Remote nested calls, concurrent continuations, reply reordering, saturation, local calls if supported | `hw/test/src/lauberhorn/host/eci/OncRpcNestedSim.scala`; C runtime completion remains separate work |
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

`model/lauberhorn.tla` models caches, FPGA handlers, services, kernel state and core assignment. Toolbox configurations include type, cache-line exclusion, preemption and isolation invariants plus temporal properties. Checked-in `MC.out` files report historical successful model checks; they are not a proof that today's RTL refines that model. Reconcile model assumptions with decoupled RX/TX, routing, nested RPC and DCU progress, and rerun a recorded configuration before making new correctness claims.

## Suggested paper structure

Problem and OS/NIC boundary → published 2F2F foundation → RPC programming/ownership model and scheduler mechanism → implementation and correctness → controlled evaluation → limitations and generalization. Make whole-system RPC efficiency, application composition, dynamic service allocation and OS isolation the core evidence. Quantify coherence traffic, FPGA resources/timing and bottlenecks to explain the implementation. CXL portability needs an explicit semantic mapping or experiment; ECI results alone do not establish it.
