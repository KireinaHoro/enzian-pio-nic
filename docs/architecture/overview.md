# System map

Repository survey: 2026-09-08, base commit `b86c68c`. Read this once, then use the topic documents. Source paths are relative to the repository root. Implementation statements describe this checkout, not all Lauberhorn branches.

## Intent and boundary

Lauberhorn is a smart NIC integrated with OS scheduling, prototyped on Enzian. Its full-system goal is efficient low-latency RPC with OS flexibility and security, built on [published 2F2F](https://arxiv.org/html/2409.08141v3). Scheduling allocates isolated worker cores among RPC services; ordinary Linux tasks do not share those cores. Tasks run to finish, with timeout/kill semantics still open. The [HotOS 2025 paper](https://arxiv.org/pdf/2501.10138), §§4–6, supplies the vision, not a checklist of completed features.

## Current composition

```text
Ethernet CMAC → Ethernet/IP/UDP/ONC-RPC decode → packet storage + metadata
                                                     ↓
                                          FPGA Scheduler
                                                     ↓
                        ECI thread router + worker 2F2F protocol
                                                     ↓
                    Linux worker scheduling ↔ userspace runtime/handler
                                                     ↓
                     host TX → RPC/UDP/IP/Ethernet encode → CMAC

Raw/control exceptions → bypass datapath 0 → kernel NAPI/netdev/ARP
```

- `hw/src/lauberhorn/GenEngineVerilog.scala` composes SpinalHDL plugins; normal generation creates four workers plus bypass datapath 0. These are logical NIC datapaths, not the machine's entire CPU population.
- `hw/src/lauberhorn/Scheduler.scala` owns NIC request placement; `sw/kmod/sched.c` implements the Linux-side worker transitions. Do not conflate these two schedulers.
- `hw/src/lauberhorn/host/eci/` implements coherent transport and preemption; `vivado/eci/rtl/lauberhorn_eci.vhd` integrates generated RTL with DCS, ECI and CMAC.
- `sw/kmod/` manages protected resources and worker activation; `sw/rt/` dispatches RPCs in userspace. The current C runtime still performs XDR work.
- `sw/include/`, generated configuration/Mackerel headers, and `sw/core/eci/core.h` form a coupled hardware/software ABI. Regenerate collateral after hardware layout changes.
- `data/eci/sys_trace/` and the microbenchmark client tools explain observed behavior; `model/lauberhorn.tla` is an abstract correctness model.

## Navigation

| Task | Read first |
| --- | --- |
| FPGA modules, buffers, protocol ordering | [Hardware architecture](../hardware/architecture.md) |
| Implemented hardware and gaps | [Hardware status](../hardware/implementation-status.md) |
| Preemption, worker lifetime, Linux policy | [Scheduler integration](../software/scheduler.md) |
| Runtime transport and dispatch | [Runtime](../software/runtime.md) |
| Write an application | [Programming model](../software/programming-model.md) |
| Build and deploy | [Workflow](../development/workflow.md) |
| Select verification | [Validation](../development/validation.md) |
| Full-paper evidence and experiments | [Paper goals](../research/paper-goals.md) |
| Unfinished work and decisions | [Roadmap](../research/roadmap.md) |

## Detailed references

- [Packet processing](../hardware/packet-processing.md) and [tracing](../development/tracing.md): source-oriented mechanism detail.
- [Nested RPC](../software/nested-rpc.md): implemented descriptor/RTL paths and remaining runtime integration plan.
- [Dandelion](../research/dandelion.md): external student report, mock-based application/runtime and porting boundaries.
- [TCP/Protobuf and gRPC roadmap](../research/grpc-roadmap.md): finished external projects and lower-priority integration scope.
- [Latency measurement](../research/latency-measurement.md): measurement definitions, correlation and historical examples.
- [Lookup spill](../hardware/lookup-spill.md): proposed software-backed table scaling.
- [Coherence progress](../hardware/coherence-progress.md) and [queue-pair design](../hardware/queue-pair-design.md): unresolved DCU layout and ownership choices.

The older top-level notes have been consolidated into these topics. Read status labels before using a proposal as an implementation contract. Resolve conflicts against current source while keeping maintainer intent separate.
