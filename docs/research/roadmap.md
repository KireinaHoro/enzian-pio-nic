# Pending features and design decisions

Survey: 2026-09-08, base `b86c68c`. Maintainer priority: Dandelion and other first-paper applications come before TCP/Protobuf integration. Merge the finished protocol projects soon to limit drift; fully offloaded gRPC is a potential later paper. Other ordering below is a survey recommendation, not an approved schedule. Keep unresolved choices explicit; do not implement a proposal merely because it appears here.

## Work supported by repository evidence

| Work | Current evidence / gap | Completion evidence to seek |
| --- | --- | --- |
| Scheduling robustness under pressure | `hw/src/lauberhorn/Scheduler.scala`: queue-full drop has buffer-free FIXME; fairness and non-retrying-core recovery TODOs | No buffer leak under saturation; bounded progress/recovery; documented policy and multi-process stress |
| Scalable service/process/session tables | `docs/hardware/lookup-spill.md` proposes hardware caches and typed software spill ownership | End-to-end admission, eviction, ownership and packet lifetime tests beyond table capacity |
| Nested RPC usable from applications | Call encoder/reply decoder and nested simulation exist; hardware support does not imply C-runtime completion support | Runtime API and continuation lifetime, reply dispatch, error/timeout cleanup, application demo |
| Coherence progress and queue-pair placement | DCU-progress and thread-router-tradeoffs notes describe constraints and alternative layouts | Selected ownership/layout policy, admission rules, checked model and adversarial board traces |
| TCP and Protobuf integration | Finished external student projects in `../flavian-tcp` and `../elena-protobuf`; unmerged here | Adapt code/dependencies and validate host contracts after higher-priority applications; [follow-on roadmap](grpc-roadmap.md) |
| Data movement and decode extensions | Current C runtime performs XDR; generator TODO suggests coupled worker protocol | Chosen ABI and workloads; measured benefit; large-message strategy independently validated |
| Reproducible evaluation | Trace/latency tooling and descheduling microbenchmark exist; historical examples are not a full evaluation | [Paper evidence matrix](paper-goals.md), reproducible raw data and controls |
| Development consistency | Build/CI/manual instructions have drift; PCIe contains unfinished preemption | See [workflow](../development/workflow.md) and [validation](../development/validation.md); repair only within a separately scoped implementation task |

The kernel/control bypass and runtime-owned request spill planes are intentionally distinct in the spill proposal. Preserve that distinction when designing new descriptors; establish who frees packet storage and owns retry before adding a message type.

## Confirmed design intent (maintainer, 2026-09-08)

- Build the full RPC system on published 2F2F; demonstrate low latency and efficiency while retaining OS flexibility and security. [Paper scope and application portfolio](paper-goals.md).
- Primary baseline: Demikernel. Planned applications: Dandelion, RPC server, userspace NFS, memcached, and consensus such as etcd/Raft.
- `../sven-dandelion/{report,platform,dandelion}` contains the student report, mock platform/runtime extensions and Dandelion port. See [source map and limitations](dandelion.md); these are external reference trees, not merged support in this checkout.
- Worker cores are isolated and never shared with ordinary Linux tasks. RPC tasks run to finish. A timer should kill a task stuck in the 2F2F receive critical section, or an application timeout should bound it; precise policy is still open. Current `BUG_ON(killed)` is an implementation gap, not that intended recovery guarantee.
- TCP and Protobuf student platform/report trees are located and documented in [TCP](tcp.md) and [Protobuf](protobuf.md). Merge soon to avoid drift, at lower priority than Dandelion and the other first-paper applications. Fully offloaded gRPC is a possible subsequent paper.
- Queue-pair ownership/DCU coloring remains open. Current thread-owned routing is the implementation baseline, not a settled final choice.

## Remaining decisions

1. Select QP ownership, DCU layout/coloring and admission rules; define guaranteed concurrency.
2. Specify receive-critical-section versus whole-application timeout, timer start/reset points, kill granularity and safe resource/reply cleanup. Define service allocation fairness if a guarantee is needed; run-to-finish alone does not define it.
3. Tail calls were proposed at the end of the student project; do not presume implementation. Follow the report review in [Dandelion](dandelion.md); unwritten design is deferred to later maintainer work. Decide which student runtime interfaces to adopt and how fibers/continuations interact with real ECI, migration and finite hardware tables.
4. Select NFS and consensus implementations, Demikernel backend/version and comparison platform, target venue/cycle, and ordered feature milestones.
5. Choose the TCP/Protobuf merge order and follow-on gRPC scope without displacing first-paper applications. Any separate Linux/full-paper source trees still need identification if required; student reports are not the full-system paper source.

Keep maintainer intent separate from source-backed implementation status. Do not silently turn these remaining choices into requirements or reopen the settled isolated-worker policy.
