# Shared userspace TCP for native applications

**Proposal; not implemented.** Support TCP memcached with one userspace TCP
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

## Timer execution and progress

Maintain software timers in shared application state. Publish the earliest
deadline to a domain-scoped hardware timer. Expiry creates sticky, coalescible
application work: release any eligible worker from blocking RX, or request
ordinary domain activation if all application workers are parked. It must work
without network arrivals and must not target a connection's previous worker.
Timer expiry does not interrupt a running handler.

At worker-loop boundaries, service due timers and ready transport work fairly
alongside packets and bounded batches of memcached commands. Any worker may
claim an expired timer; serialize its connection update with segment processing.
Drain/acknowledge the pending event and rearm the earliest remaining deadline
with a race-safe generation/recheck protocol. Inserting an earlier deadline,
concurrent rearming, close/cancellation and expiry during sleep entry must not
lose a wakeup or access a freed connection. Packet arrivals must not starve due
timers. A timer-only activation must support ACK/retransmission TX without an RX
packet or request context.

Progress requires an available worker, fair timer dispatch and finite critical
sections. One busy handler must not prevent another worker from advancing TCP.
If all assigned workers are executing handlers, transport waits until one
reaches a loop boundary; hardware timer precision does not bound that delay.
This proposal adds no asynchronous handler preemption. The domain timer ABI is
new work, distinct from existing internal RPC deadline wakeups and
[HOST_WORK](host-work.md).

## Stack selection and acceptance

Use F-Stack's FreeBSD userspace extraction as the starting point, replacing its
DPDK integration and compatibility-layer concurrency semantics. Retain the TCP
implementation and provide real locking, shared process/socket and VNET state,
per-worker thread context, safe reclamation, allocator semantics and shared
callouts. F-Stack's isolated per-thread stacks and stubbed synchronization do
not implement this contract. Use vanilla FreeBSD as the semantic reference;
pin the source revision when establishing the port.

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

These phases are planned work. The software-only backend is shared infrastructure
for Lauberhorn applications, not a TCP-specific replacement runtime.

### Phase 1: shared kernel module and runtime with VM emulation

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

### Phase 2: shared F-Stack library and TCP validation in the VM

Develop the non-DPDK F-Stack library against the emulation backend. Implement
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

### Phase 3: Lauberhorn integration and Enzian end-to-end validation

Implement the native-TCP classification, descriptors, RX/TX transport, endpoint
validation and domain timer/wakeup mechanisms in Lauberhorn hardware, kernel
module and runtime. Connect the VM-developed stack through the real backend;
this phase includes the missing Lauberhorn functionality, not just deployment
of the library onto Enzian. Regenerate ABI collateral and deploy matching
hardware/software artifacts.

Build with the real runtime/kernel backend and repeat the stack-level functional
and concurrency cases end to end on Enzian with a normal TCP link partner.
Validate real buffer ownership, scheduling handoff, timer-only wakeups and
transport progress, while preserving existing application transports. VM
results do not substitute for these hardware integration checks.

### Phase 4: TCP memcached integration and evaluation

Integrate memcached's stream parser, response buffering and readiness handling
with the validated shared stack. Keep application parsing separate from TCP
critical sections and preserve readiness while a connection's parser is busy.
Test against upstream memcached behavior with normal clients, including partial
commands, pipelining, backpressure and concurrent connections. Evaluate on
Lauberhorn after stack-level acceptance, reporting throughput, latency and total
CPU costs, including any helper work, within the application's existing
maintenance and lifecycle scope.
