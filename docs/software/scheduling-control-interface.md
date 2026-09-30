# Cooperative scheduling control and domain retirement

Selected interface contract, 2026-09-30; not the existing RTL/kernel ABI. This
supersedes the separate kernel drain/cache-retirement/commit sequence in earlier
Goal 2 notes. The kernel never accesses worker 2F2F cache lines. Normal handoff
uses one blocking **PreemptInfo read**, with no activation commit write or IRQ.
The existing `IpiAckReg`/`schedCmd` role is replaced, not extended with IRQ semantics.

## Worker ownership and the info read

One transaction is in flight per physical worker. The NIC serializes destination
reservation, wake acceptance and owner changes. All destination threads are
initialized before advertising capacity; assignments plus reservations cannot
exceed that fixed pool. The kernel retains domain/thread references throughout
dispatch. Individual threads cannot withdraw.

1. NIC reserves the destination and persistently serves PREEMPT at idle RX.
2. The runtime enters `lh_yield()`. Before issuing MMIO, the kernel validates the
   caller's assignment and establishes a no-return fence: this task cannot resume
   its old assignment, including after unrelated wakeups or syscall interruption.
   This is a software eligibility change, not a requirement to context-switch
   before issuing the read.
3. **InfoAccept:** acceptance of the info read acknowledges that relinquishment.
   NIC fences fresh old-owner admission. No kernel cache-line access follows.
4. **InfoRespond:** NIC completes whatever internal 2F2F obligations are needed
   to reuse the worker state, commits the reserved destination, and returns the
   immutable destination/parity snapshot. Read completion is the synchronization
   acknowledgment. Destination ownership is already installed at this boundary.
5. Kernel claims a thread from the guaranteed pool, sets its CPU affinity and
   saves its return snapshot, then permits that thread to resume. These are local
   kernel operations: the NIC receives no subsequent commit. The old thread stays
   parked until a future assignment, potentially on a different physical worker.

The resource harness currently guards InfoRespond with old transaction slots,
invalidations, acknowledgments and queued unlocks released. This is a conservative
NIC-internal implementation of readiness, not a kernel drain protocol or a demand
to evict every cached line. A more permissive engine can preserve tagged old
obligations across handoff if refinement establishes equivalent safety/progress.
If readiness already holds when PREEMPT returns, the read need not wait for it.

Read response must not wait for a kernel action that occurs after that read, for
new userspace execution, or for a separate software ACK. Fabric cache responses
may occur autonomously while the CPU is in the kernel. Shared MMIO backpressure
must not suppress the traffic needed to finish the read or unrelated workers.
No scheduler/callback lock needed by those paths may be held across a blocked read.

## Register payload and transaction boundaries

The following is a logical register bank, not assigned production MMIO offsets.
Generate offsets/packing and matched headers when implementing the ABI; do not
hand-edit existing generated headers. Fields are unsigned host-order scalar values.

| Register / operation | Meaning |
| --- | --- |
| `PreemptInfo` read | Side-effecting, blocking handshake above. Returns `next_valid`, `next_domain`, `reason`, `rx_next`, `tx_next`, `has_host_work`. Physical worker identity is selected by the register bank. |
| `PreemptCookie` read | Opaque domain-associated source cookie from the same latched response, valid only when `has_host_work=1`. Reading it has no acknowledgment side effect. |
| `DispatchStatus` read | Non-destructive discovery of a pending dispatch; no ownership change. Used by kernel dispatch service for initial/idle activation. |

`next_domain` is a domain-table identity, not a Linux PID or thread ID. Its generated
width must support configured domains (including the selected map's 19-bit domain
encoding if that capacity is configured), not inherit today's PID width. Cookie
width is 64 bits. `reason` is NETWORK, HOST_WAKE or CLOSED. `rx_next` and `tx_next` are one
bit each, selecting the control CL for the next software operation in that
direction; the kernel does not program old thread parity into the NIC.

InfoRespond is the NIC commit/response-offer boundary; bus response consumption
may happen later under backpressure. The NIC retains the response and its lifetime
reference until consumption. The bank presents a coherent latched snapshot across all payload words. Only the
first handshake read has the InfoAccept/InfoRespond effects. Re-reading that
snapshot is idempotent; it must not acknowledge a future handoff. Kernel serializes
readers per worker and reads the cookie before enabling the new thread. Snapshot
storage is not replaced until a later dispatch transaction. A bus transfer must
not be split into multiple side-effecting reads; actual AXI access width, beat
framing and response stability under backpressure are refinement obligations.

For an ordinary handoff, `next_valid=1`. HOST_WAKE selects a subscriber for the
returned cookie and sets `has_host_work=1`; NETWORK uses `has_host_work=0` and a
zero cookie. Later wake arrivals remain NIC/kernel pending and can be delivered
at RX; they must not mutate a response already offered. If close wins before
InfoRespond, a destination reservation may instead complete with `next_valid=0`
and reason CLOSED; parity/cookie are then ignored and the worker remains idle.
This is whole-domain teardown, not ordinary unavailable-thread cancellation.

At InfoRespond a host indication transfers from NIC-pending to a **kernel-held
activation snapshot**. Kernel owns that responsibility until userspace resumes;
then runtime owns it. A notification arriving after that snapshot stays pending
(new notification wins over clearing). There is no NIC-visible activation event.
On teardown, the kernel-held indication is explicitly disposed as closed work.

Initial/idle dispatch uses the same snapshot with no departing userspace task.
A kernel dispatch service discovers it through DispatchStatus and issues the
read. Its polling/wakeup implementation remains to be selected; no ordinary NIC
scheduling IRQ is introduced. A read is not issued speculatively while no dispatch
exists. There is no next cooperative preemption until the incoming assignment
executes RX; kernel dispatch delay alone is not failure to yield.

## Userspace results

PREEMPT is an RX result discriminator with **no application payload**, no destination
domain/cookie, and no new mailbox or parity. It consumes no network packet. The
runtime must immediately enter `lh_yield()` without further RX/TX accesses on that
assignment. Replay before the kernel acknowledgment continues to request yield;
it does not authorize additional application work.

The logical runtime signature is:

```c
int lh_yield(struct lh_activation *result);
/* result: worker_index, rx_next, tx_next, has_host_work */
```

Success returns 0 after this calling thread is assigned again. `worker_index`
indexes that domain's stable pre-mapped physical-worker aliases; it is neither a
Linux CPU number nor a thread ID. Both parity bits are authoritative for that new
assignment. The old assignment's cached runtime parity is discarded. A bootstrap
wait returns the same structure. `has_host_work` directs the runtime to service
its sealed epoll subscription before its next blocking RX; an empty epoll result
is legal. Userspace needs no cookie because each thread has at most one subscription.

The yielding thread does **not** receive the destination chosen to replace it.
The kernel delivers that snapshot to the incoming thread's pending yield/bootstrap
call. Once relinquishment is acknowledged, no error/signal may resume the outgoing
thread on its old assignment. Whole-domain termination may prevent a successful
return; detailed signal/kill syscall semantics remain part of recovery work.
Validate ioctl arguments before relinquishment and retain the authoritative result
in kernel storage. A failed userspace copy after relinquishment must not expose
an old assignment or permit uninitialized mailbox/parity use; its failure path
must preserve the no-return fence or terminate the domain.
A direct HOST_WAKE RX result enters the same epoll-service routine without yielding.

## Whole-domain close and retirement

Stopping an application and certifying its PA prefix for reuse are separate
milestones. A domain that is no longer executing needs no kill IRQ or cache flush:
fence pending dispatches/claims so it cannot resume, then reconcile callbacks and
control references before reclaiming their metadata. Cached lines at distinct old
PAs may remain accounted for after the application has stopped. The coherence
certificate below is for reusing those PAs, not an extra prerequisite for killing
an inactive application.

For a running application, latch the kill request and gate new blocking RX before
allowing another RX to stall. IRQ assertion and release of the outstanding RX may
then occur in either order or concurrently; do not wait for handler entry before
releasing a load that can prevent interrupt service. Keep the RX gate asserted
until the kernel certifies that the task cannot resume. The existing timer/IPI
model's release-before-IPI-delivery ordering is a separate interface contract,
not a requirement that the new kill path delay IRQ assertion. Concrete kill IRQ
framing, RX release and acknowledgment still need their own composed check.
Serialize the kill request with assignment changes as well: a late IRQ must not
kill a successor merely because it is now the current task. Either consume the
old notification before successor activation or retain/revalidate its target
identity. This is a kill-path obligation, not an extra ordinary-handoff commit.

Cached lines at distinct PAs need not be flushed merely to switch domains. Prefix
reuse is different: a new lifetime must not inherit stale cache state, messages,
callbacks or dispatch responses. Ordinary handoff never releases the domain prefix.

The logical domain-control operations are:

| Operation | Acceptance / completion contract |
| --- | --- |
| `CloseDomain(domain)` write | NIC atomically fences new reservations and new wake acceptance for this domain. Completion confirms the admission fence, not complete retirement. Kernel has already marked the domain closing and fenced new callbacks/claims. |
| `KernelQuiescent(domain)` write | Kernel certifies all domain threads cannot resume, callbacks/deferred publishers have joined, and every in-flight dispatch/info response and local wake reference has been reconciled. Must follow CloseDomain completion. This is domain teardown only. |
| `RetireDomain(domain)` read | May block until the kernel certificate, NIC references/reservations/assignments and old-address transactions are retired, plus the coherence endpoint's prefix-reuse-safe certificate. Completion authorizes freeing/reusing the prefix and cookie identity. |

Close races with InfoRespond at the NIC commit point. If response commits first,
it remains valid and holds a lifetime reference; closing kernel code consumes and
reconciles it without resuming that domain. If close commits first, the response
returns CLOSED and releases the reservation. Neither outcome can silently discard
an MMIO response or leave its reader blocked. Outstanding pre-close wake commands
receive either accepted ownership or explicit CLOSED disposal; the producer must
never infer acceptance from publication alone.

KernelQuiescent cannot be sent while a late response could still cause a claim or
resume. Issue the blocking RetireDomain read only after KernelQuiescent write
completion; otherwise the same CPU/control path could block its own prerequisite.
Retirement cannot wait for userspace to service work from the closed
domain: pending work may be discarded with an explicit closed disposition. The
coherence certificate is a required endpoint contract, **not an implemented flush
instruction or a passing theorem**. Its mechanism, kill path and progress under
uncooperative execution remain unresolved. Kernel still performs no 2F2F CL accesses.

Each MMIO command is published atomically, or staged under one serialized writer
with a final doorbell. Write completion means the specified effect has committed,
not merely that a posted store entered a bridge. The concrete driver must use the
required completion/readback mechanism. NIC may backpressure reads/writes only
without forming a wait cycle through the blocked CPU or shared control ingress.

## Model correspondence and remaining work

`Assignment` now has InfoAccept and InfoRespond instead of kernel drain, cache
retirement, park and install acknowledgments. `Handoff` composes the response guard
with the unchanged Goal 1 resource model and captures parity at that response.
Kernel selection/activation are local steps. `WakeHandoff` transfers host-work
responsibility at the response and only services it after local activation.

The `oldCache` ghost in Handoff now concerns **whole-domain prefix retirement**;
it no longer gates handoff or Goal 1's abstract Settled predicate. Its retirement
action is an assumed endpoint certificate for this one-domain harness. Resident
old-domain lines were not represented in this original harness. The new
retained-cache composition below accounts for their finite residency and queued
release; repeated later re-entry and concrete THX correspondence remain open.

`ControlRetirement` checks one pre-reserved dispatch and callback racing close,
with separate response offer/consumption, local activation and retirement. It
checks that a delayed response remains immutable, closed tasks cannot resume,
and prefix release waits for kernel quiescence and an endpoint certificate.
Internal NIC readiness and the endpoint certificate are abstract service actions
in this small harness; they are not proofs of physical progress.

The larger combined checks must use the close/response ordering, kernel-held snapshot
ownership and quiescence certificate above, together with reservations and fixed
thread claims. Do not interpret the standalone historical WakeRetirement test as
proof of this whole-domain interface. Actual register framing, independent control
progress, cached-generation resource bounds and kill recovery remain obligations.

The [retained-cache composition](../hardware/retained-cache-composition.md) now
accounts for nonempty dirty sets, conflict eviction and delayed directory release,
and combines those resources with the control/close races. It supersedes the
ordinary clean-cache entry assumption; actual THX replacement topology and the
teardown-only endpoint certificate remain refinement obligations.
