# Packet processing

The generated datapath supports native TCP segments, native UDP and ONC-RPC
calls/replies over Ethernet/IPv4. Unsupported traffic can use the kernel bypass path. General
software-backed RPC table spill remains a [proposal](../proposals/lookup-spill.md).

## Receive and transmit paths

RX: CMAC → Ethernet → IPv4 → UDP → native UDP or ONC-RPC call/reply decoder →
`DecoderSink` → `DmaControlPlugin`. The most specific decoder emits metadata and
its remaining payload. Admitted application requests enter scheduler process
queues; bypass descriptors go through `BypassCmdSink` to datapath 0.

The shared IPv4 decoder checks version, IHL, total length against the header and
received frame, and the IPv4 header checksum before transport admission. It
aligns the first beat and uses a registered checksum reduction over up to 60
header bytes, including options. Valid options and fragments retain IP bypass.

TCP branches directly from IPv4 into `NativeTcpDecoder`. A separate listen-port
table selects the owning domain and endpoint by destination port for ordinary
unfragmented IPv4 packets with a 20-byte header. It forwards the complete received
IPv4 packet, including Ethernet padding, without TCP checksum, TCP length or
TCP-header validation. Software trims padding and validates TCP before processing
connections. Unmatched ports follow kernel bypass. There is no TCP connection
table or whole-packet validation FIFO in hardware.

Native UDP RX buffers and validates datagram lengths before admission. It does
not validate UDP checksums, including nonzero checksum fields. Shared IPv4
header validation remains enabled.

Scheduler queue reads likewise register the selected address before BRAM.
The selected slot stays occupied until the read edge, preventing an enqueue
from overwriting it; another reader cannot select that same head meanwhile.
Other queues remain eligible for arbitration.

`PacketBuffer` DMA stores residual payload in FPGA RAM. Parsed headers and
extracted RPC bytes travel in metadata. ECI reconstructs the logical payload
from metadata prefixes and stored bytes, then exposes contiguous inline or
overflow placement according to the [HW/SW cache-line interface](../architecture/hw-sw-interface.md).
Internal parser extraction sizes are not the host payload-placement boundary.

TX: host descriptor/payload → `DmaControlPlugin` admission and packet-buffer DMA
→ `EncoderSource` → native UDP or ONC-RPC reply/call encoding → UDP → IPv4 →
Ethernet → CMAC. Outgoing nested calls reserve completion-routing state before
DMA/encoding. Native UDP TX validates the endpoint against its owner and obtains
the local port from the listener table. See [nested RPC](../software/nested-rpc.md)
and the [programming model](../software/programming-model.md).

Native TCP TX checks endpoint ownership and DMA buffer bounds before transfer.
`NativeTcpEncoder` strips the supplied 20-byte IPv4 header and forwards the TCP
bytes unchanged, including the software-computed checksum. The existing IP and
Ethernet encoders generate wire headers and perform neighbor lookup. Software must
use the configured source IP when calculating the TCP pseudo-header checksum.
There is no TCP source-port, header or checksum validation/completion in hardware.
The descriptor requires more than 20 and at most 1500 bytes so the fixed IP-header
extractor has a residual payload. The VM follows this transport contract; Linux's
IP receive path still precedes its hook, and partial offload checksums are completed
to emulate received wire bytes.

## Source navigation and ownership

Paths below are relative to `hw/src/lauberhorn/`:

| Stage | Contract |
| --- | --- |
| `MacInterface.scala` | CMAC clock crossings, frame boundaries/lengths and network AXI-Stream |
| `net/Decoder.scala`, `net/ProtoState.scala` | Decoder registration, metadata and final/bypass output |
| `net/ethernet/`, `net/ip/`, `net/udp/` | Header parsing/encoding and listener-based protocol selection |
| `net/udp/NativeUdpDecoder.scala` | Buffers one datagram, checks lengths and supported IPv4 shape before admission, trims Ethernet padding and drains malformed input |
| `net/tcp/NativeTcpDecoder.scala`, `NativeTcpEncoder.scala` | Destination-port steering, complete IPv4 host payloads and opaque TCP TX |
| `net/oncrpc/OncRpcCallDecoder.scala` | Service lookup, enabled-state check, reply-context reservation and extraction of RPC argument bytes |
| `net/oncrpc/OncRpcReplyEncoder.scala`, `RpcReplyContexts.scala` | Per-invocation routing tokens, owner/generation validation, reply retirement and bounded full-table rejection |
| `net/oncrpc/OncRpcReplyDecoder.scala` | Outgoing-request reservations and reply matching by XID/network tuple, recovering PID/cookie |
| `net/DecoderSink.scala` | Selects the most specific descriptor and its matching payload stream |
| `DmaControlPlugin.scala` | RX allocation/storage and dispatch; acknowledgment/discard cleanup; TX admission and collection |
| `PacketBuffer.scala` | FPGA RAM with AXI DMA, separate RX allocation region and per-datapath TX buffers |
| `net/EncoderSource.scala` | Selects the TX encoder and pairs its residual payload |

Metadata and payload travel separately. RX admission is acknowledgment-gated;
independent arbitration must preserve their association. A transport RX
acknowledgment releases allocated residual storage. Scheduler discards also
return buffers through DMA cleanup and retire incoming ONC-RPC reply contexts.
A request rejected before packet allocation must not acquire a release obligation
for a nonexistent buffer.

The shared `AxiStreamExtractHeader` requires `outputAck` for every emitted header,
including header-only/partial packets with no residual payload. Leaving it
undriven stalls later headers. Packets shorter than `minHeaderLen` emit neither
header nor payload and increment `incompleteHeader` without waiting for an
acknowledgment. Standalone blocks tests cover accepted packets, delayed
acknowledgment and deterministic short-packet boundaries.

## Lookup and capacity behavior

Defaults come from `Global.scala`; each resource has a different failure policy.

| Resource | Default capacity | Miss/full behavior |
| --- | ---: | --- |
| UDP listeners, `UdpDecoder.listenDb` | 16 | Unselected traffic follows ordinary packet bypass. Native UDP entries also identify PID/endpoint. |
| TCP listeners, `NativeTcpDecoder.listenDb` | 16 | Independent of UDP listeners; unselected traffic follows ordinary packet bypass. Entries identify PID/endpoint. |
| ONC-RPC services, `OncRpcCallDecoder.serviceDb` | 8 | Unmatched or disabled service requests are dropped; there is no runtime registry spill. |
| Scheduler processes, `Scheduler.procDb` | 16 | Unmatched, disabled or full-queue requests enter explicit discard/cleanup. A full process queue is not a software queue. |
| Incoming reply contexts, `OncRpcReplyEncoder.contexts` | 16 | No live overwrite. Full capacity prevents handler admission and queues a best-effort `SYSTEM_ERR`; the bounded rejection FIFO may itself drop. Replies require a live slot/generation and matching owner; stale/invalid replies are dropped. |
| Outgoing nested requests, `OncRpcReplyDecoder.requestDb` | 16 | Admission precedes TX DMA/encoding. Exact live retransmissions retain their reservation; full/conflicting admission fails without replacing an active entry. Unmatched or invalid incoming replies are dropped. |
| IPv4 neighbors, `IpEncoder.neighborDb` | 8 | Absent/incomplete entries produce packet-bearing `neighborMiss`. An absent entry with no free slot still replaces index 0 and increments `neighTblFull`; safe explicit replacement is not implemented. |

The two 16-entry RPC resources are separate tables, not a common session pool.
Hardware retirement also differs from application consumption of a completion;
see [receive-side backpressure](../software/programming-model.md#loopback-composition-receive-side-backpressure).
The [reply-context topic](../software/reply-contexts.md) describes per-invocation
routing identity; generated descriptors and the cache-line interface define the
current host layout.

## Kernel bypass and neighbor misses

`BypassCmdSink` arbitrates typed requests into a FIFO for the bypass datapath.
`sw/core/eci/core.h` decodes them; `sw/kmod/bypass.c` polls them through NAPI.
`TY_BYPASS` injects a packet into Linux netdev RX. `TY_NEIGHBOR_MISS` reconstructs
IPv4 and submits retained payload to Linux routing/`ip_local_out`; software also
updates reachable hardware neighbors.

The IP encoder does not implement ARP. Its packet-bearing neighbor miss travels
through `DecoderSink` and RX DMA, preserving bytes for Linux output. Allocation,
routing and output failures can still drop. This existing kernel-owned fallback
does not implement application-owned service/process/context spills.

## Protocol limits

ONC-RPC uses fixed structures and parser extraction arrays: 48 bytes for normal
RPC extraction and 24 for nested-call TX inline metadata in `Global.scala`.
These are not arbitrary IDL deserialization or the ECI 64-byte inline-placement
rule. Argument bytes remain wire-native; numeric descriptor fields follow the
[host-boundary endianness contract](../software/nested-rpc.md#endianness-at-the-host-boundary).
Use generated descriptors and runtime serialization when changing formats.

Native UDP validates its supported datagram shape before publishing host work;
that does not make the entire IPv4/ONC-RPC parser a complete malformed-packet
validator. IPv4 options, hardware default-gateway handling and malformed ONC-RPC
handling remain incomplete. TCP connection state and timers run in the optional
userspace stack; hardware supplies raw packet steering and DEADLINE delivery.
HTTP/gRPC offload is not implemented. See [hardware status](implementation-status.md) for capability limits
and [validation](../development/validation.md) for test selection.
