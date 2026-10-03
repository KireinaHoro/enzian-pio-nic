# Cooperative scheduling control

**Acceptance blocked (October 3):** a full-engine RTL regression confirms that
a newly activated domain can read the previous owner's TX payload through its
own valid alias. See the [reproducer and required isolation work](../hardware/payload-isolation-blocker.md).
The board campaign is paused; earlier functional results do not discharge this
isolation failure.

Selected implementation contract, October 2, 2026. RTL, kernel and runtime
implement this sole ECI design on `feat/cooperative-yield`. Validation is in
progress; [the evidence ledger](../hardware/eci-implementation.md) records its
limits. Superseded designs remain available in Git history.

The October 3 [finite board record](../../data/eci/board-evidence/cooperative-20261003-contention.json)
observes 5,926 ordinary handoffs with zero scheduling IRQ counter change,
including 4,710 handoffs overlapping three full-length peer stalls. Concurrent
control-register reads and bypass traffic completed, with clean TAD/kernel
checks. This supports the two-path contract under recorded finite exposure;
it does not establish an infinite-stall or latency guarantee.

## Activation and handoff

There are two entry paths with one kernel activation helper:

- Initial/idle: initialized application threads wait inside `lh_yield()`. Empty
  worker CPUs use Linux idle/WFI. Packet-driven NIC reservation sets a sticky
  idle-dispatch notification and sends a kick IRQ. The hard handler wakes that
  worker's pinned kernel dispatch thread. Dispatch checks the notification under
  per-worker reader serialization, reads `PreemptInfo`, atomically claims a
  waiting thread, installs affinity, and delivers its activation snapshot.
- Ordinary: NIC serves PREEMPT at idle RX. Runtime immediately yields without
  further RX/TX. Kernel fences the outgoing task against returning to its old
  assignment before reading `PreemptInfo`. Read acceptance acknowledges
  relinquishment. NIC finishes internal transport cleanup, commits the reserved
  destination, and responds. The same activation helper activates the incoming
  task; the outgoing task waits for its own later assignment. No scheduling IRQ
  or idle-dispatch notification participates in this handoff.

`PreemptInfo` has **no valid bit**. Every authorized read returns the committed
destination and both parity bits. It must never be polled or read speculatively
while idle. A pending IRQ or a local wake alone does not authorize a read:
duplicate and delayed kicks must be reconciled with the sticky hardware
notification under reader serialization. Notification stays set until dispatch
consumption. Ordinary handoff must not create it. The pinned dispatch thread
sleeps while an application owns its worker. An ordinary yield checks the
read-only `yieldRequested` register under the same reader lock before issuing
the side-effecting read. This rejects an application calling yield without a
pending handoff; it is not polled for work or a validity bit in `PreemptInfo`.

Read acceptance and response consumption are distinct boundaries. The accepted
transaction retains its destination and response identity under backpressure.
Exactly one side-effecting read is allowed per authorized dispatch. Bus access
width and framing must ensure one logical read cannot acknowledge two handoffs.

Cleanup must progress autonomously while the CPU is blocked in MMIO. It must
not wait for a subsequent command, an IRQ handler on that CPU, or a control
access queued behind the read. Shared AXI-Lite traffic may be delayed by an
ordinary handoff. A finite shared-bus test is required; there is no claim that
all unrelated register accesses are independent of that read. No claim or
lifetime lock is held across blocking MMIO.

Preserve packet-driven allocation, idle-first selection, the additional-core
watermark policy, and background discovery of queued unassigned domains.
Registration must not eagerly activate threads.

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
transport access. The shared parity page and kernel parity programming disappear.
PREEMPT has no application payload or destination and consumes no network packet.

Thread claims are atomic and lifetime-protected. Unrelated wakeups and signals
cannot authorize return to an old assignment. Validate result arguments before
relinquishment; retain the authoritative activation in kernel storage. Failed
result copying after relinquishment fails closed and marks the session
reset-required. Ordinary syscall sleep retains assignment.

No ordinary handoff cache flush, kernel access to transport cache lines, or
activation commit write is added.

## Addresses, ownership and failure

The selected [8 KiB map](../../model/data/selected-8k.json) uses RX controls at
`0x300/0x380`, TX controls at `0x1300/0x1380`, and twelve overflow lines per
direction. Its 19-bit color-neutral PA lifetime code is distinct from active
scheduler slots, Linux PIDs, pool thread indices, and CPU identities. Defaults
remain four workers and sixteen active domain slots; geometry supports up to
seven workers.

Decode physical worker and domain without replacing the accepted address.
Validate fresh admission against the trusted installed owner. Retain accepted
addresses and ownership through AXI responses, writeback, invalidation, and
LCIA. Changing the current owner must never retag an old obligation. Old-address
cleanup remains serviceable; unauthorized traffic must neither expose another
owner's data nor prevent cleanup. TX descriptors and reply contexts must be
validated against trusted assignment, not self-reported userspace identity.

Preserve final RX control invalidation and its completion before worker reuse,
including A→B→A freshness, and preserve the independent RX writeback sink.
The matched replacement removes thread-prefix translation, the preemption
control cache line, READY/BUSY CAS, and control-line locking. Components use
direct ECI address and ownership names; no parallel legacy implementation is retained.

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
reset-required. Full kill/exit recovery is deferred; ordinary scheduling does
not use legacy recovery machinery. Hardware/software ABI mismatch is rejected.

HOST_WAKE implementation and interface design are outside this stage. Add no
host-work fields, cookies, RX discriminators, subscription APIs, or dead stubs.
The existing deadline-kick mechanism is not HOST_WAKE; its integration with the
replacement scheduler is deferred. Acceptance does not imply parked-domain
timeout activation. PCIe scheduling and timing optimization are also excluded.

## Verification status

Generated C/Scala constants and the ownership decoder are integrated into the
production route. Fresh reads use trusted owner validation; accepted traffic,
old writebacks and LCIA retain their transaction addresses. The local checks
include full-domain encoding, one through seven worker builds, actual DCS slice
control routing, runtime activation, migration and shared-control contention.
See [the evidence ledger](../hardware/eci-implementation.md) for exact results
and the distinction between separate checks and a full-system proof.

`IdleDispatch` checks sticky notification through response consumption, duplicate
kicks (including delayed idle kicks during ordinary handoff), reader serialization
and autonomous cleanup with shared control-bus contention. It counts IRQs created
by ordinary handoff separately from delayed delivery of an earlier idle IRQ.
`HandoffClaims` reuses `ThreadClaim` uniqueness/capacity and checks concurrent
exchange, unrelated wakeups and failed copying. `PoolBootstrap` checks publication
after initialization/registration and delayed parking. These models do not
instantiate DCS or Linux. Existing transport models retain their settled-entry,
resource-service and endpoint-freshness assumptions; the concrete delayed-LCIA,
A→B→A and writeback tests provide separate finite evidence for those obligations.

Remaining acceptance requires successful matched hardware CI and Enzian
lifecycle, timeout and progress trials. Re-probe RX hold
limits using matching artifacts and clocks, recording every exposed L2C_TAD
unit. Preserve trace/fault evidence before reset; refine the boundary at timer
resolution. Repeat the highest exception-free tested setting under contention
across two cold boots, with one, multiple, and all-but-one workers stalled,
active-peer cooperative handoffs, and simultaneous bypass RX/TX. Report actual
trace durations, exposure time and nearest failing bound. Finite board evidence
is separate from infinite-stall model properties.
