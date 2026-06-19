# ECI 2F2F Thread Router Tradeoffs

This note compares two ways to expose 2F2F queue pairs (QPs) to RPC workers:

1. **Thread-owned routed QPs:** keep `EciThreadClRouter`. Each application
   thread owns a physical QP block. When that thread is scheduled on a worker
   core, the router maps the thread's physical QP block to the selected worker
   core's logical 2F2F engine.
2. **Worker-owned fixed QPs:** remove `EciThreadClRouter`. Each worker core owns
   a fixed QP. A process maps all worker QPs it may run on, and the userspace
   execution context selects the QP associated with the worker/thread it woke up
   on.

The second design changes the programming model. Regardless of an application's
desired parallelism, the runtime creates one userspace worker thread per hardware
worker. A logically single-threaded application may resume on a different
userspace worker thread after descheduling. That thread must know its worker
identity, for example through TLS, and poll the 2F2F CLs assigned to that worker.

The no-router design also needs an explicit process/QP ownership rule. The
simple version is that each process pre-maps the QP virtual regions for all
worker identities it may run on, and each pinned userspace worker thread uses
the region matching its worker identity. If those physical QPs are truly global
per worker core, context switches between processes need clear reset and
isolation semantics for the shared QP state. If each process still needs
private physical QP storage, then removing the router gives up the easy way to
retarget those private blocks to different worker cores.

## QP Cardinality

The key structural difference is the number of QP signatures that can become
active.

With `EciThreadClRouter`:

```text
allocated QPs = bypass + all registered application threads
active QPs    = bypass + threads currently routed to workers
```

The allocated QP population can be much larger than the worker count. A
reschedule changes which QP signatures are active.

Without `EciThreadClRouter`:

```text
allocated QPs = bypass + worker identities
active QPs    = bypass + worker identities that are running
```

The QP population is tied to worker identities. A reschedule changes which
application logic runs on a worker, but not which QP signature belongs to that
worker.

This difference is not just bookkeeping once DCU progress coloring is required.
The router design makes color compatibility a property of the dynamic routed
set. The no-router design can make color compatibility a static property of the
worker-QP set.

## Baseline Without CC Progress Coloring

### Thread-Owned Routed QPs

The motivation for `EciThreadClRouter` is stable userspace mappings. A thread's
2F2F CLs can keep the same virtual addresses even if the hardware scheduler
moves the thread from one worker core to another. Rescheduling mutates router
state, not the process page tables.

Pros:

- Userspace sees one stable QP mapping per RPC thread.
- A one-thread application can remain one userspace thread.
- Thread migration is cheap from the CPU MMU's perspective: update the router
  rather than editing PTEs or remapping VMAs.
- The application does not need to know which physical worker core currently
  runs it in order to find its 2F2F CLs.
- The model matches an application-level thread abstraction: physical worker
  cores are execution resources, not ownership boundaries for QPs.

Cons:

- The hardware has mutable routing state on the coherence path.
- Scheduler, preemption, and routing updates must be ordered carefully so no
  outstanding 2F2F invalidation is translated using stale state.
- Every registered RPC thread needs a physical QP block even when it is inactive.
- Debugging address ownership is harder because the physical QP owner is the
  thread, while the logical 2F2F state machine is the currently assigned worker
  core.
- Any routing bug can look like a coherence or 2F2F protocol failure.

### Worker-Owned Fixed QPs

Without `EciThreadClRouter`, the simplest coherent model is that each worker
core owns a fixed QP. Userspace must use the QP for the worker it is actually
running on.

Pros:

- No coherence-path thread router.
- No router update on reschedule.
- The set of QPs that can be active is fixed: bypass plus one QP per worker
  core.
- QP ownership is easier to reason about in traces because QP identity and
  worker-core identity are the same.
- Page tables can map stable worker-owned QP regions; the kernel does not need
  to remap a process when a different userspace worker thread runs.
- The CC-color active set can be made a property of the fixed worker identities,
  rather than of whichever application thread happens to be routed.

Cons:

- The runtime must create at least one userspace worker thread per hardware
  worker for every application that may receive work there.
- A logically single-threaded application is no longer represented by one
  userspace thread. It needs a dispatch/continuation model that can resume the
  logical application on whichever worker thread receives the request.
- Userspace needs a reliable way to identify the current worker/QP, for example
  TLS initialized per worker thread.
- Application-local state must be safe under this execution model. If only one
  logical request may run at a time, the runtime must enforce that above the QP
  layer.
- Processes may map more QPs than their actual degree of parallelism needs.
- If worker QPs are physically shared across processes, context switches need
  explicit QP reset/quiescence and isolation rules.
- The design pushes complexity from hardware routing into the userspace runtime
  and application scheduling model.

## With CC-Level Progress Coloring

The progress invariant from `ECI-2F2F-DCU-PROGRESS.md` is:

```text
active_controls has no duplicate colors
active_controls intersects active_overflow == empty
```

where each active full-duplex QP contributes:

```text
A = RX control CL 0
B = RX control CL 1
C = TX control CL 0
D = TX control CL 1
E = all RX/TX overflow CL colors
```

For a single-thread RPC worker whose userspace path serializes RX and TX, the
active contribution is direction-specific: RX contributes `{A, B} + Erx`, and TX
contributes `{C, D} + Etx`. A scheduler that does not track the current
direction must use a conservative compatibility relation that quantifies over
all possible active direction combinations.

### Thread-Owned Routed QPs Under Coloring

With `EciThreadClRouter`, the active QP set is dynamic. It is not simply
"worker0 QP, worker1 QP, ..."; it is whichever RPC threads are currently routed
onto worker cores, plus bypass.

That means the scheduler must be color-aware unless every possible thread QP is
allocated from a globally compatible signature set. For many registered threads,
global uniqueness is usually too restrictive. The practical policy is active
admission:

```text
route thread T only if signature(T) is compatible with the current active set
```

Consequences:

- A free worker core is not sufficient to schedule a thread.
- A one-thread application can be blocked if its QP signature conflicts with
  currently active QPs.
- The scheduler needs each thread's precomputed signature or compatibility
  class.
- Eviction removes a signature from the active set only after the normal
  preemption/quiescence requirements are satisfied.
- Replacement must check the new signature before programming the router.

This preserves the stable-userspace-address benefit of `EciThreadClRouter`, but
the cost is a dynamic color-aware scheduler.

A different workaround keeps the scheduler color-unaware by moving the color
check to thread creation. The kernel can allocate thread QPs only from a fixed
pool of independently admissible signatures, including compatibility with
always-active bypass. Once that pool is exhausted, new worker-thread creation or
datapath `mmap` must fail instead of creating a color-conflicting QP. Then any
runtime subset of created threads is safe, so routing a runnable thread does not
need a color check.

This is a capacity tradeoff. The total number of created threads, across all
processes, is capped by the size of the precomputed independently admissible
pool rather than by process slots or worker cores. For single-thread RPC worker
QPs, the pool can use the weaker half-duplex compatibility relation from the DCU
progress note: every possible active direction combination among created
workers must be compatible, but RX and TX from the same worker do not need to be
compatible with each other unless that worker can issue them concurrently.

For the current `ECI_CORE_OFFSET = 0x20000` and current RX/TX CL layout, the DCU
progress note computes a maximum independently admissible half-duplex set of
eight masks:

```text
{0, 14, 16, 30, 32, 46, 48, 62}
```

If bypass occupies mask 0 and is treated as always active, this leaves room for
seven additional single-thread RPC worker QPs in a color-unaware
creation-capped scheme. New worker-thread/datapath creation beyond that cap
must fail or use a different proven-compatible layout.

Using the example permissive block layout from the DCU progress note improves
the full-duplex story but not the total mask count under the current stride. For
that layout, an exact search over the 64 masks gives a maximum active set of
eight full-duplex QPs, for example:

```text
{0, 2, 4, 6, 24, 26, 28, 30}
```

The direction-unaware half-duplex capacity is also eight masks. So with
`ECI_CORE_OFFSET = 0x20000`, a better common block layout does not raise the
color-unaware creation cap above seven RPC worker QPs plus bypass; it makes that
cap compatible with full-duplex QPs, avoiding the current layout's local bypass
RX/TX hazard.

### Worker-Owned Fixed QPs Under Coloring

Without `EciThreadClRouter`, the physical QPs are tied to worker cores. The
maximum active set is known structurally:

```text
bypass QP
worker0 QP
worker1 QP
...
workerN QP
```

This changes the coloring problem. The allocator can statically choose the QP
block layout and block indices for bypass and every worker-owned QP so that the
entire possible active set is compatible.

If that static assignment succeeds, the scheduler does not need dynamic
color-awareness. A thread running on worker `i` always uses worker `i`'s QP, and
worker `i`'s QP was already included in the global progress proof. Scheduling
changes which application logic runs on a worker, not which QP signature becomes
active.

This can be implemented in two ways:

1. **Static block-index allocation:** use one fixed QP layout for all workers,
   then assign each worker a block index whose translated signature is
   compatible with all other worker QPs and bypass.
2. **Static per-worker layouts:** if one fixed layout does not provide enough
   mutually compatible translated signatures, generate different local QP
   layouts for different worker identities at build time, initialization time,
   or thread/process creation time.

Both are static from the scheduler's perspective. The scheduler may still need
normal load-balancing and process admission logic, but it does not need to solve
the DCU color-compatibility problem on every reschedule.

The static worker-owned design is a useful lower bound on the coloring problem.
If bypass plus all worker-owned QPs cannot be assigned mutually compatible
signatures, then a router-based design cannot magically make the same fully
static, all-workers-active guarantee true. The router can emulate the
worker-owned assignment by routing fixed thread/QP blocks to fixed worker cores,
so the no-router color problem is a subset of the router color problem.

What the router adds is not more DCU colors; it adds the ability to choose which
thread-owned QP signatures are active at a given time. This does not increase
the maximum possible degree of parallelism. Here, the **signature universe**
means all signatures the implementation can instantiate, including different
block indices and different local QP block layouts.

If there exists any compatible set of signatures large enough for bypass plus
`N` active RPC QPs, the no-router design could use that same set as a static
assignment for `N` worker-owned QPs. Conversely, if no compatible set of that
size exists in the full signature universe, dynamic admission in the router
design cannot run `N` RPC QPs concurrently either. This remains true even when
different QPs are allowed to have different block layouts.

Therefore, if the static worker-owned assignment fails because no compatible set
exists for the desired worker count, both designs must use one of the same
capacity fixes to preserve full concurrency: reduce the number of concurrently
active workers, find better local layouts, increase/change the QP block size, or
otherwise increase the set of compatible signatures. Dynamic admission is only a
correctness fallback that runs a smaller compatible subset of threads even if
more worker slots are physically free.

## Direct Comparison Under Coloring

| Topic | Thread-owned routed QPs | Worker-owned fixed QPs |
| - | - | - |
| QP owner | RPC thread | Worker core |
| Userspace QP address | Stable per logical RPC thread | Stable per worker thread |
| Migration mechanism | Update `EciThreadClRouter` | Resume on another userspace worker/QP |
| Page-table mutation on reschedule | Not needed | Not needed if all worker QPs are pre-mapped |
| Runtime thread count | Application-chosen | At least one userspace worker per hardware worker |
| Active color set | Dynamic subset of thread QPs | Static set of worker QPs |
| Scheduler color checks | Required unless all threads are globally compatible | Not required if all worker QPs are statically compatible |
| Idle-core risk from coloring | Yes, if runnable thread conflicts | No, assuming static worker QP coloring succeeds |
| Hardware complexity | Router and reverse translation | No thread router |
| Runtime complexity | Lower application/runtime migration burden | Higher runtime burden; TLS/current-worker QP selection |

## Answer To The Scheduling Question

In the thread-router design, dynamic color-awareness is needed if arbitrary
created thread QPs can conflict, because scheduling a thread changes the active
QP signature set. A color-unaware scheduler is still possible if thread creation
is restricted to a precomputed independently admissible signature pool and new
creation requests are rejected once that pool is exhausted. That makes every
possible active subset safe by construction.

In the no-router worker-owned design, not necessarily. If every worker-owned QP
plus bypass is statically assigned a mutually compatible signature, then the
scheduler can remain color-oblivious. Static block-index allocation may be
enough. If one common local layout cannot produce enough compatible translated
signatures, the next step is static per-worker block layouts generated for the
worker identities, potentially when the per-process worker threads are created.

If that still cannot provide a compatible signature for every worker-owned QP
plus bypass, the router design cannot provide a full-concurrency solution either
under the same physical constraints. This statement includes the case where QPs
may use different block layouts: if the router can pick `N` compatible
thread-owned QP signatures from that larger universe, the no-router design can
assign those same signatures statically to `N` worker-owned QPs. The router can
only switch to dynamic color-aware admission with a lower active degree, where
some runnable threads wait despite available worker slots.

The tradeoff is therefore clear: `EciThreadClRouter` preserves the logical
thread abstraction, but progress coloring must be handled either dynamically at
schedule time or statically at thread creation time by capping the total number
of QPs. Removing the router can also make progress coloring static, but only by
changing the userspace execution model so worker identity and QP identity are
fixed together.
