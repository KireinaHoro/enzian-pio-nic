# Packet processing

RX: CMAC → Ethernet → IPv4 → UDP → ONC-RPC call **or reply** decoder → `DecoderSink` → `DmaControlPlugin`. The most specific decoder emits metadata plus the remaining payload; supported RPC requests/replies become scheduler-visible host requests, while bypass descriptors go through `BypassCmdSink` to datapath 0. `PacketBuffer` DMA stores residual payload in FPGA RAM; decoded/inline data lives in metadata. The host receives control/inline fields plus overflow data through its datapath protocol.

TX: host writes payload/control → `DmaControlPlugin` → packet-buffer DMA → `EncoderSource` → ONC-RPC reply **or call** encoder → UDP → IPv4 → Ethernet → CMAC. Nested outbound calls and inbound replies are installed in the current generator. See [nested RPC details](../software/nested-rpc.md).

The IP encoder queries a software-populated neighbor table. On a missing or incomplete neighbor it emits a packet-bearing `neighborMiss` through `DecoderSink`/`DmaControlPlugin` into bypass; it does not implement ARP itself. `sw/kmod/bypass.c::rx_handle_neighbor_miss` reconstructs IPv4 and calls Linux routing/`ip_local_out`, while software updates reachable hardware neighbors. This replaces the older notification-only/drop behavior. Allocation/routing/output failures can still drop, and general RPC table spill remains a proposal.

## Source navigation and ownership

All paths below are relative to `hw/src/lauberhorn/`:

| Stage | Contract |
| --- | --- |
| `MacInterface.scala` | CMAC clock crossings, frame boundaries/lengths, network AXI-Stream |
| `net/Decoder.scala`, `net/ProtoState.scala` | Decoder registration, protocol metadata, final/bypass output |
| `net/ethernet/`, `net/ip/`, `net/udp/` | Header parsing/encoding; selected traffic advances to the next protocol |
| `net/oncrpc/OncRpcCallDecoder.scala` | Service lookup selects PID/function, extracts inline arguments, records reply-routing session |
| `net/oncrpc/OncRpcReplyDecoder.scala` | Matches nested replies by XID and network tuple; emits PID/cookie and inline reply data |
| `net/DecoderSink.scala` | Prioritizes the most specific descriptor and selects its matching payload stream |
| `DmaControlPlugin.scala` | Allocates/stores residual RX payload, dispatches host metadata, collects RX acknowledgments and TX requests |
| `PacketBuffer.scala` | FPGA RAM with AXI DMA, separate RX allocation region and per-datapath TX buffers |
| `net/EncoderSource.scala` | Selects TX encoder by descriptor type and pairs its residual payload |

The RX pipeline serializes packet admission through acknowledgment. Metadata and payload travel separately: do not independently arbitrate them or remove acknowledgment gating without preserving their association. Inline bytes do not imply the whole packet lives in the descriptor. RX acknowledgment releases allocated residual payload; queue-full/drop paths need explicit ownership handling (see [status](implementation-status.md)).

The shared `AxiStreamExtractHeader` requires `outputAck` for every emitted header,
including header-only/partial packets with no residual payload. Consumers must
acknowledge dispatch; leaving this input undriven stalls later headers. Packets
shorter than `minHeaderLen` emit neither header nor payload and increment
`incompleteHeader` in the discard path without waiting for acknowledgment.
The standalone blocks tests model both kinds of accepted packet and check delayed
acknowledgment plus deterministic short-packet boundaries.

## Current protocol limits

ONC-RPC uses fixed protocol structures and inline byte arrays (48 bytes for normal RX inline data, 24 for nested-call inline data in `Global.scala`). This is not arbitrary IDL deserialization. The call decoder explicitly leaves argument bytes in network order and assumes a little-endian host when programming selected service fields. Follow the generated host descriptors and runtime serialization when changing formats.

Unmatched ONC-RPC services currently drop; nested reply misses/malformed replies also have drop paths. IPv4 options/validation and default-gateway behavior remain incomplete. Hardware TCP, HTTP and gRPC are placeholders. See [lookup spill proposal](lookup-spill.md) before treating a miss as a software-owned request: the proposed typed slow paths are not the current behavior.

See [architecture](architecture.md) for ECI delivery and [implementation status](implementation-status.md) for capability/verification boundaries.
