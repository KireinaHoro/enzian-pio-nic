# Nested RPC: implemented hardware and runtime integration plan

Source survey: 2026-09-08, `b86c68c`. Consolidates the former ONC-RPC nested-call plan. Remote call/reply RTL and descriptor transport exist; the current C runtime is still synchronous. The [Dandelion student project](../research/dandelion.md) demonstrates a richer runtime with a software kernel mock. Local short-circuit, generic TX acceptance status and a real-ECI fiber runtime remain proposals.

## Current source map

| Mechanism | Implemented entry points |
| --- | --- |
| Server calls and replies | `hw/src/lauberhorn/net/oncrpc/OncRpcCallDecoder.scala`, `OncRpcReplyEncoder.scala`: service match and reply sessions |
| Nested/client call TX | `OncRpcCallEncoder.scala`: ONC-RPC CALL header, inline/tail data, UDP metadata and `newRequestEvent` |
| Nested/client reply RX | `OncRpcReplyDecoder.scala`: tuple lookup, PID/cookie completion, entry retirement and counters/CSR access |
| Descriptor ABI | `hw/src/lauberhorn/host/package.scala`, `net/package.scala`, `host/eci/EciHostRxCtrlInfo.scala`, `EciHostTxCtrlInfo.scala` |
| DMA and scheduling | `DmaControlPlugin.scala` translates call/reply metadata; `Scheduler.scala` queues both call RX and reply RX using the corresponding PID |
| C transport | `sw/core/eci/core.h` packs `TY_ONCRPC_CALL_TX` and unpacks `TY_ONCRPC_REPLY_RX` |
| Missing runtime dispatch | `sw/rt/eci.c` expects `TY_ONCRPC_CALL_RX`; no nested-completion event loop |

All four directional types (`oncRpcCallRx`, `oncRpcReplyTx`, `oncRpcCallTx`, `oncRpcReplyRx`) exist. They are no longer suggested renames. `Global.scala` sets normal inline data to 48 bytes and nested-call TX inline data to 24 bytes because endpoint/PID/cookie metadata must also fit. Generated ABI, DMA tail length and ECI invalidation length must agree.

The hardware in-flight key is `(xid, remoteAddr, remotePort, localPort)` and its value includes `(pid, cookie, active)`. Never replace it with XID-only matching: different UDP/IP tuples may reuse XIDs. On a matching reply, completion metadata carries PID/cookie and the entry is retired. Inspect malformed-reply and miss handling before extending accepted ONC-RPC formats.

## Outstanding acceptance and resource contract

Current behavior is not safe backpressure: `OncRpcCallEncoder` emits a `Flow` registration event with no reservation acknowledgment. `OncRpcReplyDecoder` asserts its table update even when its free lookup misses, incrementing `reqTblFull` without preventing the update. This source-level concern needs directed saturation testing; do not promise that a full table rejects a call before emission. `core_eci_tx` returns `void` and ignores its doorbell read response.

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

## Local RPC proposal

Keep UDP/IP endpoint fields in software even when services are local. A local short-circuit should detect a local service and pass ONC-RPC/UDP metadata and payload directly to the receive path, preserving tuple matching, without encoding and then decoding Ethernet/IP envelopes. A corresponding local reply path should feed completion lookup. This avoids making CMAC loopback or switch reflection the normal local transport; such reflection remains a possible test setup. No local short-circuit is established by the current remote encoder/decoder implementation.

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
