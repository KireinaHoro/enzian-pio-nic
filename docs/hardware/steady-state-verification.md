# Settled progress verification

Status: Goal 1 verification infrastructure and bounded evidence, September 30,
2026. **Not a whole-system progress proof or production scheduling pivot.**
Goal 2 remains a subsequent extension. No timing work or board run is involved.
The source audit started at platform `fcbb6aa`; blocks `b527f9c`, DCS `7b5cc87`,
and THX FSMs `a137b82`. These are evidence revisions, not verification results.

## Entry and environment contracts

`ProgressSystem!Settled` names resource typing, tracking, conservation and static
color compatibility in the abstract execution (`Steady` exports it). Its interpretation additionally requires fixed physical worker/domain
assignments, consistent mappings and owner checks, retirement of old-owner cache
state and messages, and legal concrete transport/cache state. Those environmental clauses
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

Initialization chooses independent RX and TX control parities and every direction,
not just parity zero. Repeated operations, direction changes, queued invalidations, acknowledgments
and unlocks are reachable. An unlock can remain queued across software completion
and the next operation: half-duplex is not a transport-drain assumption. Cache
payloads and the exact set of reachable THX stable/transient cache states remain
behind the single-transaction assumption. This is not exhaustive initialization
from every concrete legal cache state.

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
queue processing, return and unlock actions use `WF`. Concrete arbitration must
justify this fairness; priority RTL alone does not prove it.

## Replacement address configurations and capacity

The concrete solution is documented in the [checked block layout](coherence-progress.md#example-permissive-layout)
and [domain-qualified PA encoding](coherence-progress.md#concrete-domain-qualified-pa-encoding):
RX controls at `+0x300/+0x380`, TX controls at `+0x1300/+0x1380`, twelve
contiguous overflow lines after each pair, and a 128 KiB block stride. For the
default 64-color/38-bit geometry, PA fields are domain `[37:26]`, worker mask
`[25:20]`, domain `[19:17]`, then block offset `[16:0]`. These describe the checked
candidate for the router-free design, not installed production constants.

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

Defaults reproduce the documented candidate RX CLs 6/7 + overflow 8..19, TX CLs
38/39 + overflow 40..51, stride 0x20000, no reservations, 38-bit aperture:

| Geometry / layout / aperture | Checked color frontier (workers, domains), plus one bypass |
| --- | --- |
| 64 colors, default layout, 38 bits | every worker count 1..7, each with 32768 domains |
| 128 colors, default layout, 38 bits | every worker count 1..15, each with 16384 domains |
| 128 colors, default layout, 26 bits | every worker count 1..7, each with 8 domains |
| 32 colors, RX 0/1+2..3, TX 4/5+6..7, 26 bits | every worker count 1..3, each with 16 domains |
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

Run from the repository root. Generated output belongs under ignored `out/`.
Obtain the official TLC 1.8.0 `tla2tools.jar`; the checked artifact SHA-256 is
`ab4694601923fd5ac06452abbf847c366a5054a3d739552085edd6ed986c29ec`.

```sh
mkdir -p out/progress
nix develop -c python3 -m unittest discover -s tools/progress -v
nix develop -c python3 tools/progress/capacity.py --workers 4 --domains 16 --verify > out/progress/map.json
nix develop -c python3 tools/progress/verify_config.py out/progress/map.json
nix develop -c python3 tools/progress/witness_model.py out/progress/map.json --output out/progress/witness
nix develop -c java -cp /tmp/lauberhorn-tla2tools.jar tlc2.TLC -workers 1 -lncheck final -metadir out/progress/witness-tlc -config out/progress/witness/Witness.cfg out/progress/witness/Witness.tla
# Exhaust all offsets at the default stride/footprint (5245 equivalence classes):
nix develop -c python3 tools/progress/sweep_layouts.py --jobs 4 --output out/progress/sweep.json
# Explicitly select a wider finite family (may be expensive):
nix develop -c python3 tools/progress/capacity.py --rx 0 6 --tx 38 64 --stride-bits 15 17
nix develop -c python3 tools/progress/check_models.py --jar /tmp/lauberhorn-tla2tools.jar --output out/progress/new-run
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim -- -z rx-independent-progress
```

The runner records full TLC counterexamples, tool/source checksums and compact
results. It preserves partial output and marks timeouts incomplete (exit 124 in
its summary). `--case bypass-overflow --timeout 60` reproduces the larger optional
bound; it is excluded from the default suite. The default bypass case checks
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

## Results recorded September 30

Eight Python tests passed in Nix, including independent configuration checking,
corrupt-witness rejection, alias inversion, exhaustive small-geometry partitioning,
brute-force compatibility cross-checks and the sweep symmetry reduction. `--verify` also enumerated the entire
reported default-layout frontier's physical mappings. The standalone decoder
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
family; extend witness-bound checking to more concurrent endpoints; complete resource
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
