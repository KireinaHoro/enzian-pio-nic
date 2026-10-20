# Nested RPC: implemented hardware and runtime integration plan

See the [registered endpoint and internal-loopback contract](nested-rpc-endpoints.md) for the B2/B6 batch.

**Branch status (2026-09-08):** `feat/nested-rpc` imports the student runtime
for software-build integration. See [staging scope and deferred work](nested-rpc-integration.md).
Descriptions below of the synchronous master runtime are historical on this branch.

Updated 2026-09-14 on `feat/nested-rpc`. Remote and internal local call/reply RTL, registered endpoints and cookie-directed completion dispatch are implemented. The imported runtime uses cooperative fibers over real ECI. Software builds and RTL simulations are distinct from the pending bundled Enzian acceptance. The September 17 B3 batch adds explicit TX admission status and request outcomes; abnormal-exit recovery and matched-board acceptance remain open. See the [current API](programming-model.md).

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
`oncRpcReplyRx`) exist. ECI transport version 3 reconstructs the decoder's
48-byte RPC prefix into the contiguous RX region. TX encoders consume the whole
argument/reply stream from DMA; descriptor-resident TX argument fields are no
longer used. Generated ABI, payload lengths and invalidation counts must agree.

The hardware in-flight key is `(xid, remoteAddr, remotePort, localPort)` and its value includes `(pid, cookie, active)`. Never replace it with XID-only matching: different UDP/IP tuples may reuse XIDs. On a matching reply, completion metadata carries PID/cookie and the entry is retired. Inspect malformed-reply and miss handling before extending accepted ONC-RPC formats.

## Endianness at the host boundary

Contract updated 2026-10-09 (contiguous ECI payloads). Prefer host-order numeric metadata and perform
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

Payloads occupy the control CL upper half at byte 64 when RX length or TX
reservation capacity is <=64, otherwise the region at direction-relative 0x100.
TX bit 23 records placement independently of actual length. Byte 56 is reserved;
normal overflow invalidation uses ceil(actual_length/128), or zero for inline
and descriptor-only sends. Short overflow sends do not relocate.
The 64-byte descriptor bound, numeric field offsets and worker address map stay
unchanged. Full-width 16/32/64-bit scalars remain naturally aligned.

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

This is an incompatible hardware/software ABI change, including RPC XID
semantics and nested field offsets. Deploy regenerated headers, kernel/runtime
and a new FPGA checkpoint together; checkpoint 2812450 predates this contract.
B1 validation/status is tracked in the [blocker queue](nested-rpc-blockers.md).

## Outstanding acceptance and resource contract

See [TX completion extension uses and boundaries](../proposals/tx-completion.md) for
TCP command results, software spill/retry, a future protobuf encoder, and the
distinction between local consumption and downstream completion.

The September 17 implementation reserves nested routing state in
`DmaControlPlugin` before DMA/encoding. `OncRpcReplyDecoder` serializes admission
against retirement and rejects a new call when no free slot exists. Exact
retransmissions preserve their reservation; a different owner/cookie cannot
replace it. The TX doorbell returns accepted/consumed (1), rejected before
reservation (2), or DMA error (3). `core_eci_tx` returns this result to the
runtime. Rejection releases the software operation; DMA uncertainty does not
prove retirement. Acknowledgements survive voluntary cache eviction/reload.

Incoming admission now uses [opaque reply contexts](reply-contexts.md), reserving
a slot before host dispatch. Full capacity generates a best-effort hardware
`SYSTEM_ERR`, never an overwrite; replies retire their contexts. A bounded
rejection queue drops excess error replies rather than blocking RX progress.
Matched-hardware overload validation and general shutdown/timeout retirement
remain open.

The broader original proposal remains useful for future extensions: validity,
code, optional detail/cookie and results for invalid, unsupported, route or
neighbor failures. The implemented three-code acknowledgement does not provide
all those results. Acceptance/source-buffer consumption is distinct from wire
transmission, downstream success and peer receipt. Never hold the only worker
able to make progress while synchronously waiting for a remote response.

## Runtime integration model

Use `../sven-dandelion/platform/sw/rt/` and its report as the located student reference; the old note's `sven-standalone-runtime` absolute path is not the reference audited here. See [Dandelion](../research/dandelion.md) for exact files and limitations.

The intended two scheduling layers are NIC/Linux placement of worker threads and userspace scheduling of handler fibers. A nested-capable handler submits a call, then yields when awaiting a reply. The worker executes other ready fibers or receives another host message. A reply completion is routed to the process and the runtime resolves its cookie/XID to a waiting continuation. No original-core route is required by the proposed PID-directed contract; migrating continuations still requires explicit TLS, stack and ownership rules.

Run-to-finish is the confirmed task policy. A nested await is a cooperative fiber suspension, not arbitrary timer preemption of handler computation. If every permitted worker is running non-yielding computation, completions queue. Worker CPUs remain isolated from ordinary Linux tasks. The [B4 design](scheduler.md#failure-and-reset-boundary) separates mandatory critical-section recovery from optional whole-application execution monitoring; matching-board acceptance remains open.

The current [public API](programming-model.md) uses registered endpoints,
futures and explicit `REPLY/FAIL/DEFER/FORWARD` outcomes. Incoming dispatch starts
inline; only an explicit handoff reserves a fiber. A cookie identifies the
software operation; XID is separately verified and does not replace hardware
tuple routing. Public arbitrary nested callbacks are removed. Forwarding relays
replies locally without retaining the parent fiber; direct downstream-to-client
reply routing is not implemented.

Remaining work includes matched-hardware admission/cancellation validation,
shutdown, abnormal-exit recovery and multi-process overload validation. Endpoint
timeouts/cancellation now use an acknowledged PID/cookie retirement control
command; failed retirement still retains reservations for process cleanup.
[Service-owned events/timers and resident applications](../proposals/service-execution.md)
are documented follow-ons only, intentionally not implemented in this batch.

## Internal local RPC

`RpcLocalRoute` intercepts encoded calls and replies before UDP/IP/Ethernet
construction when the destination matches the NIC IPv4 address. Separate
whole-packet call/reply buffers feed normal UDP listener classification with
synthesized metadata. Round-robin ingress selection retains payload and dispatch
acknowledgment association. `LocalDelivery` trace events on each RPC encoder
distinguish this path from CMAC traffic. Unsupported services follow normal
classification; there is no switch-reflection fallback.

The local round-trip simulation covers inline/overflow packets, immediate replies,
queued calls, mixed external/local ingress, reversed completions and receiver
backpressure, and asserts that nested traffic does not reach CMAC. Hardware and
multi-process saturation remain pending; see the [endpoint contract](nested-rpc-endpoints.md).

## Validation and next steps

`hw/test/src/lauberhorn/host/eci/OncRpcNestedSim.scala` registers
`nested-rpc-call-tx-descriptor`, `nested-rpc-reply-rx` and
`nested-rpc-local-roundtrip`. The remote RX case seeds CSRs; the local case
installs routing through the normal call encoder and reply-session decoder.
These simulations do not establish actual Linux/ECI runtime progress.

Retain these acceptance tests from the original plan when implementing missing work:

- Inline-only and overflow calls: packet bytes, lengths and skipped/required DMA reads.
- Automatic in-flight installation and reply retirement; mismatched XID/address/ports; simultaneous reused XIDs across tuples.
- Table-full rejection before network emission; status response; release and successful retry.
- Mixed server calls and completions for one PID; one-worker handler that submits, yields, receives, resumes and replies.
- Completion cleanup under timeout, duplicate replies, process exit and resource exhaustion.
- Local call/reply with no external traffic after short-circuit is implemented.

Next run the bundled matched-artifact hardware campaign, keeping overload,
shutdown and cancellation gaps explicit. Dandelion fixture builds and local RTL
round trips do not establish composition support on Enzian. The confirmed
application portfolio remains in [paper goals](../research/paper-goals.md).
