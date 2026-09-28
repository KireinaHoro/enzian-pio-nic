# Scheduler design pivot (September 2026)

Status: **proposal**, consolidated 2026-09-28. Mechanisms below are implementation
candidates. See the [current scheduler](../software/scheduler.md),
[QP alternatives](../hardware/queue-pair-design.md), and
[DCU progress model](../hardware/coherence-progress.md).

## Existing issues

- **Handoff overhead:** READY/BUSY CAS protects transport operations while the
  NIC coordinates IRQ-driven switching. The intended scheduling boundary is
  already cooperative: a worker asks for RX when it has exhausted runnable work.
- **Host-event integration:** blocking in 2F2F RX or parking a domain must still
  permit progress on normal network I/O, storage completions and application
  timers. These sources need a notification path into NIC-controlled execution.
- **Interrupt latency:** an outstanding RX load can delay local interrupt service.
  The coherence timeout/NACK bounds the stall, but that bound can be too long for
  timely timer/IPI service.
- **Endpoint placement:** stable per-thread mailbox addresses motivate the thread
  router, while coherence progress calls for statically compatible DCU colors
  across concurrently active worker endpoints and bypass.

A domain is a Linux process with one page table and multiple registered workers.
The NIC allocates cores among domains; Linux selects and parks their threads.
Application execution is run-to-finish on the reserved worker pool. Core isolation
keeps important housekeeping away from potentially stalled CPUs; measurement
stability is an additional benefit. Retain the current isolation setup for this
proposal.

## Proposed mechanisms

### PREEMPT and `lh_yield()`

**RX means this worker has no runnable work.** Siblings may continue running.
The runtime drains finite network-derived local work, then enters RX; handlers
and their local continuations eventually finish or wait for external events.
Self-sustaining batch work belongs outside this execution contract. Applications
release locks before blocking RX/yield and use cooperative waits for continuations
that require the worker to progress.

At idle RX, the NIC either supplies work, stalls the load, or serves PREEMPT.
After choosing PREEMPT it keeps requesting handoff until userspace calls
`lh_yield()`. The kernel acknowledges entry through a register read (the current
IpiAckReg role), completes transport drain, parks the old thread and installs the
next assignment. Explicit HW/SW synchronization events authorize state changes;
2F2F replay and parity mechanics implement the transport side of that contract.
Normal TX preserves the assignment. This replaces CAS on the cooperative path.

On resumption, yield returns mailbox identity and RX/TX parity; an index into
pre-mapped regions suffices. The runtime retains the identity until its next
yield. Implement the syscall interface as a blocking device ioctl behind the
runtime wrapper; bootstrap supplies equivalent initial state. Reserve destination
parallelism while dispatch is in flight and handle an unavailable target thread
without panicking. Activation without an outstanding userspace RX needs a kernel
wake path.

Ordinary blocking syscalls retain the core/QP assignment and onehot affinity,
including their contribution to assigned parallelism. Strongly recommend
nonblocking sockets with epoll, asynchronous storage and cooperative runtime
waits: a sleeping syscall may strand reserved capacity for an unbounded interval.
This candidate targets cooperative allocation; fairness under load and general
work-conserving multiplexing are separate policies.

Start the cooperation timeout at the committed idle-preemption decision; account
for transport delivery and kernel scheduling delay before diagnosing failure to
yield. Timeout escalates to kill IRQ; execution watchdog policy is separate.
**Signals and futex integration are deferred.** Termination/recovery is lower
priority: fence new access, complete in-flight reads and retire CPU cache state
before resetting/reusing FPGA 2F2F state. Validate that ordering when implementing
recovery; see the [existing recovery design](../software/process-recovery.md).

### HOST_WAKE: epoll subscriptions and two delivery paths

Workers subscribe through the RT API during initialization. **Each thread may
subscribe to at most one epoll instance**; multiple threads may share that instance.
Seal each thread's subscription at its first Lauberhorn registration/parking
boundary, before publishing it as dispatchable. In the current kmod this is the
worker datapath `mmap`: it queues return-to-user task work which parks the thread
after mmap locks are released. Seal even if an immediate grant avoids an actual
sleep. Later subscribe calls for that thread fail; other threads can complete
their own initialization independently.

The runtime's current worker init callback runs after datapath `mmap` returns, so
subscription setup needs a pre-registration API/hook. Publish the cookie and
subscription before activation; retain initial readiness until the thread becomes
dispatchable. The kernel keeps file references and stable per-source cookies for
the domain lifetime. Application unsubscribe is deferred to teardown. A cookie
identifies the registered epoll object rather than a reusable integer FD, and is
opaque to the NIC.

The module subscribes with `fget`, `vfs_poll` and persistent waitqueue callbacks,
checking initial readiness. On readiness it records the source as pending and
resolves the subscriber set:

- If any subscriber is hot (running or ready on an assigned worker), submit a
  targeted HOST_WAKE for that worker and domain, carrying the source cookie.
  Treat an unrelated syscall-blocked subscriber as actively running a handler:
  retain its assignment and pending wake until its next RX. **Host readiness does
  not scale up while a subscriber is hot.** A reserved/in-flight activation also
  suppresses duplicate allocation requests for that source.
- Otherwise request a core allocation for the domain with that cookie. The NIC
  selects a victim, delivers PREEMPT and reports `next_pid`, `reason=HOST_WAKE`
  and the cookie in the new core's preempt information. The kernel resolves the
  source, claims a parked subscriber and resumes it through `lh_yield()` with
  mailbox/parity details and `has_host_work=1`.

For targeted delivery, **serve HOST_WAKE directly on RX instead of popping the
network request queue**. The queue stays untouched even when full. The source
cookie associates kernel/NIC requests and allocation results; the NIC still
schedules domains and physical workers, while Linux selects threads. A direct
RX pseudo packet and the yield return flag enter the same runtime servicing path.
The userspace indication is a boolean: the recipient services its sole subscribed
epoll FD. First activation through registration must expose the same pending-work
indication as a later yield return.

Maintain pending state per source; independent epoll instances may have disjoint
subscriber sets. Coalesce repeated notifications for a source while preserving
notifications arriving during service. Kernel/NIC acceptance, delivery and clearing
acknowledgments remain to specify; a cookie supplies identity, not those ordering
guarantees. Syscall blocking may delay host-event service; this follows the
retained-core execution policy rather than triggering host-driven scale-up.

On indication, the runtime calls nonblocking `epoll_wait`, harvests/services ready
FDs and retains unfinished work before RX. Other consumers may already have taken
the events, so an empty result is valid. Application deadlines use a nonblocking
monotonic timerfd watched by registered epoll, armed for the earliest runtime expiry:

```text
Linux timer expiry -> timerfd readable -> epoll notification -> kernel/NIC wake
                   -> RX HOST_WAKE or yield(has_host_work=1)
                   -> runtime harvests events, reads timerfd, runs timers/rearms
```

Serialize shared timer collection/rearm. Event loops using epoll wait timeouts
adapt by arming timerfd before RX. Ordinary timed blocking syscalls wake their
Linux thread on its retained core. Other event sources need an explicit adapter.

#### Significant races to resolve

| Race | Required invariant / candidate resolution |
| --- | --- |
| Registration versus initial readiness | Seal each thread at registration before dispatch publication; serialize adding subscribers with callback selection, publish valid cookie/subscription state before callbacks use it, and retain pre-activation readiness. |
| Targeted wake versus PREEMPT/reassignment | Validate the expected assignment, including the selected subscriber (or an assignment generation). On mismatch, retain/retry or convert to allocation demand. Domain identity alone is insufficient when another thread of the same domain takes the core. |
| Readiness versus consume/clear | Acknowledge a particular pending generation or use equivalent atomic ordering; clearing an old indication must not erase a newer one. Retain the obligation to harvest remaining events before RX. |
| Repeated source notifications versus simultaneous grants | One epoll subscription per thread makes different source subscriber sets disjoint. Still reserve domain capacity and a subscriber against duplicate source requests and network-driven dispatch; an in-flight activation suppresses another HOST_WAKE allocation for that source. |
| Allocation request versus subscriber becoming assigned | Revalidate on grant and enforce the hot-subscriber rule. Target an already-assigned subscriber and cancel/redirect a redundant grant, or retain an exclusive reservation until acknowledged completion. Stale demand must not force dispatch of an unrelated thread. |
| Concurrent kernel notifications versus NIC control-register staging | Publish domain/cookie/target as one command, with interrupt-safe serialization and explicit acceptance/backpressure. Full wake-command storage must preserve kernel pending state. |

Stable subscriptions remove normal unsubscribe races. Hold file references and
require registered userspace FDs to remain usable through the execution lifetime.
Domain teardown/cookie reuse and general signals/futex integration remain deferred.

### Early NACK

Early NACK releases **a particular worker CPU** to service interrupts; HOST_WAKE
requests application execution. Mirror Linux architectural clock-event arming
into a NIC deadline for that CPU. On expiry, complete outstanding RX and prevent
another long stall until the event is serviced/reprogrammed. Cover replacement,
disable, clock conversion and register-write latency. Timerfd's subsequent epoll
notification supplies application activation, including for parked recipients.

For IPIs, hook the sender and request early NACK for targeted worker CPUs. Order
release and delivery so the target cannot reblock before the IPI becomes pending;
define coalescing and release/rearm acknowledgment. The architectural timer is
per-CPU, and timer migration/`nohz_full` does not remove every local timer.

Implement in the C module using native fprobe entry/exit callbacks on timer
programming/shutdown functions and a direct subscription to arm64 `ipi_raise`.
Linux 6.8 [fprobe](https://docs.kernel.org/6.8/trace/fprobe.html) exposes
`register_fprobe()`/`register_fprobe_syms()` for module callbacks; consider kprobes
where ftrace attachment is unavailable. Verify deployed-kernel configuration,
actual driver/erratum paths, already-armed deadlines and IPI paths bypassing the
tracepoint. Monitor missed callbacks and synchronize hook/device lifetime.

The **coherence timeout/NACK catchall remains** for missed hooks and unmirrored
interrupts. Hook callbacks must tolerate IRQ/locking context; NIC-control accesses
must make bounded progress independently of the stalled target. An explicit
kernel hook is the fallback if probe coverage/reliability is insufficient.
Interrupt-cancelable loads would address the underlying microarchitecture issue.

### Replace the thread router with prefixed PA and fixed VA mappings

Give each physical worker a statically colored QP signature, chosen together with
bypass. Encode domain identity in PA prefixes while preserving that worker's
**scrambled** colors across domains. Map the domain's worker aliases at fixed VAs
once; yield selects the appropriate mapping on migration. Thread count remains
the application's degree of parallelism. This replaces general thread routing
with worker decoding, owner checking and domain-qualified coherence addresses.

Kernel parking and controlled affinity/TASK_IDLE transitions ensure descheduled
registered workers do not execute. Hardware rejects access inconsistent with the
current owner; optional generic return-path assertions can diagnose accidental
kernel violations. Keep these defensive checks distinct from the scheduling
invariant.

Implementation reminders:

- Reject submissions impersonating another domain: compare descriptor PID and
  reply-context ownership against trusted endpoint assignment identity.
- Choose a feasible prefix encoding/address capacity; arbitrary high PA bits are
  not automatically color-neutral. Prove static progress including bypass and
  retiring traffic.
- Retain old ownership through drain, preserve full addresses for coherence
  responses/invalidation, and define safe rejected-access and prefix-reuse rules.
  Cache hits bypass FPGA checks, so cache-state retirement is part of handoff.

### Deferred alternative: reclaim during arbitrary blocking syscalls

If stranded capacity becomes significant, consider same-core resumption with
core-constrained wake demand, or migration through a transport stub. An immutable
stub loading shared mailbox state adds a recurring fast-path dependency; patching
a thread-specific stub pays instruction-cache synchronization on migration but
complicates stub selection, concurrent execution and parity bookkeeping.

Generic reclaim needs blocked/ready-thread accounting, persistent continuation
demand, a return-to-user ownership gate and wake/reclaim/grant synchronization.
Reject reclaim inside the transport operation: changing code/state does not repair
saved registers, and replaying TX can duplicate it. These remain follow-on options.

## Adoption route: A / AB / ABC

| Capability | Meaning |
| --- | --- |
| A | NIC/OS allocation and activation driven by network and host work |
| B | L7 decode/encode offload, including handler selection and arguments |
| C | Minimal software dispatch into the selected handler |

ONC-RPC handler selection already exists; runtime payload unmarshaling remains.
Restricted argument formats could permit direct invocation. General full
marshal/unmarshal remains an extension.

| Application | Intended adoption and adaptation |
| --- | --- |
| RPC microbenchmarks, NFS, Raft | ABC where the supported call format permits it |
| Dandelion | A or AB: outer ONC-RPC envelope offload; dispatch still establishes the sandbox |
| memcached | A: retain its event loop and connect host/NIC readiness through the adapter |

These are porting directions. Epoll subscriber sets express which workers can service each event source. Measure syscall-blocked capacity and contention in actual ports
before expanding the scheduling model.

Validate handoff/yield migration and parity restoration, direct HOST_WAKE delivery
with a full queue, wake-clear races, epoll/timerfd activation, timer/IPI early-NACK
ordering, submission authorization and cross-domain cache-state isolation. Retain
same-core resumption across ordinary syscall sleeps. Hook attachment and matched
hardware acceptance remain outstanding.

Linux 6.8 references: [eventpoll](https://github.com/torvalds/linux/blob/v6.8/fs/eventpoll.c),
[timerfd](https://github.com/torvalds/linux/blob/v6.8/fs/timerfd.c),
[scheduler](https://github.com/torvalds/linux/blob/v6.8/kernel/sched/core.c),
[hrtimer](https://github.com/torvalds/linux/blob/v6.8/kernel/time/hrtimer.c),
[arm timer](https://github.com/torvalds/linux/blob/v6.8/drivers/clocksource/arm_arch_timer.c),
[arm64 IPI](https://github.com/torvalds/linux/blob/v6.8/arch/arm64/kernel/smp.c).
