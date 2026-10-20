# TCP and Protobuf integration toward gRPC

**Proposal; no integrated gRPC implementation.** The completed external TCP and
Protobuf research projects are inputs for follow-on protocol work. Integrate
them to limit drift, below active first-paper applications and the CS-A64/DPDK
baseline evaluation. Fully offloaded gRPC is a potential later paper; it is not
required for the [first systems paper](../research/paper-goals.md).

## Existing inputs

| Component | Source and canonical description | Boundary |
| --- | --- | --- |
| Partial TCP offload | `../flavian-tcp/{platform,report}`; [TCP](../research/tcp.md) | External hardware and simulated software-owner model; no production host TCP ABI in platform. |
| Protobuf receive decoder | `../elena-protobuf/{platform,report}` and dependencies; [Protobuf](../research/protobuf.md) | External UDP-fed decoder and simulated object reconstruction; no production schema/fragment ownership API in platform. |

The TCP report leaves HTTP/2 stream/frame handling and gRPC envelope decoding
outside its implementation. Protobuf decoding alone supplies neither method
selection nor response serialization. Combining the two existing components
does not supply the missing end-to-end protocol.

## Integration contracts

Adapt the components to current generated descriptors, [cache-line layout](../architecture/hw-sw-interface.md),
[borrowed-buffer ownership](../software/programming-model.md), scheduling and
tracing. Preserve working native UDP/ONC-RPC configurations and explicitly gate
incomplete paths. Matching descriptor widths alone does not establish ABI
compatibility.

TCP needs a production software owner for recovery, window/congestion and
retransmission policy, connection lifetime and privileged commands. Protobuf
needs schema registration, output ownership and a bounded rule that assembles
multi-slot output into one application invocation. Their research notes retain
the detailed alternatives and gaps.

The gRPC design must additionally specify:

- TCP stream reassembly, HTTP/2 multiplexing and flow control, and gRPC framing.
- Method/schema selection, request decoding, response serialization and dispatch.
- Concurrency/admission bounds, errors, cancellation, buffer lifetime and recovery.
- Trust boundaries and isolation for connection, schema and privileged state.

Preserve source/dependency provenance and component tests, then test the integrated
host boundary and matched FPGA/OS configuration. Compare enabled and baseline
configurations for resource cost, timing and latency. Model-host or manually
configured-schema tests cannot stand in for a production runtime consumer.
Use the [validation workflow](../development/validation.md) for integrated checks.

## Open sequencing decision

TCP and Protobuf have no selected merge order or date. A concrete first-paper
application dependency can determine that order; it does not automatically make
full gRPC a first-paper goal. Reproduction prerequisites and source reconciliation
are recorded in [agent context](../../agents/runtime-applications.md#tcp-and-protobuf-integration-prerequisites).
