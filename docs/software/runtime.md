# Software runtime and datapath

The runtime dispatches managed requests over ECI and uses cooperative fibers for
explicitly deferred work. Applications use the [programming model](programming-model.md);
Linux activation and failure fencing belong to [scheduler integration](scheduler.md).
The [cache-line interface](../architecture/hw-sw-interface.md) defines transport
placement and coherence ordering. ECI is the supported prototype; backend-neutral
names do not imply PCIe or DPDK feature parity.

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

Kernel bypass copies into an owned skb before core release;
RPC decodes into codec-owned objects before queueing deferred requests or settling
futures; reply forwarding copies into a TX reservation before RX release.
Pinned Dandelion decodes borrowed input into owned Rust strings/vectors and BSON
objects. Custom codecs must likewise decode retained values into owned storage;
see the ownership requirements in `rpc.h`.

The [cache-line interface](../architecture/hw-sw-interface.md) defines version-3
placement, length-dependent invalidation and cleanup. Application-facing UDP
borrowing and TX reservation rules are in the
[programming model](programming-model.md#native-udp-buffers).

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

Hardware reserves outgoing nested-call routing state before DMA/encoding.
The [local TX result contract](../architecture/hw-sw-interface.md#tx-completion-status)
defines staging-buffer release, definite admission rejection and errors. A local
accepted result does not establish application success or network delivery.

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
its interface are not implemented. The [failure policy](scheduler.md#failure-and-reset-boundary)
retains unresolved resources. The [Go toward etcd design](../proposals/go-runtime.md)
records the next runtime/HOST_WORK direction, currently documentation only.
The earlier [raft-rs service proposal](../proposals/service-execution.md) remains
historical design context, not a Go integration API.
Native UDP memcached adaptation and its bounded maintenance scope are tracked in
the [canonical design](../research/memcached.md); board acceptance is separate.

Per-request tracing is available through the guide below. For measured coverage,
see [ECI progress evaluation](../research/eci-progress.md) and application results.

Independent contexts, reinitialization and dynamic registration while workers
run are not established supported behavior; runtime service/worker state still
includes process-global tables and counters. Keep registration externally
serialized and follow the endpoint lifetime rules.

## Optional per-request latency tracing

The [runtime tracing guide](../development/runtime-tracing.md) describes build
variants, request/event probes, buffer sizing and one-shot export. Measurement
boundaries and clock uncertainty belong to [latency analysis](../development/runtime-tracing.md#server-csv).
