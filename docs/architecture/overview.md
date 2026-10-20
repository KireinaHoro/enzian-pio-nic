# System map

Use this map to find the system components and their topic documentation.
Source paths below are relative to the repository root.

## Intent and boundary

Lauberhorn is a smart NIC integrated with OS scheduling, prototyped on Enzian. Its full-system goal is efficient low-latency RPC with OS flexibility and security, built on [published 2F2F](https://arxiv.org/html/2409.08141v3). Scheduling allocates isolated worker cores among RPC services; ordinary Linux tasks do not share those cores. Tasks run to finish, with timeout/kill semantics still open. The [HotOS 2025 paper](https://arxiv.org/pdf/2501.10138), §§4–6, supplies the vision, not a checklist of completed features.

## Current composition

```text
Ethernet CMAC → Ethernet/IP/UDP decode → native UDP / ONC-RPC decode
                                                     ↓
                                          packet storage + metadata
                                                     ↓
                                          FPGA Scheduler
                                                     ↓
                        ECI ownership decoder + worker 2F2F protocol
                                                     ↓
                    Linux worker scheduling ↔ userspace runtime/handler
                                                     ↓
                     host TX → native UDP / ONC-RPC → UDP/IP/Ethernet encode → CMAC

Raw/control exceptions → bypass datapath 0 → kernel NAPI/netdev/ARP
```

- `hw/src/lauberhorn/GenEngineVerilog.scala` composes SpinalHDL plugins; normal generation creates four workers plus bypass datapath 0. These are logical NIC datapaths, not the machine's entire CPU population.
- `hw/src/lauberhorn/Scheduler.scala` owns NIC request placement; `sw/kmod/sched.c` implements the Linux-side worker transitions. Do not conflate these two schedulers.
- `hw/src/lauberhorn/host/eci/` implements coherent transport and preemption; `vivado/eci/rtl/lauberhorn_eci.vhd` integrates generated RTL with DCS, ECI and CMAC.
- `sw/kmod/` manages protected resources and worker activation; `sw/rt/` dispatches inline handlers, explicitly deferred fiber work and nested-call completions. ONC-RPC codecs perform XDR in software. See the [runtime](../software/runtime.md) and [programming model](../software/programming-model.md) for ownership and API details.
- `sw/include/`, generated configuration/Mackerel headers, and `sw/core/eci/core.h` form a coupled hardware/software ABI. Regenerate collateral after hardware layout changes.
- `data/eci/sys_trace/` and the microbenchmark client tools explain observed behavior; the [model index](../../model/README.md) separates current resource/scheduling models from the original historical correctness model.

## Navigation

| Task | Read first |
| --- | --- |
| Routed timing, pipelining, floorplanning | [Physical implementation](../hardware/physical-implementation.md) |
| FPGA modules, buffers, protocol ordering | [Hardware architecture](../hardware/architecture.md) |
| Implemented hardware and gaps | [Hardware status](../hardware/implementation-status.md) |
| Preemption, worker lifetime, Linux policy | [Scheduler integration](../software/scheduler.md) |
| Runtime transport and dispatch | [Runtime](../software/runtime.md) |
| RX/TX cache-line layout and payload handoff | [HW/SW interface](hw-sw-interface.md) |
| Write an application | [Programming model](../software/programming-model.md) |
| Build and deploy | [Workflow](../development/workflow.md) |
| Model contracts, assumptions and bounded results | [Formal verification](../formal/README.md) |
| Select verification | [Validation](../development/validation.md) |
| Full-paper evidence and experiments | [Paper goals](../research/paper-goals.md) |
| Unimplemented designs and alternatives | [Open proposals](../proposals/README.md) |
| Unfinished work and decisions | [Roadmap](../research/roadmap.md) |

## Detailed references

- [Packet processing](../hardware/packet-processing.md) and [tracing](../development/tracing.md): source-oriented mechanism detail.
- [Nested RPC](../software/nested-rpc.md): descriptor/RTL paths; the [runtime](../software/runtime.md) and [endpoint contract](../software/nested-rpc.md#registered-endpoints) describe software integration.
- [Go toward etcd](../proposals/go-etcd.md): design-only G/M/P/L scheduling, HOST_WORK and native null-RPC stage; no implementation yet.
- [Dandelion](../research/dandelion.md): native application integration and historical student/mock design. The [measurement report](../research/dandelion-measurement.md) describes bounded Enzian composition results and their limits.
- [TCP/Protobuf and gRPC roadmap](../proposals/grpc.md): finished external projects and lower-priority integration scope.
- [Hot-path measurement](../research/hot-measurement.md): shared methodology; [hardware tracing](../hardware/tracing.md) defines FPGA instrumentation and correlation.
- [Lookup spill](../proposals/lookup-spill.md): proposed software-backed table scaling.
- [Queue-pair design and HoL blocking](../hardware/queue-pair-design.md): selected 8 KiB layout, domain aliases, ownership and the coloring rationale. Coloring alone does not establish full-system progress.

- [ECI progress evaluation](../research/eci-progress.md): matched contention and GSYNC experiments, timeout interpretation and finite coverage.
