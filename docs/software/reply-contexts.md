# Incoming RPC reply contexts

Incoming calls use bounded per-request routing state, separate from
[outgoing nested completion routing](nested-rpc.md).

## Lifetime and admission

`RpcReplyContexts` holds 16 entries by default. Each entry retains the incoming
wire XID, client IPv4 address/UDP port and local UDP port. An inactive slot is
reserved at the call decoder's admission decision, before a registered host
descriptor can become visible. There is no independent allocation request/ack
round trip. The token is 32 bits: low `log2(NUM_SESSIONS)` slot bits and the
remaining generation bits, incremented on allocation. Generations wrap naturally;
generation tags alone do not defend against wrap/reset replay. Reply validation
also checks the trusted process owner.

Every admitted invocation has its own entry, including repeated XIDs from the
same or different peers. This is not a duplicate-request cache and does not
promise exactly-once execution. Incoming and deferred handlers receive an opaque
`lauberhorn_request_context_t`, not the wire XID. The runtime retains the token
through deferral and forwarding. Nested-call XID/cookie generation and completion
matching are unchanged; the peer receives its original XID on the wire.

A live token with a matching generation and process owner selects the return metadata. The reply encoder captures it
into output registers and clears the active bit before transport transmission.
Output backpressure cannot change that captured destination. Allocation does not
reuse a slot being retired in the same cycle. Stale or duplicate reply tokens
are dropped and counted; any overflow payload is drained. Requests abandoned without a terminal reply are not automatically expired here.
The table supports owner-wide reclamation, but this does not establish complete
process recovery: the current [scheduler](scheduler.md#failure-and-reset-boundary)
fences failed domains until coherence reset.

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

## Host interface

The opaque token occupies byte 4 of the call RX and reply TX descriptors. Call
RX carries the handler/codec pointer at byte 8; reply TX reserves that field.
The token needs no endian conversion; the original XID stays wire-native in the
table. Payload placement is defined by the
[hardware/software cache-line interface](../architecture/hw-sw-interface.md).
Generated descriptors, runtime, kernel and bitstream must match.

Application queries for peer metadata and TCP context variants are
[open proposals](../proposals/request-context-metadata.md). A transport peer is
not a persistent logical client; applications needing identity across reconnects
or UDP port changes can encode their own client ID.
