# Domain failure and reset boundary

Complete kill/exit recovery is deferred in the cooperative ECI implementation.
This stage kills and fences failed domains; it does not reclaim unresolved
resources or recycle their identities. Historical recovery campaigns describe
older ABIs and do not establish acceptance of this implementation.

An unauthorized fresh read is attributed to the requesting PA lifetime. Kernel
fault handling kills that domain and forbids later activation. It must not kill
the current owner merely because an old domain addressed the same physical
worker. Legitimate old-address writebacks and LCIA remain serviceable.

Unsupported exit, individual pool withdrawal, fatal activation failure and a
failed activation-result copy after relinquishment have the same reset-required
boundary. Claims are fenced, task and resource references retained, and unresolved
slots, mappings and endpoint identities are not offered to another domain.
The persistent lifetime allocator resets only at full coherence reset. Application
reset or module reload cannot silently restart the PA namespace.

Ordinary scheduling does not use recovery machinery. Idle activation uses its
sticky notification and pinned dispatcher; ordinary PREEMPT uses `lh_yield()`
and one autonomous-cleanup `PreemptInfo` transaction with no scheduling IRQ.

Per-call cancellation/timeout retirement remains a separate existing operation.
Parked-domain deadline activation and execution-watchdog integration are outside
this stage; their control ioctls return `EOPNOTSUPP`. No HOST_WAKE interface is
specified here. See the [control contract](scheduling-control-interface.md) and
[evidence ledger](../hardware/eci-implementation.md).
