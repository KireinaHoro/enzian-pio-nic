# Go runtime integration toward etcd

Status: **design only**, October 13, 2026. This records the maintainer discussion;
it is not an implemented Go port, HOST_WORK interface or board result. The
current task ends with documentation and terminology changes. Do not begin
runtime, kernel or RTL implementation from this handoff alone.

The first implementation stage will adapt Go, implement HOST_WORK, demonstrate
memcached hash expansion, and run a native Go null-RPC demo with goroutines and
nested calls. Porting the Go Raft core and then etcd itself, including its client
interface, are later stages. The older [raft-rs service proposal](../software/service-execution-design.md)
is historical design context, not the selected Go implementation.

## Scope and baseline

Keep the current isolated worker-core–endpoint binding, physical-worker address
map and domain ownership checks. Allowing the same endpoint to be serviced from
arbitrary CPUs, or handing its lease between OS threads outside activation, is
not selected for this stage. Ordinary Linux tasks still do not share the isolated
worker cores. Scheduling Gs within an assigned application's Go runtime is a
separate operation from Linux scheduling or hardware DESCHED.

Build Go bindings to **Lauberhorn native RPC**, not standard-library `net/rpc`
or grpc-go compatibility. [etcd/raft](https://github.com/etcd-io/raft) leaves
network and disk I/O to its embedding. Etcd's
[peer transport](https://github.com/etcd-io/etcd/tree/main/server/etcdserver/api/rafthttp)
and [gRPC client interface](https://etcd.io/docs/v3.6/dev-guide/api_grpc_gateway/)
are later adapter work; this demo does not establish either transport's support.

The [current scheduling contract](../software/scheduling-control-interface.md)
and [transport ownership contract](../software/runtime.md) remain authoritative
for implemented behavior. The C inline loop couples RX, processing and TX to a
worker; the existing deferred/fiber runtime already permits overlapping request
lifetimes. Go must own goroutine scheduling rather than run the C worker/fiber
loop underneath each handler.

## G, P, M and L

The design extends [Go's G/M/P model](https://golang.org/s/go11sched), not the
meaning of P. The [current scheduler](https://go.dev/src/runtime/proc.go) and
[runtime structures](https://go.dev/src/runtime/runtime2.go) are implementation
references; pin a concrete Go revision before patching them.

| Entity | Meaning |
| --- | --- |
| G | Ordinary Go goroutine, with its own stack and execution state. |
| P | Go execution resources, including a local run queue and runtime bookkeeping. In steady state, the number of Ps is GOMAXPROCS. P is not a physical CPU or mailbox. |
| M | In this note, an OS thread without L. Go itself uses M for all OS threads. |
| ML | An OS thread holding L. It still needs P to execute Go code. |
| L | Exclusive live assignment of a worker endpoint to a registered OS thread, retaining the current CPU affinity. It carries mailbox selection, parity and transport ownership state. |

There are as many physical L resources as configured workers, shared across
domains, not that many independently owned by each process. OS threads may
outnumber Ps. Gs requesting native access and in-flight requests may outnumber
Ls. Neither a G nor a P owns hardware access authority merely by carrying a pointer.

| Transition | P | L |
| --- | --- | --- |
| Bootstrap/activation | Acquire before Go execution | Kernel installs assignment and affinity; runtime installs mailbox state |
| Ordinary blocking syscall | Released or reclaimable according to Go's syscall machinery | Retained, as in C |
| DESCHED | Release through a GC-safe runtime path | Relinquish through the existing kernel/hardware protocol |
| G parks on a channel/child/completion | ML may use P for another G | No automatic relinquishment |
| G preemption or migration | Ordinary scheduling/handoff | Does not transfer L with G or P |

Activation returns a worker index and both parities; the
[C activation helper](../../sw/rt/activation.c) computes the VA from mapped
aliases. A returning thread may get a different worker and P. Hardware checks
domain ownership through the [address decoder](../../hw/src/lauberhorn/host/eci/EciAddressDecoder.scala);
the [kernel](../../sw/kmod/sched.c) separately installs thread affinity and
serializes assignment claims. Preserve both layers.

There is no sticky GL class. Native RPC entry publishes a **temporary demand
for 2F2F access** when the operation needs an endpoint. If the current M lacks
an eligible L, G waits for execution on ML before touching transport. Demand
persists through dispatch/activation races until serviced or withdrawn; it is
not forgotten merely because G parks awaiting L. Transport access also requires
a guard against migration and concurrent mailbox use for the operation's lifetime.

After the operation and its borrows/reservations settle, remove the demand.
Ordinary computation, channel waits and waiting for a remote reply carry no
requirement for L. A later send, receive or other native access publishes fresh
demand. Failure/cancellation must withdraw queued demand or safely settle an
already acquired operation before clearing its constraint. A native RPC's
entire application lifetime is not one transport-critical section.

Incoming handlers and children are ordinary Gs. Neither first use, ancestry nor
a channel/timer wake permanently changes scheduling eligibility. No stack
inspection or complete call-chain tracking is required. Locality preferences
may keep a continuation on ML after its operation, but cannot make it ineligible
for migration to M or give it priority over actual endpoint demand.

## Mechanism and scheduling policy

Mechanisms provide assignment acquisition/release, exclusive transport access,
safe goroutine migration/handoff, preemption requests and notification of host
work. Policy prefers computation without current endpoint demand on M and
2F2F operations on ML. G may continue computing on otherwise available ML
execution for locality; it must give way when native demand appears. Completing
an operation does not force immediate migration or relinquishment of L: policy
can retain locality until capacity is needed. Do not permanently bind each
handler with `LockOSThread`: that would prevent other goroutines from executing
on that thread while it waits.

Distinguish three populations:

- **In-flight requests:** admitted requests not yet completed, including blocked
  handlers; bounded by storage and RPC context/credit capacity.
- **Active native demand:** an operation ready to use an endpoint now, such as
  response TX. Computation alone is not active native demand, even in a handler
  that previously used RPC.
- **Receive demand:** pending calls asking for a packet. These wait in software
  until receive service can be provided; they are not endlessly runnable loops.

Use Go's existing waiting states plus runtime-owned demand queues. A G parked
on a child is not occupying ML execution. In contrast, an ML performing a
blocking 2F2F RX read is occupied until hardware completes that read.

### Dispatch and preemption matrix

| Incoming demand | Available ML | ML executing G without current endpoint demand | ML stalled in hardware RX | ML servicing an active native operation |
| --- | --- | --- | --- | --- |
| **G with active native demand**, e.g. response TX | Dispatch | Request prompt Go preemption, then dispatch | Signal HOST_WORK; dispatch after RX safely returns | Queue |
| **G requesting RX** | Begin receive service | Request prompt Go preemption, then begin receive service | Queue; do not interrupt RX merely to replace its waiter | Queue |

An available ML also needs access to P. If active work and receive demand are
both queued, active work takes precedence over entering another blocking RX.
At most one receive-service owner operates each L; oversubscribed receive callers
queue rather than issue concurrent hardware reads. Preserve sequential worker
RX/TX operation, including live reservations and borrows.

Publishing active native demand must trigger dispatch or preemption promptly when
an ML is running G without an endpoint constraint. Do not wait for the normal G
quantum to expire. Record the invariant: **active native demand waiting plus ML
executing unconstrained G implies a dispatch/preemption action is outstanding**,
unless another claimed activation already covers that demand.
Notification and dispatch claims must prevent lost work and redundant claims.

Use Go's [safe-point preemption](https://go.dev/src/runtime/preempt.go) to regain
the scheduler; do not touch transport in a signal handler. The response objective
is native-demand-publication-to-ML-dispatch delay substantially below the ordinary
G quantum.
No numerical or hard bound is established: safe points, P availability, all MLs
already servicing native operations, and syscall-retained L qualify it. If every
ML blocks in a syscall, eventual return remains the same progress assumption as in C; syscall
migration/offload and arbitrary reclamation are not added here.

Native-demand priority alone cannot discover incoming packets. Receive service
must also make progress under sustained active work, including observing DESCHED. A
bounded service opportunity is required; the precise budget/polling or arrival
notification mechanism remains to be designed. Prioritizing active work over a
**blocking** RX wait must not become permission to starve all RX indefinitely.

## HOST_WORK

**HOST_WORK replaces the proposed name HOST_WAKE.** Historical records and model
names retain their original spelling; they do not define this interface. No
existing ioctl, register, descriptor or trace event is renamed or introduced by
this document.

HOST_WORK means: **software work is pending; complete an assigned endpoint's RX
wait so the runtime can service it**. Examples include a pending response TX, a
timer continuation and memcached worker-maintenance notifications. Hardware
does not select a Go continuation or execute a callback. The assignment remains
valid; this is not DESCHED or a direct wake of a thread inside `lh_yield()`.

In the Go binding, child/timer readiness alone does not imply endpoint demand.
A normal continuation can run on M, then publish demand and signal HOST_WORK at
native entry. Runtime policy may also request host work to service a local
continuation, but such a locality hint is not persistent 2F2F priority.

The current transport can return an ordinary payload-free NACK. Whether HOST_WORK
uses that completion or a distinct reason is an open ABI decision, not permission
to add a placeholder. Timers need a software source that can publish work even
when application workers are waiting; RX timeout polling alone does not establish
timer progress for an unassigned domain.

Publish work before notifying. Persist/coalesce demand across notification,
wait-entry and acknowledgement races, so a notification just before RX cannot
be lost. If a real packet wins, handle its ownership before dispatching other
work and retain the host demand. If DESCHED wins, preserve work through
relinquishment; do not send through the old assignment.

When a domain has no L, making host work eligible for allocation is a **separate
scheduler obligation**. Preserve pending work until an eligible assignment can
service it. The complete stage needs both domain-level demand and targeted
registered-worker notification for memcached, plus host-only pause/resume
parking. Target identities are not Go P IDs or Linux CPU IDs. The targeting,
allocation and retirement interfaces remain open.

## Execution cases

### Unloaded parent and child

An ML receives and runs a handler G. After decoding and releasing the borrow,
the handler has no endpoint constraint. It spawns a child locally and waits;
the same P/ML/L can run the child. The child completes and wakes the handler
locally; response TX publishes fresh demand, already satisfiable on that ML.
This uses locality preferences, not inherited status or a guarantee of physical
placement. No mandatory handoff to an ordinary CPU is introduced, and either
parent or child may move to M between transport operations.

### Ordinary computation saturates workers

G entering native TX publishes demand and requests preemption of an ML executing
computation without an endpoint constraint. At a safe point, requeue the displaced
G and dispatch the operation on the same ML, retaining P and L. This also applies
if the displaced G previously used RPC. Excess demand queues if every ML already
services an active native operation.
Unlike hardware DESCHED, this changes the executing goroutine, not the domain's
endpoint assignment.

### A child completes while every ML waits in RX

The child makes its parent runnable through normal Go synchronization. The parent
can resume on M and prepare its response; native send entry publishes endpoint
demand and signals HOST_WORK. An eligible ML's RX completes without needing
another packet. Its runtime dispatches the parent's send using the retained L.
A binding that already has a ready owned TX operation can publish that operation
directly. Merely waking a parent still needing computation does not confer
priority for L. Both receive and send require ML execution; software completion
alone never authorizes TX from ordinary M.

### More in-flight requests than Ls

Separate bounded receive service from independently schedulable request Gs,
like a listener dispatching accepted work:

1. Receive and decode into owned Go data; retain the opaque reply context.
2. Release the RX borrow before independently scheduling the request G.
3. G computes, spawns children or submits a nested call, then may park. Nested
   submission requires L only for transport; waiting for its reply does not.
4. Receive service continues, routing new calls and nested replies to the
   appropriate handlers/completion waiters. A nested-call waiter does not start
   its own hardware RX loop.
5. Response TX publishes native demand and queues for ML execution, then encodes
   and sends using L. Once transport ownership settles, G is unconstrained again.

The request G may send its own eventual response; no extra TX goroutine is
mandatory because it is not the sole receiver. If receive service has entered
hardware RX meanwhile, HOST_WORK returns control. The mapping of receive service
to scheduler hooks or internal goroutines remains an implementation choice;
there must not be one exclusively locked OS thread per request.

An opaque reply context is not an endpoint lease. Audit and verify the native
response-routing contract before promising that any assigned L can send every
response; preserve any required affinity without retaining borrowed packet data.
Admission must respect incoming reply contexts, outgoing credits and owned
storage independently of worker count. Exhaustion requires backpressure or
explicit rejection, not unlimited goroutine creation.

### More receive callers than Ls

If a public receive API is exposed, excess callers wait in a software queue.
They may displace unconstrained G as the matrix specifies, but cannot displace an
active native operation or repeatedly interrupt a pending RX. The null-RPC server
need not expose that API merely to obtain bounded internal receive service.

### C-like admission versus multiplexing

Limiting **outstanding request lifecycles** to worker capacity reproduces the
inline C concurrency policy. Merely limiting native demand does not: a blocked
handler still owns an in-flight request. Multiplexed admission allows more
lifecycles while bounding transport execution by L and P and admission by credits.

## Borrowed packet data

Initially, independently scheduled goroutines receive owned decoded values, not
aliases into 2F2F cache lines. Direct decoding can avoid an extra raw-packet copy.
The [transport API](../../sw/core/eci/core.h) rejects RX acquisition while borrowed,
and activation rejects yielding with a borrow or TX reservation outstanding.

An eventual cross-goroutine zero-copy API would require affinity to the specific
live assignment, serialized access and explicit borrow lifetime accounting.
Demand for any available L is insufficient: another L has another mailbox.
Multiple borrowers could multiplex on one ML, but cannot allow buffer reuse or assignment release;
waiting for traffic that requires recycling the borrowed slot can prevent
progress. That extension is not part of the initial binding.

## Stages, acceptance and remaining decisions

Future implementation proceeds through these gates:

1. Patch a pinned Go runtime against a simulated wait/activation/HOST_WORK
   backend. Preserve normal goroutines, channels, timers, GC and syscall behavior.
2. Specify and implement HOST_WORK and allocation integration with matched
   kernel/RTL/generated collateral. Demonstrate actual memcached hash expansion,
   targeted pause notifications and host-only resume, including all workers parked.
3. Run native Go null-RPC receive/reply and nested-call demos on matched hardware,
   with concurrent handlers and both locality and saturated-compute controls.
4. Later: port Go Raft, then etcd and its client interface. Durability, snapshots,
   bulk transport, compatibility and failure semantics need their own designs.

Future tests must cover every matrix cell, more native callers than Ls, excess
receive demand, all handlers awaiting children/nested replies, and continued RX/TX under
credit pressure. Include channel/timer readiness, GC while workers park, P
reacquisition, both parities after activation, stale borrows, and races between
work publication, RX entry, packet arrival, HOST_WORK and DESCHED. Keep current
C/native UDP regressions and memcached resize validation in the acceptance set.

Verify that first RPC use does not permanently elevate a G; a handler can move
to M after releasing its borrow, then request ML again for TX. A nested caller
must not reserve L while waiting for its response. Under pressure, preempt a G
that previously used RPC but now computes, not just a never-native G. Check
demand withdrawal on queued cancellation, safe settlement of active operations,
and absence of inherited demand in children.

Trace G readiness separately from native demand publication/settlement,
preemption request, ML dispatch, RX return and TX alongside OS thread/CPU, P,
endpoint and domain identities. Measure native-demand-to-dispatch
latency under compute saturation separately from assignment and admission delay.
Show unloaded parent/child locality without promoting a preference to a guarantee.
Use tracing-off controls; no historical run establishes these new properties.

Before implementation, settle the Go revision/hooks and temporary demand
representation, HOST_WORK ABI and persistence/acknowledgement rules, targeting and allocation
integration, P acquisition/wake races, response routing, bounded receive-service
policy and measured latency target. Exact public APIs are not selected here.
Use the existing [validation](../development/validation.md),
[CI](../development/ci.md) and [board-test](../development/hardware-test.md)
workflows when implementation is authorized. Timing optimization, arbitrary
endpoint mobility and complete kill/exit recovery remain outside this stage.
