# Application programming model

Managed requests use explicit outcomes, borrowed transport storage and owned
asynchronous state. Deploy matching generated hardware, device headers, kernel
and runtime. The installed public headers define API declarations; the behavior
and supported lifecycle below describe the ECI implementation.

## Registration and execution

Initialize a context, register services and nested endpoints before creating
workers. Call `lauberhorn_set_worker_count(ctx, n)` before the first worker
creation. Each worker completes setup, registers and enters bootstrap yield;
capacity is published only after the whole declared pool is ready. The pool
is immutable. Individual withdrawal and normal resource-recycling teardown are
unsupported in this stage; exits fence the domain until coherence reset. Registered
endpoints must remain at a stable address and must not be copied. Codec and
service-private state must outlive their users. A service supplies `{data, func}`;
there is no service-level inline/fiber declaration. See
[`lauberhorn.h`](../../sw/include/lauberhorn.h) and the
[microbenchmark](../../../microbenchmarks/server/lauberhorn/main.c).

Every incoming handler starts on the worker's default stack. It borrows decoded
request storage and returns one of these outcomes:

| Outcome | Meaning and ownership |
| --- | --- |
| `lauberhorn_reply(data, len, destroy)` | Serialize the response, then call optional `destroy(data)`. |
| `lauberhorn_fail(error)` | Send an ONC-RPC accepted error with no application payload. |
| `lauberhorn_defer(run, context, destroy)` | Reserve a fiber and transfer the decoded request and context to `run(context, request, request_context)`. |
| `lauberhorn_forward(endpoint, data, len, destroy)` | Encode a child call, retain parent reply metadata and relay the child's response without keeping a parent fiber. |

`len` describes the input object passed to the codec; serializers also receive
an independent writable output capacity. Responses and forward arguments must
remain valid until serialization after handler return: stack-local objects are
invalid. Borrowing the decoded request as response/forward input is permitted;
it remains alive until serialization finishes. Do not also destroy that borrowed
request through an outcome destructor. Owned outcome data is destroyed exactly
once, including failed admission. Application state shared across workers needs
normal synchronization.

Deferral is a normal function return followed by starting another function on a
fiber. It neither migrates a live stack nor reinjects a packet. Runtime-owned
request storage survives the handoff; retained pointers into the worker's raw RX
buffer do not. The deferred function returns `REPLY`, `FAIL` or `FORWARD`.
A second `DEFER` is rejected as a system error: the function already has the
fiber needed for nested waits. Its context destructor runs after the returned
outcome is consumed, including response serialization, and before the decoded
request is freed. It may not transfer the
same owned context again through a second destructor.

The shared pool has 320 fibers with 64 KiB stacks. Only accepted deferrals consume
incoming-handler fibers. When unavailable, the runtime destroys the context and
request and replies with `SYSTEM_ERR`; there is no additional deferral queue.
Atomic inline calls continue to run. Application errors use normal encoded
responses where appropriate; `FAIL` supports `PROC_UNAVAIL`, `GARBAGE_ARGS` and
`SYSTEM_ERR`, rather than a custom application error wire format.

Forwarding initially requires identical response-encoder function and codec
private pointer. The application must ensure this implies compatible response
wire encodings. Successful child bytes are relayed locally with the original
parent reply context (hardware retains the wire XID); supported accepted errors propagate, and other failures
become `SYSTEM_ERR`. No application callback or result
transformation occurs. This is a supported tail-call ownership pattern, not
direct downstream-to-original-client routing. That tail-call protocol is not
fully designed; see [nested RPC](nested-rpc.md#runtime-integration).

## Opaque request context

Entry and deferred handlers receive `lauberhorn_request_context_t` as their third
argument. This 32-bit opaque value identifies the live incoming request; it is
not the wire XID, a client identity, or an application-managed reply handle.
The runtime preserves it across `DEFER` and `FORWARD`, and uses it for the terminal
reply. Hardware stores the original wire XID and UDP routing tuple and releases
the context after capturing a valid reply's routing metadata. Applications must
not interpret its bits or retain it beyond the managed request's lifetime.

Possible future query APIs could accept this token and return the client's IP
address/port, local endpoint, original XID, or transport/connection information.
No such application query API is implemented. A UDP peer address or a TCP
connection reference would identify a transport peer, not a persistent logical
client; applications needing persistent client identity can carry it in their
own request protocol. See [reply-context layout and lifecycle](reply-contexts.md) and the
[metadata-query proposal](../proposals/request-context-metadata.md).

## Nested calls and waiting

`lauberhorn_call_async` submits a request and binds a future to the current
managed fiber. Default-stack use returns `-1/EPERM` before encoding, transport
allocation or endpoint accounting. `await`, `select` and `join` also reject
unsupported contexts in debug and release builds, and require futures owned by
the calling fiber. Await/join return 0 on consumption, or -1 with errno;
select returns the selected index or -1. A consumed future cannot be waited
again. Every successfully submitted future must be consumed before its owner
exits. Cancellation leaves a result to consume; ordinary handler return does not clean up abandoned futures.

Submission returning zero means local transport admission, not remote execution
or application success. A full software table or a definite hardware capacity
rejection returns `EAGAIN`. The software table has 640 backing slots with generation-tagged
cookies, but admits at most `LAUBERHORN_NUM_SESSIONS` (16) live calls per process.
Ready futures retain admission credit until consumed: hardware retirement alone
does not mean that software drained the reply. Hardware capacity is independent and shared (16 entries per table by default).
Incoming request contexts are independently bounded: a full table rejects before
host dispatch and attempts a hardware `SYSTEM_ERR` reply. Its bounded error queue
may drop error replies under congestion; it never overwrites live contexts. A hardware rejection
releases the unsubmitted operation. Timeout and explicit future cancellation now
request acknowledged hardware retirement through a slow-path control ioctl;
successful retirement allows reservation release when the result is consumed.
`lauberhorn_future_cancel` requires the submitting fiber and leaves a CANCELLED
result to consume with await/select/join; it does not cancel remote execution.
Failed retirement or an indeterminate DMA error retains unresolved ownership. Indeterminate
TX failure settles the admitted operation with an RPC error unless a reply has
already won; it is not reported as a safe-to-retry admission failure. Inspect the
future's RPC status before accessing its result and free successful results with
`lauberhorn_free_result`.

The [local TX result](../architecture/hw-sw-interface.md#tx-completion-status)
reports admission and source-buffer consumption, not peer delivery. Error status
is broader than DMA failure and does not provide a general safe-retry guarantee.

Public arbitrary nested callbacks have been removed. Futures do not depend on
that API. Service-owned async work is
specified in the [follow-on design](../proposals/service-execution.md); these service-event interfaces are not implemented. This does not remove application-internal
callbacks or registration/init/cleanup function pointers.

## Scheduling and remaining limits

RPC workers run on isolated cores, never alongside ordinary tasks. Deferred
fibers can resume on another worker; do not retain worker-local state across a
wait or rely on CPU affinity. Incoming inline handlers must not issue nested
future calls. Long computation and blocking I/O still occupy their worker.

Workers sweep timeouts and drain ready fibers before RX polling. The NIC's
finite NACK timeout lets an assigned worker regain control; it does not activate
a parked domain for an expired call. Parked-domain deadline activation and public
application timers are unsupported.

`lauberhorn_set_watchdog()` is declared but the current kernel rejects its ioctl
with `EOPNOTSUPP`, including a zero limit. Do not rely on execution-watchdog
activation from this API. `lauberhorn_stop()`, unsupported exit and fatal
activation failures terminate/fence the domain while retaining unresolved
resources until coherence reset; they do not provide reusable teardown.
See [failure and reset behavior](scheduler.md#failure-and-reset-boundary).

### Loopback composition receive-side backpressure

The runtime preserves ONC RPC `SYSTEM_ERR` (wire status 5) as the distinct
future result `LAUBERHORN_RPC_SYSTEM_ERROR` (4), with no result data. Other
nonzero wire statuses and decode failures remain `LAUBERHORN_RPC_ERROR`.
This adds an enum value without changing the future layout; rebuild consumers
such as Dandelion with the matching runtime.

Dandelion's local-loopback composition interprets this result as receive-side
busy, equivalent to `EAGAIN`: it retains and requeues only the rejected shard.
This is an application-specific assumption approved for this workload, not an
automatic retry policy for remote RPCs or arbitrary handlers. `SYSTEM_ERR` can
also represent a handler failure. Timeouts and other failures are not retried;
the composition drains accepted children and returns failure. Retry attempts
receive distinct trace IDs and emit `LH_TRACE_CHILD_RETRY` (111).

The process-wide 16-call admission bound and this loopback busy-retry policy are
**temporary workarounds for the UDP prototype and its small hardware tables**,
not intended architectural limits. UDP does not recover a completion discarded
by a full process RX queue; therefore the current bound includes ready but
unconsumed futures. It is not a general reliability guarantee for arbitrary
concurrent incoming traffic, packet loss, or deeply nested compositions.

See the existing [lookup-table scaling and spill design](../proposals/lookup-spill.md):
these limits should eventually be lifted through scalable table backing, explicit
ownership on spill, and reliable end-to-end admission/backpressure. Hardware
capacity should affect fast-path hit rate and latency rather than impose permanent
application or graph-size limits. Merely raising the constant without addressing
completion delivery would reintroduce the failure.

## Native UDP buffers

The runtime owns the UDP view. Use
`const lauberhorn_udp_datagram_t *packet = NULL` and pass `&packet` to receive;
do not copy the view into an application struct. Its metadata is read-only, but
`packet->data` points to mutable packet bytes. After all uses finish, call
`lauberhorn_udp_release()` and clear `packet`. Both pointers expire at release,
even though the runtime may reuse the view object's address for another packet.
Application readiness/delivery flags do not own or release transport storage.

Initialize workers before `lauberhorn_udp_attach`. At the worker's idle boundary,
after runnable work and pending responses have drained, call
`lauberhorn_udp_receive_view`. It waits for network RX and handles DESCHED through
the existing yield contract; [HOST_WORK](../proposals/host-work.md) (formerly
HOST_WAKE) is not implemented. A return of 1 lends
one contiguous mutable datagram, including a zero-length datagram; 0 is NACK,
and -1 reports an error through `errno`.

Finish every use of `packet->data` before `lauberhorn_udp_release`. Release ends
its lifetime; copying the pointer or queueing it does not transfer ownership.
Another receive or assignment-releasing yield while borrowed fails with EBUSY.
The application may retain its own readiness flag while it holds the packet.

For each response, select storage with `lauberhorn_tx_get_buffer(inline_payload, &buffer)`.
Pass true only for sends of at most 64 bytes; false provides the full transport
capacity, even when the eventual send is short. Unknown sizes should use false.
Write no more than the returned `buffer.capacity` bytes into
`buffer.data`, and call `lauberhorn_udp_send_buffer` with the actual length.
Pass `packet->trace_ref` as the final `related_rx` argument on each associated
response, or zero for unrelated sends. It is trace-only metadata; retaining that
scalar does not retain the RX borrow. Multiple responses and zero-length sends
are supported. A submitted send
consumes the reservation, including on rejection; EAGAIN requires a fresh
reservation and regenerated bytes. EIO is indeterminate and must not be retried
automatically. Validation errors leave the reservation live for correction or
`lauberhorn_tx_cancel`. Never retain its pointer after submission or cancel, or
yield while reserved. Success establishes local admission, not remote delivery.
