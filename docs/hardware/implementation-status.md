# Hardware status and unresolved work

Inspected 2026-09-08. “Present” means source exists and is wired into the generator; it does not certify current simulation, timing closure, or board validation. No hardware builds or simulations were run for this documentation pass.

**October 8 consolidation:** the selected build uses the LCI-only non-locking
DC, 8 KiB physical-worker map and cooperative PREEMPT. Corrected isolation,
516232 module bring-up, GSYNC early NACK and hot/cold microbenchmarks have finite
board evidence. The [roadmap status table](../research/roadmap.md#current-status--october-8-consolidation)
is the consolidated claim/evidence index. The source survey below is older;
expanded acceptance, hardware-service/refinement obligations and timing remain
open. No fresh hardware build or test accompanies this documentation pass.

## Present in the main source tree

- ECI/Enzian integration, four worker engines plus bypass, physical-worker decoding and domain-owner validation, decoupled coherent RX/TX, preemption signaling: `hw/src/lauberhorn/GenEngineVerilog.scala`, `host/eci/`.
- Ethernet/IPv4/UDP/ONC-RPC call and reply processing, nested-call reply matching, FPGA packet RAM and allocation: `hw/src/lauberhorn/net/`, `DmaControlPlugin.scala`, `PacketBuffer.scala`. `hw/test/src/lauberhorn/host/eci/OncRpcNestedSim.scala` provides targeted simulation coverage; [nested RPC note](../software/nested-rpc.md) describes semantics and limits.
- Multi-source tracing with generated event map and separate trace DMA: `hw/src/lauberhorn/{TracePlugin,LauberhornTraceDma,LauberhornTraceDump}.scala`, `vivado/eci/rtl/lauberhorn_eci.vhd`, [tracing guide](../development/tracing.md).

## Gaps that must remain explicit

| Topic | Evidence and implication |
| --- | --- |
| DCU progress/concurrent QPs | [Progress analysis](coherence-progress.md), the selected 8 KiB map separates active control colors from overflow colors and supports full-duplex bypass. Production slice control routing and completion checks supplement the bounded models; finite board progress is recorded, but simultaneous four-worker tail behavior, three stalled peers and corrected-image RX hold qualification remain open. |
| Ownership and activation | [ECI ownership](queue-pair-design.md) is implemented by the decoder, ownership table and cooperative yield path. See [current evidence](eci-implementation.md) for validation limits. |
| Table scaling and spill | `Global.scala` explicitly attributes small tables to fully connected lookup routing/placement cost. [Lookup spill design](lookup-spill.md) is a proposal for hardware caches and typed software slow paths, not implemented general spill support. |
| TCP/HTTP/gRPC | `hw/src/lauberhorn/net/{Tcp,Http,Grpc}.scala` are empty classes. Finished external [TCP](../research/tcp.md) and [Protobuf](../research/protobuf.md) projects exist; they are unmerged. Integration follows first-paper application work; see [gRPC roadmap](../research/grpc-roadmap.md). |
| PCIe parity | Legacy bridge/datapath/test sources exist, but `host/pcie/PciePreemptionControlPlugin.scala::preemptReq` is `???`, and `PcieDatapathPlugin.scala` flags unreliable TX triggering in a shared register word. Current PCIe generation/runtime parity needs validation and implementation work. |
| Coupled worker protocol | Generator TODO proposes coupled worker RX/TX; all current datapaths use `EciDecoupledRxTxProtocol`. |
| Network completeness | IPv4 validation/options, default-gateway behavior, malformed call handling, and richer argument handling have TODOs in `net/ip/{IpDecoder,IpEncoder}.scala` and `net/oncrpc/OncRpcCallDecoder.scala`. Existing ONC-RPC inline fields are not a general serialization engine. |
| Resource exhaustion/lifetime | `PacketAlloc.scala` flags empty allocation behavior; `Scheduler.scala` flags packet-buffer release when a process RX queue is full. Inspect ownership and pressure behavior before claiming losslessness. |

## Progress and verification traps

[DCU analysis](coherence-progress.md) requires active control colors to be globally unique and disjoint from every active overflow color after **ECI scrambling**. Full-duplex QPs contribute both directions; sequential single-thread workers contribute only their active direction. A blocking RX remains active. The selected layout is installed in source and checked against the capacity utility. Arithmetic alone is not a measured progress guarantee.

`hw/test/src/lauberhorn/host/eci/OncRpcSim.scala` checks finite stalled-worker progress and RX/TX interleaving against the selected map. These host coherence models and the production slice control-array test do not establish infinite-stall silicon progress. See the evidence ledger for exact passing cases.

Prioritize address-layout/progress validation, explicit QP ownership across scheduling, and resource/table scaling before increasing worker/thread counts. Changes require both RTL-model tests and real ECI traces: simulation alone cannot establish absence of ThunderX/DCS coherence deadlocks.

## Open design decisions

The selected address and cooperative scheduling contract is the sole ECI design. Worker CPUs remain isolated. Complete exit recovery, deadline activation and PCIe scheduling are deferred. Unsupported exits and fatal activation failures fence the affected domain and require reset; identities are not recycled.

Hardware acceptance still requires the fresh Enzian RX stall boundary campaign, concurrent peer progress and examination of every exposed L2C_TAD unit. Finite model and simulation results do not establish a board timeout limit.

## CMAC register-control investigation (October 9)

The separate `fix/cmac-axil-init` branch restores F_MAC0 register control and
reset/link checks on interface UP and CMAC disable on DOWN. The historical
AXI-Lite aperture excluded the RS-FEC bank; component tests reproduce the decode failure. Pipeline 518139
passed two validated cold boots and 200 RPCs with register-controlled CMAC
initialization before the later software-only UP/DOWN lifecycle restoration.
Failed-UP retry and DOWN/UP pass local callback tests; that lifecycle has not
yet been retested on hardware. One BMC power-up failure and one rejected ECI
boot are recorded separately; timing remains unclosed. See the [investigation and checks](../development/cmac-axil-init.md).
