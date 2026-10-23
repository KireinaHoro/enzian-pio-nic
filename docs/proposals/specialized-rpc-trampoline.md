# Specialized RPC entry trampoline

Status: sketch; not implemented. No RX ABI or hardware changes are authorized by
this note.

Emit a specialized trampoline for each registered RPC procedure, with request
unmarshaling and handler execution inlined into that trampoline. Change the RX
cache-line layout so the NIC returns its executable entry address directly.
The proposed successful dispatch path has one indirect jump into that entry,
with no CPU service/codec table lookup or conditional dispatch branch. This is
not a promise that arbitrary handler or codec bodies contain no branches.

Move supported RPC-envelope validation into hardware before delivering the
trampoline address. The present call decoder selects the service in hardware,
but CALL/type and RPC-version checks are assertions rather than packet rejection;
credential/verifier validation and explicit malformed-length rejection need
implementation. Error and unsupported-message paths remain to be specified.

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

## Measurement plan

Separate Dispatch (service/codec selection, invocation, outcome routing and
scheduling handoff) from Runtime (validation, allocation and request/reply
bookkeeping). The acquired version-4 data combines these in Runtime/dispatch and
cannot isolate all removable dispatch costs. Existing setup intervals mix
selection with initialization, and codec/handler spans include indirect calls.

Place one timestamp at each transition between meaningful work intervals. Do
not introduce a separate “NIC submission starts” boundary: charge preparation
and handoff from network-packet-ready (LH: reply-descriptor-ready) through the
pre-publication boundary to NIC message passing. Retain distinct RX scheduling
wait where it separates Dispatch from NIC processing. Version-5 instrumentation implements this accounting for future acquisitions;
existing observations retain their original accounting.

Evaluate matched generic and specialized LH builds with identical codec and
handler semantics and instrumentation-off/on acquisitions. The measured scope
is request-to-reply publication, not total CPU work per request; see the
[measurement methodology](../research/hot-measurement.md) and
[timestamp contract](../development/rpc-breakdown.md).
