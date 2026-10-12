# Scheduling, wake and transport composition

Historical bounded-model evidence. This report includes wake/close scenarios
outside the current implementation stage; it does not define another supported
ECI design. The sole current contract is [cooperative scheduling
control](../software/scheduling-control-interface.md), with focused models and
concrete tests recorded in [the implementation ledger](eci-implementation.md).
No wake fields, subscriptions or close-resource reuse from this older harness
are part of that implementation. The [layered verification report](layered-verification.md)
explains reusable transport contracts and their remaining assumptions.

The [SchedulingComposition harness](../../model/tla/composition/SchedulingComposition.tla)
joins the focused scheduling contracts to actual abstract transport resources.
It checks A → B → A on one physical worker, notification/ACK generations,
concurrent fixed-pool claims, immutable preemption information, and whole-domain
close with RX/TX cleanup. This is bounded interaction verification under endpoint
service assumptions, not an RTL refinement proof.

## Components and ownership

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

## Synchronization boundaries

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
not imply eventual dispatch. Capacity and uniqueness still hold. The model now
requires strong fairness of eligible claims; `bad-full-claim-fairness` restores
the weaker assumption and checks for a stalled roundtrip. An implementation can
select and claim under the same domain lock, or provide starvation-free
arbitration/retry. This is an explicit implementation obligation, not a hardware
fairness theorem.

## Properties and bounds

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
and outstanding-access drain. The kill latch alone is insufficient (H20).
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

## Reproduction and limits

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
as incomplete. The fresh Nix case now has a six-hour budget; the aggregate was
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
cleanup in the new design (H18). Retained old PAs do not require a general kernel
cache flush. Exact correspondence of this final RX-control cleanup and returned
values remains an endpoint/refinement obligation, not a result of this harness.

Control commands and kernel MMIO completion are assumed serviceable independently
of stalled data-plane traffic. The harness does not put those commands into the
actual control-bus queues; implementation check H9 and actual-DCS tests must
establish that premise.
The harness also abstracts a CPU's request-side MMIO stall. Its conservative
IRQ-before-`Install` order must not be copied into an implementation as a response
waiting for an IRQ handler on the blocked reader. The required observable fence
is IRQ drain before successor activation. A concrete implementation can retain a
completed response in kernel storage while draining the old IRQ, preserving the
old target/reference. This is the existing control-interface no-wait-cycle rule
made explicit as H19; the request/IRQ correspondence is not discharged here.
A directed implementation test should hold IRQ acknowledgment until the info read
completes, require the response once its transport prerequisites finish, and keep
the successor non-runnable until the old IRQ is consumed.

The [October 2 board trial](../development/hardware-test-results.md#simplified-lci-only-dc-trial--2026-10-02)
exposed a concrete violation of the independent writeback-service premise: the
selected DC requests RX writeback, but the current RX AXI router has no write
service. These conditional model results do not establish progress for that
integration. Resolve the RX writeback blocker before board acceptance.

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
