# Software runtime and datapath

**October 8 status:** the payload-isolation and multi-PA faults have source
corrections and a matched functional board baseline. The cooperative DESCHED
path, corrected 516232 module, GSYNC early NACK and buffered hot/cold benchmarks
have recorded finite validation. Expanded adversarial isolation and concurrent
four-worker acceptance remain open. See the [consolidated status and open
claims](../research/roadmap.md#current-status--october-8-consolidation).
The dated implementation notes below preserve their original scope.

October 2: the sole ECI runtime declares its pool with
`lauberhorn_set_worker_count()` before creating threads. Each thread completes
its initialization callback and fiber setup, registers explicitly and enters
bootstrap `lh_yield()`. DESCHED causes the same yield before further RX/TX.
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

## Shared synchronous echo path

`rpc_request.h`, `rpc_call.h` and `rpc_reply.h` hold the existing request ownership,
decode/dispatch and synchronous reply implementation. They are included by the
ECI runtime and the DPDK echo transport backend. DPDK uses the same `oncrpc.c`
codec adapter and generated XDR functions; no memcpy-only echo shortcut exists.
The DPDK backend supports synchronous reply/failure for these benchmarks, not
ECI's nested, deferred or cooperative scheduling mechanisms. The shared echo
handler retains borrowed argument ownership until encoding and cleanup finish.

## Request and transport ownership

The core RX API is `core_eci_rx_acquire[_traced]` / `core_eci_rx_release`.
`rx_view` holds the single contiguous mutable payload pointer, its length and
whether the borrow is outstanding. This is private transport state, not an
application-owned view. UDP exposes a const pointer to a worker-owned datagram
view (protocol metadata plus the borrowed payload address); callers retain only
that pointer. Release invalidates the UDP view and the payload together. There
is no separate RX buffer. Release
invalidates the pointer in software; the next acquisition retires the hardware
slot. Acquisition and assignment-releasing yield are rejected while borrowed.
Consumers must finish using the bytes, or copy them into owned storage, before
release. This includes pointers retained by codecs or deferred work.

TX uses `core_eci_tx_get_buffer` / `core_eci_tx_commit` / `core_eci_tx_cancel`.
The boolean `inline_payload` chooses 64-byte inline storage or full-MTU
overflow storage. The committed length may be smaller; it cannot exceed the
selected storage capacity. Submission
consumes the reservation even when rejected. Cancel finishes the transaction
without emitting a packet. It is available in both kernel and userspace: worker
control requests receive success; bypass control requests receive an authorization
error acknowledgement that still completes the transaction without DMA. Cancel
discards this status. Normal TX invalidates only the overflow lines needed
by actual length; inline and control-only transactions invalidate none. Unused
same-domain cache contents may remain stale. Full cleanup belongs to ownership
handoff. RPC codecs encode directly into reservations; the kernel copies between
transport storage and owned skbs.

Reply codecs default to overflow storage. A codec may set
`rpc_codec_t.response_inline = true` when its encoded replies fit in 64 bytes;
nullrpc/adder opts in because its reply is two XDR uint32s (8 bytes). Serialization
receives the chosen capacity and must fail without writing past it. An oversized
inline response is cancelled and reported as an RPC error, never silently moved
or re-encoded into overflow. XDR performs only the encoding pass, with no
`xdr_sizeof` traversal. Unknown or variable sizes can simply leave the flag false.
Rebuild applications with the updated public codec and reservation interfaces.
The pinned Dandelion Rust FFI includes `response_inline` and leaves it false for
variable BSON replies. Its Nix build checks the codec/ops layout against the
installed C headers as well as Rust compile-time layout assertions.

The consumer ownership audit found no other retained RX-view copies in active
platform consumers. Kernel bypass copies into an owned skb before core release;
RPC decodes into codec-owned objects before queueing deferred requests or settling
futures; reply forwarding copies into a TX reservation before RX release.
Pinned Dandelion decodes borrowed input into owned Rust strings/vectors and BSON
objects. Its source does not retain a transport slice. Custom codecs must obey
the same rule, now stated explicitly in `rpc.h`. Historical student runtime
copies are separate implementations and were not changed.

Transport version 3 places RX payloads up to 64 bytes and explicitly inline TX
payloads in the current control line's payload half. Other payloads occupy a contiguous
region at direction-relative offset 0x100. TX records placement explicitly,
so committing fewer bytes does not move the payload. Deploy matching hardware
and generated headers. Application-facing UDP ownership is documented in the
[programming model](programming-model.md#native-udp-buffers) and public headers.

Decoding allocates an independently
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

There are 320 shared fibers (64 KiB each), 640 software pending backing slots and
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
housekeeping thread is removed. HOST_WORK (formerly proposed as HOST_WAKE) and
its interface are not implemented. The [failure policy](process-recovery.md)
retains unresolved resources. The [Go toward etcd design](../proposals/go-etcd.md)
records the next runtime/HOST_WORK direction, currently documentation only.
The earlier [raft-rs service proposal](../proposals/service-execution.md) remains
historical design context, not a Go integration API.
Native UDP memcached adaptation and its bounded maintenance scope are tracked in
the [canonical design](../research/memcached.md); board acceptance is separate.

Per-request CSV tracing is available through the optional interface below.
Historical trace results do not validate this scheduling/address revision.
Hardware module-load reliability, worker placement and matched-checkpoint
acceptance remain separate tracked gates.

Independent contexts, reinitialization and dynamic registration while workers
run are not established supported behavior; runtime service/worker state still
includes process-global tables and counters. Keep registration externally
serialized and follow the endpoint lifetime rules.

## Optional per-request latency tracing

`nix/software/runtime.nix` accepts `tracing = false` (`LH_ENABLE_TRACING=0`).
This build excludes server tracing and PMU objects, request/fiber trace storage,
probe calls and export threads. Its pkg-config Cflags propagate the same define
into applications, whose public annotation macros do not evaluate arguments.
Runtime and applications must use the same compiled variant. Environment variables
cannot enable tracing in this build. The default build retains optional tracing
for existing applications and acceptance tests. `runtimeUninstrumented` and
`microbenchmarksUninstrumented` expose the disabled variant through Nix.

`LAUBERHORN_SERVER_TRACE_CSV` enables runtime-owned receive/decode/handler/encode/TX
probes. Application code supplies its correlation ID through
`<lauberhorn/trace.h>`; no wire XID or client-address ABI is added. Rows follow
deferred/forwarded ownership and record actual Linux RX/TX CPUs. See
[latency measurement](../research/latency-measurement.md#server-csv) for the
schema, nested timing semantics and plotting restrictions. Tracing is disabled
by default.

`core_eci_tx_commit_traced` optionally records `server_tx_doorbell_ns` after
control descriptor writes, placement, ordering barriers and parity update,
immediately before loading the opposite control CL. The timestamp is held
locally and stored to the trace row after the load; there is no PMU sampling at
this probe. TX-enter → doorbell-probe measures preparation on the CPU clock.
Doorbell-probe → FPGA `EciTxCommitRead` measures clock-correlated request arrival,
including the timestamp callback's return tail. It is not the full blocking
load duration: control invalidation/forwarding and acknowledgement follow the
FPGA event. Clock-offset uncertainty, probe perturbation and coherence queueing
must accompany any comparison with a one-way ECI estimate. Historical rows
without this probe retain the combined preparation/arrival attribution.

Tracing now uses preallocated, prefaulted per-thread append-only buffers;
`LAUBERHORN_TRACE_CAPACITY` sets rows per thread (default 16384). The request
path copies a fixed-size record and publishes its count; it does no CSV
formatting, locking, allocation, or file I/O. Timestamp probes still have cost.
With borrowed RX, receive exit marks descriptor/view acquisition; payload cache
misses occur during decoding or application access. Compare these stages with
that boundary in mind when using older copied-RX traces.
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

`LAUBERHORN_EVENT_TRACE_CSV`, together with the request CSV setting, enables
an additional buffered stream for entry returns, fiber resume/suspend, nested
submissions and every delivered call/completion. Applications can annotate
execution/collection phases with `lauberhorn_trace_event`. Events retain the
request-context token, application correlation ID, TID, actual CPU and raw
monotonic timestamp. Capacity is 32 events per configured request slot per thread;
overflow is reported separately in `EVENT_CSV.status.json`. Wait for both export
manifests and require both to be loss-free. Entry-handler timestamps are retained
separately from deferred-handler timestamps. Fiber-active and child spans overlap
and must not be summed as independent end-to-end components.

Dandelion optionally carries a correlation ID in its BSON arguments to connect
each submitted child with its received request. The ordinary untagged envelope
is unchanged; tagged payload size and event-probe cost must be included in
measurement overhead controls. FPGA and CPU clocks still require an explicit
alignment with stated uncertainty; client RTT is a duration on another host.

The current UDP evaluation temporarily admits at most 16 live calls per process,
including ready futures until consumption. This prevents a submit burst from
reusing hardware-retired slots faster than software drains completions. It is a
[small-table workaround](programming-model.md#loopback-composition-receive-side-backpressure),
not the intended scalable limit; see the [spill plan](../proposals/lookup-spill.md).

Native UDP trace metadata version 1 exposes the wrapping RX `HostMsgID` in
`packet->trace_ref` (valid bit 15, ID bits 5:0). Pass that scalar as `related_rx`
to `lauberhorn_udp_send_buffer` for each associated response, or zero for an
unrelated send. The FPGA records it at TX submission; it grants no access and
retains no packet. Use matching generated headers and hardware. Narrow IDs need
lifetime/order validation in analysis, not global-ID joins. See the
[memcached breakdown follow-up](../research/memcached.md#rxtx-trace-correlation-and-rtt-breakdown--active-october-10-follow-up).
