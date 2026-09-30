# Scheduling transition verification

Goal 2 models the selected cooperative scheduling pivot, following the accepted
[Goal 1 milestone](steady-state-verification.md). This is executable design work,
not implementation of the new scheduler, router-free decoder or kernel ABI.
The production scheduler still uses the older IRQ/CAS/thread-router protocol.
The models check finite instances and explicit component contracts; they do not
claim whole-system hardware refinement.

## Source audit and scope

The implementation audit starts at platform `7387e05`. The
[pivot](../architecture/sched-design-pivot.md) supplies the intended behavior.
Current `Scheduler.scala` already distinguishes `coreResident` from the early
`corePidMap` reservation. `sw/kmod/sched.c` explicitly retains the old route until
`datapathDrained`, then updates affinity/routing/parity; this is useful ordering
evidence, not an implementation of cooperative yield. `EciPreemptionControlPlugin`
exposes acknowledgment and drain separately. Existing no-target-thread failure
handling and return-to-user parking are documented in the
[current scheduler](../software/scheduler.md).

No production RTL, kernel or runtime changes are part of this model work. The
[planned DCS RTL simulations](../research/roadmap.md#targeted-dcs-rtl-simulation-after-replacement-rtl-is-available)
remain scheduled for the replacement RTL. Main ECI suites substitute Scala
`DcsAppMaster` for the DCS RTL.

## Components and ports

| Component | Producer/consumer and event | Ownership, ordering and resource contract |
| --- | --- | --- |
| [Assignment](../../model/scheduling/Assignment.tla) | NIC reservation/PREEMPT -> runtime yield -> kernel acknowledgment | Owner remains old while destination capacity is reserved. PREEMPT is latched until yield; ordinary syscall sleep retains owner and affinity identity. |
| Assignment drain/retire/park | Transport and cache-retirement acknowledgment -> kernel parking | Kernel acknowledgment alone does not authorize installation. Drain precedes cache retirement and parking; old ownership is removed only at parking. |
| Assignment install/activate | Kernel thread selection -> owner installation -> runtime resume | Successful selection consumes a reservation; unavailable selection cancels it and leaves the core idle. Activation returns physical mailbox and both parities. |
| [Handoff](../../model/scheduling/Handoff.tla) transport | Domain-qualified clients -> unchanged Goal 1 `ProgressSystem`, `DcsResources`, `ThxResources`, `TwoFClient` | Old/new owners are distinct transaction identities on the same physical colors. Admission is fenced after yield. Retained acknowledgments and ordered unlocks must retire before installation. |
| [ThreadClaim](../../model/scheduling/ThreadClaim.tla) | Concurrent kernel dispatchers -> thread claim | Selection is not a claim. Revalidate availability and atomically claim; a concurrent loser retries or reports unavailable. A claim excludes withdrawal and duplicate assignment. |
| [HostWake](../../model/scheduling/HostWake.tla) | Sealed epoll subscription -> kernel pending -> NIC acceptance -> runtime responsibility -> service | Domain-associated cookies survive reassignment. Pending ownership transfers atomically at RX delivery or activation. Notifications concurrent with delivery remain pending. |
| [InterruptGate](../../model/scheduling/InterruptGate.tla) | Timer expiry / IPI sender -> release request -> release acknowledgment -> architectural pending -> service -> rearm | Gate forbids another long RX stall from release through service. Timer/IPI causes coexist; servicing one cannot release the gate while the other remains outstanding. |
| [WakeHandoff](../../model/scheduling/WakeHandoff.tla) | Incoming host notification -> reserved handoff -> atomic transport/assignment activation and wake delivery | Couples Assignment/Goal 1 resources to HostWake; runtime services the activation flag before its next RX. An unaffected worker continues independently. |
| [WakeRetirement](../../model/scheduling/WakeRetirement.tla) | Domain close -> callback drain -> pending-wake service -> detach -> prefix release | New callbacks are fenced; already-running callbacks and kernel/NIC/runtime responsibility must retire before prefix reuse. Transport/cache retirement is an imported Assignment contract. |
| [TimerMirror](../../model/scheduling/TimerMirror.tla) | Kernel arm/replace/shutdown -> ordered NIC command FIFO | Typed commands preserve generation and submission order; after delivery, NIC state matches the latest arm or shutdown. |

Module state tuples and `INSTANCE` substitution expose these boundaries. The
transport composition uses the unchanged Goal 1 resource modules rather than a
second coherence model. The other harnesses isolate control-plane contracts. `WakeHandoff` joins the
transport and wake paths for one incoming host-driven activation; passing these
checks is not a full cross-product of every scheduling mechanism.

## Handoff and settled-entry contract

The checked transition is:

```text
Run -> reserved PREEMPT -> lh_yield -> kernel acknowledgment -> transport drain
    -> old cache retirement -> parked / no owner -> install -> activate
                                                -> unavailable / idle
```

A committed PREEMPT is requested at idle RX. Finite old-owner work and the
abstract PREEMPT response use the normal transport exchange. Once that exchange
returns, no fresh old-owner operation is admitted. The request persists until
yield. Queued unlocks may outlive software return, so `Quiet` checks both phase
and all retained resources. A separate old-cache retirement acknowledgment is
required before parking and prefix release.

`Handoff` uses actual control addresses generated from
[`selected-8k.json`](../../model/steady/selected-8k.json), with both RX/TX parity bits for the changing worker. The routine handoff
regression executes RX; `handoff-rx-tx` additionally allows repeated RX/TX direction
changes. Both cases explore both values of the returned TX parity. Its dependency footprint is **control
lines only**, deliberately smaller than Goal 1's twelve-overflow-line witnesses.
The stable peer executes repeated RX operations.
Model client IDs are not physical worker IDs:

| Model client | Selected-map worker index | Domain | Role |
| --- | ---: | ---: | --- |
| 1 | 0 | 0 | departing owner |
| 2 | 1 | 2 | unaffected peer |
| 3 | 0 | 1 | arriving owner |
| 4 (optional) | 2 | 3 | permanently stalled RX |

The returned mailbox `1` is the model's one-based physical slot for selected-map
worker index 0, not a proposed new ABI numbering rule. There is one invalidation
FIFO per color, depth one; two modeled slices; three shared read/ack credits and
three reads per slice in the two-worker case, increased to four with the stalled
worker. Aggregate unlock depth is three/four (the number of domain-qualified
client identities), although departing and arriving owners cannot overlap.
 An optional third physical
worker holds RX indefinitely. Old and new domains share physical-worker colors
but cannot issue simultaneously; their full addresses remain distinct in reads,
invalidations and unlocks. Retired endpoint bookkeeping is discarded at selection;
physical parity is transferred to the newly installed endpoint.

`CurrentSettled` is the Goal 1 resource predicate projected onto currently
installed endpoints: typing, accepted-transaction tracking, conservation, unused
queues empty, and compatible current footprints. Keeping dormant old/new aliases
in a single global placement predicate would incorrectly require different
colors for two successive owners of the same worker. `New` directly instantiates the unchanged Goal 1 `ProgressSystem` on the
post-handoff clients and projects their state from the shared transport.
`ActivationSafe` checks **that instance's `Settled` predicate**, mailbox/parity
return and retirement at activation. `SteadyEntry` checks it throughout subsequent
execution. The transition-time `CurrentSettled` predicate is not being substituted
for this direct entry check.
Assignments subsequently remain fixed in this one-handoff harness. The ordinary
peer's admission and completion liveness is checked throughout the transition,
including while drain or dispatch is pending.

Prefix release requires absence of old ownership, old cache state and full-address
messages. The model checks eligibility to reuse a prefix, not an arbitrary number
of generation-reuse cycles. It has no stale CPU payload/cache-hit semantics;
cache retirement is an explicit abstract acknowledgment whose concrete correctness
still needs refinement. Fixed pre-mapped aliases and trusted ownership enforcement
remain implementation obligations.

## Bounded control-plane contracts

`Reservation` runs two handoffs competing for one destination slot, using the
same Assignment module. It tests successful dispatch and unavailable-thread
cancellation. `ThreadClaim` separately allows two dispatchers to observe the same
available thread, including withdrawal between selection and claim. Reservation
capacity and atomic thread claims are separate requirements.

`HostWake` has two domain-associated cookies, two notification generations per
cookie, one physical worker, and two threads; the shared-subscription case adds a
third thread subscribed to the first cookie and starts with no assigned thread.
Subscriptions are sealed before `Init`. Both initially parked activation and
notifications after assignment are reachable. A sleeping subscriber stays hot;
it retains its assignment and services notifications on resumption. Event IDs
are ghost accounting for coalescing, not proposed wire fields. All NIC-pending
notifications for a cookie may transfer together. Runtime responsibility persists
until service, and the old subscriber must finish runtime-owned work before
release. Source cookies remain live throughout this harness. `WakeRetirement` separately
composes it with source closure and an in-flight callback; it checks retirement
progress and prohibits freeing the prefix while any callback or wake reference
remains. A negative case deliberately omits those references from the free guard.

`InterruptGate` checks one timer and one IPI cause, including overlap and delivery
latency. A disabled timer does not generate expiry; replacement can arm it again.
`TimerMirror` separately checks an arm, replacement and shutdown sequence with
commands in flight. Exact clock conversion, deadline arithmetic, hook coverage,
IPI variants and unbounded repeated interrupt generations are outside these
finite abstractions. The coherence-timeout fallback remains necessary.

## Fairness and remaining composition obligations

Fairness applies to eligible resource service and arbitration, using the Goal 1
credit assumptions. No action forces the designated stalled worker to complete.
A progressing CPU executes its finite work; wake callbacks/commands are eventually
accepted and runnable runtime work is serviced. Ordinary syscall sleeps retain
assignment; the positive liveness environments assume eventual return. An
indefinitely sleeping application may retain capacity without violating safety,
but cannot be promised application progress. No policy fairness for allocation of
an unreserved destination is claimed.

The joined `WakeHandoff` case checks one notification for the arriving domain,
with successful selection, no ordinary syscall sleep and no interrupt. Parking
removes the old wake target only after transport/cache retirement; activation
atomically returns mailbox/parity and transfers pending wake responsibility.

The complete transport/assignment cross-product still needs unavailable-target
reconciliation, old-domain notifications racing this joined handoff, concurrent
thread claims, and actual RX gating during early NACK. It must connect the checked
wake-retirement guard to transport/cache retirement in one domain teardown. Prefix reuse must satisfy both contracts together. Accordingly these bounded checks do not discharge
all Goal 2 acceptance obligations. Repeated handoffs, migration between physical
workers, broader worker/domain bounds, overflow retirement and full-duplex bypass
during transitions also remain to be checked. Hardware refinement inherits Goal
1's DCS shared-path and undocumented THX resource assumptions.

## Reproduction

Tools come from the existing Nix lock, including nixpkgs `tlaplus18`:

```sh
nix build .#checks.x86_64-linux.scheduling-progress --out-link out/scheduling/nix-check
nix develop -c python3 tools/scheduling/addresses.py --check
nix develop -c python3 tools/scheduling/check_models.py --workers 4 --timeout 900 --output out/scheduling/run
# Extended operation directions and a separate stalled-third-worker bound.
nix develop -c python3 tools/scheduling/check_models.py --case handoff-rx-tx --workers 4 --timeout 1200 --output out/scheduling/rx-tx
nix develop -c python3 tools/scheduling/check_models.py --case handoff-stalled-peer --workers 4 --timeout 1200 --output out/scheduling/stalled-peer
```

The default suite includes the joined `wake-handoff` case and excludes the two
extended cases. Terminal component states legitimately stutter, so TLC deadlock
checking is disabled; explicit liveness properties check unfinished obligations
and unaffected-worker progress. The runner records exact source/jar/config/log hashes, commands, time bounds and
outcomes. Expected negatives must violate their named invariant/property;
parse failures and timeouts cannot pass. Generated graphs, logs and counterexample
traces stay under ignored output directories. Use a fresh tmpfs output directory
and archive it to `out/scheduling/` for larger checks if disk graph access dominates.
The Nix result excludes volatile timings/log hashes and retains reproducible
source/configuration provenance.
