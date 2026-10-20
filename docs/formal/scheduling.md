# Scheduling contracts and composition verification

Current cooperative activation is modeled by `IdleDispatch`, `HandoffClaims`
and `PoolBootstrap`. Their obligations include sticky idle notification,
IRQ-free ordinary handoff, claim serialization, activation delivery and immutable
pool publication. See the [implemented control interface](../software/scheduling-control-interface.md)
and [implementation checks](../software/scheduling-implementation-checks.md).
The original cooperative baseline recorded thirteen outcomes (five positive,
seven faults and one delayed-kick witness); domain-isolation cases are a separate
extension of the cooperative suite. Current case selection is authoritative in
[the suite catalog](../../model/config/scheduling/suite.json).

The focused handoff/wake models and joined `SchedulingComposition` below describe
an earlier, broader design. They retain useful race and resource contracts but
do not implement HOST_WORK, subscription delivery or complete kill/exit recovery.
`HostWake` names remain historical and do not verify the later HOST_WORK design.
Each evidence table belongs to its recorded source and finite bounds.

## Components and ports

| Component | Producer/consumer and event | Ownership, ordering and resource contract |
| --- | --- | --- |
| [Assignment](../../model/tla/scheduling/Assignment.tla) | NIC reservation/DESCHED -> runtime yield -> kernel acknowledgment | Owner remains old while destination capacity is reserved. DESCHED is latched until yield; ordinary syscall sleep retains owner and affinity identity. |
| Assignment InfoAccept | Kernel no-return fence -> accepted MMIO read | Old task cannot resume; no kernel 2F2F CL access. |
| Assignment InfoRespond | NIC-internal readiness -> MMIO response | Atomically installs reserved destination, consumes reservation and captures parity. No commit write follows. |
| Assignment select/activate | Kernel-local claim -> userspace resume | Uses fixed initialized pool and returns the received activation snapshot. |
| [Handoff](../../model/tla/composition/Handoff.tla) transport | Domain-qualified clients -> unchanged Goal 1 resources | Conservatively requires retained old transactions/acknowledgments to finish before InfoRespond; distinct old cached PAs do not gate ordinary activation. |
| [ThreadClaim](../../model/tla/scheduling/ThreadClaim.tla) | Concurrent kernel dispatchers -> thread claim | Selection is not a claim. Atomically claim from the initialized pool; a concurrent loser reselects under its existing reservation. Individual withdrawal is forbidden. |
| [HostWake](../../model/tla/scheduling/HostWake.tla) | Sealed epoll subscription -> kernel pending -> NIC acceptance -> runtime responsibility -> service | Domain-associated cookies survive reassignment. Pending ownership transfers atomically at RX delivery or activation. Notifications concurrent with delivery remain pending. |
| [InterruptGate](../../model/tla/scheduling/InterruptGate.tla) | Timer expiry / IPI sender -> release request -> release acknowledgment -> architectural pending -> service -> rearm | Gate forbids another long RX stall from release through service. Timer/IPI causes coexist; servicing one cannot release the gate while the other remains outstanding. |
| [WakeHandoff](../../model/tla/composition/WakeHandoff.tla) | Incoming notification -> reserved handoff -> info response with host-work snapshot -> local activation | Couples Assignment/Goal 1 resources to HostWake; the kernel holds the indication until runtime can service it. An unaffected worker continues independently. |
| [WakeRetirement](../../model/tla/scheduling/WakeRetirement.tla) | Domain close -> callback drain -> pending-wake service -> detach -> prefix release | New callbacks are fenced; already-running callbacks and kernel/NIC/runtime responsibility must retire before prefix reuse. Historical component assumes endpoint retirement separately; it is not supplied by the revised info read. |
| [ControlRetirement](../../model/tla/scheduling/ControlRetirement.tla) | Kernel close / NIC close fence versus info response, callback and local resume | Preserves offered snapshot; waits for kernel quiescence and endpoint certificate before prefix reuse. Internal resource readiness is abstract in this harness. |
| [TimerMirror](../../model/tla/scheduling/TimerMirror.tla) | Kernel arm/replace/shutdown -> ordered NIC command FIFO | Typed commands preserve generation and submission order; after delivery, NIC state matches the latest arm or shutdown. |

Module state tuples and `INSTANCE` substitution expose these boundaries. The
transport composition uses the unchanged Goal 1 resource modules rather than a
second coherence model. The other harnesses isolate control-plane contracts. `WakeHandoff` joins the
transport and wake paths for one incoming host-driven activation; passing these
checks is not a full cross-product of every scheduling mechanism.

## Handoff and settled-entry contract

The checked transition is:

```text
Run -> reserved DESCHED -> lh_yield / no-return fence
    -> InfoAccept -> [NIC-internal readiness] -> InfoRespond / destination installed
    -> local kernel selection -> local userspace activation
```

DESCHED completes the old abstract RX operation and remains latched until yield.
The response guard checks retained transaction resources, including pending invalidation completions.
This is NIC work, not a kernel drain/flush operation. Parity is captured at the
response, not at a later commit. The separate `RetireDomain` ghost certificate
only gates old prefix reuse; it may happen after ordinary activation. That
certificate's concrete implementation remains unproved. Retained cached generations
are outside the resource model; prior concrete cache-retirement/RTG assumptions
must not be inferred from the abstract Settled predicate.

`Handoff` uses actual control addresses generated from
[`selected-8k.json`](../../model/data/selected-8k.json), with both RX/TX parity bits for the changing worker. The routine handoff
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
worker. The historical UL-era bounds additionally had aggregate unlock depth three/four;
current models have no UL queue. Departing and arriving owners cannot overlap.
 An optional third physical
worker holds RX indefinitely. Old and new domains share physical-worker colors
but cannot issue simultaneously; their full addresses remain distinct in reads,
invalidations and their completions. Retired endpoint bookkeeping is discarded at selection;
physical parity is transferred to the newly installed endpoint.

`CurrentSettled` is the Goal 1 resource predicate projected onto currently
installed endpoints: typing, accepted-transaction tracking, conservation, unused
queues empty, and compatible current footprints. Keeping dormant old/new aliases
in a single global placement predicate would incorrectly require different
colors for two successive owners of the same worker. `New` directly instantiates the unchanged Goal 1 `ProgressSystem` on the
post-handoff clients and projects their state from the shared transport.
`ActivationSafe` checks **that instance's `Settled` predicate**, mailbox/parity
return and transaction readiness at activation. `SteadyEntry` checks it throughout subsequent
execution. The transition-time `CurrentSettled` predicate is not being substituted
for this direct entry check.
Assignments subsequently remain fixed in this one-handoff harness. The ordinary
peer's admission and completion liveness is checked throughout the transition,
including while the info response or local dispatch is pending.

Prefix release requires absence of old ownership, old cache state and full-address
messages. The model checks eligibility to reuse a prefix, not an arbitrary number
of generation-reuse cycles. It has no stale CPU payload/cache-hit semantics;
whole-domain cache retirement is an abstract endpoint certificate whose concrete correctness
still needs refinement. Fixed pre-mapped aliases and trusted ownership checks implement those boundaries;
that source implementation is not a refinement proof of this harness.

## Bounded control-plane contracts

`Reservation` runs two handoffs competing for one destination slot, using the
same Assignment module. It tests successful dispatch with serialized NIC reservations. `ThreadClaim`
connects reservations to a fixed initialized pool of one or two threads, with two
requesting workers. It checks that every reservation remains backed and finishes;
two kernel dispatchers may observe the same candidate but must claim atomically.
There is no individual withdrawal. Historical unavailable cases are removed from the current runner; their recorded
source revisions remain reproducible, but they are not part of this protocol.

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
accepted and runnable runtime work is serviced. Control-register command delivery
and whole-domain retirement certification are abstract ports: their ability to progress
independently of a stalled CPU is an implementation assumption, not a consequence
of these checks. Multi-word MMIO publication, response framing and a full network
queue remaining untouched by HOST_WAKE need implementation-level checks. Ordinary syscall sleeps retain
assignment; the positive liveness environments assume eventual return. An
indefinitely sleeping application may retain capacity without violating safety,
but cannot be promised application progress. No policy fairness for allocation of
an unreserved destination is claimed.

The joined `WakeHandoff` case checks one notification for the arriving domain,
with successful selection, no ordinary syscall sleep and no interrupt. InfoRespond
changes ownership and transfers the host indication to a kernel-held return
snapshot; local activation makes it serviceable without another NIC command.

The [resource model](resource-progress.md) adds retained-cache accounting.
The joined composition below connects focused claim/wake/kill models to it.
Neither validates current kernel MMIO framing or the undocumented THX resource
assumptions; broader bounds remain separately identified.

## Historical results before blocking-info-read revision (2026-09-30)

The final source at `2117f92` passed **26 expected baseline outcomes** through
Nix and an independent development-shell run: eleven positive cases and fifteen
intentional counterexamples. Their normalized results and provenance agree
exactly. The larger stalled-worker RX case also passed. The unchanged Goal 1
Nix check remains independently runnable and passed (cached result).

The [tracked evidence manifest](../../model/evidence/scheduling/evidence.json) records
source, checker, configuration and log hashes, bounds and timings. Full recorded
logs/counterexamples are local ignored artifacts in `out/scheduling/entry-baseline/`
and `out/scheduling/entry-stalled/`. No new RTL simulation or board result is claimed.

| Positive case | Distinct states | Checked scope |
| --- | ---: | --- |
| handoff | 16176 | RX handoff, both initial RX/TX parity values, direct Goal 1 entry, old-prefix retirement and peer admission/completion |
| unavailable | 8688 | No target thread: idle cancellation, released reservation, peer progress |
| reservation | 17 | Two dispatches compete for one destination slot |
| reservation-unavailable | 28 | Failed selections release destination capacity |
| thread-claim | 73 | Concurrent selection/claim and withdrawal races |
| host-wake | 1867 | Two cookies, four notifications, hot subscriber, reassignment and delivery races |
| host-wake-parked-shared | 2795 | Initially parked activation; two threads share one subscription |
| wake-handoff | 23488 | Joined host-driven transport handoff, atomic activation/delivery, direct Goal 1 entry and unaffected peer progress |
| wake-retirement | 190 | In-flight callback and pending wake ownership delay prefix release |
| interrupt | 54 | Timer/IPI release, pending, service and rearm ordering |
| timer-mirror | 10 | Ordered arm/replacement/shutdown commands |
| handoff-stalled-peer (extended) | 67968 | RX handoff and peer progress while a third worker holds RX indefinitely; direct Goal 1 entry |

Expected negatives remove drain, cache retirement or parking protection; corrupt
returned parity; release a prefix early; over-reserve destination capacity;
lose a concurrent notification or accepted wake at reassignment; deliver to the
wrong subscriber; rearm or release the interrupt gate early; double-claim a
thread; ignore callback/wake references at retirement; or reorder timer commands.
The interrupt fault is checked both as an invariant failure and a liveness failure.

Full RX/TX operation-switching remains a larger bound. An earlier two-worker
snapshot completed an 84464-state safety graph but timed out in liveness at
600 seconds. The `eec6277` snapshot with a third stalled worker completed a
366400-state graph but timed out in liveness at 1200 seconds. These are
**incomplete**, not passing progress results or verification of the final source's
new entry assertion. Their historical logs remain archived; the latter has its
own provenance in the manifest. Parser/initialization debugging runs and
interrupted development Nix runs are not acceptance evidence.

## Revised blocking-info-read evidence

At source `5b9c922`, all **35 baseline outcomes** passed through both the pinned
Nix check and `nix develop`: 13 positives and 22 expected negatives. Their normalized
case results match. Handoff explores 17,952 states; the revised host-wake/transport
composition explores 25,408; the close/response/callback harness explores 115.
The [control-interface evidence](../../model/evidence/scheduling/control-evidence.json)
records source/configuration/log hashes and the comment-only provenance difference
between developer and Nix snapshots. Debug parser/evaluation failures are excluded.
No production RTL/kmod, actual-DCS simulation or board validation was performed.

The extended `handoff-stalled-peer` case also passed on the revised sources:
75,648 states, with safety, direct abstract settled entry, handoff completion and
unaffected-peer admission/completion liveness checked while the third worker
holds RX indefinitely. Full RX/TX switching was not rerun in that recorded revision.

## Reproduction

Tools come from the existing Nix lock, including nixpkgs `tlaplus18`:

```sh
nix build .#checks.x86_64-linux.scheduling-progress --out-link out/scheduling/nix-check
nix develop -c python3 tools/scheduling/addresses.py --check
nix develop -c python3 tools/scheduling/check_models.py --workers 4 --timeout 900 --output out/scheduling/run
# Focused notification, claim and retirement races.
nix develop -c python3 tools/scheduling/check_models.py --races-only --output out/scheduling/races --timeout 900 --workers 4
# Extended operation directions and a separate stalled-third-worker bound.
nix develop -c python3 tools/scheduling/check_models.py --case handoff-rx-tx --workers 4 --timeout 1200 --output out/scheduling/rx-tx
nix develop -c python3 tools/scheduling/check_models.py --case handoff-stalled-peer --workers 4 --timeout 1200 --output out/scheduling/stalled-peer
```

`--workers` selects TLC checker threads, not physical workers in the model.
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

## Joined scheduling, wake and transport

`SchedulingComposition` imports focused claim/wake components and `KillTransport`
for A→B→A, notification generations, concurrent claims and close with RX/TX
cleanup. It is distinct from the older `CombinedScheduling`, which restates
control state inside a resource harness. Neither is a complete system refinement.

## Joined composition: Components and ownership

The harness imports actions and properties rather than copying their state machines.
It supplies cross-component guards and applies fairness to those guarded actions;
it does not assume the imported `Spec`s.

| Instance | Owned state and reused checks |
| --- | --- |
| `KillTransport` | Old A's kill control, cached 2F2F resources, response queue, TX invalidation batch, writeback and packet ownership. Reuses `Safety`, kill stop/exit and peer admission/completion properties. |
| `WakeReassignment` | Current assignment, submitted routing hint and kernel/NIC/runtime wake responsibility. Reuses type, responsibility and subscriber invariants. |
| `WakeAckRace` | Notification generations and separate submit/accept/ACK boundaries. Reuses no-lost-notification and eventual resolution. |
| `ThreadClaim` | NIC reservations and atomic kernel claim/retry in the initialized B pool. Reuses type, uniqueness, capacity and reservation-backing invariants. |
| `ControlRetirement` | Its immutable-response property is instantiated through a view of the offered info record. Its retirement state machine is not duplicated or assumed. |

The imported kill reply is a close-completion record only. Ordinary dispatch
uses the `info` record; there is no second ordinary MMIO exchange. Callback
lifetime is enabled in close-capable cases; direct notifications remain enabled
in the roundtrip-only cases.

Transport clients 1 and 4 represent A and B on the **same** moving worker. They
use different full addresses but the same colors. Client 2 holds RX indefinitely;
client 3 is the optional repeating independent peer. The `ThreadClaim` instance's
second claimant is a separate abstract kernel dispatcher competing for B's pool;
it is **not** transport client 2. Its finite work/release assumption in the normal roundtrip cases never forces
the stalled RX to finish. The kill/reservation case removes release fairness
entirely: the destination thread may remain assigned elsewhere forever. The old-domain kill state continues to describe A when
B runs; B's work is a real `CachedTransport` exchange, not `SuccessorWork` from
the abstract kill-control harness. B and the peer use its abstract CPU-completion
boundary; the victim alone has separate NIC response and CPU-consumption stages.
B's parking action represents cooperative kernel acknowledgment after quiescence,
not a permission to stop a CPU merely because its transport happens to be quiet.

`KillTransport` exposes cooperative normal completion and optional successor
addresses; its focused configuration keeps both disabled. `ThreadClaim` exports
release and cancellation ports without changing its focused one-shot `Next`.
Cancellation releases an unused destination reservation when close parks the
worker; it does not withdraw or shut down an individual initialized thread.
`WakeReassignment` exports snapshot capture, close disposition, command reset and
stage-update ports. Their callers must enforce the guards documented below.

## Joined composition: Synchronization boundaries

Publication creates both a generation-qualified command and a routing hint.
Acceptance transfers notification responsibility to the NIC; its ACK can arrive
before or after runtime service. A later callback must survive that ACK. Only
after ACK **and** terminal disposition does the adapter reset the one-command
routing state for another generation. Coalescing is permitted; generation counts
are proof state, not a proposed unbounded wire field.

Delivery revalidates the current subscriber. Returning to A can capture pending
work into the blocking info response. The entire offered `{owner, parity, wake}`
record stays stable until consumption, including across close. Capture transfers
responsibility to runtime; response consumption and activation remain separate.
This reduced harness has one transport direction; the production response still
contains physical mailbox identity and separate RX/TX parities as specified in
[the control interface](../software/scheduling-control-interface.md).

Voluntary kernel entry requires already-delivered host work to finish. Host work
remains serviceable after DESCHED while the worker is draining. The original
composition violated this rule: `Deliver → Begin` disabled service in `Draining`,
while `Park` waited for runtime responsibility to disappear. The resulting fair
execution never completed the wake or handoff. `bad-full-host-drain` retains that
counterexample by disabling draining-stage service. This is a runtime/worker
sequencing requirement, not a reason to force the stalled peer to return.

Parking waits for old transport, response consumption and packet disposition.
Installation transfers parity only after that boundary; activation checks the
existing `ProgressSystem!SettledFor` predicate for the active clients. That
predicate includes resource accounting, compatible placement, legal transport
state and quiet inactive endpoints. It does not require clean caches. This discharges the modeled resource part of
the Goal 1 entry contract; endpoint freshness remains a premise below. Retained
old-domain cache entries and replacement traffic remain represented.

After close, voluntary exit remains possible but is never a fair service action.
The IRQ/RX-release path must stop a running A without application cooperation,
while an optional exit can still race an already pending IRQ. A cross-component
`NoLateIrq` invariant checks that B cannot run with A's IRQ still needed/pending;
the old kill component alone cannot see B's separate transport identity. A task
already in the kernel before close needs no new scheduling IRQ.
`bad-full-irq` removes the IRQ-drain fence and must violate that invariant.

Close fences new notification acquisition but a callback already in flight may
still publish its last notification. Teardown resolves accepted work by explicit
disposition, without executing killed userspace. The instance substitutes
`resolved` for `WakeAckRace.serviced`: its liveness means service **or close
disposition**, not successful application execution after kill. Metadata release
waits for callback join, all generations resolved, command ACK and no old offered
info response. A return to a newly closed A is canceled; B retains its existing
claim. Killing A can park without waiting for destination pool capacity.

A repeating competing claimant exposed another counterexample: a candidate can
become free and be stolen repeatedly, so weak fairness of an atomic claim does
not imply eventual dispatch. Capacity and uniqueness still hold. The model
requires strong fairness of eligible claims; `bad-full-claim-fairness` restores
the weaker assumption and checks for a stalled roundtrip. An implementation can
select and claim under the same domain lock, or provide starvation-free
arbitration/retry. This is an explicit implementation obligation, not a hardware
fairness theorem.

## Joined composition: Properties and bounds

New properties concern only the joins: transport quiet at install, generation and
routing-command consistency, no freed wake references, and correct info identity
plus the imported settled predicate at activation. Lower-level conservation,
queue tracking, unique claims, notification ownership and kill completion are
referenced through instances. `MovesFinish` covers the bounded roundtrip if A
never closes. It does not promise eventual scheduling of an arbitrary domain
under an unspecified allocation policy.

In the targeted kill-handoff configurations, departure from A requires close;
normal A → B → A is checked separately. Once B activates after kill,
`SuccessorProgress` requires its available exchange to be admitted and complete.
A dedicated witness reaches that completion after freeing A while client 2
remains held. The broader close-at-any-point cases retain normal migration.

The configured matrix separates:

- RX handoff with and without close, one or two notifications, one- and two-thread
  B pools, and a competing claimant;
- committed TX versus abandoned TX cleanup during close;
- recurring peer admission/completion during roundtrip and close, while another
  worker holds RX indefinitely;
- deliberate faults in ACK reconciliation, routing, reservation/claim,
  transport drain, response stability, wake lifetime and draining-stage service;
- reachable roundtrip with a completed B exchange, and closed-wake disposal with
  the stalled load still held.

The positive configurations are explicit in the tracked suite catalog:

| Case | Added coverage (other settings use the default RX, one notification, one-thread pool) | Distinct states checked |
| --- | --- | --- |
| `full-scheduling` | Close may race either handoff and wake resolution. | 735,466 |
| `full-roundtrip` | Close disabled; eventual A → B → A is required. | 8,034 |
| `full-wake-generation` | Two notifications, including late callback versus ACK/close. | 2,436,144 |
| `full-reservation` | Kill handoff with a one-thread destination possibly held forever; kill must not wait for its release. | 404,428 |
| `full-reservation-roundtrip` | Normal roundtrip contending for one thread; eligible NIC reservation is strongly fair. | 16,656 |
| `full-claim` | Two-thread pool, repeating competitor and two notifications; close disabled. | 206,592 |
| `full-committed-tx` | Committed TX read, invalidation/writeback/output and close. | 894,124 |
| `full-abandoned-tx` | Abandoned TX; cleanup starts after no-resume certification. | 112,686 |
| `full-stalled-progress` | Committed-TX kill handoff to B with a repeating independent transport peer. | 4,480,700 |
| `full-roundtrip-progress` | Repeating peer and required roundtrip, close disabled. | 200,850 |
| `full-claim-contention` | Two-thread repeating claim race isolated from notifications and close. | 4,836 |

The fault configurations each select their named obligation, while retaining
lower-level resource safety. A parser error, timeout or unrelated invariant
failure is not accepted as the expected counterexample.

| Fault case | Required failing obligation |
| --- | --- |
| `bad-full-ack` | `NoLostNotification` |
| `bad-full-drop` | `WakeResponsibility` |
| `bad-full-route` | `RightSubscriber` |
| `bad-full-reserve` | `CapacitySafe` |
| `bad-full-claim` | `UniqueClaim` |
| `bad-full-claim-fairness` | `MovesFinish` |
| `bad-full-drain` | `DrainBeforeInstall` |
| `bad-full-snapshot` | `SnapshotStable` |
| `bad-full-free` | `MetadataWakeSafe` |
| `bad-full-host-drain` | `WakeCompletes` |
| `bad-full-irq` | `NoLateIrq` |

Three additional checks negate desired reachable states: a roundtrip after B's
exchange, closed-wake disposal with client 2 held, and successor completion after
A is freed with client 2 held. Their counterexamples establish reachability,
not universal progress; temporal properties in the positive cases provide the
bounded liveness checks.

Every case includes the indefinitely stalled transport client. Peer progress is
asserted only in the two `WithPeer` configurations. These cases are a selected
matrix, not the Cartesian product of every parameter. `full-claim-close`,
`full-reservation-extended` and `full-stalled-progress-extended` retain larger
combinations as explicit cases outside the default Nix check. Close versus destination reservation is covered by `full-reservation`;
after installation B's fixed pool survives A's close. Closure of B itself remains
a separate scope limit.

Moving A performs at most one RX before its first handoff and B at most one
exchange. Returning A checks activation, parity and wake/control races; subsequent
data traffic belongs to the separately checked Goal 1 entry contract. Packet
ownership models the initial buffers, not repeated allocation of every RPC.
That bookkeeping does not prove safe reuse by a shared allocator: an actual RX
loan must be recycled at protocol release, or after kernel no-return acknowledgment
and outstanding-access drain. The kill latch alone is insufficient.
Peer-enabled cases repeat client 3 indefinitely; other cases still include the
stalled client but omit recurring peer traffic to isolate control interleavings.
The TX batch has one control and two overflow lines. Initial parity is zero;
subsequent exchanges and returning A exercise parity changes. Three shared read
slots, four conservative THX credits, two directory ways per set and one queue
entry per color are finite model bounds. Addresses use a small modulo-12 geometry
with B offset by 12. The selected physical 8 KiB layout remains independently
checked by the capacity and selected-address handoff suites.

The finite control workflow has weakly fair eligible service as one actor.
Repeating transport clients and claim contenders have separate service fairness
and cannot satisfy that control obligation by their own activity. Eligible
transport admission, preparation, completion and candidate selection are weakly
fair. With a repeating contender, one-slot handoff reservation and two-slot
eligible claims are strongly fair. The other configurations need only weak
fairness: a reserved one-slot claim cannot be stolen, and the two-slot pool always
has room for the moving worker's reservation.
This grouping relies on the configured finite notification/handoff scope; it is
not a fairness claim for unbounded arrivals. Close, callbacks and
voluntary work generation are not forced. Output destinations accept committed
work under the existing packet-resource contract. No fairness assumption enables
a blocked coherence action or requires the stalled worker to release its load.

## Joined composition: Reproduction and limits

```sh
nix build --max-jobs 3 .#checks.x86_64-linux.scheduling-full
nix build .#checks.x86_64-linux.tla-modules
nix develop -c python3 tools/scheduling/check_models.py \
  --full-only --output out/modeling/scheduling-full --timeout 21600 --workers 4 --heap-mib 4096
```

Each full-suite case is a separate Nix derivation; the aggregate verifies matching
source/tool identities and joins the results. Each case uses four TLC workers and
a 4 GiB heap; `--max-jobs 3` bounds simultaneous processes on the development host.
The Nix check uses locked `tlaplus18`, preserves generated configurations and TLC
logs, and rejects timeouts/parser errors as negative-case evidence. The selected
matrix completed: 11 positive cases, 11 expected fault
counterexamples and 3 reachability witnesses, plus all 33 module-boundary/SANY
checks. Twenty-four outcomes completed as Nix derivations. `full-stalled-progress`
reached the original two-hour timeout, then completed exploration and temporal
checking from its preserved checkpoint using the same pinned Nix TLC, source,
configuration, fingerprint and properties. The original timeout remains recorded
as incomplete. The fresh Nix case has a six-hour budget; the aggregate was
dry-run after this change, not rebuilt. Other cases retain two hours. The
[evidence record](../../model/evidence/scheduling/scheduling-composition-evidence.json)
records exact source/configuration/tool and log hashes, individual Nix outputs,
checkpoint provenance, final counts and component regressions. The recovery tool
was separately tested with both passing and failing temporal fixtures; those
fixtures are tool diagnostics, not protocol evidence. These results complete the
selected bounded modeling round under the assumptions below.

The abstract client also assumes an issued operation becomes a fresh protocol
transaction, rather than a stale CPU cache hit. Directory occupancy does not
prove that value-level condition on A → B → A. The existing RX engine
(`repeatDesc → invalidateCtrl → waitInvResp`) invalidates the last delivered
control line during preemption and waits for LCIA; preserve that protocol-local
cleanup across implementation changes. Retained old PAs do not require a general kernel
cache flush. Exact correspondence of this final RX-control cleanup and returned
values remains an endpoint/refinement obligation, not a result of this harness.

Control commands and kernel MMIO completion are assumed serviceable independently
of stalled data-plane traffic. The harness does not put those commands into the
actual control-bus queues; control-path tests must
establish that premise.
The harness also abstracts a CPU's request-side MMIO stall. Its conservative
IRQ-before-`Install` order must not be copied into an implementation as a response
waiting for an IRQ handler on the blocked reader. The required observable fence
is IRQ drain before successor activation. A concrete implementation can retain a
completed response in kernel storage while draining the old IRQ, preserving the
old target/reference. This is the existing control-interface no-wait-cycle rule
at the request/IRQ boundary; the request/IRQ correspondence is not discharged here.
A directed implementation test should hold IRQ acknowledgment until the info read
completes, require the response once its transport prerequisites finish, and keep
the successor non-runnable until the old IRQ is consumed.

The implemented [RX writeback service](../hardware/eci-implementation.md#rx-writeback-and-permission-changes)
is a correspondence anchor for independent response service. Its directed checks
and board evidence do not make this abstract composition an RTL proof.

Separate obligations remain: single-line endpoint correctness, THX replacement
and independent coherence service, actual AXI framing/same-line ordering, RTL
refinement and board validation. This harness does not enumerate multiple closing
workers sharing packet queues, closure of B while its two dispatchers claim,
concurrent full-duplex bypass, all starting parities, arbitrary notification
counts or packet lengths. Request-side MMIO framing remains in the focused
control-interface contract; this join represents snapshot offer/consumption. Monotonic PA codes are
not reused. No kernel cache flush, normal scheduling IRQ, or activation commit
write is introduced. The older `CombinedScheduling` retains its distinct bounded
two-worker and optional PA-reuse coverage; it is not evidence for this composition.
