# Software runtime and datapath

Current branch: `feat/nested-rpc`, September 17, 2026. The runtime dispatches
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
submitted future before returning; cancellation/abandoned future retirement is
still B4 work.

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
Timeout settlement does not prove hardware retirement and does not make an
endpoint safe to destroy. Software completion-cookie generations retire before wrap; hardware incoming
context generations wrap and are not an authentication mechanism. Shutdown, cancellation, abnormal exit and endpoint quarantine remain
tracked in B4.

Workers sweep timeouts and drain ready fibers before polling RX. Hardware RX
NACK timing lets an assigned worker regain control, but cannot activate a parked
service or replace an application timer API. Runtime-managed Raft services and
resident memcached workers are [documented follow-on designs](service-execution-design.md)
only. Neither is implemented by this batch.

Per-request CSV tracing from the old synchronous runtime has not been restored
in this branch. Do not infer current tracing support from historical logs.
Hardware module-load reliability, worker placement and matched-checkpoint
acceptance remain separate tracked gates.

Independent contexts, reinitialization and dynamic registration while workers
run are not established supported behavior; runtime service/worker state still
includes process-global tables and counters. Keep registration externally
serialized and follow the endpoint lifetime rules.
