# Software runtime and datapath

**October 8 status:** the payload-isolation and multi-PA faults have source
corrections and a matched functional board baseline. The cooperative PREEMPT
path, corrected 516232 module, GSYNC early NACK and buffered hot/cold benchmarks
have recorded finite validation. Expanded adversarial isolation and concurrent
four-worker acceptance remain open. See the [consolidated status and open
claims](../research/roadmap.md#current-status--october-8-consolidation).
The dated implementation notes below preserve their original scope.

October 2: the sole ECI runtime declares its pool with
`lauberhorn_set_worker_count()` before creating threads. Each thread completes
its initialization callback and fiber setup, registers explicitly and enters
bootstrap `lh_yield()`. PREEMPT causes the same yield before further RX/TX.
Activation replaces the selected mapped alias and both local parity bits.
Mapping never registers or parks threads. The shared parity page and transport
READY/BUSY helpers are removed. See the [control contract](scheduling-control-interface.md)
and [evidence ledger](../hardware/eci-implementation.md); board acceptance is pending.

October 3 finite board tests observe 5,926 ordinary activations with matching
first-RX and first-TX parities, repeated A→B→A reuse, correct RPC replies and
zero scheduling IRQ changes. Separate same-thread observations establish
cross-worker movement. The evidence ledger distinguishes these results from
the unfinished maximum-timeout search and the unresolved baseline lockup.

The runtime dispatches
managed request outcomes over ECI, with cooperative fibers for explicitly
deferred work. See the [programming contract](programming-model.md),
[blocker ledger](nested-rpc-blockers.md) and
[endpoint contract](nested-rpc-endpoints.md). Implementation is not evidence of
successful deployment on the real coherent FPGA datapath.

## Source map

| Layer | Entry points | Responsibility |
| --- | --- | --- |
| Public API | `sw/include/lauberhorn.h`, `lauberhorn/{rpc,future}.h` | Managed outcomes, endpoint registration, bounded codecs, futures |
| Dispatch | `sw/rt/{rpc,future,worker}.c` | Inline dispatch, deferral ownership, forwarding, completion and timeout processing |
| Pending operations | `sw/rt/pending_call_table.*`, `fiber/` | Generation-tagged operations, cooperative fiber scheduling |
| ECI transport | `sw/core/eci/core.h` | Descriptor/payload transfer, parity/barriers and TX acknowledgement |
| Kernel control | `sw/kmod/chrdev.c`, `ioctl.h`, `endpoints.h` | Service/endpoint registration, protected resources and mappings |
| Hardware | `DmaControlPlugin`, `OncRpcReplyDecoder`, ECI routers | Admission, descriptor translation, completion retirement, host protocol |

Applications use the installed runtime Nix package. PCIe remains unfinished;
backend-neutral naming does not establish support there. Device headers and
configuration are regenerated from the matching hardware, not edited by hand.

## Request and transport ownership

RX copies into reusable worker storage. Decoding allocates an independently
owned request; inline handlers borrow it. `REPLY`/`FAIL` consume that request.
`DEFER` reserves a fiber and moves it into an owned context on the existing
ready queue. `FORWARD` serializes the child and keeps only parent reply metadata
in a pending operation, so the handler's fiber can finish. All outcome cleanup
runs exactly once, including rejected deferrals. Codec failure produces an RPC
error rather than silently dropping a request.

Futures and forwards share transport submission and generation-tagged matching,
with different completion consumers. Arbitrary nested callbacks and callback
retry states have been removed. Replies with nonzero accepted status bypass
success decoding. Deferred handlers remain responsible for consuming every
submitted future before returning. Explicit cancellation and timeout settlement
now acknowledge outgoing hardware retirement. Ordinary handler return does not
automatically consume abandoned futures; unsupported whole-process exit fences unresolved ownership until coherence reset. Cancellation produces a result that must still be consumed.

Both encoders receive explicit output capacities. XDR encoding uses this bound
rather than the in-memory object size. Rust encoders receive a slice of exactly
the supplied capacity. Decode allocations remain safely freeable on failure.

Before passing nested TX to encoding/DMA, hardware reserves in-flight routing
state. Full capacity returns a rejection. The ECI TX doorbell acknowledges only
after the per-worker packet buffer has been consumed, or the request rejected.
The acknowledgement survives cache eviction/reload. Values 1/2/3 distinguish
accepted/consumed, definite rejection and DMA error. The protocol preserves
existing parity, sequential per-worker RX/TX access and cache-line addresses.
An accepted local send is not application success or proof of network delivery.

## Limits and lifecycle

There are 320 shared fibers (64 KiB each), 640 software pending slots and a
separate generated hardware limits (16 outgoing operations and 16 incoming
reply contexts by default). Incoming admission rejects before host dispatch
when full, with best-effort hardware error replies and no live-entry overwrite.
Contexts retire when valid replies capture their routing metadata. See
[reply contexts](reply-contexts.md). Exhausted fibers affect deferrals,
not atomic inline handlers. Software admission errors and hardware rejection
are explicit; downstream/coherence stalls are not universally time bounded.
Timeout/cancellation now request acknowledged hardware retirement before releasing
endpoint ownership. Failed retirement and indeterminate submission still retain
unresolved ownership. Software completion-cookie generations retire before wrap;
hardware incoming context generations wrap and are not an authentication
mechanism. Whole-process shutdown and abnormal exit fail closed and require
reset; complete reclamation is deferred.

Workers sweep timeouts and drain ready fibers before polling RX. Hardware RX
NACK timing lets an assigned worker regain control. Parked-domain deadline
activation and execution-watchdog integration are deferred; the former deadline
housekeeping thread is removed. HOST_WAKE and its interface are outside this
stage. The [failure policy](process-recovery.md) retains unresolved resources.
Runtime-managed Raft services and
resident memcached workers are [documented follow-on designs](service-execution-design.md)
only. Neither is implemented by this batch.

Per-request CSV tracing is available through the optional interface below.
Historical trace results do not validate this scheduling/address revision.
Hardware module-load reliability, worker placement and matched-checkpoint
acceptance remain separate tracked gates.

Independent contexts, reinitialization and dynamic registration while workers
run are not established supported behavior; runtime service/worker state still
includes process-global tables and counters. Keep registration externally
serialized and follow the endpoint lifetime rules.

## Optional per-request latency tracing

`LAUBERHORN_SERVER_TRACE_CSV` enables runtime-owned receive/decode/handler/encode/TX
probes. Application code supplies its correlation ID through
`<lauberhorn/trace.h>`; no wire XID or client-address ABI is added. Rows follow
deferred/forwarded ownership and record actual Linux RX/TX CPUs. See
[latency measurement](../research/latency-measurement.md#server-csv) for the
schema, nested timing semantics and plotting restrictions. Tracing is disabled
by default.

Tracing now uses preallocated, prefaulted per-thread append-only buffers;
`LAUBERHORN_TRACE_CAPACITY` sets rows per thread (default 16384). The request
path copies a fixed-size record and publishes its count; it does no CSV
formatting, locking, allocation, or file I/O. Timestamp probes still have cost.
Initialize tracing before creating application threads: the opt-in tracing mode
reserves and blocks SIGUSR2, inherited by workers. A control thread waits for it.
After **all measured traffic has drained**, send `kill -USR2 SERVER_PID` once.
Wait for `CSV.status.json`, require `loss_free: true`, and verify the CSV count
and expected request IDs. Both CSV and completion manifest appear only at export;
existing output files are never overwritten. Export is a single snapshot for the
process lifetime, not a rearm operation. Size the buffer for all warmups and
measurement phases; duplicate request IDs across phases need external phase
boundaries. Overflow invalidates the capture. SIGTERM/SIGINT still use the
unsupported-exit/fencing path and must not be used to flush traces.
