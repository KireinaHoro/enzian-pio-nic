# Scheduler design pivot (September 2026)

Status: **proposal**, consolidated 2026-09-24; not an implemented contract.
See the [current scheduler](../software/scheduler.md),
[QP alternatives](../hardware/queue-pair-design.md), and
[DCU progress model](../hardware/coherence-progress.md).

## Capabilities and execution contract

- **A:** allocate isolated worker cores to protection domains: Linux processes,
  with multiple threads sharing a page table, driven by network and host work.
- **B:** offload L7 decode/encode, including handler selection and arguments.
- **C:** invoke the selected handler with minimal software dispatch.

ONC-RPC handler selection already exists; runtime payload unmarshaling remains.
Restricted argument formats could permit direct invocation. General full
marshal/unmarshal is not complete. Intended adoption: microbenchmarks/NFS/Raft
ABC; Dandelion A/AB (outer ONC-RPC envelope; sandbox setup still required);
memcached A through its event loop. These are directions, not completed ports.

RX means **this worker has no runnable work**, not that the whole domain is idle.
It stalls awaiting work or receives PREEMPT and yields; siblings may keep running.
Handlers remain run-to-finish. **The candidate reclaims/migrates workers only at
explicit Lauberhorn yield points**, apart from termination/recovery. Ordinary
blocking syscalls retain the core/QP assignment and onehot affinity; Linux may
sleep and resume the thread without changing its mailbox. No universal syscall
interposition is required. Assigned parallelism includes these sleeping threads;
the NIC cannot allocate their reserved capacity to another domain.

Strongly recommend nonblocking sockets with epoll, asynchronous storage and
cooperative runtime waits. Blocking is permitted but can strand capacity for an
unbounded interval. Assume applications do not hold locks across blocking RX or
Lauberhorn yield, or block waiting for a continuation that requires the same
worker to progress. **Futex integration and its application-specific contention/
progress implications are deferred, alongside signals**; this is not a claim of
compatibility with arbitrary synchronization patterns.

## Cooperative handoff and transport exposure

At idle RX, return PREEMPT instead of a request/NACK; further RX reads remain
PREEMPT until handoff. Userspace calls `lauberhorn_yield()`. Retain the kernel-entry
acknowledgment currently provided by reading IpiAckReg (rename it), then drain
RX/TX/coherence state before reassignment. Normal TX does not trigger descheduling.
These boundaries replace CAS on the cooperative path; ordering and forced-kill
retirement still need specification.

Start the cooperation timeout at the committed idle-preemption decision;
distinguish delivery failure from failure to enter the syscall. Timeout escalates
to kill IRQ; execution watchdog policy is separate. Bootstrap/activation without
a userspace RX waiter still needs a kernel wake mechanism: “IRQ only for kill”
is not a complete idle-core protocol.

On return, `lauberhorn_yield()` supplies mailbox identity plus RX/TX parity
(an index suffices). The runtime retains that identity until the next yield;
ordinary syscall returns need no mailbox refresh. This avoids requiring a shared
mailbox-state lookup on every transport entry. Bootstrap must supply equivalent
initial state. Parity restoration and ownership ordering remain to specify.

## Static coloring and protection

Worker-owned QP signatures permit static coloring of workers plus bypass, while
logical threads migrate at yield boundaries. No thread per physical worker or
per-migration page-table rewrite is required. This enables removal/simplification
of the general thread router; it does not itself prove DCU progress.

Candidate isolation: domain-qualified PA aliases preserving each worker's
**scrambled** DCU colors. Page tables expose only the domain's aliases; hardware
checks the prefix against the worker's current owner. Open: prefix encoding and
capacity, reverse coherence addressing, safe unauthorized-access rejection,
cached-state retirement and prefix reuse. Hardware cannot check cache hits;
arbitrary high PA bits are not automatically color-neutral. Drain old ownership
before reuse and include retiring traffic in the progress argument.

## HOST_WAKE: domain work

HOST_WAKE is a non-network RX control item asking an eligible domain worker to
reap host events; results remain in their event/completion sources. For epoll,
receive HOST_WAKE, call nonblocking `epoll_wait`, then service ready FDs. Epoll
is not a universal I/O completion API. Retain unfinished local work before RX.
This avoids an epoll syscall on every network iteration.

Do **not** suppress HOST_WAKE just because the network queue is nonempty.
Coalesce only with a persistent pending indication or an equivalent flag on
another delivered RX item. Saturation, registration, rearm and consumption must
not lose notifications. Host work must activate even a domain with no core.

Candidate kernel integration: register an epoll FD and subscribe through its
`.poll` waitqueue interface, notifying the NIC through a kernel control register.
Linux 6.8's `ep->poll_wait` supports notification without a sleeping epoll thread.
Check existing readiness at registration and handle teardown. Generic scheduler
wake hooks miss events without sleeping tasks; probes are investigative, not an
established correctness mechanism. Other event sources need subscriptions too;
the source callback must itself be able to run.

Optional app deadline: **one per domain**, programmed through a TX control
message, typically for the runtime's earliest timer expiry. NIC expiry injects
HOST_WAKE; the NIC does not maintain individual application timers. Define
replacement/cancellation and stale expiry semantics. Userspace timer bookkeeping
cannot wake a stalled/parked worker without Linux, this deadline, or polling/NACK.

## Early NACK: core-local interrupt service

RX already has a configurable NACK bound for coherence progress, but long bounds
add interrupt latency. Separate **per-worker early NACK** from HOST_WAKE: a
domain notification may reach a sibling instead of the CPU needing an interrupt.

Candidate: mirror Linux architectural clock-event programming into a NIC deadline
for that CPU. At expiry, complete outstanding RX and prevent another long stall
until the event is serviced/reprogrammed. Cover replacement/shutdown, clock
conversion, write latency and actual driver/erratum paths. The ISR cannot initiate
its own release from a stalled load.

Arm64 timer interrupts are per-CPU; Linux 6.8 timer migration and `nohz_full` do
not remove all local hrtimers. Mirroring could make kernel-backed application
timers sufficient, leaving the app NIC deadline as a syscall-saving option.
Unpredictable interrupts/IPIs still need bounded NACK or another release path.
Interrupt-cancelable loads would address the underlying limitation.

## Deferred alternative: generic syscall reclaim

Reclaiming syscall-blocked cores could recover stranded capacity, at additional
kernel/runtime and NIC-scheduler complexity. Possible approaches:

- Preserve same-core resumption and mailbox identity, but publish core-constrained
  domain demand when the blocked thread wakes; restore its QP state before reuse.
- Allow migration via an immutable kernel-exposed transport stub and per-thread
  shared mailbox/parity state updated before userspace resumes. This adds a
  mailbox-state load/dependency to the transport fast path.
- Patch a thread-specific transport stub on migration to embed mailbox selection,
  paying code-update/instruction-cache synchronization costs then. Shared page
  tables, stub selection, concurrency and changing parity complicate this option;
  mailbox-address patching alone does not solve parity bookkeeping.

Migratable variants need a return-to-user ownership gate. Reject reclaim when the
saved PC is inside the transport stub or helpers retaining mailbox state; the
stub should neither syscall nor fault. Updating state/code does not repair saved
registers mid-operation, and replaying TX can duplicate it. Fault handling and
safe patch/state publication remain open; signals are deferred.

The NIC could remain domain-aware while the kernel owns eligible-thread lists,
but needs blocked-capacity accounting and persistent awakened-continuation demand,
even with an empty RX queue or running sibling. Reclaim/wake/grant races and
Linux-runnable versus permitted-to-resume state need synchronization. Existing
`softwarePending` clearing on any resident worker, zero-core fallback allocation,
and `enabled && worker_idx == -1` kernel selection are insufficient. These
additions are **not required by the current yield-only candidate**.

## Remaining validation

Exercise cooperative handoff and HOST_WAKE/grant races, notification saturation/
rearm, timer reprogramming during RX, yield migration/parity restoration, and
stale-alias/coherence isolation across domains. Verify that ordinary syscall sleep
retains assignment and resumes on the same core; measure stranded capacity and
its effect on competing domains before deciding whether generic reclaim is needed.
No implementation or new simulation/board acceptance is implied by this note.

Linux 6.8 references: [eventpoll](https://github.com/torvalds/linux/blob/v6.8/fs/eventpoll.c),
[scheduler](https://github.com/torvalds/linux/blob/v6.8/kernel/sched/core.c),
[hrtimer](https://github.com/torvalds/linux/blob/v6.8/kernel/time/hrtimer.c),
[arm timer](https://github.com/torvalds/linux/blob/v6.8/drivers/clocksource/arm_arch_timer.c),
[arm64 user return](https://github.com/torvalds/linux/blob/v6.8/arch/arm64/kernel/entry-common.c).
