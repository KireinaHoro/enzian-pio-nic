# Incoming RPC reply contexts

September 17, 2026, `feat/nested-rpc`. This replaces the incoming session table
with bounded per-request routing state. It is separate from outgoing nested
completion routing. Matched-bitstream Enzian acceptance remains pending.

## Lifetime and admission

`RpcReplyContexts` holds 16 entries by default. Each entry retains the incoming
wire XID, client IPv4 address/UDP port and local UDP port. An inactive slot is
reserved at the call decoder's admission decision, before a registered host
descriptor can become visible. There is no independent allocation request/ack
round trip. The token is 32 bits: low `log2(NUM_SESSIONS)` slot bits and the
remaining generation bits, incremented on allocation. Generations wrap naturally;
this prototype does not authenticate tokens or defend against wrap/reset replay.

Every admitted invocation has its own entry, including repeated XIDs from the
same or different peers. This is not a duplicate-request cache and does not
promise exactly-once execution. Incoming and deferred handlers receive an opaque
`lauberhorn_request_context_t`, not the wire XID. The runtime retains the token
through deferral and forwarding. Nested-call XID/cookie generation and completion
matching are unchanged; the peer receives its original XID on the wire.

A valid reply token selects the return metadata. The reply encoder captures it
into output registers and clears the active bit before transport transmission.
Output backpressure cannot change that captured destination. Allocation does not
reuse a slot being retired in the same cycle. Stale or duplicate reply tokens
are dropped and counted; any overflow payload is drained. No ownership or
authentication check is added. Requests abandoned without a terminal reply still
need B4 shutdown/timeout recovery; they are not automatically expired here.

If full, the decoder does not dispatch the request, allocate packet storage for
its handler, or overwrite an entry. It drains the body and queues a header-only
ONC-RPC accepted `SYSTEM_ERR`, using the rejected packet's XID and UDP tuple.
Normal replies and rejections alternate when both are ready. The rejection FIFO
is bounded to `NUM_SESSIONS`: when full, the error reply is dropped and counted,
while RX keeps draining. Blocking RX behind this queue could prevent local
completion traffic from reaching the worker and releasing resources. UDP error
delivery remains best effort, including ordinary network loss/neighbor failure.
`sessTblFull` counts incoming rejections; `rejectionDropped` counts errors lost
at this FIFO; `dropped` counts invalid reply tokens. The former arbitrary
session-write CSRs are removed; privileged diagnostic readback remains.

## Unchanged control-cache-line footprint

Offsets are relative to the active 128-byte ECI control cache line. The
parity/doorbell protocol, overflow CL addresses, and nested descriptors are
unchanged. The first 64 bytes contain the descriptor and inline data:

| Bytes | Incoming call RX | Outgoing reply TX |
| --- | --- | --- |
| 0–3 | Existing valid/type/length/padding | Existing type/length/status |
| 4–7 | 32-bit opaque request token | 32-bit opaque request token |
| 8–15 | Handler/codec pointer | Reserved |
| 16–63 | 48-byte inline arguments | 48-byte inline response |
| 64–127 | First existing overflow segment | First existing overflow segment |

The token occupies the former XID word and needs no endian conversion; the
original XID stays wire-native in the table. The reply no longer carries a
handler pointer. RX still needs that pointer for software handler/codec dispatch.
Generated Mackerel accessors, runtime, kernel and bitstream must match: preserving
byte offsets does not make this semantic ABI compatible with old artifacts.

## Deferred client-information APIs and TCP extension

No application metadata-query API is implemented in this change. A future API
could accept the live request-context token and return peer IP address/port,
local endpoint, original XID, transport kind, or a connection identifier. It
must check that the request is still live and define the query/completion race;
a pointer into worker-local RX storage is insufficient across fiber suspension.
The existing privileged debug readback is not such an application API.

If TCP is integrated, extend context metadata with a transport tag selecting
UDP routing fields or a TCP connection-table reference (and any framing state
needed for that response). TCP connection lifetime and retransmission storage
are independent of per-request reply-context retirement. Handing a response to
the connection transmit machinery releases the request entry when the transport
owns all required metadata/bytes; it does not close the connection or wait for
peer acknowledgement. No TCP variant or expanded field layout is implemented.

A transport peer is not a persistent logical client. Applications needing
identity across reconnects or UDP port changes can encode their own client ID.
Sven's report describes XID bits selecting pending-call slots and mock processes;
those are caller-side completion mechanisms, not server application identity.
The benchmark client's XID tags likewise work as long as replies echo the XID.
