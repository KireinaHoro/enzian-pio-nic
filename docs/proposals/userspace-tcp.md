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

Prefer a stack with concurrent shared connection state and callable packet/timer
processing. Audit a FreeBSD userspace extraction (for example, F-Stack's stack
and compatibility layer without DPDK) for preserved locking, thread context,
allocator and timer semantics. mTCP's per-context thread model needs redesign
for this contract; a globally serialized lwIP instance does not provide parallel
transport processing. No source revision is selected yet.

Resolve the stack's concurrent-entry API, memcached readiness adapter and the
timer arm/ack/cancel ABI before implementation. Acceptance must cover four
workers with same-connection packet spraying, concurrent connections, loss and
reordering, close/timer races, buffer exhaustion, timer-only wake from a parked
domain, and TCP progress while another worker runs a long handler. Confirm that
DESCHED/yield never carries locks or transport borrows across handoff. Compare
TCP memcached behavior with upstream and report CPU costs including any helper
work; retain the existing application's maintenance/lifecycle scope limits.
