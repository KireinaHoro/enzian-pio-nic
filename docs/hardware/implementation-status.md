# Hardware implementation and gaps

ECI is the active Enzian prototype: the selected LCI-only nonlocking DC, 8 KiB
physical-worker address map and cooperative DESCHED are implemented. Source
presence, model results and board measurements have different scopes; the
references below distinguish them.

## Implemented components

| Component | Contract and evidence |
| --- | --- |
| Four worker engines plus bypass by default; protected domain aliases and cooperative activation | [ECI integration](eci-implementation.md), [address-space design](queue-pair-design.md), [scheduling interface](../software/scheduling-control-interface.md) |
| Decoupled coherent RX/TX, contiguous payloads, mutable RX replay and TX scrub | [Cache-line interface](../architecture/hw-sw-interface.md), [isolation checks](../formal/domain-isolation.md) |
| Ethernet/IPv4/UDP, native UDP and ONC-RPC call/reply processing, nested reply matching, packet RAM and allocation | [Packet processing](packet-processing.md), [nested RPC](../software/nested-rpc.md) |
| Multi-source tracing, generated event map and dedicated trace DMA | [Tracing](../development/tracing.md) |
| One-shot GSYNC early NACK and programmable RX wait timeout | [ECI behavior](eci-implementation.md#one-shot-early-nack-implementation), [finite progress evaluation](../research/eci-progress.md) |

## Gaps and limits

| Topic | Limit and reference |
| --- | --- |
| Shared-resource progress | Coloring separates active control colors from overflow colors; it does not discharge shared queues, CPU synchronization or endpoint-service assumptions. [Finite contention and GSYNC results](../research/eci-progress.md) distinguish three-peer contention from one/two-peer early-NACK coverage. They do not establish infinite-stall progress, all worker permutations or four-worker tail distributions. |
| Ownership recovery | Fatal faults fence the affected domain; lifetime codes are not recycled. Complete kill/exit resource recovery and deadline activation remain separate work. See [recovery](../software/scheduler.md#failure-and-reset-boundary). |
| Table scaling | `Global.scala` limits tables because fully connected lookup routing is costly to place. [Lookup spill](../proposals/lookup-spill.md) proposes caches and typed software slow paths; general spill is not implemented. |
| TCP/HTTP/gRPC | The in-tree classes are placeholders. External [TCP](../research/tcp.md) and [Protobuf](../research/protobuf.md) projects are unmerged; see the [protocol roadmap](../proposals/grpc.md). |
| PCIe parity | `PcieDeschedulingControlPlugin.deschedReq` is unimplemented. `PcieDatapathPlugin` flags unreliable TX triggering from a shared register word. PCIe does not provide the ECI scheduling contract. |
| Coupled RX/TX | Generated datapaths use `EciDecoupledRxTxProtocol`. [RX-return response transmission](../research/hot-path-performance.md#hardware-reuse-the-rx-return-half-for-responses) is a proposal. |
| Network completeness | IPv4 validation/options, default-gateway behavior and malformed ONC-RPC handling have explicit source TODOs. Inline ONC-RPC fields are not a general serialization engine. |
| Resource pressure | Packet allocation is finite; `PacketAlloc.scala` flags empty-allocation behavior. The scheduler drops requests for unmatched, disabled or full process queues. Do not infer losslessness from low-load RPC success. |
| Physical timing | Routed implementation results and the optimization pause belong to [physical implementation](physical-implementation.md). Functional success does not establish timing closure. |

[Validation](../development/validation.md) selects model, RTL and board checks.
[Research goals and remaining claims](../research/roadmap.md#evidence-and-remaining-claims)
define further application-driven evaluation; this index does not reopen the
closed standalone scheduling campaign.
