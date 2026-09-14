# Nested RPC: implemented hardware and runtime integration plan

See the [registered endpoint and internal-loopback contract](nested-rpc-endpoints.md) for the B2/B6 batch.

**Branch status (2026-09-08):** `feat/nested-rpc` imports the student runtime
for software-build integration. See [staging scope and deferred work](nested-rpc-integration.md).
Descriptions below of the synchronous master runtime are historical on this branch.

Updated 2026-09-14 on `feat/nested-rpc`. Remote and internal local call/reply RTL, registered endpoints and cookie-directed completion dispatch are implemented. The imported runtime uses cooperative fibers over real ECI. Software builds and RTL simulations are distinct from the pending bundled Enzian acceptance. Generic TX status and abnormal-exit recovery remain open.

## Current source map

| Mechanism | Implemented entry points |
| --- | --- |
| Server calls and replies | `hw/src/lauberhorn/net/oncrpc/OncRpcCallDecoder.scala`, `OncRpcReplyEncoder.scala`: service match and reply sessions |
| Nested/client call TX | `OncRpcCallEncoder.scala`: ONC-RPC CALL header, inline/tail data, UDP metadata and `newRequestEvent` |
| Nested/client reply RX | `OncRpcReplyDecoder.scala`: tuple lookup, PID/cookie completion, entry retirement and counters/CSR access |
| Descriptor ABI | `hw/src/lauberhorn/host/package.scala`, `net/package.scala`, `host/eci/EciHostRxCtrlInfo.scala`, `EciHostTxCtrlInfo.scala` |
| DMA and scheduling | `DmaControlPlugin.scala` translates call/reply metadata; `Scheduler.scala` queues both call RX and reply RX using the corresponding PID |
| C transport | `sw/core/eci/core.h` packs `TY_ONCRPC_CALL_TX` and unpacks `TY_ONCRPC_REPLY_RX` |
| Runtime dispatch | `sw/rt/{rpc,worker,pending_call_table}.c`: call/reply dispatch, cookie generation checks and cooperative futures/callbacks |

All four directional types (`oncRpcCallRx`, `oncRpcReplyTx`, `oncRpcCallTx`, `oncRpcReplyRx`) exist. They are no longer suggested renames. `Global.scala` sets normal inline data to 48 bytes and nested-call TX inline data to 24 bytes because endpoint/PID/cookie metadata must also fit. Generated ABI, DMA tail length and ECI invalidation length must agree.

The hardware in-flight key is `(xid, remoteAddr, remotePort, localPort)` and its value includes `(pid, cookie, active)`. Never replace it with XID-only matching: different UDP/IP tuples may reuse XIDs. On a matching reply, completion metadata carries PID/cookie and the entry is retired. Inspect malformed-reply and miss handling before extending accepted ONC-RPC formats.

## Endianness at the host boundary

Contract updated 2026-09-13. Prefer host-order numeric metadata and perform
network conversion in hardware, where byte swaps are wiring. Keep scalar fields
naturally aligned to avoid split accesses even though Mackerel supports them.
Mackerel describes storage positions; the ECI pack/unpack RTL owns RPC byte-order
conversion. Packet encoders/decoders and their lookup tables remain wire-order.

| ECI boundary structure | Convention and alignment |
| --- | --- |
| Server call RX / reply TX | Host-order XID at byte 4, opaque function pointer at byte 8, raw inline payload at byte 16. ECI swaps XID on RX and TX. |
| Nested call TX | Host-order PID at byte 4, padding at 6, cookie at 8, XID at 12, IPv4 address at 16, source/destination ports at 20/22, program/version/procedure at 24/28/32, padding at 36, raw inline payload at 40. ECI swaps XID/address/ports/program/version/procedure. |
| Nested reply RX | Host-order PID at byte 4, padding at 6, cookie at 8 and XID at 12, raw inline payload at 16. ECI swaps XID. |
| Bypass RX/TX | Packed type/length and header type fit the first 32-bit word; raw packet header starts at byte 4. Header/payload bytes are copied unchanged. |
| Neighbor miss RX | Index shares the first word; IPv4 destination/source at bytes 4/8 are wire-native Linux `__be32` values, protocol at byte 12. Padding rounds metadata to 16 bytes. Kernel uses the addresses directly in IP headers, route lookup and ARP state. |
| Error RX, common descriptor fields | Valid/type/length are packed host bitfields contained in one 32-bit access. No network conversion. |
| Worker preemption control / IPI acknowledgment | Flags, PID and parity are host metadata; no network conversion. Control-line placement and READY/BUSY ordering are unchanged. |

The nested alignment repair moves existing padding after PID, retaining the
64-byte descriptor bound, inline capacities (24-byte call, 48-byte reply),
payload offsets, overflow addresses, ownership and barriers. Full-width 16/32/64
bit scalar fields are naturally aligned; intentionally packed flags/lengths do
not cross their scalar access boundary.

| Control registers exposed through ECI | Convention |
| --- | --- |
| Service-registration program/version/procedure and UDP listen port | Host-order numbers, swapped in hardware on table installation. PID/function pointer/protocol selector are host metadata. |
| RPC session and nested in-flight table update/readback | Host-order numeric XID, IPv4 address and ports; hardware swaps both directions. Opaque pointer/cookie, PID, index and active flag are unchanged. |
| IP address, neighbor address and MAC address controls/readback | Wire-native values matching Linux network-stack types and byte arrays. Preserving them avoids adding software swaps or repacking. |
| Scheduler, thread router, DMA, timeout, interrupt and statistics controls | Host-order numbers, addresses, flags and counters. No network conversion. |

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

The call encoder now waits for a Stream registration handshake before exposing a call. The reply decoder serializes registration against retirement and stalls when no free entry exists, preserving existing entries. This is hardware backpressure, not a software rejection/status API: `core_eci_tx` still returns `void` and ignores its doorbell read response. Directed saturation and system-wide progress under overload remain acceptance gaps.

Proposed contract retained from the original design:

1. Runtime reserves a software completion slot containing codec, endpoint, XID and continuation state.
2. Hardware reserves in-flight routing state before accepting a nested call into TX.
3. TX reports accepted, retry/full, invalid, unsupported or dropped. On rejection, release the software slot and any hardware reservation.
4. The application collects completions and retries or batches fanout when capacity is exhausted. Do not block the only context able to drain completions indefinitely.

A proposed generic status response uses the TX doorbell read control line, with validity, code, optional detail and cookie fields. Acceptance means routing state reserved and TX accepted, not necessarily wire transmission. Immediate neighbor/route failure needs explicit ownership and cleanup. Plumb status through encoder/DMA, `EciDecoupledRxTxProtocol`, `DcsTxAxiRouter` and C transport together. This is an ABI proposal, not a generated status format today.

## Runtime integration model

Use `../sven-dandelion/platform/sw/rt/` and its report as the located student reference; the old note's `sven-standalone-runtime` absolute path is not the reference audited here. See [Dandelion](../research/dandelion.md) for exact files and limitations.

The intended two scheduling layers are NIC/Linux placement of worker threads and userspace scheduling of handler fibers. A nested-capable handler submits a call, then yields when awaiting a reply. The worker executes other ready fibers or receives another host message. A reply completion is routed to the process and the runtime resolves its cookie/XID to a waiting continuation. No original-core route is required by the proposed PID-directed contract; migrating continuations still requires explicit TLS, stack and ownership rules.

Run-to-finish is the confirmed task policy. A nested await is a cooperative fiber suspension, not arbitrary timer preemption of handler computation. If every permitted worker is running non-yielding computation, completions queue. Worker CPUs remain isolated from ordinary Linux tasks. Receive-critical-section kill timing versus whole-application timeout, and safe recovery, are open.

API shape to adapt, not an API promised by this checkout: endpoint metadata `(daddr, dport, progNum, progVer, procNum, codec)`, async submission returning a completion handle, and await-any/await-all or callback consumption. Use a cookie for software continuation identity; XID encoding can accelerate lookup but must not replace hardware tuple routing. The student mock uses a fixed nested UDP port and XID routing; map this deliberately onto generated hardware tables.

Remaining software work:

- Adopt codecs for call marshaling/reply unmarshaling and a fiber/event loop while retaining synchronous handlers where appropriate.
- Decode generic TX status and distinguish acceptance, completion, cancellation and timeout lifetimes.
- Dispatch reply RX instead of asserting all received descriptors are calls.
- Configure the nested reply listen port and relevant generated devices in kernel/runtime initialization.
- Define exhaustion, stale completion, duplicate reply, XID/cookie reuse, process exit and fiber-stack failure behavior.
- Reconcile planned tail calls with report/code evidence; do not equate ordinary nested calls with tail-call continuation transfer.

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

`hw/test/src/lauberhorn/host/eci/OncRpcNestedSim.scala` currently registers `nested-rpc-call-tx-descriptor` and `nested-rpc-reply-rx`. The latter seeds routing state through CSRs. Their presence does not establish a complete runtime-driven nested round trip, saturation behavior or local dispatch.

Retain these acceptance tests from the original plan when implementing missing work:

- Inline-only and overflow calls: packet bytes, lengths and skipped/required DMA reads.
- Automatic in-flight installation and reply retirement; mismatched XID/address/ports; simultaneous reused XIDs across tuples.
- Table-full rejection before network emission; status response; release and successful retry.
- Mixed server calls and completions for one PID; one-worker handler that submits, yields, receives, resumes and replies.
- Completion cleanup under timeout, duplicate replies, process exit and resource exhaustion.
- Local call/reply with no external traffic after short-circuit is implemented.

First reconcile the student runtime contract with the current ABI and implement acceptance/lifetime handling; then integrate fibers, nested-port setup and end-to-end tests. Local short-circuit is separate. The old matrix-multiplication proposal (multiply fanout, await, add reduction) remains an optional focused demo; the confirmed application portfolio centers on Dandelion and the other services in [paper goals](../research/paper-goals.md).
