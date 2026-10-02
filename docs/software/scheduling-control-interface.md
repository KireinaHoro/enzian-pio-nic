# Fixed-worker cooperative scheduling control

Selected implementation contract, October 2, 2026. The production RTL, kernel
and runtime still use the legacy scheduler. The replacement is under development
on `feat/fixed-worker-cooperative-yield`; the arithmetic and focused control checks
alone do not establish implementation or board acceptance. Earlier retirement
and wake proposals are retained as [historical design notes](scheduling-control-history.md),
not interfaces to implement in this stage.

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
sleeps while an application owns its worker.

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
Remove thread-prefix translation, the preemption control cache line, READY/BUSY
CAS, and control-line locking only as part of the matched replacement.

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

The generated C/Scala arithmetic and standalone RTL decoder are preparatory
components, not a replacement production route. The decoder's admission result
is only for fresh host reads; old writebacks and LCIA require retained transaction
matching. `IdleDispatch` checks sticky notification, duplicate kicks, reader
serialization, ordinary handoff without an IRQ, and shared control-bus contention
under abstract autonomous cleanup. Its four fault controls reproduce violations;
it does not instantiate the DCS, kernel, thread claims or transport engine.
Existing capacity, claim and transport-readiness checks retain their separate
scopes and must be composed with the implementation.

Required acceptance remains: matched RTL/software collateral, runtime lifecycle
and kernel builds, ECI and actual DCS slice tests, A→B→A and migration, ownership
and reply validation, CI, and Enzian timeout/progress trials. Re-probe RX hold
limits using matching artifacts and clocks, recording every exposed L2C_TAD
unit. Preserve trace/fault evidence before reset; refine the boundary at timer
resolution. Repeat the highest exception-free tested setting under contention
across two cold boots, with one, multiple, and all-but-one workers stalled,
active-peer cooperative handoffs, and simultaneous bypass RX/TX. Report actual
trace durations, exposure time and nearest failing bound. Finite board evidence
is separate from infinite-stall model properties.
