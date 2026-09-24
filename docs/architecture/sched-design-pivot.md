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
Handlers remain run-to-finish. Ordinary blocking syscalls should not require
universal runtime interposition.

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

Yield could return mailbox identity plus RX/TX parity (an index suffices).
The more flexible candidate is an **immutable kernel-exposed transport stub**
with per-thread mailbox/parity/assignment state, updated before userspace resumes
after reassignment. This supports migration after ordinary blocking syscalls too,
without a yield return or executable-code patching.

Reject reclaim if the saved userspace PC is within the stub's protected range,
including helpers retaining mailbox state. The stub should neither syscall nor
fault; an ordinary interrupt there does not itself justify reclaim. Updating
state cannot repair saved registers inside an interrupted operation; replaying
TX can duplicate it. Fault handling, parity ownership, state publication and the
final return-to-user ownership gate remain to design. **Signals are deferred.**

## Static coloring and protection

Worker-owned QP signatures permit static coloring of workers plus bypass, while
logical threads migrate through the stub. No thread per physical worker or
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

## Reclaim during blocking syscalls

Current onehot affinity preserves the worker across a syscall sleep. Reclaiming
it requires distinguishing Linux-runnable from authorized to resume with a QP.
The stub/state design allows resumption on another core; kernel thread selection
can keep TIDs out of the NIC. Same-core-only resumption would instead require
core-constrained domain demand.

Suggested counters (T is thread capacity, not a separate policy cap):

| Counter | Meaning |
| --- | --- |
| T | Registered schedulable threads, currently maxThreads |
| A | Assigned threads, including dispatch reservations |
| B | Syscall-blocked threads whose cores were reclaimed |
| W | Awakened syscall continuations awaiting assignment |

Available threads = `T - A - B`; invariant `0 <= W <= T - A - B`.
W is a subset of availability, not another subtraction.

| Transition | Update |
| --- | --- |
| RX yield completes | A-- |
| Blocked thread's core is reclaimed | A--, B++ |
| Reclaimed thread's wait resolves | B--, W++ |
| Reserve for an awakened continuation | A++, W-- |
| Reserve for a parked RX thread | A++ |

Eligibility requires available capacity AND (network/host work OR W > 0).
Policy still decides scaling/fairness. W creates demand even with an empty RX
queue or a running sibling. The kernel owns thread lists and claims an eligible
thread when accepting a grant. A sibling consuming HOST_WAKE cannot satisfy
another thread's continuation demand.

Serialize wake-versus-reclaim and grant cancellation; count B after reclaim,
not syscall entry. Prevent premature Linux execution and preserve physical drain
separately from accounting. Existing `corePidMap` early reservations are useful.
Current `softwarePending` clears on any resident worker and fallback allocation
targets zero-core domains: both need stronger semantics. Kernel selection
(`enabled && worker_idx == -1`) needs blocked/ready/parked eligibility. A ready
count versus an acknowledged kernel-backed pending flag remains open.

## Remaining validation

Exercise wake/reclaim/grant races, completion-only activation with a running
sibling, notification saturation/rearm, timer reprogramming during RX, migration
and parity restoration, and stale-alias/coherence isolation across domains.
No implementation or new simulation/board acceptance is implied by this note.

Linux 6.8 references: [eventpoll](https://github.com/torvalds/linux/blob/v6.8/fs/eventpoll.c),
[scheduler](https://github.com/torvalds/linux/blob/v6.8/kernel/sched/core.c),
[hrtimer](https://github.com/torvalds/linux/blob/v6.8/kernel/time/hrtimer.c),
[arm timer](https://github.com/torvalds/linux/blob/v6.8/drivers/clocksource/arm_arch_timer.c),
[arm64 user return](https://github.com/torvalds/linux/blob/v6.8/arch/arm64/kernel/entry-common.c).
