# Scheduler design pivot (September 2026)

Status: **proposal**, consolidated 2026-09-25. Mechanisms below are implementation
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

### HOST_WAKE

HOST_WAKE indicates host work is ready; event details remain in Linux/runtime
queues. Maintain a pending wake indication. **When serving RX with a wake pending,
serve HOST_WAKE directly instead of popping the network request queue.** The
queue remains untouched, so even a full queue cannot prevent wake delivery.
Serialize consuming/clearing the indication with new notifications; coalesce
notifications while preserving pending work. Pending wake also requests execution
capacity when its recipient is parked.

**Open for the next design discussion:** whether the recipient is a domain or a
specific worker thread. Any-worker delivery requires that worker to service or
dispatch the event; thread-owned event loops may need targeted activation. Settle
this before choosing wake-state indexing and kernel/NIC selection semantics.

Register the application's epoll FD(s) with the module. It retains the files with
`fget` and subscribes via `vfs_poll` and persistent waitqueue callbacks, checking
initial readiness. Callbacks notify the NIC through a kernel control register.
Linux's epoll poll-wait notification works even without a sleeping epoll thread;
a generic scheduler-wakeup hook alone misses that case.

On HOST_WAKE, the runtime calls nonblocking `epoll_wait`, harvests/services ready
FDs and retains unfinished work before RX. This keeps epoll off ordinary network
iterations. Application deadlines use a nonblocking monotonic timerfd watched by
registered epoll; the runtime may arm it for its earliest userspace timer expiry:

```text
Linux timer expiry -> timerfd readable -> epoll notification -> NIC HOST_WAKE
                   -> runtime harvests events, reads timerfd, runs timers/rearms
```

Serialize timer collection/rearm where shared. Event loops using epoll wait
timeouts adapt by arming timerfd before RX. Ordinary timed blocking syscalls wake
their Linux thread on its retained core. Other host sources need an explicit
notification adapter. Registration lifetime, rearm races and eventual teardown
remain implementation checks.

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

These are porting directions. HOST_WAKE ownership remains central to event-loop
compatibility. Measure syscall-blocked capacity and contention in actual ports
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
