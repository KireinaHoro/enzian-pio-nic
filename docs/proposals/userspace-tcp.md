# Shared userspace TCP for native applications

**TCP proposal; VM infrastructure implemented.** Support TCP memcached with one userspace TCP
stack per application, shared by its one to four request workers. Hardware
classifies and transports segments; software owns all TCP connection state,
ACK generation, reassembly, flow/congestion control, retransmission and close.
This does not integrate Flavian's [split TCP state engine](../research/tcp.md).
Only independently useful header/checksum logic is a candidate for reuse.

## Hardware packet dispatch and transport

Mirror [native UDP memcached](../applications/memcached.md): a privileged bind
registers a local TCP listen port against an application domain and endpoint
handle. TCP and UDP port namespaces are distinct. On the configured local IPv4
address, match **every TCP segment's destination port**, not just SYNs. Deliver
matching SYN, ACK-only, data, FIN and RST segments through ordinary domain
scheduling to any assigned worker, distributing traffic across available
workers without connection affinity. Unmatched traffic retains bypass routing.
The binding remains valid while accepted connections and close processing need
it; stopping new accepts must not remove delivery for existing connections.

Validate IP/TCP lengths, header bounds and checksums before host admission;
initial scope is MTU-bounded IPv4 without IP options or fragmentation. Preserve
the complete TCP header, options and payload, including zero-payload segments.
Expose source/destination IP context, endpoint identity, length and checksum
status alongside the borrowed packet. The stack adapter supplies a normal IP
packet view if its input API requires one. Hardware does not update sequence
state, acknowledge bytes, reorder segments or maintain a connection table.

TX accepts the software-generated TCP header/options/payload and remote IP,
validates the bound local endpoint, and supplies IP/Ethernet framing and TCP
checksum completion. Retain existing neighbor resolution and bounded TX
admission semantics. Regenerate descriptors, headers and mappings together;
define new native-TCP RX/TX variants without importing the split-offload ABI.
Preserve native UDP, ONC-RPC and bypass configurations.

## Shared execution and buffer ownership

Any receiving worker enters the shared stack and completes the segment's TCP
processing on that worker. There is no fixed connection owner, per-owner
forwarding queue or extra stack thread. Short connection locks serialize updates
to the same TCP control block; unrelated connections can progress concurrently.
Shared lookup/listener structures and connection references must remain safe
against concurrent accept, close, timer execution and packet arrival.

Release transport locks before running memcached handlers. Serialize each
connection's parser/response state separately: an available worker may claim
ready application work, while another worker can still process ACKs, receive
bytes and service timers for that connection. Bytes arriving while its parser is
busy remain in the normal stream receive buffer. Pending application readiness
must survive races between callback publication and parser release.

Start with stack-owned RX copies where data can outlive input processing, and
stack-owned retransmission buffers for TX. Release ECI borrows before another
blocking RX or yield. Local TX completion permits transport-buffer reuse; peer
ACK controls retransmission-buffer retirement. Avoid retaining memcached item
references across transmission initially by copying response bytes into the
stack's send buffer.

The [cooperative scheduling boundary](../software/scheduling-control-interface.md)
is essential: RX DESCHED requests relinquishment; the worker actually hands off
only when it calls `lh_yield()`. It does not preempt a running handler or stack
critical section. Release all stack/application locks, RX borrows and TX
reservations before blocking RX or yield. After DESCHED, yield immediately
without further transport access. Ordinary syscall blocking retains assignment;
critical sections must not contain blocking operations.

## DEADLINE execution and scheduling (proposed)

Software keeps connection timers in shared application state and publishes its
minimum absolute deadline to the device. **DEADLINE** is a domain-scoped,
payload-free notification that this minimum has expired. It contains a domain
lifetime and publication generation, not a connection or worker identity.
Application workers execute callbacks; the device only records expiry and
schedules service. No helper TCP thread or asynchronous handler preemption is
introduced. Hardware implementation belongs to phase 4 alongside raw TCP
segment steering; phase 2 first emulates the agreed policy in the VM.

Queue placement and core allocation are separate decisions:

| Policy | Response after expiry | Fairness and cost |
| --- | --- | --- |
| Append to packet FIFO | Waits for the packets already ahead of it, plus core allocation and the current handler | Preserves FIFO order, but a full queue needs reserved admission; packet count does not bound execution time |
| Reserved slot selected before packets | Removes queued-packet delay at the next eligible RX boundary; still needs a core | Strict repeated priority can starve packets, including ACKs needed by TCP; bound service batches |
| Reserved slot plus cooperative core reclaim | Also makes a zero-core domain eligible to take an idle or RX-ready core | Improves progress under competing traffic; repeated urgent requests need fair arbitration to avoid starving other domains |

The recommended starting policy is the third, with bounded priority. Keep one
reserved pending slot per domain outside the packet FIFO, so a full packet
queue cannot suppress expiry and duplicate expiries do not consume extra slots.
A worker selects a pending DEADLINE before the next queued packet, except that
continued overdue work alternates bounded timer and packet batches when both
are ready. Use a domain-wide claim so multiple workers cannot consume the same
notification concurrently. Already accepted packet delivery is not withdrawn.
Connection locks still serialize actual callbacks with packet processing;
unrelated callbacks may execute concurrently on different workers.

If a domain has no assigned or reserved core, pending DEADLINE participates in
core allocation without requiring packet arrival or the queue watermark. Prefer
an idle core, otherwise request an RX-ready core through ordinary DESCHED/yield.
Request at most one core for deadline-only work; packet backlog retains the
existing scaling policy. If the domain already has a core, notify it at its
next loop/RX boundary without taking more cores solely for deadline work.
This deliberately does not rescue an application whose only assigned worker
is indefinitely blocked in a syscall or handler.

Arbitrate deadline activation and packet-driven allocation fairly; neither
class has permanent priority. Rotate among eligible deadline domains and keep
requests pending when no core is available. Repeated rearming must not reset a
domain's position to the front. Bound each timer batch and allow packet service
and competing-domain handoffs between batches. These are service-opportunity
fairness rules, not equal CPU-time guarantees: handlers still run cooperatively.
The exact arbitration weights and batch limits are VM design parameters owned
by Nix, to be chosen before implementing the hardware policy.

A useful decomposition is: expiry-detection delay + allocation/handoff delay +
time to the next safe worker boundary + local dispatch/connection-lock delay.
Head selection reduces local backlog; cooperative reclaim reduces allocation
waiting. Neither bounds a running handler, syscall sleep or lock holder.
Punctual response therefore requires bounded cooperative work and sufficient
capacity. Hard real-time deadlines would require a different execution and
admission contract. TCP connections can survive finite pauses, but not arbitrary
starvation beyond their own or their peer's timeout policy.

### Publication and acknowledgement

DEADLINE remains pending until an explicit generation-checked acknowledgement
or superseding publication; core reservation, activation and descriptor delivery
alone must not clear it. Delivery claims suppress duplicate notifications while
service is in flight. Complete service with an atomic acknowledgement/rearm of
the next minimum (or cancellation when no timer remains). A newer publication
must survive an old acknowledgement, and publishing an already expired minimum
must leave the domain runnable. An earlier timer insertion must publish before
its worker can park; recheck shared timer state around publication to prevent a
concurrent older minimum from replacing it. Serialize control publication
outside connection locks. Exact register packing and clock conversion remain
phase-2 ABI design work.

Failure to acquire a connection lock leaves the original deadline overdue;
it is not cancellation or successful service. Bounded dispatch rotates among
due timers, retains the remaining work, and retries without needing a packet.
It must also give packet processing and other domains service opportunities.
Release all stack/application locks, RX borrows and TX reservations before
blocking RX or yielding. Timer-only activation permits ACK/retransmission TX
without a packet or RPC context. This interface is distinct from internal RPC
timeout polling and [HOST_WORK](host-work.md).

### Fit with the current scheduler

The intended changes to [Scheduler.scala](../../hw/src/lauberhorn/Scheduler.scala)
are localized; the current scheduler does not implement this policy:

- Keep deadline state beside per-domain queue metadata, leaving `queueMem`,
  packet head/tail accounting and packet-buffer allocation unchanged.
- Include pending deadline work in the domain-runnable predicate. The existing
  `drainLookup` discovers domains with no reserved core, but its consumer is
  behind packet dispatch and gated by `!rxDeschedReq.valid`; simply adding a
  pending bit there cannot ensure progress under continuous traffic.
- Feed a persistent deadline activation request into fair arbitration with
  packet-driven allocation. Reuse idle-first `DeschedCmdType.ready` victim
  selection, `corePidMap` reservations, `drainProcInProgress` protection and
  `coreDesched` handoff. Revalidate enabled state, lifetime and core count at
  grant. The existing `force` value is legacy kill intent, not timer priority.
- Add a per-domain claim and bounded selection between a DEADLINE descriptor
  and packet popping at the per-core idle dispatch boundary. Hold the selected
  descriptor stable under backpressure. Keep DESCHED and failure fencing ahead
  of delivery. A payload-free descriptor must traverse RX without allocating
  or freeing a packet buffer; descriptor encoding and transport handling must
  be updated together.
- Clear state on domain disable/failure and reject stale lifetime commands.
  Add tracing for expiry, selection, activation, delivery and acknowledgement
  so allocation delay can be distinguished from callback delay.

`softwarePending` is legacy scaffolding from commit `b11c85a6` (September 17,
2026): a housekeeping thread used `KICK_PROCESS`/`wake_idx` to request a core
for expired nested RPC calls. It clears on core residency rather than timer
service. Commit `2fd67e37` excluded its control register from cooperative ECI;
the current kernel rejects `KICK_PROCESS`. It is not an active ECI deadline
implementation and must not be relabeled as the proposed acknowledged slot.
Existing first-set-bit arbitration and background lookup are not evidence of
starvation freedom; the new arbitration needs explicit finite validation.

### VM policy checks before hardware implementation

Exercise a full packet queue at expiry, repeated immediate rearming, concurrent
publication/acknowledgement, expiry during sleep entry and contended callbacks.
Compare FIFO placement with reserved-slot selection using the same controlled
workload. Alternate bursts between two applications, observe each lose every
core, and require timer-only reactivation and stream progress on their existing
connections. Include a packet-only competing domain to expose deadline-induced
starvation. Report expiry-to-notification and expiry-to-callback separately,
along with packet progress and handoff counts. Long-handler cases demonstrate
the cooperative limit rather than claiming a deadline latency guarantee.

## Stack selection and acceptance

Start from vanilla FreeBSD and build a Lauberhorn userspace port of its TCP
stack. Preserve FreeBSD's connection/socket synchronization and lifetime
contracts while adapting execution to Lauberhorn workers. Pin the FreeBSD
source revision when establishing the port.

Reuse suitable parts of F-Stack's build system, source-selection/import machinery,
host interfaces, stubs and initialization scaffolding. Audit each reused stub:
facilities outside the supported paths may be omitted, but locking, reclamation,
allocation and timer cancellation require working semantics. Provide shared
process/socket and VNET state, per-worker thread context, safe reclamation,
allocator semantics and shared callouts. F-Stack's isolated per-thread stacks
and stubbed synchronization do not implement this contract.

Describe the result as a FreeBSD userspace TCP port for Lauberhorn, with selected
scaffolding derived from F-Stack and its attribution retained. It does not use
DPDK or F-Stack's execution model.

Define the stack's concurrent-entry API and timer arm/ack/cancel ABI during VM
development, then the memcached readiness adapter before application integration.
Acceptance must cover four
workers with same-connection packet spraying, concurrent connections, loss and
reordering, close/timer races, buffer exhaustion, timer-only wake from a parked
domain, and TCP progress while another worker runs a long handler. Confirm that
DESCHED/yield never carries locks or transport borrows across handoff. Compare
TCP memcached behavior with upstream and report CPU costs including any helper
work; retain the existing application's maintenance/lifecycle scope limits.

## Development phases

The TCP phases remain planned work. The software-only backend is shared infrastructure
for Lauberhorn applications, not a TCP-specific replacement runtime.

### Phase 1: shared kernel module and runtime with VM emulation

The compile-time VM backend shares kernel registration/activation and runtime
dispatch with ECI. Its kernel-thread device emulator supports native UDP,
ONC-RPC and nested RPC through mapped buffers and transport ioctls. Nixpkgs
NixOS tests exercise these paths and UDP memcached. See the
[VM backend contract and validation](../software/vm-backend.md) for its bounds
and lifecycle limitations. Native TCP and domain timer activation remain phase 2.

Start from the current kernel module and runtime, with a compile-time backend
selection for real hardware or software-only VM execution. Share application
and endpoint registration, worker-pool lifecycle, activation bookkeeping,
runtime dispatch and buffer-ownership checks. Isolate hardware operations behind
the backend boundary. Reuse ideas from Sven's mock, including mapped buffers and
RX/TX ioctls that emulate 2F2F; do not carry forward its separate, older runtime
or mmap-based worker registration and parking.

Steal matching packets from the normal host network receive path and retain
ordinary host handling for unmatched traffic. Emulate all hardware actions in a
kernel thread: classification and delivery, queues, scheduling decisions,
transport completion, notifications and device timer events. Network hooks
enqueue work for that emulator. The kthread models the device; application
workers execute handlers and, in phase 2, TCP processing and TCP timer callbacks.
Emulation preserves explicit registration/bootstrap yield, RX DESCHED followed
by yield, activation identity, RX borrow and TX reservation lifetimes, and
bounded admission. Ordinary syscall blocking must retain the assignment.

The goal is to support all Lauberhorn applications in a software-only VM through
the shared runtime. Acceptance covers the supported application/transport
matrix, including native UDP, ONC-RPC, nested RPC and bypass, plus multi-worker
activation, handoff and buffer-exhaustion cases. Device emulation establishes
functional behavior; it does not establish ECI coherence or hardware timing.

### Phase 2: shared FreeBSD TCP library and validation in the VM

The source/build checkpoint lives in the separate `lauberhorn-tcp` repository.
It imports only selected vanilla FreeBSD TCP sources and their transitive
headers, with a pinned revision and per-file hashes. Its source-owned Nix recipe
builds a development core archive, not a runnable userspace stack. The completed
TCP library is an optional component alongside the runtime: application Nix
recipes explicitly include both packages, and the runtime does not depend on
TCP. Shared callouts, safe reclamation and the remaining host interfaces must
be implemented before exposing that application library or running TCP VM
acceptance.

Develop the FreeBSD userspace TCP library against the emulation backend, reusing
the selected F-Stack scaffolding described above. Implement
the proposed native-TCP transport and domain timer interfaces in the emulator,
and integrate callable packet/timer processing with the shared runtime. Use
one stack per application with one to four workers and stack-owned RX/TX copies.

Exercise TCP independently of memcached with a normal TCP link partner:
connection establishment, bidirectional stream transfer, ACK-only traffic,
flow control, loss/retransmission, reordering, FIN and RST. Inject concurrent
same-connection arrivals across workers, independent connections, accept/close/
timer races, allocation and TX exhaustion, earlier-deadline rearming, and expiry
during sleep entry. Require timer-only activation with all workers parked and
TCP progress while another worker runs a long handler. Validate stream contents,
connection lifetime, readiness delivery and ownership at DESCHED/yield boundaries.

Keep established connections open in two applications while alternating bursts
force each application to lose all worker assignments. Verify the zero-worker
intervals explicitly, then require both applications to resume stream progress
on the same connections. Include an outstanding retransmission whose deadline
expires while its application has no workers and receives no new packets.
Expiry must remain pending and make the domain eligible for activation; after
the scheduler grants a worker, dispatch must service the overdue timer. This
requires eventual worker service, not execution at the exact deadline while
the application has no workers. Bound the test's preemption intervals below
the configured connection timeout and check for starvation under competing load.

### Phase 3: TCP memcached port and validation in the VM

Port our existing memcached tree to the shared Lauberhorn TCP stack. Implement
TCP support in place alongside that tree's Lauberhorn UDP support, preserving
the UDP path and using the same application source and packaging. Integrate
memcached's stream parser, response buffering and readiness handling with the
stack validated in phase 2. Keep application parsing separate from TCP critical
sections and preserve readiness while a connection's parser is busy.

Run the port on the Lauberhorn emulator in the VM with normal TCP clients.
Compare behavior with upstream memcached, including partial commands,
pipelining, backpressure and concurrent connections. Exercise connection close,
timer progress and multi-worker concurrency through the application, and retain
UDP regression coverage. This establishes the application port before real
hardware integration.

### Phase 4: Lauberhorn integration and TCP/memcached validation on Enzian

Implement the native-TCP classification, descriptors, RX/TX transport, endpoint
validation and the VM-designed DEADLINE mechanisms in Lauberhorn hardware, kernel
module and runtime. Connect the VM-developed stack and the memcached port from
phase 3 through the real backend. This phase includes the missing Lauberhorn
functionality, not just deployment of the library onto Enzian. Regenerate ABI
collateral and deploy matching hardware/software artifacts.

Build with the real runtime/kernel backend and repeat both the stack-level
functional/concurrency cases from phase 2 and the memcached application cases
from phase 3 end to end on Enzian with normal TCP peers and clients. Validate
real buffer ownership, scheduling handoff, timer-only wakeups and transport
progress, while preserving existing application transports, including
Lauberhorn UDP memcached. VM results do not substitute for these hardware
integration checks.

After functional acceptance, evaluate TCP memcached throughput, latency and
total CPU costs on Lauberhorn, including any helper work, within the
application's existing maintenance and lifecycle scope.
