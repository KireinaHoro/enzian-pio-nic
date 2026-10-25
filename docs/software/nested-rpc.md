# Nested RPC transport and runtime integration

Registered endpoints support remote and internal local ONC-RPC calls. The runtime
uses cooperative fibers, cookie-directed completions and explicit request outcomes.
See the [application API](programming-model.md) for ownership and waiting rules.

## Current source map

| Mechanism | Implemented entry points |
| --- | --- |
| Server calls and replies | `hw/src/lauberhorn/net/oncrpc/OncRpcCallDecoder.scala`, `OncRpcReplyEncoder.scala`: service match and bounded per-request reply contexts |
| Nested/client call TX | `OncRpcCallEncoder.scala`: ONC-RPC CALL header, contiguous DMA payload, UDP metadata (admission now precedes encoding in `DmaControlPlugin`) |
| Nested/client reply RX | `OncRpcReplyDecoder.scala`: tuple lookup, PID/cookie completion, entry retirement and counters/CSR access |
| Descriptor ABI | `hw/src/lauberhorn/host/package.scala`, `net/package.scala`, `host/eci/EciHostRxCtrlInfo.scala`, `EciHostTxCtrlInfo.scala` |
| DMA and scheduling | `DmaControlPlugin.scala` translates call/reply metadata; `Scheduler.scala` queues both call RX and reply RX using the corresponding PID |
| C transport | `sw/core/eci/core.h` packs `TY_ONCRPC_CALL_TX` and unpacks `TY_ONCRPC_REPLY_RX` |
| Runtime dispatch | `sw/rt/{rpc,worker,pending_call_table}.c`: call/reply dispatch, cookie generation checks and cooperative futures and fiber-free reply forwarding |

All four directional types (`oncRpcCallRx`, `oncRpcReplyTx`, `oncRpcCallTx`,
`oncRpcReplyRx`) exist. ECI transport version 4 reconstructs the decoder's
48-byte RPC prefix into the contiguous RX region. TX encoders consume the whole
argument/reply stream from DMA; descriptor-resident TX argument fields are no
longer used. Generated ABI, payload lengths and invalidation counts must agree.

The hardware in-flight key is `(xid, remoteAddr, remotePort, localPort)` and its value includes `(pid, cookie, active)`. Never replace it with XID-only matching: different UDP/IP tuples may reuse XIDs. On a matching reply, completion metadata carries PID/cookie and the entry is retired. Inspect malformed-reply and miss handling before extending accepted ONC-RPC formats.

## Endianness at the host boundary

Use host-order numeric metadata and perform
network conversion in hardware, where byte swaps are wiring. Keep scalar fields
naturally aligned to avoid split accesses even though Mackerel supports them.
Mackerel describes storage positions; the ECI pack/unpack RTL owns RPC byte-order
conversion. Packet encoders/decoders and their lookup tables remain wire-order.

| ECI boundary structure | Convention and alignment |
| --- | --- |
| Server call RX / reply TX | Opaque request token at byte 4; RX handler pointer / TX reserved field at byte 8; payload is separate from descriptor metadata. Original XID stays wire-native in the hardware reply context. |
| Nested call TX | Host-order PID at byte 4, padding at 6, cookie at 8, XID at 12, IPv4 address at 16, source/destination ports at 20/22, program/version/procedure at 24/28/32, padding at 36; bytes 56–63 reserved. ECI swaps XID/address/ports/program/version/procedure. |
| Nested reply RX | Host-order PID at byte 4, padding at 6, cookie at 8 and XID at 12, payload is separate from metadata. ECI swaps XID. |
| Bypass RX/TX | Packed type/length and header type fit the first 32-bit word; TX header-generation command starts at byte 4, and TX length excludes that generated header. RX includes the complete header in its contiguous payload. |
| Neighbor miss RX | Index shares the first word; IPv4 destination/source at bytes 4/8 are wire-native Linux `__be32` values, protocol at byte 12. Padding rounds metadata to 16 bytes. Kernel uses the addresses directly in IP headers, route lookup and ARP state. |
| Error RX, common descriptor fields | Valid/type/length are packed host bitfields contained in one 32-bit access. No network conversion. |
| Worker activation snapshot | Domain lifetime, scheduler slot and RX/TX parity are host metadata; no network conversion. Cooperative yield returns the committed assignment without a control cache line. |

Payload placement, invalidation counts and reservation/commit rules are defined
in the [hardware/software cache-line interface](../architecture/hw-sw-interface.md).

| Control registers exposed through ECI | Convention |
| --- | --- |
| Service-registration program/version/procedure and UDP listen port | Host-order numbers, swapped in hardware on table installation. PID/function pointer/protocol selector are host metadata. |
| Reply context readback / nested in-flight table control | Host-order numeric XID, IPv4 address and ports; hardware swaps at the CSR boundary. Incoming contexts have debug readback only, not arbitrary indexed writes. Nested in-flight table control is unchanged. |
| IP address, neighbor address and MAC address controls/readback | Wire-native values matching Linux network-stack types and byte arrays. Preserving them avoids adding software swaps or repacking. |
| Scheduler, ownership, DMA, timeout, interrupt and statistics controls | Host-order numbers, addresses, flags and counters. No network conversion. |

Endpoint initialization parses IPv4 with `inet_pton` and caches its host-order
value using `ntohl`. `rpc_send_call` supplies cached PID/address/source port,
numeric endpoint fields and cookie/XID directly. Futures and pending-call lookup
remain host-order. XDR payload bytes remain the codec's concern; ECI does not
interpret or swap application payloads.

Deploy matching generated headers, kernel/runtime and FPGA artifacts. Descriptor
field offsets and numeric byte order are part of that shared ABI.

## Registered endpoints

Call `lauberhorn_rpc_endpoint_init(ctx, ep)` before creating workers and
`lauberhorn_rpc_endpoint_destroy(ctx, ep)` after joining them. Both return zero
or minus one with `errno`. Existing configuration fields and outgoing-call
signatures are retained; appended private transport state must start at zero.
Duplicate initialization returns `EALREADY`; destruction of an uninitialized
endpoint succeeds. Registered endpoints must not be copied or mutated. Keep the
endpoint, context, address and codec storage alive until destruction succeeds.
Control operations and worker creation/join require external serialization.
Retain codec storage until all returned results using that codec are freed,
even when the associated call has completed.

Initialization validates/caches the IPv4 address and uses a fixed-width padded
control ioctl to reserve a Linux UDP socket without reuse and an FPGA reply
listener. The kernel derives the hardware PID from the open device context,
rejecting identities that do not fit the generated PID width. A hardware PID is
a process identity, not a NIC datapath or a Linux CPU ID. Handles belong to the
open file context; another process cannot use its control operations.

The current NIC is restricted to the initial network namespace. Registration
from another namespace returns `EXDEV`. Endpoint source ports are selected from
49152–65535, skipping Linux bindings, registered FPGA services and endpoint
reservations. Service and endpoint table writes are serialized, capacity is
checked before installation, and partial registration failures are unwound.

## Completion and lifetime

The cookie is a protocol-independent pending-slot/generation token. ONC-RPC XID
initially has the same numeric value, but is separately checked after locating
the slot by cookie. A slot is retired before its 16-bit generation wraps;
exhaustion returns `EAGAIN`. Numeric descriptor fields remain host-order; ECI converts
wire fields as specified in the [boundary contract](#endianness-at-the-host-boundary).
Sending uses cached PID, source port and address, without control ioctls.

Destruction returns `EBUSY` while workers or unresolved calls remain. A software
timeout alone does not prove hardware retirement: timeout/cancellation uses an
acknowledged PID/cookie retirement command before releasing its reservation.
Failed retirement retains unresolved ownership. Device close fences the domain;
unresolved resources and endpoint identities remain retained until coherence
reset. It is not a successful drain/reclaim operation. See the
[scheduler failure boundary](scheduler.md#failure-and-reset-boundary).

## Admission and TX completion

`DmaControlPlugin` reserves nested routing state before DMA/encoding.
`OncRpcReplyDecoder` serializes admission against retirement and rejects a new
call when no free slot exists. Exact retransmissions preserve their reservation;
a different owner/cookie cannot replace it. The default outgoing table has
16 entries. The TX doorbell returns accepted/consumed (1), rejected before DMA
(2), or general error (3), including invalid descriptors and DMA failures.
See [TX completion status](../architecture/hw-sw-interface.md#tx-completion-status)
for buffer ownership and retry boundaries. DMA uncertainty does not prove
retirement. Acknowledgements survive voluntary cache eviction/reload.

Incoming admission uses [opaque reply contexts](reply-contexts.md), reserving a
slot before host dispatch. Full capacity generates a best-effort hardware
`SYSTEM_ERR`; a bounded rejection queue drops excess error replies rather than
blocking RX progress. Source-buffer consumption is distinct from wire
transmission, downstream success and peer receipt. Proposed richer operation
results belong to [TX completion extensions](../proposals/tx-completion.md).

## Runtime integration

NIC/Linux placement assigns worker threads; userspace schedules handler fibers.
A nested-capable handler submits a call and cooperatively suspends while awaiting
its reply. The worker runs other ready fibers or receives another message.
Completion metadata routes to the process, whose runtime resolves cookie/XID to
the waiting operation. If every permitted worker is running non-yielding
computation, completions queue. Worker CPUs remain isolated from ordinary tasks.

The [public API](programming-model.md) defines `REPLY/FAIL/DEFER/FORWARD`.
Incoming dispatch starts inline; only explicit deferral reserves a fiber.
Forwarding retains parent reply metadata in a child operation and relays the
child's reply locally without retaining the parent fiber. Tail calls that reply
directly from the downstream service to the original client are not implemented
and are not fully designed.

Parked-domain deadlines use the [scheduler allocation rules](scheduler.md#allocation-and-reclaim-rules).
Execution-watchdog integration remains deferred. Per-call retirement does not imply whole-domain recovery.
[Service-owned events and timers](../proposals/service-execution.md) are proposals.

## Internal local RPC

`RpcLocalRoute` intercepts encoded calls and replies before UDP/IP/Ethernet
construction when the destination matches the NIC IPv4 address. Separate bounded
whole-packet call/reply buffers feed normal UDP listener classification with
synthesized metadata. Space is reserved before acceptance, and in-flight
installation finishes before the request becomes visible. Round-robin ingress
selection retains metadata, payload and dispatch acknowledgment association.
`LocalDelivery` trace events on each RPC encoder distinguish this path from CMAC
traffic. Unsupported services follow normal classification; there is no
switch-reflection fallback.

[Dandelion](../applications/dandelion.md) uses this path for compositions. Its
application integration and [measurements](../applications/dandelion.md#composition-measurements)
are separate from the generic transport contract.

## Validation

`hw/test/src/lauberhorn/host/eci/OncRpcNestedSim.scala` contains descriptor TX,
reply RX and local-roundtrip cases. Coverage includes inline/overflow payloads,
asymmetric fields, immediate/reversed completions, mixed external/local ingress,
receiver backpressure and absence of nested CMAC output. Native runtime tests
exercise endpoint ownership/rollback, stale cookies, XID checks, outcomes and
bounded resource exhaustion. Kernel fault-injection shims do not establish
real socket or board behavior.

Use the [validation guide](../development/validation.md) for builds and board
orchestration. [Historical nested-RPC evidence](../../agents/runtime-applications.md#historical-bundled-nested-rpc-validation)
records the tested revisions and finite coverage; old shutdown/reuse results do
not establish recovery for the current cooperative ECI implementation.
