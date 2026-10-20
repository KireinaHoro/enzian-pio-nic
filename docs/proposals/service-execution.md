# Service execution and resident applications

This unimplemented alternative describes long-lived, runtime-managed services
using raft-rs as an example. The selected next direction is
[Go toward etcd](go-runtime.md), also a design rather than a deployed Go interface.
The [managed request API](../software/programming-model.md) does not supply the
service-event and timer facilities proposed here.

## Runtime-managed services: raft-rs

The application embeds a serialized `RawNode` and lets Lauberhorn decide when
it runs. A service outlives individual incoming RPCs. Elections, heartbeats,
replication and storage completions can continue after an election-trigger
RPC has replied. This lifetime does not fit a request-owned future or forward.
The embedding supplies transport, storage and state-machine application;
raft-rs supplies the consensus state machine. Preserve its persistence ordering
and distinguish `Ready.messages` from `Ready.persisted_messages`.

### Proposed interface and ownership

Register a service instance with owned state, an event-step function and a
shutdown destructor. Registration supplies bounded mailbox and outstanding-send
limits. The runtime guarantees at most one activation per instance; separate
instances can run concurrently on isolated RPC workers. An activation receives
a bounded batch of owned events and returns when the batch or work budget ends.
All mutable `RawNode` access belongs to this activation, including `step`,
`tick`, `propose`, `ready`, and advancement. Do not call it from arbitrary I/O
callbacks or hold a runtime worker during blocking storage operations.

Events cover peer messages, local proposals/triggers, timer expiry, transport
completion and storage completion. Every admitted async operation reserves
completion capacity before submission. The runtime uses generation-tagged
operation identities and queues completion events; application code executes
only during service activation. Enqueue failure is explicit and does not
transfer ownership. Successfully enqueued events have exactly one consumer or
shutdown cleanup. Completion delivery must remain possible when the ordinary
input mailbox is full, using its pre-reserved completion capacity.

A service-owned send has no parent request and requires no fiber. Its local
admission result, transport completion and Raft protocol response are distinct:
a successful packet send is not a positive vote or committed proposal. Encode
Raft responses as ordinary peer messages. Feed definite delivery failure to
`report_unreachable` and snapshot results to `report_snapshot` as applicable;
transport completion itself must not fabricate a Raft response. Reserve an
outbox slot before extracting work which cannot yet be submitted. Retry bounded
outbox work through later activations, not a busy loop inside `RawNode`.

A SQL/proposal request which must await commit/application needs an explicit
owned reply token in the embedding. An immediate election-trigger acknowledgement
can use `REPLY` after admitting a local service event. If admission fails, return
`FAIL`; do not acknowledge work that was discarded. Neither pattern requires
restoring the removed arbitrary nested-RPC callback API.

### Timers and scheduling

Provide monotonic one-shot deadlines and periodic ticks, identified by service
and generation. Cancellation/rearm invalidates stale events. Coalesce periodic
ticks with elapsed-tick accounting; do not silently treat a long deschedule as
one interval. The embedding decides how many bounded tick operations to process
per activation, retaining remaining elapsed work for subsequent activations.
Timers enqueue events; they never run application code in a signal handler.

Existing ECI RX polling has a configurable NACK timeout. It lets an assigned
worker regain control; it neither wakes a parked service nor provides a timer
API. A complete implementation needs a kernel/FPGA activation path for software
runnable work and the earliest timer deadline. Keep timer policy in software;
a hardware timer wheel is not required. Add an explicit service-runnable
notification/control path and integrate it with scheduler allocation and
preemption acknowledgements. No RX/TX fast-path ioctl or concurrent signal-based
access to an ECI QP is permitted. Review the
[coherence progress contract](../hardware/queue-pair-design.md#motivation-avoid-head-of-line-blocking) before changing
ownership, ordering or cache-line accesses.

Deschedule only between bounded service activations, after publishing ready
work and releasing application locks. The worker must check runnable work before
blocking again. An activation is cooperative: a long `step` or `Ready` processing
sequence is not transparently preemptible. Storage and snapshot streaming must
use asynchronous completion events or separate ordinary threads, outside the
isolated worker execution path. Large messages/snapshots require a separately
specified bulk transport; the current single-MTU RPC interface is insufficient.

### Shutdown and future acceptance

Stop new admissions, cancel timers, fence activation generations, drain or
explicitly fail sends and reply tokens, and wait for hardware retirement before
destroying service state. Software timeout alone is not a drain guarantee; this
requires the [full recovery contract](../software/scheduler.md#failure-and-reset-boundary),
which the current fencing-only implementation does not provide. Test mailbox/outbox saturation, completion reservation, duplicate
and stale events, wakeup races, elapsed ticks and shutdown with pending storage.

Build a raft-rs harness with serialized-access assertions and a deterministic
clock: idle tick-only leader election, dropped/reordered peer traffic, a trigger
reply preceding election completion, and proposals acknowledged only after the
configured commit/apply condition. Verify parked-service activation on hardware.
An in-memory `MemStorage` harness establishes functional integration only; durable
restart, snapshots and full database semantics require separate validation.

Primary design references: [raft-rs](https://github.com/tikv/raft-rs),
[`RawNode`](https://github.com/tikv/raft-rs/blob/master/src/raw_node.rs).
Pin the chosen revision before implementation and recheck those contracts.

## Relation to native applications

The former resident-worker memcached proposal is superseded by the
[native UDP design](../applications/memcached.md). Its maintenance requirements and
[HOST_WORK proposal](host-work.md) are distinct from implementing the
service-event API above.
