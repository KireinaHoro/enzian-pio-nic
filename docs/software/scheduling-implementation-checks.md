# Cooperative scheduler: races and implementation obligations

Design/model checklist, 2026-09-30. These are requirements for the replacement
kmod/HW, not claims that the current IRQ/thread-router implementation supplies
them. The [pivot](../architecture/sched-design-pivot.md) defines the intended
protocol; [Goal 2 evidence](../hardware/scheduling-verification.md) records bounds.

Normal handoff is **PREEMPT -> `lh_yield()` -> kernel acknowledgment**.
The NIC scheduling interrupt is only for application kill. Existing Linux timer/
IPI service and early NACK are separate from cooperative dispatch; the timer/IPI
model is not a kill model. The [kill/exit model](../hardware/kill-exit-verification.md) now checks kill
racing dispatch/teardown under explicit endpoint service contracts.

## What merits an interleaving model

A single-owner state machine can rule out its own out-of-order transitions.
Independent actors cannot be ordered by an assertion on one side: they need an
identified linearization point, pending ownership or lifetime protocol. A lock
can implement that boundary, but the correct boundary and outcomes still need
checking. Assertions diagnose violations; they do not replace synchronization.

| Class | Events / actors | Decision |
| --- | --- | --- |
| Real race | Poll callback vs kernel processing a delayed NIC acceptance acknowledgment | Keep new readiness pending; clear only the submitted generation. Model the split submit/accept/ack boundaries. |
| Real race | Kernel wake publication vs NIC release/install/delivery | A target observed at submission may be stale. Serialize acceptance/delivery with assignment changes; retain source ownership and re-resolve eligibility. |
| Real race | Two kernel dispatchers selecting the same thread from a fixed initialized pool | Atomically validate-and-claim under lifetime protection; a losing selection retries within its guaranteed reservation. NIC serialization prevents over-reservation. |
| Real race | Callback already in flight vs unsubscribe/domain teardown | Fence new callbacks, wait for references, then retire accepted/pending work before cookie/prefix reuse. |
| Shared serialization | Network allocation, host allocation and per-domain capacity reservations | One NIC scheduling commit point and accounting rule. Simultaneous inputs need defined priority; do not let two pipelines independently observe the last free slot. |
| Local sequencing | Yield fence, info-read acceptance/response and local activation | Encode legal predecessors in the worker FSM. Do not expand every impossible permutation into a separate scheduling-race model. |
| Local sequencing | Mailbox/parity return, ordinary syscall sleep, sequential timer-register updates | Use one committed snapshot / owner and an ordered command path, with assertions. CPU timer expiry concurrent with programming remains a separate CPU-service concern. |
| Structural/progress | DCU coloring, overflow/coherence-response traffic, shared credits, DCS/VC backpressure and independent bypass | Not merely a race or local ordering assertion. Retain resource modeling and the planned actual-DCS RTL tests. |

The [focused race suite](../../tools/scheduling/check_models.py) deliberately
abstracts the locally sequenced handoff stages. It does not replace the existing
transport-composed tests; those check the NIC readiness guard at the info-read
response boundary. Keep the two levels separate rather than multiplying every
cache-line interleaving into each control-plane race.

## Modeled independent actors

- [WakeAckRace](../../model/tla/scheduling/WakeAckRace.tla): two notifications of the
  same cookie, one command in flight, distinct NIC acceptance and kernel ACK.
  A second callback can run while the first command is outstanding. A plain
  `pending = false` on ACK loses the second event; the positive action acknowledges
  only its own generation. Service can occur before the command ACK returns.
- [WakeReassignment](../../model/tla/scheduling/WakeReassignment.tla): a callback
  snapshots a worker hint, then acceptance and delivery race an A -> B -> A
  reassignment, including the parked/installed window. Acceptance must preserve
  readiness even with no eligible current target; delivery must revalidate the
  current subscriber. Reassignment does not erase accepted source ownership.
  The command ACK can arrive after NIC delivery or runtime service.
- [ThreadClaim](../../model/tla/scheduling/ThreadClaim.tla): concurrent observations
  of one thread are not exclusive claims. NIC reservations guarantee backing threads. One-slot and two-slot cases check
  capacity and eventual claims; duplicate claims and over-reservation have separate
  counterexamples. No individual thread withdrawal is permitted.
- [WakeRetirement](../../model/tla/scheduling/WakeRetirement.tla): source closure can
  race an already-running callback. Prefix release waits for both that callback
  and kernel/NIC/runtime wake references. Transport/cache retirement is an
  imported contract, not reimplemented here.

Notification counters and assignment epochs in these small models distinguish
interleavings; they **do not mandate new unbounded wire fields**. A per-cookie
in-flight/dirty scheme or guarded sequence acknowledgment can implement the first
contract. Current owner/subscriber revalidation or invalidating cached targets at
assignment changes can implement the second. Cookie generation tagging is needed
only if the implementation permits reuse before all stale references are excluded;
prefer retirement before reuse. Assignment epochs and cookie lifetimes are distinct.

Liveness assumes eligible command service and runtime execution. The reassignment
harness fairly completes its two handoffs, eventually making A eligible again;
it does not prove allocation-policy fairness for a perpetually parked domain.
Subscriptions/cookies remain stable in that harness. The retirement harness covers
closure separately. No production concurrency primitive is verified by these tests.

## Required kmod serialization and checks

| ID | Required behavior / check | Implementation boundary |
| --- | --- | --- |
| K1 | Seal the subscription before dispatchability; publish domain, cookie and retained file reference before callbacks can observe it. Preserve initial readiness. | Registration/parking in `chrdev.c`, future RT setup hook and epoll registration. |
| K2 | Serialize pending update, command snapshot and ACK reconciliation per cookie. An ACK must never clear a notification recorded after its snapshot. | Poll callback vs control-command completion. Use callback-context-safe synchronization; `READ_ONCE`/`WRITE_ONCE` alone do not make a read-modify-write atomic. |
| K3 | Bound outstanding commands and retain the source reference until acceptance/ACK is accounted for. Serialize writers of a multi-field MMIO command; publish one complete command. | Common control submission helper, shared by callbacks and dispatch. A blocking process-context mutex cannot simply be reused inside an atomic callback. |
| K4 | Validate enabled/not-stopping, subscription eligibility and unclaimed status atomically with claim, under a stable thread/domain lifetime reference. | Initialize the entire pool before advertising NIC capacity; serialize claims between dispatchers. Losing a candidate means reselection, not failed dispatch. Whole-domain teardown is separate. |
| K5 | Define one lock order for domain lifetime, thread claims and per-source pending state. Do not wait for drain, callback synchronization or another CPU while holding a lock it needs. | Before implementing callback/dispatch/teardown paths. Exact locks remain an implementation choice. |
| K6 | Establish the old task no-return fence before InfoAccept; retain old transaction addresses while NIC-internal obligations remain. No kernel CL access or ordinary cache flush. | Blocking yield and return-to-user parking. Recheck the parking predicate after every wake. |
| K7 | Return mailbox, RX parity, TX parity and host-work flag from the immutable info-response snapshot. Assert affinity names the physical worker represented by that snapshot. | Yield ioctl/bootstrap completion. Ordinary syscall sleep preserves this assignment. |
| K8 | Fence new callback/claim acquisition during teardown; unregister and synchronize callbacks, retain object references through deferred work and wait for pending command/wake ownership to retire. | Domain/cookie teardown. Only then free associated objects/cookies. Domain PA codes are monotonic and are not reused. |

Source-inspected starting points: `sched.c` already uses a checked return-to-user
parking loop and explicitly retains the route until `datapathDrained`; `chrdev.c`
has process-context `control_lock` regions. These are not proof that the new
callback/claim protocol is protected by the same lock. Audit all accesses and
lifetimes when implementing it; do not infer cross-context exclusion from a lock
being present elsewhere in the file.

## Required HW serialization and assertions

| ID | Required behavior / assertion | Owning block |
| --- | --- | --- |
| H1 | Accept complete `{domain, cookie, target hint}` commands on a defined valid/ready or commit boundary; payload stays stable under backpressure. | Control-register command ingress. |
| H2 | Serialize wake acceptance, assignment release/install and delivery. Target hints are not authorization. At delivery, assert current ownership and subscriber eligibility, or defer/re-resolve. | NIC scheduler commit point plus kernel eligibility handshake. |
| H3 | Accepted wake responsibility persists until runtime transfer or an explicit teardown disposition. Same-cycle notification and clearing use set-dominant semantics, preserving the new event. | Pending-cookie storage and HOST_WAKE/activation arbitration. |
| H4 | Reserve capacity before dispatch starts, count assignments plus reservations consistently, and consume/release a reservation exactly once. Assert limits after every commit. | Network/host allocation arbitration; fixed initialized thread pool backs every reservation. |
| H5 | At most one handoff is in flight per worker. PREEMPT stays latched until yield acknowledgment. Require legal predecessor state for every control command/ACK. | Worker handoff FSM. Prefer waiting for cancellation acknowledgment before slot reuse; if overlapping generations are introduced, validate reply identity explicitly. |
| H6 | InfoRespond implies old-task no-return fence and NIC-internal readiness. The resource harness includes queued responses and invalidation/LCIA obligations; distinct cached old PAs need not be flushed. | Handoff FSM and 2F2F drain interface. Keep full old addresses until release. |
| H7 | Local activation follows InfoRespond; return physical-worker identity and both parities from that response, with no NIC commit write. Assert no old-owner admission after fencing. | Worker decoder and activation return path. |
| H8 | HOST_WAKE delivery does not pop the network queue, even when full. Ordinary TX and syscall sleep do not alter assignment. | RX pseudo-packet selection and worker ownership logic. |
| H9 | Check accepted/resource counts, no double release, full-address preservation and transaction beat framing at actual interfaces. Control MMIO must progress independently of a stalled worker. | DCS/AXI/ECI integration; these require RTL/resource validation, not just scheduler assertions. |
| H10 | Retain directory charges for queued evictions until downgrade/writeback delivery and for transient hit invalidations through their modeled LCIA release. Establish the concrete release correspondence. Selected absent-line LCI does not allocate a directory entry. Serialize invalidation and eviction of the same address. | Cache/coherence endpoint and DCS resource monitors; see the [retained-cache model](../hardware/retained-cache-composition.md). |
| H11 | Establish both victim generation for a full directory set and independent service of eligible replacement traffic. Never infer either from a small active-assignment footprint. | CPU cache/DCS geometry and downgrade/writeback paths; concrete correspondence remains open. |
| H12 | Latch kill and gate new blocking RX. IRQ assertion and outstanding-RX release may occur in either order; never wait for handler entry before releasing RX. Hold the gate until the no-resume certificate, and prevent a late IRQ from killing a successor. | Kill/control interface; focused IRQ/assignment interleavings are checked; concrete resource/framing correspondence remains open. Drain the kill notification before successor activation. Application stopping is distinct from PA-prefix reclamation. |
| H13 | Preserve read ordering around the held doorbell; establish that target refills cannot acquire DCS slots behind it before publication, or supply a checked alternative. Do not infer this from software half-duplex alone. | AXI router, CPU issue contract and DCU read transient; [conditional cycle](../hardware/axi-response-contract.md). |
| H14 | An earlier same-line response cannot reinstall stale data after invalidation; retire, cancel or retry it with a proven endpoint ordering. Stop must fence new old-generation admission while existing responses finish. | Coherence endpoint and stop/worker interface. AXI RLAST alone is insufficient evidence. |
| H15 | Service eligible coherence data and LCIA independently of publication and the held doorbell. Check shared response backpressure and unaffected peer progress. | THX/ECI/DCS service paths; modeled fairness still requires implementation evidence. |

A local assertion should name the owner and violated contract. Expected races
such as a stale thread candidate or routing hint must take a defined fallback
path; they are not assertion failures in a correct implementation.

## Next race compositions

The [combined model](../hardware/retained-cache-composition.md) now joins pending
source ownership, fixed-pool dispatch, concurrent claims and retained-cache
resources. Its bounds and verification status are recorded separately from the
historical component results below. There is no individual thread shutdown or ordinary
unavailable-thread cancellation. Whole-domain teardown must fence new reservations
and retain the initialized pool until outstanding dispatches are reconciled.
The remaining refinement must connect source closure to the concrete
transport/cache retirement boundary. Repeated migration is useful where it can
expose stale replies/targets, rather than as a reason to enumerate already-forbidden
local reorderings. Kill versus dispatch/teardown is checked in the focused kill/exit model;
normal cooperative handoff does not acquire an IRQ path.

## Reproduction

```sh
nix build .#checks.x86_64-linux.scheduling-races --out-link out/scheduling/races-nix
nix develop -c python3 tools/scheduling/check_models.py --races-only --output out/scheduling/races-run
```

The ordinary `scheduling-progress` check also includes the race cases. Expected
negatives must fail their named obligation; parse errors and timeouts do not pass.

## Historical checked results (before fixed-pool clarification)

At source `f2f0286`, the focused TLC run passed four positive configurations and
found all seven expected counterexamples. Positive reachable-state counts are
102 (reassignment), 22 (notification/ACK), 73 (thread claim) and 190 (retirement).
The [evidence manifest](../../model/evidence/scheduling/race-evidence.json) records source,
configuration and log hashes, tool versions and results. Negative state counts
are diagnostic prefixes, not exhaustive successful safety proofs.

Representative counterexamples are `Notify -> Publish -> Notify -> Accept -> Ack`
with an unconditional ACK clear, and `Notify -> Accept -> Begin -> Park -> Install
-> Activate -> Deliver` with stale delivery eligibility. The former loses newer
readiness; the latter delivers A's notification to B. The corrected actions
preserve notification responsibility and revalidate the current subscriber.

Both pinned Nix checks passed: the focused 11 outcomes (four positives, seven
expected negatives) and the full 33 outcomes (13 positives, 20 expected negatives).
The normalized focused developer results match the Nix results exactly. The
optional extended RX/TX and stalled-peer cases were not rerun for this change.

## Fixed-pool correction

The initialized domain pool is immutable until whole-domain teardown. NIC
assignments plus reservations cannot exceed the advertised pool size. The updated
`ThreadClaim` checks one and two threads against two requesting workers: every
reserved dispatch has a backing thread and eventually claims one. A request
without capacity stays at NIC admission, not in a failing kernel dispatch.

The updated focused suite passed all 12 outcomes (five positives, seven expected
negatives) through `nix develop` and pinned TLC. The
[fixed-pool evidence](../../model/evidence/scheduling/fixed-pool-evidence.json) supersedes
the earlier withdrawal case. The full transport suite was not rerun; transport
models are unchanged. At that revision, historical unavailable scenarios remained explicit-only. The
blocking-info-read revision removes them; reproduce them from source `740b005`
or the earlier evidence revision rather than the current runner.

Current MMIO boundaries and whole-domain retirement are specified in the
[control interface](scheduling-control-interface.md); they supersede older
cache-retirement and unavailable-dispatch descriptions in historical results.

The first implementation uses monotonic, non-reused PA prefixes. Assert exhaustion
instead of wrapping; preserve allocation state across driver reload or require a
proved coherence-reset boundary. Free software objects only after their references
are joined, and keep late old-address coherence service independent of freed objects.

The [directory-pressure audit](../hardware/directory-pressure.md) narrows H11:
matched CPU/directory geometry explains CPU-initiated replacement, but not
FPGA-initiated absent-line LCI. A full-set counterexample is now reproduced.
Local allocation capacity and shared release-path progress remain open.
