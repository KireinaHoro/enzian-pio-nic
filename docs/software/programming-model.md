# Application programming model

Branch contract: `feat/nested-rpc`, September 17, 2026. Managed requests use
explicit outcomes; the API is intentionally incompatible with the earlier
incoming execution-mode and nested-callback declarations. Real ECI deployment
requires matching regenerated hardware, device headers, kernel and runtime.
See [blockers and validation status](nested-rpc-blockers.md).

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
[microbenchmark](../../sw/apps/microbenchmarks/main.c).

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
direct downstream-to-original-client routing.

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
own request protocol. See [reply-context layout and lifecycle](reply-contexts.md).

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
rejection returns `EAGAIN`. The software table has 640 slots with generation-tagged
cookies. Hardware capacity is independent and shared (16 entries per table by default).
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

The ECI TX doorbell now returns 1 for accepted/consumed, 2 for rejection before
reservation, and 3 for DMA error. Accepted indicates the per-worker TX buffer
may be reused. These values require the new matching bitstream; old bitstreams
returning arbitrary saved descriptor bytes are incompatible. The runtime does
not promise bounded latency under every downstream network/coherence stall.

Public arbitrary nested callbacks have been removed. Futures do not depend on
that API. Service-owned async work and transport-only resident applications are
specified in the [follow-on design](service-execution-design.md); neither
interface is implemented in this run. This does not remove application-internal
callbacks or registration/init/cleanup function pointers.

## Scheduling and remaining limits

RPC workers run on isolated cores, never alongside ordinary tasks. Deferred
fibers can resume on another worker; do not retain worker-local state across a
wait or rely on CPU affinity. Incoming inline handlers must not issue nested
future calls. Long computation and blocking I/O still occupy their worker.

Timeout sweeps precede ready-fiber draining before RX polling. An internal
housekeeping thread asks hardware to activate a service for expired calls,
without requiring network traffic. Public application timers remain deferred.

`lauberhorn_set_watchdog(ctx, limit_us)` must precede worker creation. Zero (the
default) disables execution monitoring and watchdog-only TX. When enabled, actual
fiber resumption starts a fresh approximate execution interval; an already-ready
await does not refresh it. Limits beyond half the hardware's 32-bit cycle range
return `ERANGE`. This is a debugging watchdog, not CPU billing.

`lauberhorn_stop(ctx)` forcibly terminates the application's workers after
hardware drain; callers should expect process termination. SIGINT/SIGTERM and
fatal process exits also initiate cleanup, including parked workers. See the
[recovery contract](process-recovery.md). Matching-bitstream recovery and module
startup reliability remain explicit [acceptance gates](nested-rpc-blockers.md).
