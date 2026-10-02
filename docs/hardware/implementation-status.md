# Hardware status and unresolved work

Inspected 2026-09-08. “Present” means source exists and is wired into the generator; it does not certify current simulation, timing closure, or board validation. No hardware builds or simulations were run for this documentation pass.

**2026-10-02 update:** the selected build now uses the [LCI-only non-locking
DC](lauberhorn-rom.md), with UL removed through the application interfaces. The
[bounded scheduling model](scheduling-composition.md) is checked; the router-free
8 KiB layout and cooperative PREEMPT ABI are now the sole ECI source implementation; validation and board acceptance remain in progress. The source survey
below predates this change; hardware-service/refinement obligations remain open.

## Present in the main source tree

- ECI/Enzian integration, four worker engines plus bypass, physical-worker decoding and domain-owner validation, decoupled coherent RX/TX, preemption signaling: `hw/src/lauberhorn/GenEngineVerilog.scala`, `host/eci/`.
- Ethernet/IPv4/UDP/ONC-RPC call and reply processing, nested-call reply matching, FPGA packet RAM and allocation: `hw/src/lauberhorn/net/`, `DmaControlPlugin.scala`, `PacketBuffer.scala`. `hw/test/src/lauberhorn/host/eci/OncRpcNestedSim.scala` provides targeted simulation coverage; [nested RPC note](../software/nested-rpc.md) describes semantics and limits.
- Multi-source tracing with generated event map and separate trace DMA: `hw/src/lauberhorn/{TracePlugin,LauberhornTraceDma,LauberhornTraceDump}.scala`, `vivado/eci/rtl/lauberhorn_eci.vhd`, [tracing guide](../development/tracing.md).

## Gaps that must remain explicit

| Topic | Evidence and implication |
| --- | --- |
| DCU progress/concurrent QPs | [Progress analysis](coherence-progress.md), `host/eci/DcsTxAxiRouter.scala`, `EciDecoupledRxTxProtocol.scala`: current RX/TX layout aliases control colors. Bypass RX/TX serialization is a workaround; neither distinct physical blocks nor a thread router proves safe concurrent progress. |
| Ownership and activation | [ECI ownership](queue-pair-design.md) is implemented by the decoder, ownership table and cooperative yield path. See [current evidence](eci-implementation.md) for validation limits. |
| Table scaling and spill | `Global.scala` explicitly attributes small tables to fully connected lookup routing/placement cost. [Lookup spill design](lookup-spill.md) is a proposal for hardware caches and typed software slow paths, not implemented general spill support. |
| TCP/HTTP/gRPC | `hw/src/lauberhorn/net/{Tcp,Http,Grpc}.scala` are empty classes. Finished external [TCP](../research/tcp.md) and [Protobuf](../research/protobuf.md) projects exist; they are unmerged. Integration follows first-paper application work; see [gRPC roadmap](../research/grpc-roadmap.md). |
| PCIe parity | Legacy bridge/datapath/test sources exist, but `host/pcie/PciePreemptionControlPlugin.scala::preemptReq` is `???`, and `PcieDatapathPlugin.scala` flags unreliable TX triggering in a shared register word. Current PCIe generation/runtime parity needs validation and implementation work. |
| Coupled worker protocol | Generator TODO proposes coupled worker RX/TX; all current datapaths use `EciDecoupledRxTxProtocol`. |
| Network completeness | IPv4 validation/options, default-gateway behavior, malformed call handling, and richer argument handling have TODOs in `net/ip/{IpDecoder,IpEncoder}.scala` and `net/oncrpc/OncRpcCallDecoder.scala`. Existing ONC-RPC inline fields are not a general serialization engine. |
| Resource exhaustion/lifetime | `PacketAlloc.scala` flags empty allocation behavior; `Scheduler.scala` flags packet-buffer release when a process RX queue is full. Inspect ownership and pressure behavior before claiming losslessness. |

## Progress and verification traps

[DCU analysis](coherence-progress.md) requires active control colors to be globally unique and disjoint from every active overflow color after **ECI scrambling**. Full-duplex QPs contribute both directions; sequential single-thread workers contribute only their active direction. A blocking RX remains active. Its candidate layouts/capacity calculations are design analysis, not installed address maps or measured guarantees.

`hw/test/src/lauberhorn/host/eci/OncRpcSim.scala` contains `rx-tx-interleaved`, tagged `Slow`, with a FIXME to rework the address map and re-enable for CI. It is still registered as a test; excluding `Slow` omits it. `rx-hol-blocking-free` has a TODO-only body, so a passing suite does not establish this property. Inspect test bodies/tags before quoting coverage.

Prioritize address-layout/progress validation, explicit QP ownership across scheduling, and resource/table scaling before increasing worker/thread counts. Changes require both RTL-model tests and real ECI traces: simulation alone cannot establish absence of ThunderX/DCS coherence deadlocks.

## Open design decisions

The selected address and cooperative scheduling contract is the sole ECI design. Worker CPUs remain isolated. Complete exit recovery, deadline activation and PCIe scheduling are deferred. Unsupported exits and fatal activation failures fence the affected domain and require reset; identities are not recycled.

Hardware acceptance still requires the fresh Enzian RX stall boundary campaign, concurrent peer progress and examination of every exposed L2C_TAD unit. Finite model and simulation results do not establish a board timeout limit.
