# Specialized RPC entry trampoline

Status: sketch; not implemented. No RX ABI or hardware changes are authorized by
this note.

Change the RX cache-line layout to carry a code pointer to a fully specialized,
inlined RPC trampoline. Hardware service selection would choose the entry so
that the CPU no longer needs generic handler/codec table reads, switches or
software dispatch on the selected fast path. Specialize the entry for its RPC
procedure and codec, retaining the required argument/result processing and
handler semantics.

The intended comparison is necessary RPC work versus avoidable software
indirection and dispatch. The proposed LH path would eliminate the latter;
the current DPDK and Caladan baselines would retain it. This is an evaluation
hypothesis, not a measured speedup or a claim that those systems cannot also be
specialized.

Still to define: exact RX layout and entry ABI; pointer registration, validation
and lifetime under process isolation; specialization/inlining boundaries; error
and non-fast paths; code-size tradeoffs; and a matched comparison that isolates
the specialization's savings. Coordinate any implementation with the
[ECI boundary contract](../software/nested-rpc.md#endianness-at-the-host-boundary)
and [cache-line interface](../architecture/hw-sw-interface.md), including ownership
and progress rules and matched generated hardware/software collateral.

The [version-3 unloaded accounting](../development/rpc-breakdown.md) provides
precisely bounded elapsed dispatch intervals for synchronous inline replies.
RPC (un)marshal now contains only the codec-call spans; allocation is separately
timed within Runtime/dispatch, and TX-buffer reservation belongs to CPU–NIC.
The exported `runtime_details_ns` partitions Runtime/dispatch into named spans
and an explicit `other_runtime` remainder:

- LH `rx_dispatch` measures `rx_exit → backend_enter`; `backend_dispatch`
  measures `backend_enter → rpc_enter`, including packet-type selection.
- `request_setup` measures `rpc_enter → alloc_enter`, including LH's
  descriptor-to-registration/codec pointer access and request initialization.
  Decode/handler setup, handler return, outcome dispatch, encode setup, reply
  dispatch and reply preparation have separate boundaries.
- DPDK and CS-A64 separately time software `lookup`, RPC-header processing and
  backend setup. CS-A64 also separates request-thread setup and dispatch wait.

These intervals account for the current path, but their sum is not the precise
cost removable by a trampoline. Setup spans mix necessary work with pointer
accesses and tracing; codec and handler spans include their indirect calls.
Runtime/dispatch also includes allocation and, for CS-A64, scheduling waits.
Probe effects remain in the measured durations. Individual pointer reads,
switches and indirect-call overhead are not isolated.

Evaluate savings with matched generic and specialized LH builds, preserving
codec and handler semantics, using version-3 breakdowns and matched
instrumentation-off/on acquisitions. Historical version-1/2 breakdowns retain
their broader RPC intervals and cannot supply this accounting. The measured
scope is the request-to-reply-submission critical path, not total CPU work per
request; see the [measurement methodology](../research/hot-measurement.md).
