# Retained cache accounting and composed scheduling races

This revision removes **clean cache / no conflict miss** as an ordinary handoff
entry requirement. Retained lines consume finite directory entries, CPU conflict
replacement creates downgrade/writeback traffic, and that traffic retains resources
until delivery. This is a bounded abstract resource model, not THX cache-value or
whole-system hardware refinement. The [control interface](../software/scheduling-control-interface.md)
still uses one blocking info read and no kernel 2F2F accesses or ordinary flush.

## Source evidence and the accounting correction

DCS represents an 18-bit tag and 13-bit set index above the 7-bit CL offset. The
index is split into DCU and within-DCU set fields; there are 16 ways per set in
[`eci_dcs_defs.sv`](../../vivado/eci/directory-controller-slice/dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv).
Crucially, [`dcu_controller.sv`](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu_controller/rtl/dcu_controller.sv)
READ_RTG does **not induce eviction** on an allocating miss to a full set. It
stalls awaiting a CPU downgrade. `dcu.sv` describes the same limitation. Therefore
fairness of a fictional directory-victim action would be an unsound progress fix.

The revised [audit](../../tools/progress/audit_resources.py) projects every
configured domain prefix onto the full 13-bit set index, preserving multiplicity.
It uses XOR linearity rather than enumerating hundreds of millions of lines.
Tests cross-check the compressed count against full enumeration for small domain
counts, including a non-power-of-two count. RX and TX footprints are both included.

| Configured domains, seven workers plus bypass | Maximum potential distinct footprint tags per directory set |
| --- | ---: |
| 16 | 2 |
| 128 | 6 |
| 512 | 24 |
| 524288 | 24576 |

These are **potential aliases across lifetimes**, not simultaneous CPU residency.
The CPU cache and directory have finite capacity: excess aliases cause replacement
and waiting, not an unbounded physical allocation. Background traffic can fill
sets even when the selected footprint is small. The old six-lines-per-DCU count
is retained only as an active-assignment statistic; it is no longer used to infer
free ways or rule out misses. Color-compatible address capacity remains distinct
from a resource progress guarantee.

## Reusable resource module

[`RetainedCache`](../../model/steady/RetainedCache.tla) has no scheduling policy,
domain, cookie or parity. Its parameters describe finite lines, cache/directory
sets, associativity, eviction queues and queue depths. Its state is:

- `resident`: CPU-resident/tag-allocated lines, including abstract in-flight fills;
- `directory`: retained directory entries;
- `dirty`: resident lines requiring writeback rather than clean downgrade;
- `retiring`: invalidated entries still charged until their final protocol unlock;
- `evictions`: ordered typed `{type, address}` messages, retaining the victim's
  full address until the downgrade/writeback is delivered.

The invariant is `directory = resident union queued victims union retiring`, with disjoint
ownership, no duplicate queued entry and a finite per-set bound. CPU eviction
removes residency first; it does not release the directory way. Only delivery
releases an eviction entry; protocol invalidations retain their directory charge
until final unlock. A fill waits for a free directory way. A protocol invalidation
must serialize with a pending eviction of the same address. Harnesses exclude
active transactions from victim selection; stable old-domain lines remain evictable.

The parameterized `Init` permits legal nonempty, dirty sets. Concrete harnesses
start with **full sets and dirty old lines**, deliberately exercising conflicts.
The two-way cache harness also starts with an old dirty eviction queued at the
stalled client's color. It permits later conflict eviction of live control lines.
No fairness forces a stalled read or its blocked downgrade to complete.

This is a resource abstraction of CPU replacement and directory admission; it does
not implement payloads, cache hits/replay values, all CPU cache levels, replacement
policy, or the detailed ThunderX single-line FSM. The cache/directory set relation
and autonomous victim response are endpoint contracts to map to THX. The model
must not be used to infer undocumented CPU associativity or write-buffer topology.
In particular, a full DCS set does not itself request a CPU victim. Hardware
correspondence must establish that such a miss induces an eligible CPU downgrade
for that directory set, or that the concrete cache/directory geometry rules out
the problematic full-set state. Merely assuming fair service of an eviction
queue cannot help if the CPU never creates the required eviction.

## Composition with coherence resources

[`CachedProgress`](../../model/steady/CachedProgress.tla) composes the new module
with unchanged `ProgressSystem`, `DcsResources`, `ThxResources` and `TwoFClient`.
Two clients, four colors, two ways per modeled set and bounded eviction queues
exercise an indefinitely held RX, a progressing peer, repeated RX operations,
retained dirty lines, queued unlocks and conflict eviction. Both live RX parities
are initialized. Three read/ACK credits and three per-slice read slots are used.
The address/color numbers are a synthetic resource geometry, not a new PA layout.

Positive cases use independent eviction queues, both with independent downgrade
service and with downgrade service blocked by a held read at its target color.
Only **eligible** service is fair. A shared FIFO behind that blocked downgrade
produces a progress counterexample. Disabling CPU victim selection also produces
a counterexample from the full-cache initial state. These distinguish cache
replacement from assuming either a clean cache or global downstream readiness.

The writeback payload path is abstract. Message occupancy and ordering are modeled,
but shared data beats, VC/CDC backpressure and THX write-buffer arbitration still
need correspondence. A passing per-color-queue case is a required topology/service
contract, not evidence that the concrete THX queues are per-color. A shared physical
queue is acceptable only if its head cannot be indefinitely blocked or another
proved mechanism provides the required independent progress.

## Combined scheduling model

[`CombinedScheduling`](../../model/scheduling/CombinedScheduling.tla) directly
composes the same coherence and retained-cache modules with:

- two physical workers initially assigned to domain A and independently requesting B;
- fixed initialized pools, NIC reservation accounting and separate kernel
  candidate selection, atomic claim, retry and local activation;
- PREEMPT/yield, blocking info acceptance, internal readiness, immutable response
  offer, response consumption and returned RX parity;
- one sealed cookie per domain and a bounded number of notifications, a callback that can outlive close,
  kernel publication, NIC acceptance, delayed ACK, NIC pending ownership,
  kernel-held activation snapshots, runtime service and explicit closed disposal;
- whole-domain kernel close and NIC admission fencing, cancellation when close
  beats the info response, local activation fencing and prefix retirement;
- retained old-domain addresses, full dirty initial sets, finite eviction queues,
  subsequent conflict misses, invalidation/eviction serialization and prefix
  retirement waiting for the actual modeled directory/messages to disappear.

The one-slot case forces NIC contention for the last destination thread. The
two-slot case permits concurrent kernel selection of the same candidate. No
ordinary unavailable-thread cancellation or individual thread withdrawal exists.
The NIC installs ownership at its info response; there is no activation commit.
Returned wake responsibility remains kernel-owned until local activation.

Completed old endpoint parity/progress bookkeeping is discarded at InfoRespond
after capture; those dormant clients never execute again in this one-handoff
bound. Retained cache entries are not discarded by that canonicalization.
Likewise, candidate IDs are discarded after a successful claim, response fields
after local activation, and parity/completion bookkeeping after a worker stops.
Those historical values no longer affect enabled actions or monitored obligations
in this one-handoff bound; a stopped worker cannot restart. This reduction preserves the modeled race boundaries and
retained resources. It must be revisited before adding repeated assignments or
runtime return-value semantics beyond the separately checked parity contract.

Both workers share finite read/ACK and slice resources. Each physical worker has
two control colors reused by successive domains at distinct addresses. Each
modeled cache/directory set has one way to force old/new alias conflicts. Eviction
delivery is blocked while a read holds that color, with independent queues and
fairness only on eligible delivery. Protocol dependencies are control-only RX;
the initial RX parity is fixed in this larger composition. Each worker makes at
most one A-to-B handoff. Network work is available; a pending host event uses the
same destination reservation and can be captured at the response.

The default exhaustive regression sets `EventBudget = 1` and `RepeatRx = FALSE`:
one notification may originate from either domain, and each assignment executes
at most one RX operation. All control actors and both workers remain present.
Its stable-assignment property requires that offered operation to complete,
including admission; it does not claim infinitely repeated progress. The explicit
`combined-extended-scheduling` and `combined-extended-one-slot` cases set
`EventBudget = 2` and `RepeatRx = TRUE`, admitting one notification per domain and
indefinitely repeated RX operations. They require a substantially larger graph
and are excluded from default regression selection. The independent retained-cache
harness checks repeated RX progress with an indefinitely stalled peer, but that is
not a substitute for checking the extended combined graph.

Checked obligations include capacity, unique claims, exact wake responsibility,
subscriber routing, no early info response, response stability, resource tracking,
no read dependency cycle, close/retirement completion and progress for any worker
whose live assignment eventually remains stable. Admission/resource fairness is
explicit; no allocation-policy fairness is asserted for unreserved work. The composition
uses strong fairness for eligible client service, including shared ACK-credit
arbitration: recurring availability must not starve one client. This does not
make a permanently blocked action eligible. Close
uses cooperative finite work here; kill of an uncooperative application remains
outside this model. Full prefix reuse safety relies on the single-transaction
endpoint contract after modeled references have disappeared.

The retirement endpoint may initiate evictions only after kernel quiescence;
this abstracts the existing undischarged prefix-retirement certificate. It does
not add a kernel CL operation or ordinary handoff flush. Establishing a concrete
mechanism for that teardown-only certificate remains necessary.
`ApplicationStopped` names the earlier kernel no-resume condition; it does not
require an empty cache or completed callbacks. `PrefixReusable` names the later
PA-reuse certificate, and `StopBeforeReuse` checks their ordering. The existing
`Retires` liveness property is about that later certificate under assumed endpoint
reclamation, not about killing the application. Actual kill IRQ assertion/release
ordering is specified in the [control interface](../software/scheduling-control-interface.md)
and is not yet implemented by this model's abstract stop action.

This is a **combined bounded race model**, not an arbitrary-size theorem. Repeated
migrations, simultaneous notifications from both domains in the default bound,
multiple cookies per domain, repeated same-cookie generations, TX/
overflow/bypass during transitions, a permanently stalled third worker alongside
these combined races, prefix reclamation while a successor on the same worker remains
stalled in RX, and concrete bus framing remain separate bounds
or component obligations. The existing notification/ACK generation model remains
necessary; one notification per cookie cannot prove its coalescing case. The
fault configurations disable unrelated callback/close actions for claim,
reservation and early-info diagnostics to keep their counterexamples focused;
positive configurations include those actors.

In particular, the combined prefix-reclamation property assumes finite work on the live
successor. It does not establish that an old prefix can become reusable independently of
a successor's indefinitely held RX at a reused physical-worker color. That case
must be checked with retained RX/TX footprints and the concrete endpoint service
contract; it is not justification for an ordinary kernel cache flush.

## Implementation checks carried by the composition

| Boundary | Required serialization or local check |
| --- | --- |
| NIC reservation | Atomically count installed owners and outstanding destination reservations against the initialized thread pool; close fencing shares this commit order. |
| Kernel claim | Recheck/claim a selected thread atomically, retry stale candidates, and serialize local activation with domain close. No individual thread withdrawal is assumed. |
| Info response | Check internal transaction/UL readiness before ownership changes; hold the offered domain, parity and wake snapshot immutable until consumption. Keep its lifetime reference across close. |
| Wake transfer | Transfer responsibility once at acceptance or snapshot capture; retain command/ACK references until reconciled. Dispose closed work explicitly. Repeated notification generations additionally need the separate ACK-generation contract. |
| Directory accounting | Charge queued victims and invalidated entries until their release event, forbid duplicate allocation, and serialize invalidation against an already queued eviction of the same address. |
| Replacement progress | A stalled color must not indefinitely suppress another worker's eligible downgrade/writeback. A per-color abstract queue is one sufficient contract, not a required literal microarchitecture. |
| Replacement initiation | Relate CPU cache indexing/associativity to the DCS directory: prove a required victim can be generated, or exclude a full directory set without a corresponding CPU replacement path. |
| Prefix retirement | Join callbacks, reconcile responses and command references, fence all resumes, then obtain the endpoint certificate. Ordinary handoff neither releases a prefix nor waits for all old-domain cache residency to disappear. |

These are implementation obligations, not assertions already added to the kmod or
RTL. The resource conservation checks can become handshake monitors; control
commit ordering needs tests against the actual MMIO framing and arbitration.

## Diagnostic counterexamples

The fault configurations require the named violation, not merely a nonzero TLC
exit. Current-source traces are preserved with the development-shell results.

| Deliberate fault | Observed failure |
| --- | --- |
| Shared replacement FIFO | A dirty victim at the stalled read's color blocks the head; a peer's victim waits behind it, preventing the peer's fill and admission. |
| No CPU victim selection | Full initial sets prevent the peer's first fill; completing unrelated traffic does not create a way in its set. |
| No NIC capacity check | Two workers reserve the single destination slot. |
| No atomic claim recheck | Both dispatchers select and claim the same destination thread. |
| No info-read readiness guard | Ownership/response is offered while old transport obligations remain. |
| No retirement guard | Prefix retirement precedes kernel/endpoint quiescence. |
| Drop accepted wake on close | Issued work loses its owner instead of receiving a closed disposition. |
| Rewrite an offered response on close | A committed NEXT response changes to CLOSED before consumption. |

## Checked results (2026-09-30)

The [evidence manifest](../../model/scheduling/retained-cache-evidence.json)
records source/configuration/tool hashes and separates completed checks from
incomplete temporal exploration. The pinned Nix `scheduling-composed-invariants`
regression passed all **12 expected outcomes**: two combined safety cases, two
cache-progress cases and eight deliberate faults. The strengthened regression
also passed `StopBeforeReuse` after separating application stop from PA reuse.

| Case | Exhaustively explored states | Established result |
| --- | ---: | --- |
| Two destination threads | 4,513,359 | Combined state invariants; not combined liveness |
| One destination thread | 889,743 | Combined state invariants; temporal analysis timed out |
| Independent replacement queues/service | 11,720 | Cache safety, admission and repeated peer progress with an indefinitely stalled RX |
| Independent queues, target-color downgrade blocking | 11,720 | Same cache properties without assuming global downstream readiness |

The two combined graph counts come from the development safety run. The later
stop/reuse clarification adds a safety assertion without changing initial states,
transitions, fairness or temporal definitions; the manifest checks that
correspondence and records the new Nix result separately. The original 16-outcome
focused race suite and all 12 Python capacity/accounting tests also passed.

The larger temporal explorations are **not passing evidence**. In particular,
the two-slot run was stopped before its growing tmpfs graph exhausted available
memory. Full combined temporal checking and the repeated-traffic/two-notification
extensions remain open. Neither incomplete exploration nor these finite bounds
establish whole-system hardware refinement. No new RTL or board validation was
performed for this change.
The one-slot temporal run enumerated its complete graph, then timed out at
1,800 seconds during temporal analysis with TLC heap-pressure warnings (2 GiB
heap). Its completed state enumeration does not make the temporal properties
passing results. The larger reproduction target uses a 4 GiB heap and a longer
budget; it has not been reported as passed.

## Reproduction

```sh
nix develop -c python3 -m unittest discover -s tools/progress -v
nix develop -c python3 tools/progress/audit_resources.py model/steady/selected-8k.json
nix build .#checks.x86_64-linux.scheduling-composed-invariants --out-link out/scheduling/composed-invariants-nix
nix develop -c python3 tools/scheduling/check_models.py --composed-invariants --output out/scheduling/composed-regression --timeout 1200 --workers 4 --heap-mib 2048
# Full combined temporal checks, with a larger per-case wall-time budget:
nix build .#checks.x86_64-linux.scheduling-composed --out-link out/scheduling/composed-nix
nix develop -c python3 tools/scheduling/check_models.py --composed-only --output out/scheduling/composed-run --timeout 7200 --workers 4 --heap-mib 4096
nix develop -c python3 tools/scheduling/check_models.py --case combined-scheduling --case combined-one-slot --safety-only --output out/scheduling/composed-safety --timeout 1200 --workers 2 --heap-mib 1024
# Larger extension; a timeout is inconclusive, not a passing result:
nix develop -c python3 tools/scheduling/check_models.py --case combined-extended-scheduling --case combined-extended-one-slot --output out/scheduling/composed-extended --timeout 1200 --workers 4 --heap-mib 4096
```

The original scheduling baseline remains separately runnable; use the new composed
check explicitly. Parse failures and timeouts are not passing results. Generated
state graphs and raw traces belong under ignored output directories; tools and
source/configuration hashes are recorded by the runner. Use the documented tmpfs
workflow for large liveness graphs and archive both success and incomplete runs.
The explicit `--safety-only` run checks state invariants on the full transition
graph but omits temporal properties, including the response-stability action
property. It cannot establish eventual handoff, retirement or worker progress.
`--composed-invariants` combines those positive combined-state checks with the
full cache-progress cases and every fault diagnostic. Each result records its
verification kind; this target must not be described as a combined liveness proof.
The large temporal graph should use disk-backed output unless sufficient tmpfs
headroom is available in addition to the Java heap. Do not risk host exhaustion
merely to make a check faster.

## Kill/exit follow-up (2026-10-01)

The [focused kill/exit check](kill-exit-verification.md) separates
application stopping from prefix reclamation. The first implementation allocates
domain codes monotonically without reuse; prefix-reuse certification is deferred.
The focused model checks uncooperative userspace, outstanding RX, IRQ ordering,
close/dispatch races and reference cleanup. It does not complete the larger
combined temporal or concrete resource/refinement obligations. The linked report
lists the remaining implementation and verification work.
