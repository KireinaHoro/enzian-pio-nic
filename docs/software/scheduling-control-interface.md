# Cooperative scheduling control

The ECI idle-RX command **DESCHED** asks a worker to relinquish its assignment
at its next RX wait; it does not interrupt a running handler. RX status bit 1
with valid clear denotes DESCHED. The blocking `DeschedInfo` register returns
the committed destination and RX/TX parity snapshot.

Historical PREEMPT/PreemptInfo trace names refer to the same numeric IDs and
field layouts. The decoder normalizes these names; saved evidence and historical
analysis JSON metric keys retain their original spelling. Generated headers and
displayed protocol labels use DESCHED/DeschedInfo.

## Execution boundary

A worker enters RX after exhausting runnable local work. Handlers run to finish
or cooperatively wait for external results; applications must release locks
before blocking in RX/yield. Self-sustaining batch work is outside this
cooperative execution contract. Ordinary syscall sleep retains the assignment.

At idle RX the NIC supplies a packet, stalls, returns a finite NACK or returns
DESCHED. The runtime drains ready fibers and sweeps RPC timeouts before RX;
DESCHED causes immediate yield without further transport access.

## Activation and handoff

There are two entry paths with one kernel activation helper:

- Initial/idle: initialized application threads wait inside `lh_yield()`. Empty
  worker CPUs use Linux idle/WFI. Packet-driven NIC reservation sets a sticky
  idle-dispatch notification and sends a kick IRQ. The hard handler wakes that
  worker's pinned kernel dispatch thread. Dispatch checks the notification under
  per-worker reader serialization, reads `DeschedInfo`, atomically claims a
  waiting thread, installs affinity, and delivers its activation snapshot.
- Ordinary: NIC serves DESCHED at idle RX. Runtime immediately yields without
  further RX/TX. Kernel fences the outgoing task against returning to its old
  assignment before reading `DeschedInfo`. Read acceptance acknowledges
  relinquishment. NIC finishes internal transport cleanup, commits the reserved
  destination, and responds. The same activation helper activates the incoming
  task; the outgoing task waits for its own later assignment. No scheduling IRQ
  or idle-dispatch notification participates in this handoff.

`DeschedInfo` has **no valid bit**. Every authorized read returns the committed
destination and both parity bits. It must never be polled or read speculatively
while idle. A pending IRQ or a local wake alone does not authorize a read:
duplicate and delayed kicks must be reconciled with the sticky hardware
notification under reader serialization. The sticky notification clears when
the authorized read is accepted; ordinary handoff does not create a notification.
The committed response remains stable until consumption. The pinned dispatch
thread sleeps while an application owns its worker. An ordinary yield checks the
read-only `yieldRequested` register under the same reader lock before issuing
the side-effecting read. This rejects an application calling yield without a
pending handoff; it is not polled for work or a validity bit in `DeschedInfo`.

Read acceptance and response consumption are distinct boundaries. The accepted
transaction retains its destination and response identity under backpressure.
Exactly one side-effecting read is allowed per authorized dispatch. Bus access
width and framing must ensure one logical read cannot acknowledge two handoffs.

Cleanup must progress autonomously while the CPU is blocked in MMIO. It must
not wait for a subsequent command, an IRQ handler on that CPU, or a control
access queued behind the read. Shared AXI-Lite traffic may be delayed by an
ordinary handoff; unrelated register accesses are not guaranteed independent
progress while that read is pending. No claim or lifetime lock is held across
blocking MMIO.

Allocation is packet-driven, with idle-first selection, an additional-core
watermark policy and background discovery of queued unassigned domains.
Registration does not eagerly activate threads.

## Pool and runtime lifecycle

`lauberhorn_set_worker_count(ctx, n)` declares the fixed pool before thread
creation. Each thread completes its initialization callback and runtime setup,
explicitly registers, then enters bootstrap yield. NIC capacity is published
only when every declared thread is ready. Pool changes and individual withdrawal
are rejected. `mmap()` only maps stable physical-worker aliases; it neither
registers nor parks a task.

```c
int lh_yield(struct lh_activation *result);
/* result: worker_index, rx_next, tx_next */
```

A successful bootstrap or subsequent yield returns the **calling thread's** new
assignment, not its replacement's assignment. `worker_index` selects that
domain's pre-mapped alias. Runtime replaces both local parity bits before any
transport access. Parity travels in the activation snapshot; there is no shared
parity page or kernel parity programming.
DESCHED has no application payload or destination and consumes no network packet.

`lh_yield()` rejects an outstanding RX borrow or TX reservation with `EBUSY`
before invoking the kernel.

Thread claims are atomic and lifetime-protected. Unrelated wakeups and signals
cannot authorize return to an old assignment. Validate result arguments before
relinquishment; retain the authoritative activation in kernel storage. Failed
result copying after relinquishment fails closed and marks the session
reset-required. Ordinary syscall sleep retains assignment.

Ordinary handoff uses no kernel cache flush, kernel access to transport cache
lines or activation commit write.

## Addresses, ownership and failure

The [worker-endpoint and domain-alias contract](../hardware/queue-pair-design.md)
defines the selected 8 KiB geometry and capacities. The PA lifetime code is
distinct from scheduler slots, Linux PIDs, pool thread indices and CPU identities.

Decode physical worker and domain without replacing the accepted address.
Validate fresh admission against the trusted installed owner. Retain accepted
addresses and ownership through AXI responses, writeback, invalidation, and
LCIA. Changing the current owner must never retag an old obligation. Old-address
cleanup remains serviceable; unauthorized traffic must neither expose another
owner's data nor prevent cleanup. TX descriptors and reply contexts must be
validated against trusted assignment, not self-reported userspace identity.

Preserve final RX control invalidation and its completion before worker reuse,
including A→B→A freshness, and preserve the independent RX writeback sink.
The implementation uses direct worker/domain addresses, without thread-prefix
translation, a preemption control cache line, READY/BUSY CAS or control-line
locking.

An unauthorized fresh read is a fatal violation by the requesting domain. Kill
that domain and forbid subsequent activation, fence its claims, and mark the
session reset-required while retaining unresolved resources. Do not attribute
the violation to the worker’s current owner or treat legitimate old-address
writebacks and LCIA as violations. DCS can complete a rejected AXI read as
cacheable data, so returning zero data alone is insufficient for later freshness.

Allocate PA lifetime codes monotonically, fail at exhaustion, and preserve the
allocator across driver reload. Reset allocation only at a full coherence-reset
boundary. No unresolved identity or resource is recycled. Unsupported exit or
fatal activation failure fences affected claims and marks the session
reset-required. Automatic resource reclamation after these failures is unsupported;
see the [failure policy](scheduler.md#failure-and-reset-boundary). Hardware/software ABI mismatch is rejected.

## Unsupported interfaces and open design

HOST_WORK (formerly proposed as HOST_WAKE) is outside this implemented contract.
The [Go design](../proposals/go-etcd.md#host_work) specifies its intended role:
complete an assigned RX wait for software work while retaining the assignment.
Allocation for unassigned domains is a separate requirement. Host-work fields,
cookies, RX discriminators and subscription APIs are not implemented. Existing
deadline kicks do not implement HOST_WORK or parked-domain timeout activation.
This contract covers ECI, not PCIe scheduling.

## Verification boundaries

The focused models cover distinct parts of this protocol:

- [`IdleDispatch`](../../model/tla/scheduling/IdleDispatch.tla): sticky notification,
  response consumption, duplicate/delayed kicks, reader serialization and
  autonomous cleanup with shared-control contention. It distinguishes IRQs
  created by ordinary handoff from delayed delivery of an earlier idle kick.
- [`HandoffClaims`](../../model/tla/scheduling/HandoffClaims.tla): thread-claim
  uniqueness/capacity, concurrent exchange, unrelated wakes and failed copying.
- [`PoolBootstrap`](../../model/tla/scheduling/PoolBootstrap.tla): publication after
  initialization/registration and delayed parking.

These models do not instantiate DCS or Linux. Transport models assume settled
entry, resource service and endpoint freshness; concrete delayed-LCIA, A→B→A
and writeback checks address separate finite obligations. The
[implementation evidence](../hardware/eci-implementation.md) records source,
model, RTL and board results within their respective scopes. Finite board
exposure does not establish infinite-stall progress or a latency guarantee.
The [bounded scheduling composition](../formal/scheduling.md#joined-scheduling-wake-and-transport) and
[coherence progress analysis](../hardware/queue-pair-design.md#motivation-avoid-head-of-line-blocking) describe the
remaining resource and hardware-refinement assumptions.

Source entry points: [handoff RTL](../../hw/src/lauberhorn/host/eci/EciDeschedulingControlPlugin.scala),
[kernel activation](../../sw/kmod/sched.c), [runtime yield](../../sw/rt/activation.c)
and [worker loop](../../sw/rt/worker.c).
