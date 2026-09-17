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
