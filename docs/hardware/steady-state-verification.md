# Settled progress verification

Status (2026-09-30): **Goal 1 complete as a bounded modeling/layout milestone**,
accepted by the maintainer to proceed with Goal 2 modeling and implementation.
This is not full hardware verification, a whole-system progress proof or an
implemented production scheduling pivot. Remaining validation is tracked in the
[roadmap](../research/roadmap.md#scheduling-verification-milestones-maintainer-2026-09-30).
No timing work or board run is involved.
The source audit started at platform `fcbb6aa`; blocks `b527f9c`, DCS `7b5cc87`,
and THX FSMs `a137b82`. These are evidence revisions, not verification results.

## Entry and environment contracts

`ProgressSystem!Settled` names resource typing, tracking, conservation and static
color compatibility in the abstract execution (`Steady` exports it). Its interpretation additionally requires fixed physical worker/domain
assignments, consistent mappings and owner checks, completion or safe tracking of old-owner transactions, and legal concrete
transport/cache state. Ordinary cache residency need not be empty;
[retained-cache accounting](retained-cache-composition.md) supplies the additional
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
initialization: the harness does not yet freeze a worker after a previous TX
operation while that same worker retains an old unlock. Active clients do exercise
retained unlocks across direction changes. The `LateUnlock` component check below
seeds one such retained obligation and checks its drain; it is not an exhaustive
late-stall extension of the full 2F2F harness.

## Evidence audit and correspondence

| Concrete evidence | Abstract event/resource | Status / limitation |
| --- | --- | --- |
| `EciCmdDefs.scala` alias/unalias equations | Python `alias`, `unalias`, `color` | Exhaustive small input and basis checks; all candidate lines checked by `--verify` |
| `eci_dcs_defs.sv`: 38-bit slice address, 5/6/7 color bits | aperture, Colors, ColorOf | Source confirmed; kernel aperture is not extra preserved address bits |
| `EciInterfacePlugin.scala`: `coreOffset=0x20000` | candidate stride input | Current constant, not a new architecture limit |
| `rd_trmgr.sv`: REQ_ACCEPT/RSP_ACCEPT | DCS reads[color] stores the full Read record, ReceiveRead/CompleteRead | One retained read per color confirmed |
| `wr_trmgr.sv`, `dcu_controller.sv` launch/stall paths | invalidate processing blocked by occupied target | Coarse abstraction; writeback/RTG/ECI transaction slots are not individually modeled |
| `dcu_controller.sv` explicit HoL comments | per-resource FIFO; shared-queue counterexample | Actual VC/queue topology still needs full correspondence |
| `DcsAppMaster.scala` default five slice reads | DCS ReadSlots per SliceOf[color] | Simulation evidence; five is not a proven silicon limit |
| `EciInterfacePlugin.scala` crossbar W+2 upstream, one operation per destination | finite admission/backpressure | Crossbar issue queues not independently instantiated in this model |
| `EciDecoupledRxTxProtocol.scala` LCIA -> queued UL, overflow+2 depth per direction | Ack -> unlockQ -> DeliverUnlock | Distinct acceptance/release; model serializes ack occupancy per client and uses aggregate unlock depth N |
| Protocol `rxInvDone`/`txInvDone` versus `transportQuiescent` | Return may precede DeliverUnlock | Source confirms that software completion is not final unlock delivery |
| `EciThreadClRouter.scala` AXI prefix rewrite, LCI/LCIA/UL translation | future direct worker/domain decoder | Current router is NOT the generated configuration target |
| `deps/thx-fsms/README.md` explicitly excludes LFB/write-buffer internals | ThxResources pool/credit alternatives | All THX slot counts and sharing assumptions are unconfirmed |

The DCS abstraction omits standalone data writes, RTG/set capacity, data beat
queues, CDC buffers, AXI response ordering and transaction framing. Thus a positive
model result requires their refinement or a separate nonblocking contract; it
cannot establish the production end-to-end capacity. There is no assumption that
an arbitrary blocked transaction eventually completes. Service fairness only
applies when the modeled action is enabled. Downstream ability to deliver eligible
unlocks is assumed; extending backpressure to those omitted paths is still needed.

### Additional source-backed resource bounds

The [reproducible RTG audit](../../tools/progress/audit_resources.py) now counts
potential tags from **all configured domain aliases** using the full set index.
It does not assume old-owner lines have vanished, nor infer available ways from
the active footprint. Full dirty sets and conflict replacement are modeled in
[the retained-cache composition](retained-cache-composition.md). DCS does not
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
The current single invalidation queue abstraction does not reproduce those five
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

## Ports and composition

Modules expose state tuples and use `INSTANCE ... WITH`. The DCS module contains
no domain, scheduler, epoll, worker policy, or parity. `Probe.tla` uses it unchanged
with a non-2F2F probe client. Message records carry `type`, `txn`, and full abstract
`address`; queues are sequences, preserving equal-valued repetitions. The current
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

## Replacement address configurations and capacity

The **selected** replacement uses the [8 KiB block layout](coherence-progress.md#example-permissive-layout)
and [19-bit XOR domain encoding](coherence-progress.md#concrete-domain-qualified-pa-encoding):
RX controls at `+0x300/+0x380`, TX controls at `+0x1300/+0x1380`, twelve
contiguous overflow lines after each pair, and no preemption control CL. The
searcher's default is now this selected 64-color/38-bit, 8 KiB geometry. Seven
workers plus full-duplex bypass have 524288 domain codes each at the color/address
level. The checked-in [configuration](../../model/steady/selected-8k.json) allocates
seven workers and 16 domains; it targets the router-free decoder, not production
thread-router registers. See the [selected-layout evidence and Goal 1 status](#selected-8-kib-layout-evidence-and-goal-1-status).

The 128 KiB stride is inherited, not necessary. The [smaller-block check](coherence-progress.md#block-size-is-a-search-parameter-not-a-2f2f-requirement)
finds three workers plus bypass in 4 KiB blocks, and seven plus bypass in 8 KiB
blocks, with respectively 1048576 and 524288 domain codes per worker. These are
color/address results only. Reproduce the 4 KiB placement sweep in `nix develop`:

```sh
PYTHONPATH=tools/progress python3 - <<'PYTHON'
from capacity import Layout, frontier
for rx in range(5):
    for tx in range(rx + 14, 19):
        rows = frontier(Layout(rx, tx), stride=12)['frontier']
        print(rx, tx, [(r['workers'], r['domains']) for r in rows])
PYTHON
python3 tools/progress/capacity.py --stride-bits 13
```

`tools/progress/capacity.py` emits `lauberhorn.fixed-worker-domain-map.v1`
configurations, **not thread-router register programming**. A configuration carries
worker bases, shared direction offsets, full-duplex bypass, allocated domain
prefix indices, XOR kernel vectors, decoder/rejection rules and required full
address retention. Worker indices exclude bypass and are distinct from Linux CPU,
thread, process and NIC datapath IDs. `encode` and `decode` are executable reference functions.
Null `prefix_indices` means consecutive indices `0 .. domain_count-1`. Domain
identity is a process/address-space identifier, not a thread count or physical CPU.
Installed-owner checks and safe prefix reuse are requirements for future RTL/SW.

For a stride of 2^s and address width A, the map from block bits to colors is a
linear map over GF(2). Row reduction constructs a canonical representative for
each reachable color and a kernel basis. Every block has a unique decomposition
`representative XOR kernel(prefix)`. With rank r there are 2^(A-s-r) prefixes.
Distinct worker colors imply distinct blocks across all domains and preserve each
worker's signatures. Bypass occupies a separate compatible representative.

The search is exact **only over the supplied offsets, stride, canonical worker
representatives and common kernel-prefix encoding**. Pairwise checks expand every
worker direction against every other worker direction and both bypass directions;
this suffices because a color conflict involves at most two signatures. Both
control parities and every configured overflow line contribute. Every clique size
is searched and paired with its maximum common allowed prefix count. Reserved
regions exclude whole blocks, conservatively, and can reduce the joint frontier.
The verifier separately enumerates all mappings and signatures. Small geometries
are cross-checked by brute-force active-direction enumeration.

The selected default has RX CLs 6/7 + overflow 8..19, TX CLs 38/39 + overflow
40..51, stride 0x2000, no reservations and a 38-bit aperture. Historical checks
below retain their explicitly recorded 128 KiB stride:

| Geometry / layout / aperture | Checked color frontier (workers, domains), plus one bypass |
| --- | --- |
| **64 colors, selected layout, 8 KiB stride, 38 bits** | every worker count 1..7, each with 524288 domains |
| 64 colors, same offsets, 128 KiB stride, 38 bits | every worker count 1..7, each with 32768 domains |
| 128 colors, same offsets, 128 KiB stride, 38 bits | every worker count 1..15, each with 16384 domains |
| 128 colors, same offsets, 128 KiB stride, 26 bits | every worker count 1..7, each with 8 domains |
| 32 colors, RX 0/1+2..3, TX 4/5+6..7, 128 KiB stride, 26 bits | every worker count 1..3, each with 16 domains |
| 32 colors, default 12-overflow layout | no full-duplex bypass witness |
| Current RX 0/1, TX 256/257, 12 overflow | no full-duplex bypass witness |

Four workers/16 domains are an emitted default-geometry regression candidate.
This is **color-compatible capacity**, not resource-verified architecture capacity.
`sweep_layouts.py` additionally exhausted **every disjoint RX/TX placement within
one 0x20000 block**, with adjacent controls, 12 contiguous overflow lines per
direction, 64 colors and no reservations. It reduces the 256-by-256 residue pairs
to 5245 XOR/direction/parity-equivalent full signatures and solves each exactly.
A proper coloring of each compatibility graph bounds clique size for pruning;
it is not used as a heuristic optimum. The result is a maximum of seven workers
plus full-duplex bypass throughout that entire offset family, with the same
32768-domain frontier. Independent checks validate the 256-CL color period,
active-direction/union-signature equivalence, and the winning physical mappings.
The larger adjacent-control/contiguous-overflow/power-of-two-stride family
has not been exhausted: these are best-found architecture candidates, and no
maximum over that larger family is claimed. Current scheduler tables and thread
prefix widths are not imposed on this new-design search. `Global.scala` currently
sets NUM_PROCS=16, PID_WIDTH=16, THR_PREFIX_WIDTH=16 and
NUM_THREADS=NUM_PROCS*NUM_WORKER_CORES. `Scheduler.scala` allocates NUM_PROCS+1
entries. `chrdev.c` limits per-process registrations to NUM_WORKER_CORES and maps
`prefix = 1 + proc_index*NUM_WORKER_CORES + thread_index`; `sched.c` retains the
old prefix until drain before unmapping. These are implementation constraints and
handoff evidence, not replacement architecture limits.

## Reproduce and interpret checks

Run from the repository root with the locked submodules. Python, Java and
**nixpkgs `tlaplus18`** are declared Nix inputs; `flake.lock` pins the package set.
`nix develop` supplies `tlc` and `TLA2TOOLS_JAR`; no manually downloaded jar or
`/tmp` artifact is required. The current nixpkgs jar SHA-256 is
`397829775c6ecaf86f7ae9e5c9b062fcdea39f1b69fc9f0a9a9f0f617dde499e`.
The historical jar with hash `ab469460...` was different despite its 1.8.0 label;
its results below are retained as historical evidence, not reused as current runs.

[The Nix check](../../nix/checks/steady-progress.nix) copies only declared model,
Python and DCS-definition inputs into the sandbox, checks the selected mapping
and RTG bound, and runs the regression suite plus the selected two-worker witness.
Its output contains deterministic source/tool hashes and outcomes plus the RTG
bound. Raw logs, elapsed times and temporary paths are retained by working-tree
runner invocations, not embedded in the Nix output. The flake exposes the checker
as `packages.<system>.tlc` and the check as `checks.<system>.steady-progress`.
It is a standalone flake check; CI pipeline definitions are unchanged, and no
timing work is resumed.

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
nix develop -c python3 tools/progress/audit_resources.py model/steady/selected-8k.json
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

The directed RTL tests hold one or two compatible-color RX requests for a finite
window while an independent worker performs repeated receives and bypass probes
both RX parities. They observe simulated DCS admission, assert no stalled read
returns before peers finish, and check LCI/LCIA/UL-enqueue/UL-fire accounting with
`CoherenceEventChecks`. Same-cycle handshakes are linearized in dependency order;
repeated identical events are counted. Residual events remain outstanding, not
silently released. This checks protocol-side observed executions; it does not
check post-router domain addresses or AXI/ECI word framing. The current layout
prevents a positive production full-duplex bypass test without changing the design.

## Selected 8 KiB layout evidence and Goal 1 status

**Goal 1 milestone complete: layout selected and conditional bounded progress
checked; accepted for subsequent modeling and implementation.** This scope of
completion does not close the shared-resource and hardware-correspondence gaps below. In particular, no result here establishes
hardware-wide HoL freedom for seven active workers plus full-duplex bypass.

The exact [selected configuration](../../model/steady/selected-8k.json) is generated
by [capacity.py](../../tools/progress/capacity.py) with the default 8 KiB stride,
`--workers 7 --domains 16`. [witness_model.py](../../tools/progress/witness_model.py)
binds its full addresses, both control parities, and all 12 overflow lines to
[ProgressSystem.tla](../../model/steady/ProgressSystem.tla), composing the unchanged
[DCS resources](../../model/steady/DcsResources.tla),
[THX resources](../../model/steady/ThxResources.tla), and
[2F2F client](../../model/steady/TwoFClient.tla). The
[single-transaction vocabulary/assumption](../../model/steady/Coherence.tla)
remains an undischarged replacement boundary.

Initial checks for this selection at `f9981d5`, September 30, before the Nix
integration and explicit direction choices (current reruns are recorded below):

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

The shared-FIFO [negative model](../../model/steady/Probe.tla) and its
[bad-hol configuration](../../model/steady/bad-hol.cfg) deliberately place an
otherwise eligible request behind a permanently blocked request. Its counterexample
shows why coloring alone cannot justify HoL freedom; the positive model's separate
queues and credit assumptions need concrete correspondence.

The [initial compact evidence manifest](../../model/steady/selected-8k-evidence.json)
records source/configuration/TLC hashes, state counts and expected negative results
for that earlier source revision.
Full logs and generated harnesses remain ignored under `out/progress/selected-8k/`.
Reproduce the current selected address-bound run in a fresh output directory:

```sh
nix develop -c python3 tools/progress/check_models.py --case selected-workers --output out/progress/selected-recheck
```

Remaining hardware-verification work requires broader concurrent worker/stall-subset progress coverage
and sufficient shared-resource fidelity for DCS write/RTG/VC/CDC/crossbar paths
and THX contention. The new selected-bypass run below closes the one-stalled-worker
plus full-duplex bypass/overflow instance, not all worker/bypass combinations.
Earlier incomplete runs remain historical, not proofs. Existing RTL tests exercise the
current production layout with finite stalls, not this replacement mapping;
no new RTL or board result is claimed. The [entry contract](#entry-and-environment-contracts)
is unchanged and remains the contract Goal 2 must establish. These steady-state
resource-progress obligations remain tracked independently of Goal 2 in the
[targeted DCS RTL validation roadmap](../research/roadmap.md#targeted-dcs-rtl-simulation-after-replacement-rtl-is-available).
The main ECI suites use Scala `DcsAppMaster` in place of DCS RTL; the planned
targeted tests must instantiate the actual DCS RTL once the replacement RTL is available.

## Nix-pinned reruns and expanded bounds

The current checker is `tlaplus18` from the existing `flake.lock`, not a custom
TLC derivation. Its jar hash and Java version are recorded by the runner. The
sandboxed `steady-progress` check passed eleven Python tests, verified all 112
selected worker/domain blocks, checked the RTG occupancy bound, and passed all
15 expected TLC outcomes (including six expected counterexamples). A Nix
`--rebuild` check reproduced the output byte for byte.

The current model makes per-client direction choices explicit. Active workers
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

The [tracked Nix evidence manifest](../../model/steady/nix-progress-evidence.json)
records source, checker, harness and log hashes, bounds and outcomes. Baseline
model/tool sources are at `1d83a27`; extended-run sources are at `b73d238`.
Raw local output is retained in `out/progress/nixpkgs-baseline-final/` and
`out/progress/nixpkgs-tmpfs/`. The four-client timeout is not a passing safety or
progress result.

These finite cases do not certify seven simultaneous workers or the actual THX
credit topology. The selected-bypass case has both progressing bypass directions,
but no additional active worker. The color/address maximum and resource-model
bounds therefore remain separate.

[LateUnlock.tla](../../model/steady/LateUnlock.tla), with
[late-unlock.cfg](../../model/steady/late-unlock.cfg), reuses the unchanged DCS/THX
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
is now imposed on stalled CPU actions, including admission. These failures to
finish were checker limits, not discovered protocol counterexamples.

Local generated logs, counterexamples and compact summaries are in
`out/progress/final-address-suite/`, `out/progress/full-bypass-extended/`,
`out/progress/witness-complete.log` and `out/progress/rtl.log`. The model runner records
source checksums alongside the TLC checksum. Reproduce checks rather than treating
these local output paths as durable repository artifacts.

## Single-transaction replacement boundary and remaining obligations

`Coherence.tla` and the endpoint actions are an **undischarged protocol-correctness
assumption**, not a placeholder theorem. A future `thx-fsms` instance must preserve
transaction/address identity, legal message sequences, initial cache states,
permitted transitions, and the resource-visible acceptance/completion/release
boundaries. It must not smuggle in eventual completion. Payload integrity and exact
value semantics remain outside Goal 1. Jasmin's canonical model and Toolbox
snapshots are unchanged; their historical outputs are not new evidence.

For concrete refinement, prove initialization establishes the abstract relation;
map each handshake/allocation/queue release to the named actions or stuttering;
show hidden steps cannot stutter forever while preventing enabled abstract service;
and prove the fairness assumptions. Simultaneous concrete events need a valid
linearization or a combined abstract step. AXI/ECI packet lengths and beat framing
need separate assertions (one two-word transaction is not two one-word requests).

Remaining verification work is explicit: exhaust or tighten the intended layout
family; extend witness-bound checking to more concurrent endpoints and late RX
stalls following earlier operations; complete resource
fidelity for writes/RTG/VC/CDC/crossbar/THX; check all supported stall subsets at
larger bounds; and validate a future decoder's full-address preservation. The
current bounded model results do not discharge these obligations. No universal
hardware-refinement obligation is formally discharged by this change.

Board conformance later needs matched bitstream/software/trace maps, reservation,
LCI/LCIA/UL and request/response framing visibility, trace loss accounting and the
CMAC-first capture procedure. Incomplete/dropped traces are inconclusive for missing
events; an observed good trace never proves universal refinement. No board evidence
is newly claimed here.

Goal 2 must use these same resource interfaces for drain, owner installation,
activation, wake ownership, early NACK and retirement. It must establish the full
entry contract above and preserve unaffected workers' progress during transitions,
not merely prove a final settled snapshot. Keep Goal 1 independently runnable.

The [Goal 2 scheduling contract suite](scheduling-verification.md) now reuses
these resources for bounded handoff and wake-activation checks, including a
direct instance of this model's settled predicate at activation. Goal 2 remains
in progress; its component results do not change the hardware-refinement scope
of Goal 1.

## Clarification: retained cache state at ordinary handoff

The [single-read control interface](../software/scheduling-control-interface.md)
does not require kernel cache-line accesses or an old-domain cache flush on every
handoff. Domains have distinct PAs; the revised handoff model waits only for its
modeled NIC-internal obligations before returning the new snapshot. Cached old
generations are now accounted for by the separate retained-cache composition.
The six-line active-assignment statistic is not a residency or progress bound;
full sets, dirty entries and conflict replacement are allowed. Concrete CPU
replacement/downgrade correspondence remains to be established. Whole-domain
prefix reuse still requires a separate coherence retirement certificate. This
clarification does not extend the existing Goal 1 proof to those omitted states.
