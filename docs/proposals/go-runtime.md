# Go runtime and goroutine scheduling

**Proposal; not implemented.** Integrate native RPC with Go's goroutine scheduler
while preserving GC, ordinary Go synchronization and Lauberhorn endpoint ownership.
[HOST_WORK](host-work.md) is a separate shared mechanism required for pending
native work to interrupt assigned RX waits. [Raft and etcd](../applications/etcd.md)
are later applications of this runtime, not prerequisites for its design.

## Scope and baseline

Keep the current isolated worker-core–endpoint binding, physical-worker address
map and domain ownership checks. Allowing the same endpoint to be serviced from
arbitrary CPUs, or handing its lease between OS threads outside activation, is
not selected for this stage. Ordinary Linux tasks still do not share the isolated
worker cores. Scheduling Gs within an assigned application's Go runtime is a
separate operation from Linux scheduling or hardware DESCHED.

Build Go bindings to Lauberhorn native RPC. Standard-library `net/rpc` and
grpc-go compatibility are outside this proposal. The [Raft/etcd application
plan](../applications/etcd.md) describes the later embedding and transport work.

The [current scheduling contract](../software/scheduling-control-interface.md)
and [transport ownership contract](../software/runtime.md) remain authoritative
for implemented behavior. The C inline loop couples RX, processing and TX to a
worker; the existing deferred/fiber runtime already permits overlapping request
lifetimes. Go must own goroutine scheduling rather than run the C worker/fiber
loop underneath each handler.

## G, P, M and L

The design extends Go's G/M/P model, not the meaning of P. Implementation
references are the pinned Go 1.27.2 `src/runtime/proc.go` and
`src/runtime/runtime2.go` described below.

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

## Execution cases

### DESCHED, relinquishment and reactivation

DESCHED removes this thread's usable L assignment; it does not destroy a
physical endpoint. The system's physical L capacity stays constant while a
domain's assigned share can shrink and later grow. A successful return from
`lh_yield()` supplies the **calling thread's new assignment**, not necessarily
its former L. It does not merely report that the replacement thread was activated.

| Boundary | P in the proposed Go runtime | L and permitted transport access |
| --- | --- | --- |
| Before DESCHED | ML holds P while executing Go | Current L is usable |
| RX returns DESCHED | Enter runtime relinquishment path | Mark old L unusable immediately; no further RX/TX |
| Kernel accepts the authorized `DeschedInfo` read | P has been released through a runtime-safe path | Relinquishment acknowledged; hardware cleanup and destination commitment follow |
| Outgoing thread waits for its activation | No P | No L; old mailbox must not be used |
| Yield returns successfully | Acquire P before resuming Go work | New worker index, parities and CPU affinity establish the new assignment |

Receiving DESCHED is not itself completed ownership transfer. Follow the
[existing handoff protocol](../software/scheduling-control-interface.md#activation-and-handoff):
fence transport use, enter kernel yield, acknowledge through the authorized
read, and let hardware finish cleanup before reusing the endpoint. No RX borrow
or TX reservation may cross this boundary. Ordinary handoff does not require a
scheduling IRQ.

The Go integration must preserve runnable Gs and pending native demand when
releasing P; they cannot be stranded with the parked M. The registered thread
remains inside the kernel until its own fresh activation and cannot run ordinary
Gs during that interval merely because it no longer holds L. Other execution
resources may service its domain's queued work where eligible.

On successful return, install the returned worker index and both parities and
derive the matching mailbox VA, as the C activation helper does today. The new
L can correspond to another worker CPU, and the reacquired P can differ from
the old P. Even reactivation on the same endpoint requires the fresh activation
state; do not restore cached pre-yield parity. A failed yield does not authorize
old-assignment access and retains the existing fail-closed recovery boundary.
Exact Go P-release/reacquisition hooks remain to be implemented and validated.

```text
M1 + P2 + L0
    -> RX returns DESCHED: stop using L0
    -> release P2; kernel relinquishes L0; M1 waits without P or L
    -> L0 can serve the committed destination
    -> M1 later receives L3 and its matching CPU affinity
    -> install L3 mailbox state; acquire P1; resume eligible work
```

HOST_WORK is different: it ends an RX wait while retaining L so ML can service
software work. If HOST_WORK races with DESCHED, preserve the work through the
relinquishment/reactivation path rather than transmitting on the old assignment.

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
4. Later: follow the [Raft/etcd application plan](../applications/etcd.md).

Future tests must cover every matrix cell, more native callers than Ls, excess
receive demand, all handlers awaiting children/nested replies, and continued RX/TX under
credit pressure. Include channel/timer readiness, GC while workers park, P
reacquisition, both parities after activation, stale borrows, and races between
work publication, RX entry, packet arrival, HOST_WORK and DESCHED. Keep current
C/native UDP regressions and memcached resize validation in the acceptance set.

For DESCHED, assert that old-L transport access stops at RX return, P and queued
work are not stranded, and the outgoing thread returns only with its own fresh
activation. Exercise same-endpoint and different-endpoint reactivation with fresh
parities, reacquisition of a different P, HOST_WORK overlap and failed yield.

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

Before implementation, settle the proposed hooks
and temporary demand representation, the [HOST_WORK ABI and allocation semantics](host-work.md), P acquisition/wake races, response routing, bounded receive-service
policy and measured latency target. Exact public APIs are not selected here.
Use the existing [validation](../development/validation.md),
[CI](../development/ci.md) and [board-test](../development/validation.md#board-validation)
workflows when implementation is authorized. Timing optimization, arbitrary
endpoint mobility and complete kill/exit recovery remain outside this stage.

## Proposed runtime integration

The source baseline is Go `go1.27.2`, commit
`022c8636110ebac86a9b88724cda168ed713c21f`. Paths below are relative to that
source tree; platform paths are relative to this repository. New filenames and
APIs are proposed. Package the patched toolchain by immutable revision through
Nix; builds must not depend on an untracked sibling checkout. The baseline's
`src/cmd/dist/buildtool.go` requires bootstrap Go `go1.24.6` or newer.
Linux/arm64 is the native target; Linux/amd64 hosts the simulated backend.
No compiler, language or general-purpose `net` API change is proposed.

### Runtime state and deterministic simulated backend

Add proposed `src/runtime/lauberhorn.go`, platform-specific backend files and
runtime tests. Extend `m` in `runtime2.go` with registration identity, assignment
generation, worker index, both parities, mapped mailbox and explicit states
`unassigned`, `assigned`, `rx-wait`, `native-active`, `relinquishing`, `failed`.
Keep registration identity separate from assignment: a thread can be registered
while having no L. P contains no endpoint authority. Choose GC-visible storage
or the runtime's established pointer conventions for all queued G/operation
references; do not hide Go pointers in integer mailbox state.

Use a runtime-owned domain queue for active operations and a separate receive
queue. An operation has a sequence, queued/claimed/settled/cancelled state and
optional assignment constraint; its G parks using the existing waiting state.
Publish under the queue lock and use the `gopark` unlock protocol to prevent
completion-before-park loss. Claim exactly once, wake exactly once, and retain
claimed cancellation until transport settlement. Clear temporary demand at
settlement; `newproc`/child creation must not inherit it. Waiting for a nested
reply is ordinary waiting, not queued endpoint demand.

The simulator must control assignment, RX completion, notification, P starvation,
DESCHED and failure independently, with reproducible schedules and event logs.
Start with tests for every dispatch-matrix cell, cancellation versus claim,
notification before park, stale assignment generation and over-capacity queues.
Keep the backend disabled for ordinary programs and unsupported platforms.

### ML dispatch, P handoff and prompt preemption

Patch `proc.go`: `schedule`/`findRunnable` select native demand only on eligible
ML; `execute` checks claimed operation eligibility before user execution.
Ordinary G queues remain stealable. Parked native demand must not be inserted
into ordinary run queues and repeatedly bounced off ineligible Ms. At native
entry, a G already on eligible ML may claim immediately; otherwise it parks and
is resumed on ML for the operation, with no permanent G-to-M binding.

Audit `ready`, `wakep`, `startm`, `handoffp`, `stopm`, `goschedImpl` and the
syscall-return paths together. `findRunnable` explicitly requires agreement with
`handoffp`; editing only its queue selection misses wakeups. An assigned ML
without P needs a targeted P handoff/wakeup, not simply `wakep` starting any M.
Drain/re-expose ordinary local work when releasing P; keep native demand in the
domain queue across P movement. Keep GC workers, safe-point requests and timer
checks ahead of application priority where required for runtime progress.

Use `preemptone`/`preemptM` and existing `preempt.go`/`signal_unix.go` machinery
to request prompt scheduling of an unconstrained G on ML. Recheck the M/P and
assignment generation after races; maintain one outstanding dispatch/preemption
claim or activation claim for uncovered work. Never perform MMIO in the signal
handler. Guard transport only for its bounded nonblocking portions; disabling
preemption across a potentially unbounded load is not a GC solution.

Test compute saturation, previously native G now computing, concurrent demand
publishers, no idle P, `GOMAXPROCS=1`, P-count changes and all MLs in syscalls.
Audit `procresize`, `checkdead`, runtime tracing and thread accounting: externally
deliverable work must prevent false deadlock without hiding a real deadlock.
Keep ordinary channel, timer, syscall, cgo and GC regression coverage.

### Registered M lifecycle and GC-safe transport waits

Create a fixed registered M pool matching the kernel's declared worker count.
Use runtime thread initialization (`newm`/`mstart`, Linux `minit` in
`os_linux.go`) to register the actual TID after setup; do not register whichever
M happens to run an initialization goroutine. Mirror `sw/rt/activation.c` and
the ioctls in `sw/rt/ioctl.h`/`sw/kmod/ioctl.h`, without depending on C worker TLS
or creating the C fiber scheduler. Preserve all-threads-ready publication.
Ordinary Go Ms and newly cloned threads must remain on host CPUs: Linux clone
inherits the creator's affinity, including when ML creates another M. Set/check
the host CPU mask before ordinary Go execution and before worker registration;
only kernel activation installs the isolated worker CPU assignment.

Implement a reviewed low-level Linux/arm64 wait trampoline for RX and other
potentially blocking coherent loads, and for bootstrap/DESCHED yield. Use
`entersyscallblock` as the P-release/stack-publication reference and inspect
`exitsyscall`/`exitsyscallNoP` for return handling. Once P is released, the wait
path must not allocate, grow the Go stack, execute write barriers or access Go
objects concurrently with GC. Keep transport/activation result storage stable
and alive; use a tiny assembly/non-Go execution portion where required.

Install activation's worker index, mailbox VA and both parities on the calling
M **before** normal syscall exit can migrate its G. On DESCHED, invalidate old L
immediately, require no borrow/reservation, release P, and enter the existing
authorized kernel handoff. A failed yield fences the session. On a wait returning
with a live borrow/reservation, retain M ownership until copied/settled; generic
`exitsyscallNoP` may globally enqueue G and cannot be used blindly. Implement an
M-specific P reacquisition path for this case, then return owned data to a freely
schedulable G. An internal receive driver is allowed; a locked thread per request
is not. Account for a stopped world while the load completes.

Acceptance: force GC/STW during every wait, exhaust Ps at return, reactivate on
same/different workers with both parities, inject activation-copy failure and
verify no stale access. Validate affinity of worker, ordinary, sysmon and cgo
threads. A simulated wait does not validate ARM64 coherence or instruction
ordering; review against `sw/core/eci/core.h` before real transport integration.

### Shared notification dependency

The [HOST_WORK proposal](host-work.md) specifies assigned RX completion,
host-driven allocation, targeted maintenance and pause/resume. Go must preserve
its publication and assignment rules; its scheduler cannot substitute a
userspace pending bit for hardware allocation.

### Owned-data Go RPC binding and native null-RPC

Add a platform Go module (proposed `sw/go/lauberhorn`) and demo packages, backed
by a narrow fork-runtime API for register, native entry/settlement and wait. Keep
that private integration API versioned with the toolchain; avoid application
`go:linkname` access to unstable scheduler internals. RX decodes into owned Go
values before scheduling handlers. Use an integer dispatch handle or stable
non-Go metadata for the descriptor's existing handler/codec pointer; never hand
hardware an unrooted Go object or treat it as a callable Go function address.
No allocation or independent handler execution may retain a cache-line borrow.

Port transport semantics from `sw/core/eci/core.h`, `sw/rt/oncrpc.c`, `rpc.c`,
`future.c`, `endpoint.c` and `pending_call_table.c`, not their fiber execution
loop. Implement explicit reply-context ownership, bounded admission, nested
cookie/XID matching, completion delivery, timeout/cancel retirement and safe
buffer lifetimes. A local TX acknowledgement is not remote completion. Do not
reuse a credit on timeout until hardware retirement is acknowledged; keep
indeterminate submissions fenced. Decode error/rejection paths and consume
cancelled futures as deliberately as successful replies.

Source evidence narrows response affinity: `RpcReplyContexts.scala` checks
token generation and domain owner, not RX worker. `DmaControlPlugin.scala`
supplies the trusted current worker's owner to `OncRpcReplyEncoder.scala`.
Thus the proposed binding may send a response from another live L of the same
domain, subject to a targeted end-to-end test and current admission checks.
Never move a borrowed buffer with the token. Nested replies are separately
routed by the outgoing table in `OncRpcReplyDecoder.scala`; test their completion
matching across worker and assignment changes rather than assuming identical
semantics to incoming response tokens.

Add receive fairness explicitly: cap consecutive active operations, then offer
one receive-service opportunity. A blocking RX is permitted only with a race-safe
HOST_WORK path and bounded completion policy for already queued active work.
Select and expose the budget after simulator measurements; test continuous TX
with incoming packets, nested replies and DESCHED. No fixed number is justified
yet. Bound request Gs by owned storage and live contexts, independently of Ls;
preserve the current small-table admission workaround until replacement is
validated. Run simple reply, concurrent parent/child, nested RPC, rejection,
timeout and oversubscribed-handler cases through the same receive dispatcher.

### Reproducible builds and acceptance sequence

Add a pinned Go source/patch derivation and checks alongside
`nix/software/runtime.nix` and `nix/checks/runtime.nix`, exposed through the
existing flake composition. First build pristine Go, then run the patched
runtime tests and standard scheduler/GC/channel/timer/syscall tests on the
simulated backend, including the feature-disabled configuration. Run race and
pointer checks where supported; arm64 cross-compilation alone is not execution
coverage. Define new Nix check names in source before documenting commands.

For platform changes, retain `runtime-tests`/`runtime-interface`, regenerate
`nix build .#devHdrs -L`, and select the actual cooperative allocation/yield,
RX/TX, nested-RPC and ownership suites from the validation guide. Build the
matching kernel and userspace artifacts. Only after these gates, use the
scheduled hardware CI and reserved Enzian harness workflow for C regressions,
real memcached resize and Go null-RPC. Capture demand publication, preemption,
dispatch, P acquisition, assignment change and transport completion with distinct
G/M/TID/CPU/P/worker/domain identifiers; compare tracing-on/off cohorts.

Report demand-to-dispatch distributions separately from admission and assignment
delay. Set a numerical target after obtaining a baseline; “below a Go quantum”
is an objective, not an acceptance threshold. Raft/etcd adapters, persistence,
snapshot/bulk transfer and gRPC remain a later patch series. 

## Design constraints and open decisions

| Constraint | Consequence / required resolution |
| --- | --- |
| A hardware RX load can stall while Go expects its running G to reach a safe point. Go signal preemption cannot itself complete the load. | The wait path must release P and expose a scannable stack before potentially blocking transport; test STW without waiting for incoming traffic. |
| `exitsyscallNoP` can place G on the global queue and stop its M. | Install activation on M before exit; use an M-specific return path while transport ownership remains live. A syscall wrapper alone is insufficient. |
| Registered threads acquire FIFO policy in `register_worker_thread`; activation pins them to isolated CPUs. New Go Ms inherit creator affinity. | Audit OS policy/affinity for every thread class; host-only wake needs an explicit policy transition. Never let an ordinary M inherit a worker CPU accidentally. |
| Go may change the P count; an assigned ML may have no P. | Test explicit P handoff and `procresize`; increasing GOMAXPROCS is not a correctness fix or an endpoint allocation mechanism. |
| Current kernel chooses any waiting thread; registration is fixed and all-ready. | Dynamic Go M creation is not dynamic worker registration. Use a dedicated registered pool and add targeted claims separately. |
| Existing DESCHED yield is conditional on hardware handoff, and returning yield always installs L. | Host-only pause/resume cannot be implemented by casually calling existing `lh_yield`; define new transitions before implementation. |
| Assigned HOST_WORK does not activate an unassigned domain or guarantee a particular thread runs. | Allocation, targeted maintenance and a host-side timer source are mandatory distinct pieces. |
| Active demand priority conflicts with indefinite RX starvation; blocking RX can in turn delay TX. | Receive service needs a measured fairness budget and wake race tests, including already queued work at RX entry. |
| Current reply-context RTL checks trusted owner and token. | Validate same-domain cross-worker replies and cross-domain rejection. |
| Hardware handler metadata currently follows C dispatch conventions; Go stacks move and Go function values are not C function pointers. | Define stable dispatch handles and ownership/rooting before registering a Go service. |
| Current C small-table workaround, finite credits and failed-retirement fencing remain real constraints. | Do not equate many goroutines with unbounded RPC concurrency or full cancellation/exit recovery. |
| Standard Go `LockOSThread`/cgo callbacks can impose competing M affinity. | Specify native entry on an externally locked G: initially reject incompatible calls explicitly; do not silently move it or deadlock waiting for L. Test nested runtime/internal locking separately. |
| Exact wake ABI, host-only target execution, fairness budget and latency threshold are still open. | These are implementation gates, not settled contracts; resolve them in the relevant specification commits with simulator evidence. |

