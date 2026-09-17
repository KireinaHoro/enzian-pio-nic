# Process recovery and execution watchdog

Approved B4 design, September 17, 2026. **Implementation in progress** on
`feat/nested-rpc`; this document is not evidence of hardware acceptance.

## Lifecycle and ownership

A process transitions from running to stopping, draining, then retired.
Stopping prevents new admission and dispatch. The kernel stops all its workers,
including parked workers and pending scheduling work, before hardware resources
are reclaimed. Outstanding ECI transactions must complete, queued packets must
be freed, and incoming reply contexts and outgoing completion entries must be
retired before mappings, process slots or endpoint ports can be reused.
Repeated stop requests join the same operation. Unexpected exit and SIGKILL
must initiate recovery without waiting for parked threads to release mappings.

Explicit software termination, execution watchdog expiry and preemption
critical-section failure use the same whole-process recovery mechanism.
Disabling execution monitoring does not disable reclamation or ECI recovery.
Application cooperation and successful delivery of UDP error replies are not
prerequisites for forced termination. A missing FPGA coherence response is a
protocol bug to fix, not an accepted quarantine/reset recovery result.

Internal ownership metadata may grow; host request tokens remain opaque 32-bit
values and descriptor sizes/inline capacities remain unchanged. Reclaiming
incoming contexts requires admission to be stopped and old transactions drained;
a table clear alone is not a process-stop acknowledgement.

Nested timeout and cancellation settle local interest exactly once and retire
hardware routing state before releasing endpoint reservations. Cancellation
does not undo execution at a remote peer. Deadline progress must work without
new packets or actively polling workers. Internal deadline wakeups do not
implement the deferred public Raft timer/service scheduling API.

## Optional execution watchdog

The watchdog detects hung execution; it is not CPU billing or a real-time
reservation scheduler. Configuration is per process, fixed before workers start,
with an enable flag and execution limit. Default: disabled. A shared clock and
per-physical-worker deadline/active state avoid timers proportional to process
table capacity. Checks live alongside per-core scheduling logic, independently
of readiness, packet arrivals or demand for a victim.

When enabled, existing RX/TX handshakes carry execution boundaries where useful.
Submitting nested calls and awaiting already-ready results do not refresh the
deadline. An actual suspension ends an interval; resumed execution receives a
fresh interval. Immediate inline-to-fiber deferral can retain the interval.
Control-only TX is reserved for otherwise invisible software transitions; adjacent
end/begin transitions can share a command. Commands are applied locally, not
after network transmission. Approximate boundaries may include runtime overhead
or leave small housekeeping gaps; do not reorder destructors solely for exact
accounting. Completion arrival is not equivalent to a particular fiber resuming.

When disabled, the runtime emits no watchdog-only TX commands and hardware never
terminates on execution-budget expiry. Explicit kills, nested-call deadlines and
ECI/preemption critical-section timeouts remain independent and operational.

## Trace and paper rationale

Extend existing scheduler/ECI/kernel trace events with process lifetime, worker,
request/operation identity and termination reason. Add events only for missing
execution/ownership transitions or boundaries of variable waits. Avoid duplicated
events separated only by deterministic pipeline delays. Reconstruct queueing,
dispatch, execution, nested suspension, preemption/drain and kernel handoff;
report trace loss and incomplete intervals. Count watchdog-only transactions to
quantify inter-packet-gap overhead as well as end-to-end latency.

Paper framing: run-to-finish execution with a conventional configurable watchdog.
Elapsed-time comparison is policy; execution notifications and safe process
quiescence/reclamation are mechanisms. More elaborate policies can reuse these
mechanisms with additional accounting. This is an extension point, not a claim
that arbitrary policies or exact CPU-time measurement are implemented.

## Acceptance

Exercise idle/active shutdown, parked workers, SIGINT/SIGTERM/SIGKILL, repeated
stops, late replies, cancellation races, timeout with no traffic, and kills during
RX/TX overflow and invalidation. Demonstrate resource-capacity restoration and
service restart without reset while unrelated services continue operating.
Repeat explicit-kill and critical-section recovery with the watchdog disabled;
verify zero watchdog-only TX and no budget-triggered kills. Native/RTL checks
precede a matching-bitstream Enzian campaign; only the latter closes B4.

## Implementation and review boundaries

The current B4 implementation adds a process-stop path to the per-core pop FSM.
Disabling a process withdraws only unaccepted descriptors through an explicit
cancel/discard handshake, drains its queue, and switches every resident worker
to PID zero. Admission-to-scheduler counters include descriptors in decode/DMA;
process readback reports draining until these, queue reclamation and both old
and reserved next worker assignments are clear. Service lookup results are
revalidated after disable. Incoming contexts remain 32-bit tokens.

The kernel keeps the old prefix routed until `datapathDrained`, then unroutes it.
A stop retains the process slot through final device/VMA release, retires all
outgoing entries by PID, waits again for late admitted completions, reclaims
incoming contexts and releases endpoint sockets/classifiers. Explicit stop,
hardware `killed`, worker unmap and fatal signals converge on this path. A 10 ms
kernel monitor notices fatal signals on parked `TASK_IDLE` workers. It is not
an execution-budget scanner. IRQs interrupting kernel code use return-to-user
task work rather than parking a task while it may hold an ioctl lock. A drain
failure remains a diagnostic failure with ownership retained, not successful
recovery or a reset-based acceptance result.

`lauberhorn_set_watchdog(ctx, limit_us)` configures an optional per-process limit
before any worker is created. Zero disables it. The kernel converts microseconds
to cycles and rejects limits at least half the 32-bit clock range. Each physical
worker has active/deadline state driven by a shared clock. Expiry disables the
whole process and requests the same stop handshake independently of RX readiness.

Two reserved bits at TX positions 19–20 encode end=1, begin=2 and combined=3.
RPC scalar alignment, request-token size and inline capacities are unchanged.
Descriptor type 7 is a zero-length local control transaction, acknowledged without
DMA or network encoding. A reply/fail TX and a forwarded-call TX carry end.
Ordinary child submission carries no refresh. Incoming-call dispatch begins an
interval; completion delivery alone does not. Immediate deferral inherits the
inline interval. After an actual fiber suspension, end is held pending: the next
fiber start combines end/begin, an outcome TX absorbs end, or the runtime emits
one end-only TX before polling RX. A resumed fiber emits begin before executing.
An already-ready await does not switch contexts or refresh the interval.
Disabled monitoring skips these notifications in the runtime and expiry in RTL.

A runtime housekeeping thread observes pending deadlines every 10 ms and uses
an internal control ioctl to request a worker when a call has expired. It never
settles calls or executes handlers itself. Workers retain the timeout sweep and
forwarding code. The scheduler can activate an idle worker or switch a ready
worker without an incoming packet; an active non-yielding worker remains subject
to the optional execution watchdog. This does not implement the deferred public
Raft service/timer API. Applications must still consume futures before returning;
automatic cleanup of an abandoned future on ordinary handler return is not added.

Native and RTL results must be recorded separately from the matching Enzian
campaign. The `recovery-test` application deliberately uses private interfaces
to hang handlers, leave BUSY set, or abandon dirty TX. It is a fault fixture,
not an application API example.

A multi-core RTL stress failure also exposed an existing TX ownership bug:
`DmaControlPlugin` latched an arbitrated request whenever it was valid, including
while backpressured behind another transaction. It now latches only on acceptance.
This preserves the DMA buffer, completion cookie and acknowledgement destination
until the accepted operation finishes. The saved regression uses setup seed
1838538013 and simulation seed 1900081430 in `rx-sched-idle-scale-many`.

## Validation snapshot (2026-09-17)

The targeted RTL regression passed 15 tests across watchdog, reply-context,
nested-RPC and ordinary-RPC suites; two pre-existing empty tests are now explicitly
ignored. This covers active/critical-section/dirty-uncommitted-TX stop and reuse,
queue/context reclamation, outgoing retirement, deadline wake without traffic,
watchdog suspension/resumption and multi-core dispatch. The saved TX ownership
reproducer also passed independently. A subsequent focused watchdog regression
passed after adding the sticky process-kill latch: stale enable/maxThreads writes
cannot resurrect an expired process; configuration of a drained new lifetime can
reuse the slot.

Native runtime tests and cross builds of runtime, kernel module and the recovery
fixture passed before the final sticky-latch change; the matching deployment
build rechecks the final source. Logs are under `out/b4-20260917/`. These are
simulation/build results, not Enzian acceptance. Synthesis, timing and the no-reset
board recovery campaign remain required.
