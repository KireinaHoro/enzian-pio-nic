# Resource and retained-cache progress verification

The active TLA+ resource models use LCI/LCIA without UL. They check finite
admission and completion under explicit resource topology, endpoint-service and
fairness assumptions. They do not prove undocumented ThunderX internals or
universal RTL liveness. See [model layers](model-structure.md) and the
[architectural HoL motivation](../hardware/queue-pair-design.md).

Static layout arithmetic and search results belong to the queue-pair topic;
formal results below identify the actual client counts and resource bounds.
The selected ROM's nonallocating absent LCI removes the old local-lock pressure
case; [historical auto-lock counterexamples](../hardware/lauberhorn-rom.md#historical-auto-lock-counterexamples-and-regression-coverage)
remain evidence for that former protocol, not the current endpoint.

## Current endpoint semantics

For the selected endpoint:

- Processing an LCI retains any hit directory entry while invalidation/dirty
  writeback completion and acknowledgment delivery remain pending. An absent
  target does not allocate a directory entry, even when its set is full.
- LCIA acceptance removes the response from the modeled DCS queue, releases the
  retained entry and advances the client's dependency counter. There is no
  client UL phase, queued UL, or retained acknowledgment credit afterward.
- The THX acknowledgment admission guard remains an explicit conservative resource
  assumption. It is not proof of actual THX credit topology. Dirty writeback
  service is abstracted by the processing event; the RTL test separately checks
  delayed WDDA. Modeling those resources and delays independently is still needed.
- DCS response backpressure, held reads, retained dirty cache lines, conflict
  replacement and eviction-delivery queues remain modeled. Removing UL does not
  assume a clean cache or make blocked response service fair by definition.

`CachedProgress` releases its `RetainedCache.retiring` charge on LCIA acceptance.
`CombinedScheduling` composes the same event with DeschedInfo, reservations,
claims, host notifications and retirement. `Handoff` selects the same endpoint
for ordinary, RX/TX and stalled-peer transition cases, including its Goal 1
post-handoff instance. The cleanup also converts `Steady` and `WakeHandoff` to the selected endpoint.

The combined model's optional prefix-reuse certificate still assumes autonomous
closed-domain eviction. That is stronger than current monotonic-no-reuse kill
completion; it must not be mistaken for a required kernel cache flush or a new
commit write. The optional certificate is not part of the implemented no-reuse lifetime policy.

## Directory accounting and replacement

DCS represents an 18-bit tag and 13-bit set index above the 7-bit CL offset. The
index is split into DCU and within-DCU set fields; there are 16 ways per set in
[`eci_dcs_defs.sv`](../../vivado/eci/directory-controller-slice/dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv).
Crucially, [`dcu_controller.sv`](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu_controller/rtl/dcu_controller.sv)
READ_RTG does **not induce eviction** on an allocating miss to a full set. It
stalls awaiting a CPU downgrade. `dcu.sv` describes the same limitation. Therefore
fairness of a fictional directory-victim action would be an unsound progress fix.

The [audit](../../tools/progress/audit_resources.py) projects every
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

[`RetainedCache`](../../model/tla/steady/RetainedCache.tla) has no scheduling policy,
domain, cookie or parity. Its parameters describe finite lines, cache/directory
sets, associativity, eviction queues and queue depths. Its state is:

- `resident`: CPU-resident/tag-allocated lines, including abstract in-flight fills;
- `directory`: retained directory entries;
- `dirty`: resident lines requiring writeback rather than clean downgrade;
- `retiring`: invalidated entries still charged until their LCIA acceptance;
- `evictions`: ordered typed `{type, address}` messages, retaining the victim's
  full address until the downgrade/writeback is delivered.

The invariant is `directory = resident union queued victims union retiring`, with disjoint
ownership, no duplicate queued entry and a finite per-set bound. CPU eviction
removes residency first; it does not release the directory way. Only delivery
releases an eviction entry; protocol invalidations retain their directory charge
until LCIA acceptance. A fill waits for a free directory way. A protocol invalidation
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

[`CachedProgress`](../../model/tla/steady/CachedProgress.tla) composes the module
with unchanged `ProgressSystem`, `DcsResources`, `ThxResources` and `TwoFClient`.
Two clients, four colors, two ways per modeled set and bounded eviction queues
exercise an indefinitely held RX, a progressing peer, repeated RX operations,
retained dirty lines, pending acknowledgments and conflict eviction. Both live RX parities
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

## Historical combined scheduling abstraction

[`CombinedScheduling`](../../model/tla/composition/CombinedScheduling.tla) directly
composes the same coherence and retained-cache modules with:

- two physical workers initially assigned to domain A and independently requesting B;
- fixed initialized pools, NIC reservation accounting and separate kernel
  candidate selection, atomic claim, retry and local activation;
- DESCHED/yield, blocking info acceptance, internal readiness, immutable response
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
and is outside this abstract stop action; the [kill/exit models](kill-exit.md)
cover the explicit control/transport composition.

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
| Info response | Check internal transaction/LCIA readiness before ownership changes; hold the offered domain, parity and wake snapshot immutable until consumption. Keep its lifetime reference across close. |
| Wake transfer | Transfer responsibility once at acceptance or snapshot capture; retain command/ACK references until reconciled. Dispose closed work explicitly. Repeated notification generations additionally need the separate ACK-generation contract. |
| Directory accounting | Charge queued victims and invalidated entries until their release event, forbid duplicate allocation, and serialize invalidation against an already queued eviction of the same address. |
| Replacement progress | A stalled color must not indefinitely suppress another worker's eligible downgrade/writeback. A per-color abstract queue is one sufficient contract, not a required literal microarchitecture. |
| Replacement initiation | Relate CPU cache indexing/associativity to the DCS directory: prove a required victim can be generated, or exclude a full directory set without a corresponding CPU replacement path. |
| Prefix retirement | Join callbacks, reconcile responses and command references, fence all resumes, then obtain the endpoint certificate. Ordinary handoff neither releases a prefix nor waits for all old-domain cache residency to disappear. |

These are obligations of the historical combined contract, not claims that
all of its wake/close features are implemented in the kernel or RTL. The resource conservation checks can become handshake monitors; control
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

The [evidence manifest](../../model/evidence/scheduling/retained-cache-evidence.json)
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



Those results describe the September 30 source revision, before active UL
removal. The one-slot temporal analysis timed out after full state enumeration;
the two-slot temporal run was stopped before completion. Neither is passing
liveness evidence. Later LCI-only results are recorded separately below.

## Checked results

[Evidence, source hashes and exact commands](../../model/evidence/steady/lci-only-model-evidence.json)
record these bounded runs using TLC 1.8.0:

| Case | Distinct states | Result |
| --- | ---: | --- |
| Ordinary no-UL handoff | 8,256 | Safety and temporal properties pass |
| Handoff with indefinitely stalled peer | 33,024 | Safety and temporal properties pass |
| RX/TX handoff | 28,800 | Safety and temporal properties pass |
| Retained dirty cache, independent delivery | 8,592 | Safety and progress pass |
| Color-separated delivery with blocked peer downgrade | 8,592 | Safety and progress pass |
| Full directory reached from empty cache | 8,616 | Safety and progress pass |
| Shared blocked eviction queue | 4,364 | Expected progress counterexample |
| Replacement disabled | 8 | Expected progress counterexample |
| Combined scheduling, two destination slots | 2,528,455 | State invariants pass; temporal properties not checked |

The combined bound has two workers, two domains, one directory way per modeled
set, one notification budget, and no repeated-RX loop. Both reservations/claims
and close/wake paths interleave with transport and retained cache state. The
focused handoff cases include repeated independent peer operations and legal
parities. These finite bounds are not architectural worker/domain limits.


The evidence records development-shell runs; evaluation of the Nix derivation
is not an additional execution. Combined temporal properties were not checked
by the 2,528,455-state safety run. Independent THX service and concrete
RTL/software correspondence remain assumptions, as described above.

## Historical steady-state abstraction

The following source correspondence and port table describe the September 30
UL-capable model snapshot. They explain the recorded results below, not the
current `DcsResources` API. Current models retire invalidations at LCIA acceptance;
`LateUnlock`, UL queues and the old fixed-stride router are historical.

### Historical entry and environment contracts

`ProgressSystem!Settled` names resource typing, tracking, conservation and static
color compatibility in the abstract execution (`Steady` exports it). Its interpretation additionally requires fixed physical worker/domain
assignments, consistent mappings and owner checks, completion or safe tracking of old-owner transactions, and legal concrete
transport/cache state. Ordinary cache residency need not be empty;
[retained-cache accounting](resource-progress.md#directory-accounting-and-replacement) supplies the additional
replacement/directory contract. Prefix reuse still requires endpoint retirement. Those environmental clauses
are **assumptions**, not consequences of the predicate. Goal 2 must establish all
of them at a handoff and maintain unaffected workers' progress during transitions.
The model cannot represent stale old-domain cache hits or owner changes.

Workers execute finite independent work on isolated CPUs and serialize software
RX/TX. A progressing CPU executes, input work exists, and output destinations
accept it. Progress excludes indefinite blocking by another stalled load; it
does not promise unchanged latency or throughput. A stalled client may retain its read forever; there is no kill, timeout,
assignment release or fairness requirement on that client's Work action. Two
independent clients represent full-duplex bypass; their endpoint colors must both
be reserved. No application waits on a stalled client's locks are in scope.

Initialization chooses both parities for every used direction. Active workers
can start in RX or TX and later switch. Stalled clients hold RX; bypass RX and TX
are separate fixed-direction clients. `AllowedDirections` disables unused
directions and fixes their unobservable parity to zero, avoiding duplicate states
without restricting the parities of accessed lines. Repeated operations, direction changes, queued invalidations, acknowledgments
and unlocks are reachable. An unlock can remain queued across software completion
and the next operation: half-duplex is not a transport-drain assumption. Cache
payloads and the exact set of reachable THX stable/transient cache states remain
behind the single-transaction assumption. This is not exhaustive initialization
from every concrete legal cache state. Designated stalled clients are inert from
initialization: the historical harness does not freeze a worker after a previous TX
operation while that same worker retains an old unlock. Active clients do exercise
retained unlocks across direction changes. The `LateUnlock` component check below
seeds one such retained obligation and checks its drain; it is not an exhaustive
late-stall extension of the full 2F2F harness.

### Historical source correspondence

| Concrete evidence | Abstract event/resource | Status / limitation |
| --- | --- | --- |
| `EciCmdDefs.scala` alias/unalias equations | Python `alias`, `unalias`, `color` | Exhaustive small input and basis checks; all candidate lines checked by `--verify` |
| `eci_dcs_defs.sv`: 38-bit slice address, 5/6/7 color bits | aperture, Colors, ColorOf | Source confirmed; kernel aperture is not extra preserved address bits |
| `EciInterfacePlugin.scala`: `coreOffset=0x20000` | candidate stride input | Historical constant, not an architecture limit |
| `rd_trmgr.sv`: REQ_ACCEPT/RSP_ACCEPT | DCS reads[color] stores the full Read record, ReceiveRead/CompleteRead | One retained read per color confirmed |
| `wr_trmgr.sv`, `dcu_controller.sv` launch/stall paths | invalidate processing blocked by occupied target | Coarse abstraction; writeback/RTG/ECI transaction slots are not individually modeled |
| `dcu_controller.sv` explicit HoL comments | per-resource FIFO; shared-queue counterexample | Actual VC/queue topology still needs full correspondence |
| `DcsAppMaster.scala` default five slice reads | DCS ReadSlots per SliceOf[color] | Simulation evidence; five is not a proven silicon limit |
| `EciInterfacePlugin.scala` crossbar W+2 upstream, one operation per destination | finite admission/backpressure | Crossbar issue queues not independently instantiated in this model |
| `EciDecoupledRxTxProtocol.scala` LCIA -> queued UL, overflow+2 depth per direction | Ack -> unlockQ -> DeliverUnlock | Distinct acceptance/release; model serializes ack occupancy per client and uses aggregate unlock depth N |
| Protocol `rxInvDone`/`txInvDone` versus `transportQuiescent` | Return may precede DeliverUnlock | Source confirms that software completion is not final unlock delivery |
| `EciAddressDecoder` / `EciAxiIngress` | Physical-worker/domain decoding with retained transaction addresses | Sole ECI implementation; see the [current evidence](../hardware/eci-implementation.md) for validation limits |
| `deps/thx-fsms/README.md` explicitly excludes LFB/write-buffer internals | ThxResources pool/credit alternatives | All THX slot counts and sharing assumptions are unconfirmed |

The DCS abstraction omits standalone data writes, RTG/set capacity, data beat
queues, CDC buffers, AXI response ordering and transaction framing. Thus a positive
model result requires their refinement or a separate nonblocking contract; it
cannot establish the production end-to-end capacity. There is no assumption that
an arbitrary blocked transaction eventually completes. Service fairness only
applies when the modeled action is enabled. Downstream ability to deliver eligible
unlocks is assumed; extending backpressure to those omitted paths is still needed.

### Additional source-backed resource bounds

The [reproducible RTG audit](../../tools/progress/audit_resources.py) counts
potential tags from **all configured domain aliases** using the full set index.
It does not assume old-owner lines have vanished, nor infer available ways from
the active footprint. Full dirty sets and conflict replacement are modeled in
[the retained-cache composition](resource-progress.md#directory-accounting-and-replacement). DCS does not
induce eviction on a full-set miss: CPU downgrade/writeback service must provide
progress. The earlier six-line active-footprint statistic is not a clean-cache
entry condition or a proof that a set cannot fill.

The same source declares a per-slice data buffer sized as two 512-bit words per
DCU index. [`dp_data_store.sv`](../../vivado/eci/directory-controller-slice/dcs/dcs_data_path/dp_data_store/rtl/dp_data_store.sv)
indexes it by DCU and releases its gate on the return handshake. This supports
per-DCU storage allocation, but shared serialization, gates and downstream
backpressure still need a progress correspondence; it is not a global readiness
guarantee.

[`dcu_top.sv`](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu_top/rtl/dcu_top.sv)
and [`arb_4_ecih.sv`](../../vivado/eci/directory-controller-slice/dcs/dcu/arb_4_ecih/rtl/arb_4_ecih.sv)
separate incoming message classes and rotate priority on acceptance **or stall**.
The single invalidation queue abstraction does not reproduce those five
input classes or their shared upstream VC topology. The controller's explicit
protocol/read/write/ECI/RTG stall reasons must be mapped separately before claiming
that a stalled color cannot block a shared path.

Finally, `DcsAppMaster.maxInflightReqsPerSlice=5` is a **simulation default**;
it does not establish a five-entry hardware limit. The production crossbar's
upstream limit is `NUM_WORKER_CORES+2`, with one operation per destination, in
[`EciInterfacePlugin.scala`](../../hw/src/lauberhorn/host/eci/EciInterfacePlugin.scala).
Neither count discharges ThunderX read/ack-credit sharing: the available
[`thx-fsms` evidence](../../deps/thx-fsms/README.md) explicitly omits internal
write and in-flight buffers. Those resource assumptions cannot be closed simply
by increasing the TLC bound or treating a successful RTL test as a proof.

### Historical ports and composition

Modules expose state tuples and use `INSTANCE ... WITH`. The DCS module contains
no domain, scheduler, epoll, worker policy, or parity. `Probe.tla` uses it unchanged
with a non-2F2F probe client. Message records carry `type`, `txn`, and full abstract
`address`; queues are sequences, preserving equal-valued repetitions. The historical
harness reuses a client transaction slot only after its read completes; residual
unlock identity includes its address and must retire before another acknowledgment
can occupy that client's ack slot.

| Port | Producer -> consumer / boundaries | Capacity, ordering and responsibility |
| --- | --- | --- |
| ReceiveRead(Read) | CPU client -> THX/DCS, atomic abstract admission | One per client and color, finite THX pool and per-slice limits; pending Submit remains outside admission and is covered by liveness |
| SendInvalidate(Invalidate) | protocol -> DCS queue acceptance | QueueDepth per QueueOf[color], FIFO within each queue, independent queues unordered; producer retains request under backpressure |
| Process | DCS queue head -> abstract endpoint Ack | Cannot process behind a retained read of the target color; Ack retains queue entry/target lock |
| ReceiveAck | endpoint -> THX/client | Finite shared or separate ack credit; client gains obligation to send unlock; no implicit release |
| Unlock | client -> unlockQ | Bounded ordered submission; client can continue after enqueue |
| DeliverUnlock | unlockQ -> DCS and THX | Releases retained Ack queue entry/lock and ack credit only on delivery; independently fair eligible delivery |
| CompleteRead | device -> CPU | Releases DCS read slot, slice credit and THX read credit; software completion toggles progress marker, may leave UL queued |

DCS `CanRead` also excludes an address locked by a processed invalidation. THX
`SharedAck` selects shared read/ack credits versus a separate per-client ack slot;
Pools/PoolOf are generic module parameters. These are sensitivity models, not a
reverse-engineered ThunderX microarchitecture. `SF` is used for intermittently
eligible shared admission/invalidation/ack arbitration. Continuously eligible CPU,
queue processing, return and unlock actions use `WF`. Only queues reachable from
non-stalled clients' declared dependencies are included in `ServiceQueues`:
`UnusedQueuesEmpty` checks that all other queues remain empty. Omitting their
vacuous fairness clauses and disabled return directions does not remove any
eligible service requirement. Concrete arbitration must
justify this fairness; priority RTL alone does not prove it.

## Selected-address historical evidence

These recorded checks separate static mapping capacity from finite resource
progress. Historical UL-era snapshots require their recorded source revisions;
the current runner uses the LCI-only endpoint.

The exact [selected configuration](../../model/data/selected-8k.json) is generated
by [capacity.py](../../tools/progress/capacity.py) with the default 8 KiB stride,
`--workers 7 --domains 16`. [witness_model.py](../../tools/progress/witness_model.py)
binds its full addresses, both control parities, and all 12 overflow lines to
[ProgressSystem.tla](../../model/tla/steady/ProgressSystem.tla), composing the unchanged
[DCS resources](../../model/tla/steady/DcsResources.tla),
[THX resources](../../model/tla/steady/ThxResources.tla), and
[2F2F client](../../model/tla/contracts/TwoFClient.tla). The
[single-transaction vocabulary/assumption](../../model/tla/common/Coherence.tla)
remains an undischarged replacement boundary.

Initial checks for this selection at `f9981d5`, September 30, before the Nix
integration and explicit direction choices (recorded reruns appear below):

| Check | Result and scope |
| --- | --- |
| Capacity regressions | Nine Python tests passed, including selected 8 KiB encoding, compensated domain vectors, maximum prefix and independent configuration verification |
| Full address-base enumeration | All 3670016 worker/domain bases are distinct, color-invariant and match the documented encoder/decoder formulas; boundary/sample domains additionally checked at every footprint line |
| Selected configuration | 112 distinct worker/domain blocks (seven workers, 16 domains), both parities and every overflow line checked against full-duplex bypass |
| Address-bound TLC | 25088 distinct states, 45632 generated, depth 141; `Settled`, `NoReadDependencyCycle`, `Progress` and `Admission` passed |
| Negative sensitivity checks | `bad-hol`, `bad-color`, `bad-credits`, `bad-slice`, `bad-ack` each produced its expected liveness counterexample |

This initial positive TLC instance selects workers 0/1 in domains 0/15; worker 0 may retain
its read indefinitely while worker 1 executes repeated half-duplex operations.
It includes independent initial RX/TX parities, subsequent direction changes,
all overflow addresses, and unlocks retained after software completion. Its
resources are **one invalidation FIFO per color, depth one; three shared THX
read/ack credits; three read slots per modeled slice; two aggregate unlock
entries**, with the eligible-action fairness in `ProgressSystem!Spec`. In this
instance, these assumptions permit admission and completion independently of the
stalled worker. They are not confirmed hardware topology/credit guarantees.
The checked-in configuration has seven workers, but this liveness instance has
only two clients and no bypass client. Address/color capacity must not be relabeled
as resource-model-verified progress capacity.

The shared-FIFO [negative model](../../model/tla/steady/Probe.tla) and its
[bad-hol configuration](../../model/config/steady/bad-hol.cfg) deliberately place an
otherwise eligible request behind a permanently blocked request. Its counterexample
shows why coloring alone cannot justify HoL freedom; the positive model's separate
queues and credit assumptions need concrete correspondence.

The [initial compact evidence manifest](../../model/evidence/steady/selected-8k-evidence.json)
records source/configuration/TLC hashes, state counts and expected negative results
for that earlier source revision.
Full logs and generated harnesses remain ignored under `out/progress/selected-8k/`.
Run the current selected address-bound case (with current endpoint semantics) in a fresh output directory:

```sh
nix develop -c python3 tools/progress/check_models.py --case selected-workers --output out/progress/selected-recheck
```

## Historical Nix-pinned results and expanded bounds

The recorded checker is `tlaplus18` from the existing `flake.lock`, not a custom
TLC derivation. Its jar hash and Java version are recorded by the runner. The
sandboxed `steady-progress` check passed eleven Python tests, verified all 112
selected worker/domain blocks, checked the RTG occupancy bound, and passed all
15 expected TLC outcomes (including six expected counterexamples). A Nix
`--rebuild` check reproduced the output byte for byte.

That model revision makes per-client direction choices explicit. Active workers
retain both RX/TX choices and both parities. Stalled clients remain in RX; their
unused TX parity is unobservable. Bypass clients each use a single direction and
both control parities. This removes duplicate states from the previous harness;
it does not reduce the twelve-line overflow footprint, force transport drain,
assume completion of blocked operations, or omit admission liveness.

`ServiceQueues` restricts queue-service fairness to queues that the declared
clients can actually use. `UnusedQueuesEmpty` checks that the other queues remain
empty. All queues still exist in the resource model, and arbitration/service
fairness on every potentially used queue is unchanged. Disabled return directions
likewise no longer contribute vacuous fairness clauses. Older state counts are
not directly comparable to these reduced representations.

| Baseline case | Distinct states | Outcome |
| --- | ---: | --- |
| no-stalls | 1248 | pass |
| positive | 1184 | pass |
| minimal | 144 | pass |
| two-stalls | 4736 | pass |
| bypass (control-only) | 5280 | pass |
| separate-ack | 1184 | pass |
| probe | 8 | pass |
| late-unlock | 5 | old unlock retires; stalled read persists; peer admission and progress pass |
| selected-workers (all 12 overflow lines) | 6272 | pass |
| bad-color, bad-credits, bad-slice, bad-ack, bad-hol, bad-cycle | recorded in manifest | expected counterexamples |

The selected-address extended cases use the same model and fairness as the
baseline, with all twelve overflow lines per direction. The synthetic case uses
the smaller footprint stated in its row:

| Extended case | Clients and bounds | Result |
| --- | --- | --- |
| selected-two-stalls | workers 0/1 stalled in RX, worker 2 active; domains 0/7/15; shared slots 4, per-slice reads 4, queue depth 1, unlock depth 3 | 25088 states; safety, Progress and Admission pass |
| selected-bypass | worker 0 stalled in domain 15, independent fixed RX/TX bypass clients; shared slots 4, per-slice reads 4, queue depth 1, unlock depth 3 | 522144 states, depth 272; safety, Progress and Admission pass; 760.6 seconds |
| bypass-overflow | synthetic three-client case, one stalled RX, two live clients, one overflow dependency per operation; shared slots 4, per-slice reads 4 | 356864 states, depth 59; safety, Progress and Admission pass; 508.1 seconds |

| selected-peer-bypass | one stalled worker, one active worker, fixed RX/TX bypass clients; shared slots 5, per-slice reads 5, queue depth 1, unlock depth 4 | **incomplete** at 1200 seconds; last reported 3041960 distinct states and 219240 queued; graph exploration unfinished, liveness not checked |

The [tracked Nix evidence manifest](../../model/evidence/steady/nix-progress-evidence.json)
records source, checker, harness and log hashes, bounds and outcomes. Baseline
model/tool sources are at `1d83a27`; extended-run sources are at `b73d238`.
Raw local output is retained in `out/progress/nixpkgs-baseline-final/` and
`out/progress/nixpkgs-tmpfs/`. The four-client timeout is not a passing safety or
progress result.

These finite cases do not certify seven simultaneous workers or the actual THX
credit topology. The selected-bypass case has both progressing bypass directions,
but no additional active worker. The color/address maximum and resource-model
bounds therefore remain separate.

[LateUnlock.tla](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/blob/4cb1e8a/model/steady/LateUnlock.tla), with
[late-unlock.cfg](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/blob/4cb1e8a/model/steady/late-unlock.cfg), reuses the unchanged DCS/THX
modules and seeds worker 0's held RX at `0x200300`, old TX Ack/unlock at
`0x201300`, and a peer read at `0x4300`. These addresses are checked against the
selected encoder. Two shared credits are initially exhausted by the held read
and old acknowledgment. Under fair **eligible unlock delivery**, the old TX
obligation retires, the RX read remains held forever, and the peer repeatedly
admits and completes. The peer is a minimal read client, not full 2F2F; the
five-state result checks that resource boundary, not reachability of every
concrete late-stall/cache state or every old-traffic combination.

The final extended checks use a fresh tmpfs output directory to avoid repeated
ZFS graph reads during TLC's final liveness pass. This changes storage only; the
jar, model, properties and fairness are identical. Copy the output back under
ignored `out/progress/` after the run, including when a case times out. The default
runner remains usable on ordinary disk; `--output` selects the location. Heap is
bounded explicitly, and each case has a recorded 1200-second limit. Interrupted
ZFS and preliminary parsing/profiling runs are not counted as successful evidence.

For a Linux tmpfs rerun with durable output (the archive destination must be new):

```sh
nix develop -c bash -c '
  mkdir -p out/progress
  test ! -e out/progress/extended-tmpfs || exit 1
  progress_tmp=$(mktemp -d /dev/shm/lauberhorn-progress.XXXXXX) || exit 1
  python3 tools/progress/check_models.py --case selected-two-stalls --case selected-bypass --case bypass-overflow --case selected-peer-bypass --workers 4 --heap-mib 3072 --timeout 1200 --output "$progress_tmp/run"
  model_result=$?
  cp -a "$progress_tmp/run" out/progress/extended-tmpfs || exit 1
  rm -rf "$progress_tmp"
  exit "$model_result"
'
```

## Earlier results recorded September 30 (128 KiB address witness)

Eight Python tests passed in Nix, including independent configuration checking,
corrupt-witness rejection, alias inversion, exhaustive small-geometry partitioning,
brute-force compatibility cross-checks and the sweep symmetry reduction. `--verify` also enumerated the entire
reported 128 KiB-layout frontier's physical mappings. The standalone decoder
verifier checked all 64 blocks of the emitted four-worker/16-domain configuration.
Two directed RTL tests passed after elaboration and Verilator compilation, with
protocol-event checks enabled. No production RTL or software was changed.

The final default TLC suite passed all 13 expected outcomes, including six expected
counterexamples. Counts below are distinct states, not architectural capacities.

| Configuration | Resource/client bound | Result |
| --- | --- | --- |
| positive | 2 clients, 1 stalled, 8 colors, shared THX slots 3, slice slots 4, queue depth 1 | 4736 states; safety and infinite progress pass |
| two-stalls | 3 clients, 2 stalled, 16 colors, shared THX slots 4 | 75776 states; safety and infinite progress pass |
| bypass | 1 stalled worker plus independent RX/TX clients; control-only, 16 colors, slots 4 | 5280 states; safety and infinite progress pass |
| no-stalls | 2 independent live control-only clients | 1248 states; pass |
| minimal | 1 stalled/1 live control-only client | 144 states; pass |
| separate-ack | 2 clients, 1 stalled, separate acknowledgment credits, 2 read slots | 4736 states; pass |
| probe | non-2F2F client, independent queues | 8 states; pass |
| bad-color | same control colors, one stalled read | Progress and Admission counterexample |
| bad-credits | one shared THX slot | Progress and Admission counterexample |
| bad-slice | one read credit per slice | Progress/Admission counterexample |
| bad-ack | two shared THX read/ack slots | Progress counterexample: accepted reads leave no ack credit |
| bad-hol | one shared queue behind permanently blocked probe | Progress counterexample |
| bad-cycle | incompatible colors, both clients live | NoReadDependencyCycle counterexample |

The address-bound generated harness additionally checked workers 0/1 in domains
0/15 of the four-worker/16-domain map, with worker 0 stalled. It explores independent
RX/TX parities, both directions, all 12 overflow lines and retained UL traffic.
It checks 25088 distinct states. This establishes resource-model progress only for
that finite instance and its stated resource assumptions, not for all four
simultaneously active workers or 32768 simultaneous domains. Static domain-color
invariance covers the address arithmetic across prefixes separately.

The larger synthetic bypass-with-overflow case exceeded a bounded time limit;
its partial output is retained and is not a passing result. An extended run with
four checker workers generated the full 1427456-state graph but did not finish
liveness checking within 600 seconds. This extended run preceded the final
full-address read-slot representation; it is recorded as incomplete historical
work, not verification of the final source. The final default bounded suite and
address-bound instance were rerun after that representation change. Earlier stronger
fairness on stalled-client admission also made the two-stall run exceed 300 seconds;
removing that unnecessary assumption yielded the passing result above. No fairness
is imposed on stalled CPU actions in those runs, including admission. These failures to
finish were checker limits, not discovered protocol counterexamples.

Local generated logs, counterexamples and compact summaries are in
`out/progress/final-address-suite/`, `out/progress/full-bypass-extended/`,
`out/progress/witness-complete.log` and `out/progress/rtl.log`. The model runner records
source checksums alongside the TLC checksum. Reproduce checks rather than treating
these local output paths as durable repository artifacts.

## Reproduce and interpret checks

Run from the repository root with the locked submodules. Python, Java and
**nixpkgs `tlaplus18`** are declared Nix inputs; `flake.lock` pins the package set.
`nix develop` supplies `tlc` and `TLA2TOOLS_JAR`; no manually downloaded jar or
`/tmp` artifact is required. The recorded nixpkgs jar SHA-256 for these checks was
`397829775c6ecaf86f7ae9e5c9b062fcdea39f1b69fc9f0a9a9f0f617dde499e`.
The historical jar with hash `ab469460...` was different despite its 1.8.0 label;
its results are retained as historical evidence. For new runs, use the locked
Nix input and the runner's recorded tool hash.

[The Nix check](../../nix/checks/steady-progress.nix) copies only declared model,
Python and DCS-definition inputs into the sandbox, checks the selected mapping
and RTG bound, and runs the regression suite plus the selected two-worker witness.
Its output contains deterministic source/tool hashes and outcomes plus the RTG
bound. Raw logs, elapsed times and temporary paths are retained by working-tree
runner invocations, not embedded in the Nix output. The check is `checks.<system>.steady-progress`; use the
[CI guide](../development/ci.md) for pipeline selection.

```sh
# Clean-input baseline; deterministic summary and RTG bound are in this result.
mkdir -p out/progress
nix build .#checks.x86_64-linux.steady-progress --out-link out/progress/nix-check
# Same model runner on the working tree; output directory must be new.
nix develop -c python3 tools/progress/check_models.py --output out/progress/new-run
# Extended finite bounds, with explicit memory and time limits per case.
nix develop -c python3 tools/progress/check_models.py --case selected-two-stalls --case bypass-overflow --case selected-bypass --case selected-peer-bypass --workers 4 --heap-mib 3072 --timeout 1200 --output out/progress/extended
# Independently reproduce the selected mapping and conservative RTG bound.
nix develop -c python3 tools/progress/capacity.py --workers 7 --domains 16 > out/progress/map.json
nix develop -c python3 tools/progress/verify_config.py out/progress/map.json
nix develop -c python3 tools/progress/audit_resources.py model/data/selected-8k.json
# Historical 128 KiB placement sweep (5245 equivalence classes).
nix develop -c python3 tools/progress/sweep_layouts.py --jobs 4 --output out/progress/sweep.json
```

The runner records full TLC counterexamples, exact commands, Java version,
tool/source/harness hashes, memory limits, elapsed time and compact results. It preserves partial output and marks timeouts incomplete (exit 124 in
its summary). `--case bypass-overflow --timeout 60` reproduces the larger optional
synthetic bound; extended cases are excluded from the default suite. The default bypass case checks
control-only dependencies, not the overflow case. `witness_model.py` binds actual
emitted full addresses and every overflow line to the unchanged `ProgressSystem`
composition; by default it selects workers 0/1 in domains 0/15, stalls worker 0,
and checks worker 1. `--bypass` adds independent RX and TX clients. The other
workers/domains are covered only by the separate color/mapping verifier, not by
this finite resource instance.
Expected negatives must fail the intended property, not parsing or arbitrary
invariants. `Progress` requires each active client to complete infinitely often;
`Admission` separately excludes indefinite pre-admission blocking. The finite
state space uses a toggling completion marker rather than an unbounded counter.
`NoReadDependencyCycle` diagnoses cycles among doorbell/invalidation dependencies;
shared-credit starvation and queue HoL are additionally caught by liveness.

## Cache and combined-model reproduction

```sh
nix develop -c python3 -m unittest discover -s tools/progress -v
nix develop -c python3 tools/progress/audit_resources.py model/data/selected-8k.json
nix build .#checks.x86_64-linux.scheduling-composed-invariants --out-link out/scheduling/composed-invariants-nix
nix develop -c python3 tools/scheduling/check_models.py --composed-invariants --output out/scheduling/composed-regression --timeout 1200 --workers 4 --heap-mib 2048
# Full combined temporal checks, with a larger per-case wall-time budget:
nix build .#checks.x86_64-linux.scheduling-composed --out-link out/scheduling/composed-nix
nix develop -c python3 tools/scheduling/check_models.py --composed-only --output out/scheduling/composed-run --timeout 7200 --workers 4 --heap-mib 4096
nix develop -c python3 tools/scheduling/check_models.py --case combined-scheduling --case combined-one-slot --safety-only --output out/scheduling/composed-safety --timeout 1200 --workers 2 --heap-mib 1024
# Larger extension; a timeout is inconclusive, not a passing result:
nix develop -c python3 tools/scheduling/check_models.py --case combined-extended-scheduling --case combined-extended-one-slot --output out/scheduling/composed-extended --timeout 1200 --workers 4 --heap-mib 4096
```

The original scheduling baseline remains separately runnable; use the composed
check explicitly. Parse failures and timeouts are not passing results. Generated
state graphs and raw traces belong under ignored output directories; tools and
source/configuration hashes are recorded by the runner. Retain both success and incomplete runs; select disk or tmpfs output according
to the graph and available memory.
The explicit `--safety-only` run checks state invariants on the full transition
graph but omits temporal properties, including the response-stability action
property. It cannot establish eventual handoff, retirement or worker progress.
`--composed-invariants` combines those positive combined-state checks with the
full cache-progress cases and every fault diagnostic. Each result records its
verification kind; this target must not be described as a combined liveness proof.
The large temporal graph should use disk-backed output unless sufficient tmpfs
headroom is available in addition to the Java heap. Do not risk host exhaustion
merely to make a check faster.
