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
and non-fast paths; code-size tradeoffs; and measurement boundaries that isolate
the targeted overhead. Coordinate any implementation with the
[ECI boundary contract](../software/nested-rpc.md#endianness-at-the-host-boundary)
and [cache-line interface](../architecture/hw-sw-interface.md), including ownership
and progress rules and matched generated hardware/software collateral.

The current unloaded breakdown does not measure the removable cost directly.
RPC intervals include some dispatch and buffer preparation, while the residual
runtime interval includes other work and waiting. See the
[breakdown definitions](../development/paper-charts.md).
