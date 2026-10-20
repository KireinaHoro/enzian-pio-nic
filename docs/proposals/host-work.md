# HOST_WORK and host-driven worker scheduling

**Proposal; not implemented.** HOST_WORK returns an assigned worker from RX wait
so it can service pending software work. Domain allocation, targeted worker
notification and host-only pause/resume are distinct requirements described here.
The [Go scheduler](go-runtime.md) and [memcached](../applications/memcached.md) are
consumers; neither application defines the shared mechanism.

The current [scheduling contract](../software/scheduling-control-interface.md)
remains authoritative for implemented behavior.

## Notification contract

**HOST_WORK replaces the proposed name HOST_WAKE.** Historical records and model
names retain their original spelling; they do not define this interface. The notification ABI is not implemented.

HOST_WORK means: **software work is pending; complete an assigned endpoint's RX
wait so the runtime can service it**. Examples include a pending response TX, a
timer continuation and memcached worker-maintenance notifications. Hardware
does not select a Go continuation or execute a callback. The assignment remains
valid; this is not DESCHED or a direct wake of a thread inside `lh_yield()`.

In the [Go runtime proposal](go-runtime.md), child/timer readiness alone does not imply endpoint demand.
A normal continuation can run on an ordinary host thread, then publish demand and signal HOST_WORK at
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

Here L means a live worker assignment. Making host work eligible for allocation
when a domain has no L is a **separate scheduler obligation**. Preserve pending work until an eligible assignment can
service it. The complete stage needs both domain-level demand and targeted
registered-worker notification for memcached, plus host-only pause/resume
parking. Target identities are not Go P IDs or Linux CPU IDs. The targeting,
allocation and retirement interfaces remain open.

## Allocation and notification state machine

First commit an ABI/state-machine specification and executable race tests; then
implement kernel, RTL and generated collateral together. Use a domain lifetime
identity plus registered pool-thread index for targeting, and an assignment
generation for resolving a live worker. Never accept an arbitrary physical
worker/CPU/Go-P number from userspace as notification authority. Specify publication,
coalescing, acknowledgement, cancellation, retirement, and sequence-wrap rules.

The starting proposal is sticky pending software work with publish-before-notify
ordering and acknowledgement of the observed sequence after draining eligible
work. An older acknowledgement must not clear a newer publication. An assigned
RX wait returns without changing L; packet-first completion keeps its ownership
and leaves work pending. DESCHED-first completion preserves demand through
reallocation. Choose plain NACK versus a distinct RX reason only after writing
these transitions; neither choice is an existing ABI. A plain NACK requires a
separate authoritative pending-work check and must preserve parity/replay rules.

Patch `Scheduler.scala` for host-driven eligibility of domains without packets,
with fairness against packet demand and fixed-pool capacity accounting. Patch
`EciInterfacePlugin.scala`, `EciDecoupledRxTxProtocol.scala` and
`DcsRxAxiRouter.scala` for assigned wake persistence and RX completion, and
`EciDeschedulingControlPlugin.scala` for interactions with handoff. Paths are
under `hw/src/lauberhorn/` (the ECI files under `host/eci/`). Reuse owner checks;
do not conflate an assigned wake with `DeschedInfo` read authorization. Add kernel
validation/control in `sw/kmod/` and public wrappers in `sw/rt/`, plus generated
register/descriptor/version updates through `devHdrs` if the chosen ABI needs them.

Add simulation cases for notification before RX, repeated/coalesced publications,
acknowledgement races, packet and DESCHED competition, stale targets, wrong-domain
access, no assigned L and multiple tenants contending. Extend cooperative
allocation/handoff tests; preserve ordinary no-IRQ handoff. A user-space pending
bit alone cannot allocate an unassigned domain.

## Targeted maintenance and host-only pause/resume

This is a separate change from ending assigned RX waits. In `sw/kmod/sched.c`,
`activate` currently chooses the first enabled waiting thread. Introduce target
claims and eligibility accounting so each registered worker can acknowledge its
own maintenance notification even when there are more registered threads than
physical workers. Specify whether servicing an unassigned target temporarily
allocates L or resumes it on a host CPU without L; this is an unresolved design
choice and cannot be inferred from HOST_WORK. Host-only execution must not use
the mailbox or consume an assignment slot.

Add a voluntary pause/relinquishment protocol with kernel/RTL agreement; existing
`worker_yield` rejects an assigned yield without `yieldRequested`. Do not bypass
that check or issue speculative `DeschedInfo` reads. Define pause epochs,
all-target acknowledgement, parked eligibility, resume-before-park and duplicate
resume handling. Keep a host-side maintenance/timer source runnable when every
worker is paused, with no dependence on receiving a packet or owning Go execution resources or a worker assignment. Retain fail-closed teardown rather than claiming complete recovery.

The [memcached application](../applications/memcached.md) supplies the maintenance
use case. Its adapter is a separately pinned repository. Patch `thread.c` (`pause_threads`, worker
notification/dispatch) and the native worker loop there; `assoc.c` calls the
pause/resume pair during expansion. Advance the platform input/lock to the
tested adapter commit and update `nix/checks/memcached.nix`, measurement tools
and `tools/memcached/mock-runtime.c` tests. Replace the unsupported-pause guard
only after actual hash expansion succeeds: include more worker targets than Ls,
all workers paused, domain initially unassigned, repeated resize, and no new
network traffic needed for resume. Record item/hash statistics and protocol
events, not merely successful mock notifications.

## Open decisions and acceptance

Choose the RX completion ABI, publication/acknowledgement representation, target
lifetime and assignment-generation rules before implementing controls. Decide
whether an unassigned maintenance target temporarily receives an endpoint or runs
on a host CPU without one. Specify fairness against packet demand and pause/resume
eligibility accounting. An assigned notification alone solves none of these
allocation or targeting questions.

Acceptance must cover notification before wait, coalescing, stale acknowledgements,
packet/DESCHED races, wrong-domain and stale targets, and more registered workers
than physical workers. Real memcached hash expansion must complete with every
worker initially parked and without a new packet to drive resume. Existing
ordinary no-IRQ handoff, ownership checks and fail-closed teardown remain required.
Use the [validation workflow](../development/validation.md) for simulation and
matched-image board testing; this proposal records no such implementation result.
