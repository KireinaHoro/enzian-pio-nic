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
PREEMPT contains no next-owner information and persists until `lh_yield()` enters
the kernel. The kernel establishes a no-return fence and issues the blocking
`PreemptInfo` read. Read acceptance acknowledges relinquishment; the response
installs the reserved destination and returns its stable parity snapshot. Any
remaining 2F2F work is NIC-internal: the kernel accesses no transport CLs, performs
no ordinary cache flush and sends no activation commit write. Normal TX preserves
assignment. See the [control and retirement interface](../software/scheduling-control-interface.md)
for MMIO boundaries, response fields, snapshot lifetime and close races.

On success, `lh_yield(&activation)` returns 0 with this calling thread's **new**
worker index, RX/TX next parity and `has_host_work`. It does not return the domain
that replaced the caller. Bootstrap provides the same activation record. Kernel
thread selection, affinity and resumption are local steps after the info response.
All domain threads are initialized before NIC capacity publication; assignments
plus reservations cannot exceed that fixed pool. Only whole-domain teardown can
end the guarantee. Idle dispatch discovery is a kernel-service implementation
obligation; normal scheduling uses no NIC IRQ.

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
priority: fence new access and complete RX/TX protocol cleanup before reusing
worker state. Retained old-domain PAs need not be generally flushed; domain-code
reuse is deferred. Validate that ordering when implementing
recovery; see the [existing recovery design](../software/process-recovery.md).

### HOST_WAKE: epoll subscriptions and delivery

Each worker may subscribe to **at most one epoll instance** through the RT API;
multiple workers may share an instance. The kernel retains its file reference
and assigns a stable, domain-associated cookie identifying the epoll object.
The cookie is opaque to the NIC. Subscriptions persist through execution;
registered userspace FDs remain usable until teardown.

Seal each thread's subscription at its initial Lauberhorn registration/parking
boundary, before making it dispatchable. In the current kmod, datapath `mmap`
queues return-to-user task work that parks the thread after mmap locks are
released. Registration seals even if immediate dispatch avoids sleeping. Provide
an RT setup hook before this boundary: the current worker init callback runs
after mmap returns. Publish valid subscription state before callbacks can use it
and preserve initial readiness until the subscriber is dispatchable.

The module subscribes via `vfs_poll` and persistent waitqueue callbacks, checking
initial readiness. For each notification it identifies the domain and source
cookie, then resolves subscribers:

- **Hot subscriber:** select an assigned subscriber and request HOST_WAKE on its
  worker core. Running and ready subscribers are hot. A subscriber blocked in an
  unrelated syscall counts as actively running a handler; it retains its core
  and receives the indication on its next RX. Host work requests another core
  only when no subscriber is hot; a reserved activation also covers that source.
- **Parked subscribers:** request a core for the domain and cookie. The NIC
  selects an available core or preempts a victim. Its preempt information carries
  `next_domain`, `reason=HOST_WAKE` and the cookie. The kernel resolves the source,
  selects a subscribed thread and completes dispatch. Yield returns mailbox/
  parity details with `has_host_work=1`; first activation exposes equivalent state.

On an assigned core, **serve the HOST_WAKE RX pseudo packet directly instead of
popping the network queue**. The network queue stays untouched, including when
full. Both the RX indication and yield flag enter the same RT routine, which
calls nonblocking `epoll_wait` on the thread's sole subscribed epoll FD and
harvests/services available work before blocking RX again. An empty result is
valid if another subscriber already consumed the readiness.

#### Scheduler ordering and pending ownership

The NIC serializes wake acceptance, network/host allocation and reassignment in
its scheduling logic. The kernel selects threads and resolves subscription
eligibility within the explicit dispatch handshake. The protocol is:

1. **Kernel pending:** record readiness per source and submit the complete
   domain/cookie/target command. Preserve pending work until NIC acceptance.
2. **NIC pending:** associate the accepted cookie with targeted delivery or a
   reserved activation. Order this against assignment changes: deliver to the
   selected subscriber before release, or preserve the cookie for subsequent
   dispatch. An accepted wake survives reassignment. Kernel thread selection
   reconciles pending source cookies before committing network- or host-driven
   assignments, preserving the hot-subscriber rule and capacity reservations.
3. **Kernel-held activation:** an info response with `has_host_work=1` transfers
   that indication into the immutable kernel return snapshot. Clear only the
   captured NIC-pending indication; a concurrent new notification wins over
   clearing. The kernel retains responsibility through local thread selection.
4. **RT responsible:** local userspace resumption transfers the saved indication
   to the runtime, without another NIC command. Direct HOST_WAKE RX delivery also
   transfers responsibility to the runtime. It retains unfinished host work until
   serviced, then returns to RX. Domain close must reconcile a kernel-held snapshot.

Coalesce repeated requests per cookie while preserving new notifications across
delivery. Source cookies distinguish independent epoll instances; duplicate wakes
are harmless. Use the scheduler's ordering points and kernel handshakes to keep
assignment and subscriber selection consistent. Specify command acceptance and
delivery boundaries during implementation. Kernel registration and control-register
submission require ordinary interrupt-safe synchronization, including backpressure
and atomic publication of multi-field commands.

Application timers use a nonblocking monotonic timerfd watched by registered
epoll. The runtime arms it for its earliest timer expiry:

```text
Linux timer expiry -> timerfd readable -> epoll notification -> kernel/NIC wake
                   -> RX HOST_WAKE or yield(has_host_work=1)
                   -> runtime harvests events, reads timerfd, runs timers/rearms
```

Serialize shared timer collection/rearm. Event loops arm timerfd before waiting
in RX. Ordinary timed blocking syscalls wake their thread on its retained core.
Other host sources use an explicit notification adapter. Domain teardown and
cookie lifetime cleanup remain part of the lower-priority recovery work.

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

The **selected replacement layout** uses [8 KiB blocks and a color-neutral XOR
PA encoding](../hardware/coherence-progress.md#concrete-domain-qualified-pa-encoding),
with RX controls at `+0x300/+0x380`, TX controls at `+0x1300/+0x1380`, twelve
overflow CLs after each pair, and no preemption control CL. For 64 colors and a
38-bit slice-relative aperture, worker masks `[2,4,6,24,26,28,30]` plus fixed bypass
mask 0 support seven workers and 524288 domain codes at the color/address level.
The 13-bit offset leaves 19 color-neutral domain bits; some require compensating
XORs, so the old 128 KiB PA bit-field formula no longer applies.

The [selected-layout model and evidence](../hardware/steady-state-verification.md#selected-8-kib-layout-evidence-and-goal-1-status)
record conditional finite-instance progress and the remaining hardware HoL gaps.
Selection does not install new production constants or establish whole-system
HoL freedom. Goal 1 is accepted as a completed bounded modeling/layout milestone;
remaining hardware-verification obligations stay explicit in the report and
[targeted DCS RTL roadmap](../research/roadmap.md#targeted-dcs-rtl-simulation-after-replacement-rtl-is-available).

Kernel parking and controlled affinity/TASK_IDLE transitions ensure descheduled
registered workers do not execute. Hardware rejects access inconsistent with the
current owner; optional generic return-path assertions can diagnose accidental
kernel violations. Keep these defensive checks distinct from the scheduling
invariant.

Implementation reminders:

- Reject submissions impersonating another domain: compare descriptor PID and
  reply-context ownership against trusted endpoint assignment identity.
- Use the checked prefix/layout candidate or re-run capacity verification for
  another geometry; arbitrary high PA bits are not automatically color-neutral.
  Complete resource-progress verification including bypass and retiring traffic.
- Retain old ownership through drain, preserve full addresses for coherence
  responses/invalidation, and define safe rejected-access and prefix-reuse rules.
  Cache hits bypass FPGA checks: retain the software no-return fence and
  protocol-local control invalidations, including freshness on a return to an
  earlier domain/worker PA (implementation check H18). This does not require a
  general flush of unrelated old-domain cache lines.

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

These are porting directions. Epoll subscriber sets express which workers can
service each event source. Measure syscall-blocked capacity and contention in
actual ports.

Validate handoff/yield migration and parity restoration, direct HOST_WAKE delivery
with a full queue, wake/assignment ordering, epoll/timerfd activation, timer/IPI early-NACK
ordering, submission authorization and cross-domain cache-state isolation. Retain
same-core resumption across ordinary syscall sleeps. Hook attachment and matched
hardware acceptance remain outstanding.

Linux 6.8 references: [eventpoll](https://github.com/torvalds/linux/blob/v6.8/fs/eventpoll.c),
[timerfd](https://github.com/torvalds/linux/blob/v6.8/fs/timerfd.c),
[scheduler](https://github.com/torvalds/linux/blob/v6.8/kernel/sched/core.c),
[hrtimer](https://github.com/torvalds/linux/blob/v6.8/kernel/time/hrtimer.c),
[arm timer](https://github.com/torvalds/linux/blob/v6.8/drivers/clocksource/arm_arch_timer.c),
[arm64 IPI](https://github.com/torvalds/linux/blob/v6.8/arch/arm64/kernel/smp.c).

## Verification boundary

Verification proceeds in two stages: fixed assignments after a settled handoff,
then scheduling transitions. The first milestone is accepted as complete for
proceeding with Goal 2 modeling and implementation, without claiming full hardware
verification. The [Goal 1 infrastructure and
evidence](../hardware/steady-state-verification.md) emits configurations for the
proposed direct physical-worker/domain decoder, without implementing this pivot.
Color-compatible capacity is separate from resource-model progress and hardware
refinement. Goal 2 must establish the settled entry contract and preserve progress
during transitions using the same resources.

The [Goal 2 scheduling models](../hardware/scheduling-verification.md) make these
ordering contracts executable and reuse the Goal 1 resources. Their bounded
component results are separate from production implementation and from full
control/transport composition.

The [implementation checks](../software/scheduling-implementation-checks.md) distinguish
concurrent races from local sequencing and list required kmod/HW assertions.
Normal handoff uses no scheduling IRQ; NIC scheduling interrupts are reserved
for application kill/recovery.
