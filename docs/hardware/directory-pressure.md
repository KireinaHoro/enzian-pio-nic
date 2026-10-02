# Directory pressure: replacement initiation and delivery

**Follow-up:** the [LCI-only non-locking build](lauberhorn-rom.md) is now selected
on the Goal 2 branch. The investigation below records its historical inputs and
remaining proof obligations; earlier production-selection statements are historical.

Source audit, bounded model checks and targeted RTL simulation, 2026-10-01.
**An absent local invalidation on a full directory set is an unresolved progress
case in the selected design.** Auto-lock is retained by maintainer decision.
The corrected model produces a counterexample; a new reset-to-full RTL test now
reaches the dependency through actual directory reads, fills and eviction, without
forcing lookup/full outputs. See [the reachable-directory test](#auto-lock-reachability-through-the-actual-directory).
Earlier retained-cache progress results used a nonallocating invalidation
abstraction and do not establish progress for the active `auto_lock` DCS protocol.
The selected 8 KiB coloring/address-capacity calculation remains valid, but is
not a solution to this directory-capacity issue.

## 1. What causes a CPU eviction?

The [DCS architecture notes](../../vivado/eci/directory-controller-slice/documentation/introduction.tex)
explicitly describe matching a 16 MiB CPU LLC with an 8192-set, 16-way directory.
The [RTL geometry](../../vivado/eci/directory-controller-slice/dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv)
implements 13 set bits, 16 ways and 128-byte lines. The
[toolkit alias function](../../vivado/eci/eci-toolkit/hdl/eci_cmd_defs.sv)
scrambles the physical line index before its low 13 bits select the directory set.
The [Enzian cache-control thesis, §2.2.1](https://www.research-collection.ethz.ch/bitstreams/dcebdb42-4213-4d3a-ada1-cc58715b2760/download)
independently describes the CPU's 8192 sets/16 ways, replacement policy, way
partitioning and lockable cache lines. It cites the CN88XX hardware manual;
that manual and the running machine's cache configuration were not checked here.

The intended CPU-request argument is a pigeonhole argument. Assume:

1. CPU and directory set mappings agree for the relevant addresses.
2. An emitted CPU miss reserves a way in that CPU set, including in-flight fills.
3. Every directory entry is backed by a CPU allocation or an outstanding release
   obligation (eviction/writeback, invalidation/unlock); nothing disappears silently.
4. The CPU's effective per-set capacity does not exceed the directory's.

If a new CPU miss reaches a full 16-entry directory set, those 16 old entries
cannot all still occupy the CPU set alongside the new allocation. At least one
old directory charge must therefore have a release obligation. The incoming
request may overtake that release without invalidating this argument. This
proves existence of a release obligation, **not its eventual delivery**.
Locked ways, allocation timing, silent drops and FPGA-created entries need their
own correspondence; equal aggregate cache sizes alone are insufficient.

[`DirectoryAdmission.tla`](../../model/tla/steady/DirectoryAdmission.tla) checks this
contract with one initially full set, 1/2/3 ways, `Ways+2` addresses, repeated CPU
requests, ordered typed clean/dirty victims, and delayed invalidation unlocks.
CPU reservations are charged before request emission. `BlockingHasRelease`
checks the argument; accounting and request-completion properties are checked too.
Weak fairness applies to eligible delivery/unlock/completion and strong fairness
to eligible directory admission. CPU access, eviction and invalidation initiation
are not fair: the model cannot invent a victim merely to make progress. Completion
is conditional on the modeled release service, not proof of THX arbitration.
Deliberately mismatching the CPU set mapping, issuing before CPU allocation or
silently discarding a victim each violates `BlockingHasRelease`.

## The exception: FPGA invalidation is not a CPU miss

The active build selects the `dc_stmc_auto_lock` protocol in
[`create_project.tcl`](../../vivado/eci/create_project.tcl). Its generated
[entry classification](../../vivado/eci/directory-controller-slice/gen_dc_protocol/dc_stmc_auto_lock/cc_rom_from_cc_tbl/eci_cc_defs.sv)
marks LC/LCI as allocating requests. The
[protocol table](../../vivado/eci/directory-controller-slice/gen_dc_protocol/dc_stmc_auto_lock/cc_rom_from_cc_tbl/eci_cc_table.sv)
transitions an absent line (`s1__1`) on LCI to the charged lock state `s1pUL__1`,
returns LCIA and keeps the entry until UL. This is not a CPU cache-line lock.
The [controller](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu_controller/rtl/dcu_controller.sv)
stalls an allocating miss when the RTG set is full. It does not generate a victim.

A CPU RX on parity 0 may need a local invalidation of parity 1 before it can
return. Those lines have different directory sets. If parity 1's set contains
16 stable old-domain lines, the new domain's absent parity-1 LCI cannot allocate.
The current CPU access is to parity 0, so it need not force any eviction from the
parity-1 set. No amount of fair delivery helps when no victim was generated.
This does not depend on a clean cache, address reuse or a faulty application.

The [address-witness generator](../../tools/progress/directory_witness.py) uses
the unchanged selected map, expanded from the 16-domain regression to the full
19-bit encoding. For physical worker 0 it finds 16 distinct old-domain RX parity-1
lines in RTG set 517, using domain codes
`0, 210, 292, 502, 512, 722, 804, 1014, 1024, 1234, 1316, 1526, 1536, 1746, 1828, 2038`.
Domain 2048's absent target is `0x40200380`, also set 517; its RX doorbell is
`0x40200300`, set 516. Every address is checked by encode/decode round trip.
This is a realizable address pattern under monotonic allocation, **not a captured
board execution or a full concrete reachability proof of the cache state**.

The 16-way `directory-local-full` configuration demonstrates the fixed point
with no CPU demand for that target set. The corrected reusable
[`RetainedCache`](../../model/tla/steady/RetainedCache.tla) now exposes
`AllocateOnInvalidate`. TRUE reserves the absent line until UL and refuses a
full-set allocation. FALSE is the explicitly historical nonallocating endpoint
contract. The source-faithful `retained-cache-dcs-counterexample` composes TRUE
with the existing worker/resource harness. Its entire reachable graph satisfies
resource accounting but violates peer progress: worker 2 holds its RX and waits
for invalidation of address 3, whose set is full of old addresses 7 and 11.
No eviction is queued. Worker 1 may remain stalled, but is not needed to explain
this target-set capacity obstruction.

The older positive `CachedProgress` and `CombinedScheduling` defaults explicitly
retain FALSE to preserve their abstract regression contracts. They must not be
reported as DCS-corresponding progress results. An explicit
`combined-extended-local-allocation` case enables TRUE in the larger model; it
has not been run here. The new small counterexample already prevents claiming
whole-system progress from the earlier positive abstraction.

## 2. Can the release traffic get through?

There is positive evidence at the DCU input boundary:

- The [decoder](../../vivado/eci/directory-controller-slice/dcs/dcu/decode_eci_req/rtl/decode_eci_req.sv)
  places clean V21/V31/V32 victim messages on response-without-data VCs, separate
  from CPU read requests. Dirty V31d/V32d messages use response-with-data VCs.
- [`dcu_top`](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu_top/rtl/dcu_top.sv)
  buffers/arbitrates these classes separately. Its actual
  [`arb_4_ecih`](../../vivado/eci/directory-controller-slice/dcs/dcu/arb_4_ecih/rtl/arb_4_ecih.sv)
  rotates selection on a stall without popping the stalled head.
- `dcu_controller` prioritizes available memory responses and returns to input
  selection after reporting a full-set/resource stall.

This establishes a mechanism for bypassing a stalled *different message class*.
It does not establish independent queues for every color end to end. In particular,
[`dcs_dcus`](../../vivado/eci/directory-controller-slice/dcs/dcs_dcus/rtl/dcs_dcus.sv)
has shared per-class ingress before destination routing. Dirty victims also pass
through the write data path's
[`dp_gate`](../../vivado/eci/directory-controller-slice/dcs/dcs_data_path/dp_gate/rtl/dp_gate.sv):
there is one busy slot per DCU, but the incoming stream waits if its head names a
busy slot. It cannot skip that head to expose a later other-DCU message. The
controller can also wait for outgoing-header readiness before handling another
input. These are real shared-resource dependencies, not proved deadlocks.

The [DCS notes](../../vivado/eci/directory-controller-slice/documentation/dcu.tex)
already identify the outgoing-header wait and shared output channel as possible
deadlock concerns. To close this second obligation, establish finite service of
the blocked head under the actual writeback/VC/credit contracts, or an independent
escape path. The single-line THX FSMs explicitly exclude write buffers and LFBs;
they cannot establish that contract. No new claim about undocumented THX queue
organization is made here.

## Actual RTL checks and reproduction

The [directed testbench](../../hw/test/rtl/dcs_pressure/DcsPressureTb.sv) instantiates
the current DCS controller, selected auto-lock protocol table, five-input arbiter
and data gate. It uses controlled directory lookup results and downstream
readiness; it does **not** instantiate the whole directory RAM, slice, link or CPU.
Checks cover full-set absent-LCI retry, admission after a way is freed, lock charge
until UL, bypass of a stalled request class, busy-DCU data-head backpressure for
64 cycles, and recovery/independent slot use after that head is released.
Finite backpressure tests do not prove infinite-stall liveness. The wider planned
actual-DCS routing/transport tests remain necessary.

```sh
nix build .#checks.x86_64-linux.scheduling-cache-contracts --out-link out/scheduling/cache-contracts-nix
nix build .#checks.x86_64-linux.dcs-pressure --out-link out/progress/dcs-pressure-nix
nix develop -c python3 tools/scheduling/check_models.py --cache-contracts-only --output out/scheduling/cache-contracts --timeout 300 --workers 4 --heap-mib 2048
nix develop -c python3 tools/progress/check_dcs_pressure.py --output out/progress/dcs-pressure
nix develop -c python3 tools/progress/directory_witness.py model/data/selected-8k.json
```

Both Nix targets completed successfully: eight model outcomes (three conditional
positives, three deliberately broken contracts, and two genuine design progress
counterexamples) and six RTL checks. The corrected retained-cache graph has 296
distinct states, satisfies its resource-accounting invariant, and fails Progress.
The 12 Python capacity/accounting tests and both historical nonallocating-cache
positives also passed. Full combined scheduling checks were not rerun.

TLC (`tlaplus18`) and Verilator are pinned by Nix. The
[evidence manifest](../../model/evidence/steady/directory-pressure-evidence.json) distinguishes
positive properties, intended fault diagnostics and genuine design counterexamples.
A runner PASS for `directory-local-full` or `retained-cache-dcs-counterexample`
means the expected **progress failure was reproduced**, not that progress passed.
No production RTL, toolkit branch, driver or board configuration was changed.

## Next required design decision

Guarantee local-invalidation allocation independently of a CPU miss at its target
set. Candidate mechanisms include reserved directory headroom backed by a verified
CPU occupancy bound, a separately accounted local lock entry, or a proved DCS
victim/retry mechanism. Merely assuming fair background evictions, flushing on
every domain exit, or acknowledging an absent LCI without preserving the lock
contract does not discharge this obligation. No mechanism is selected or implemented
by this audit. Once selected, compose it with the real shared release path and
rerun the positive resource and scheduling checks before claiming hardware progress.

## Non-auto-lock investigation (2026-10-01)

**Correction after control-path inspection:** the alternative is not safe as a
wholesale table/package swap. It removes lock allocation but exposes a full-miss
write hazard described below. The active allocation problem remains unresolved.

The alternative generated `dc_stmc_auto_no_lock` protocol is a semantic reference,
not a selected production replacement. The extended controller test now also runs this
table: an absent LCI completes with the full flag asserted, returns to `s1__1`,
and creates no new lock charge. However, the controller also asserts directory
write enable on that full miss: the original harness omitted the unrelated
resident entries and missed the destructive effect. The corrected test diagnoses
this unsafe write. The same arbitration/data-gate tests still pass;
nonlocking does not remove shared-path backpressure.

[`check_lock_tables.py`](../../tools/progress/check_lock_tables.py) compares all
explicit generated transitions (2028 auto-lock, 1800 nonlocking). All 1800 shared
entries have identical actions and identical next states after removing the
`pUL` lock marker. Exactly 36 shared entries differ, all producing LCA or LCIA.
The remaining auto-lock entries are UL requests or lock states. This is a
structural table check, not a single-line protocol or progress proof; it does not
prove defaults, concrete THX behavior, or all surrounding RTL compatible.

The lock protects the interval from coherence completion until UL: CPU requests
cannot acquire the line during that interval. It does not itself protect the
entire NIC packet-processing interval. In
[`EciDecoupledRxTxProtocol.scala`](../../hw/src/lauberhorn/host/eci/EciDecoupledRxTxProtocol.scala),
`recvLciaSendUl` immediately enqueues UL on each LCIA. RX invalidates overflow,
then control, and changes parity on control LCIA. TX invalidates control, then
selected overflow, and only subsequently submits the packet. Thus individual
lines can already be unlocked before the larger operation finishes. UL delivery
can be delayed; enqueue is not remote release.

For a cooperative, ordered CPU client, the opposite-parity read acts as the
handoff: it must not touch retired control/overflow while waiting for publication.
RX publication waits for invalidation completion in `DcsRxAxiRouter`; TX commits
only after its invalidation sequence. Under that client contract, no software
access needs the post-LCIA lock. This is a source-derived candidate argument,
**not yet a checked claim covering speculation, prefetch, or malicious accesses**.
The existing resource model abstracts those accesses away, so its nonallocating
positive results cannot discharge this race.

Before selecting nonlocking, check these cases explicitly:

- Refill/replay of the retired control parity between invalidation completion,
  NIC receipt of LCIA and the next publication, including queued reads.
- RX overflow prefetch/refill after its individual LCIA and before the next
  descriptor; verify stale data cannot survive to the next use of that address.
- TX dirty writeback completion versus descriptor/overflow consumption, and a
  late CPU store or acquisition while commit is outstanding.
- Stopped-owner late requests, kill release and subsequent same-domain activation;
  domain-qualified PAs prevent cross-domain aliasing but not same-PA refill races.

Do not assume software serialization prevents speculative coherent requests.
Determine the effective mapping attributes, runtime barriers and NIC response
rules, then model the permitted requests. Retaining auto-lock only until immediate
UL is not automatically a solution to refills after that UL either.

A switch also requires integration work: `decode_eci_req.sv` unconditionally
references `UL`, which the alternative package does not define. Remove/parameterize
that decode together with NIC UL generation, UL accounting and preemption-control
users; select matching generated collateral. Do not mix enum encodings from the
two packages. The targeted alternative test deliberately excludes the decoder
and is not a whole-slice compilation test. No production sources were switched.

Reproduce both variants and the table comparison with the existing pinned
`dcs-pressure` Nix check. It emits `summary.json` (six auto-lock checks),
`nonlocking-summary.json` (five checks, including the expected unsafe-write diagnostic), and `lock-tables.json`.
The nonlocking controller test and structural comparison passed in this audit;
full protocol-race checking and shared release-path guarantees remain pending.

### AXI response gating is the candidate replacement for the lock

Maintainer clarification: Lauberhorn synthesizes AXI responses and deliberately
blocks reload completion; the DCS need not be treated as an ordinary always-ready
memory controller. Source inspection supports this distinction. Both RX and TX
routers have one serial read FSM behind an eight-entry AXI queue. While the
opposite-parity control read is active, neither engine returns to its read-idle
state to service later control or overflow requests. TX additionally waits for
submission/result before releasing the read. Writeback has a separate TX FSM.
In the nonlocking table, absent R12/R13 issue RDD and enter pending-response
states; removing the post-LCIA lock does not remove this normal coherence
transaction serialization. Holding AXI R therefore delays CPU acquisition.

[`AxiReloadGate.tla`](../../model/tla/composition/AxiReloadGate.tla) checks the narrow
replacement contract: one held door read followed by a reload of either of two
retired lines (control/overflow), arbitrary invalidation and acknowledgment order,
and publication only after both acknowledgments. There are no local directory
locks. Serial read service prevents stale-generation refills; eligible release
service permits completion. Weak fairness applies only to enabled invalidation,
acknowledgment, publication and serial read service. Deliberately allowing a
reload to bypass the held read violates `NoStaleRefill`; making LCIA service wait
for the read queue to empty violates `Completes`. Both negative outcomes are
expected diagnostic failures. All three checks completed with pinned TLC (24 states for the positive case).
The [evidence manifest](../../model/evidence/steady/nonlocking-evidence.json) records
model outcomes, source hashes and both Nix RTL results.

This model starts **after the door has become the active AXI head**. It does not
establish that every concrete transition reaches that state safely. Remaining
obligations are requests already ahead of the door, coherence responses already
in flight, CPU speculative accesses that trigger parity transitions, and
preemption/kill paths without an active door. The replacement must establish an
explicit response fence on those paths or prove their existing sequencing
sufficient. CPU access permission changes that need no new AXI read also require
single-line correspondence; the model assumes acquisition after invalidation
requires a new response. Full payload values and the whole DCS transport remain
outside this small check. This is stronger support for the nonlocking candidate,
not a production-switch decision or whole-protocol proof.

```sh
nix develop -c python3 tools/scheduling/check_models.py \
  --case axi-reload-gate --case bad-axi-reload-bypass --case bad-axi-release-gate \
  --output out/scheduling/axi-reload-gate --workers 2 --heap-mib 1024
```


### Control-path incompatibility and ROM provenance

The active allocation stall is **not resolved**. The pinned DCS revision is
`7b5cc874155f96735f135d3cd1f2c514bacfe28f` on `og-state-machine`; production still
selects auto-lock. AXI gating is a possible correctness argument for removing
post-LCIA exclusion, not a directory-allocation fix by itself.

The precise wholesale-swap hazard is:

1. `dcu_controller.READ_RTG` lets a nonallocating miss proceed even when full.
2. `SEND_LCIA` completes through `handle_op_handshake` and `write_rtg`, which
   unconditionally asserts `rtg_wr_en_o`.
3. [`dcu.sv`](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu/rtl/dcu.sv)
   directly wires that enable to TSU write enable and uses `tsu_rd_tag_way_o`.
   The table's `ccr_ns_valid_o` exists but does not gate this write.
4. On a full miss, `dcu_tsu` returns an arbitrary way, explicitly documented
   as **do not write**. `tag_state_ram` writes `INVALID_TAG` when the written
   state is invalid (`s1__1`). Consequently, this combination invalidates an
   unrelated resident entry rather than doing a harmless no-op.

The corrected actual-controller test observes the full-miss write strobe and
invalid next state. The resulting unrelated-entry invalidation follows from
source wiring and RAM semantics; this harness still does not instantiate TSU/RAM.
The [corrected evidence](../../model/evidence/steady/nonlocking-write-hazard-evidence.json)
records both pinned RTL runs and the additional source hashes.
Its diagnostic PASS means the unsafe behavior was reproduced, not a passing
replacement implementation. This supersedes the earlier inference from completion
alone in commit `70373df`; that commit's historical evidence is retained.

Any nonallocating invalidation implementation must distinguish lookup hit from
miss at directory commit. Hit-to-invalid must still remove the matched entry;
miss-to-invalid must not write any entry. Miss-to-valid requires a genuinely
available way and must retain the existing capacity check. Preserve lookup context
across output backpressure and completion. Merely clearing LCI's allocation bit
while retaining the lock-producing ROM would be worse: it could overwrite an
unrelated entry with a live lock. These are required design/verification conditions,
not implemented fixes. The UL decoder/interface incompatibility remains separate.

The maintainer's recollection of later auto-lock evolution is also supported by
local Git history: `a818e5d` (2024-05-22) regenerated exclusive-response behavior;
`4bc6183` (2025-05-20) simplified the generated FSM; `2d77ada` (2025-10-20)
updated CSV/generator simplification. These changes are on other local-history
branches, not ancestors of the pinned `og-state-machine` revision. The checked-out
ROM comparison therefore says nothing about replacing those evolved variants.
The pinned tables/generator trace to the 2023 synchronization. Do not silently
switch branches or regenerate from a different branch's CSV/generator. Choose and
record the intended protocol baseline before deriving a minimal nonlocking change;
retain its response semantics and later fixes explicitly.

Next: establish the AXI fence contract, select the intended active/evolved FSM
baseline, and derive a narrow coordinated ROM/allocation/writeback/interface change.
Then validate hit, empty miss and full miss against the actual directory RAM,
including output backpressure and preservation of every unrelated entry. No DCS
submodule, production ROM, or controller has been modified by this investigation.


## Auto-lock reachability through the actual directory

**Decision: keep auto-lock.** The nonlocking experiment above is historical
investigation, not the implementation direction. The remaining question is
allocation progress under the retained protocol.

[`DcsDirectoryTb.sv`](../../hw/test/rtl/dcs_pressure/DcsDirectoryTb.sv) now
instantiates the actual `dcu_controller`, active auto-lock table, `dcu_tsu`,
`tag_state_ram` and `ram_tdp`, at the current 128 sets/DCU, 16 ways and registered
TSR-output geometry. It mirrors `dcu.sv`'s lookup/context/write wiring. The only
memory substitution is a test model of the XPM primitive's configured common-clock,
one-cycle, write-first 1Kx36 RAM, with 9-bit write enables and collision assertions.
The production reset sweep, tag comparison, free-way selection, state reads and
writes all execute as RTL. There are no forced full/hit flags, preloaded entries,
or direct testbench directory writes.

The trace starts from reset and uses these legal endpoint events:

1. R12 followed by memory RDDA fills the target and fifteen other tags in one
   set. Every line reaches stable `s1__2`.
2. A clean CPU victim V21 removes the target. R12/RDDA fills that freed way with
   a sixteenth other tag. CPU occupancy never needs to exceed sixteen ways.
3. A read in a different set is admitted and its memory response is withheld.
   It does not force a victim in the target set. This is generic other-set demand,
   not an instantiated Lauberhorn doorbell/AXI engine.
4. LCI for the absent target retries 57 times over 512 cycles. No LCIA, allocation
   or directory write occurs. The full flag is produced by the actual lookup.
5. Input arbitration is allowed to select LCI for a **resident** target in the
   full set. LCI/F21/A21 completes and changes its existing entry to `s1pUL__1`.
   No extra way is required. Holding that UL leaves the absent-target LCI blocked
   for another 512 cycles/57 retries.
6. UL releases that resident lock. Retried absent-target LCI now allocates the
   available way, returns LCIA and remains charged until its own UL.
7. The test verifies all fifteen unrelated resident tags still exist, and the
   other-set pending read can complete when its memory response is supplied.

This resolves the **directory-component reachability** question: the obstruction
is real in the active RTL, not an artifact of forcing an impossible full-set flag.
It also narrows the claim: *full-set hit invalidation is fine*. The problematic
operation is allocating a lock for an absent target. Completing an invalidation
of a resident line does not itself free capacity; UL does. No autonomous victim
is generated during the repeated full miss. The infinite-wait argument remains
conditional on no subsequent CPU victim or other release; the linked finite
TLA+ local-full counterexample captures that fixed-point contract. Simulation
observes bounded repeated stalls, not infinite execution or a board deadlock.

The harness supplies decoded requests, memory completions, output readiness and
input selection. It does not instantiate THX, link/credit paths, transaction
managers, the whole DCU/slice or Lauberhorn's runtime. The selected-layout address
witness supplies valid PA collisions separately; this test uses generic tags in
one set. It therefore does **not** prove the whole workload/scheduler history.
In particular, a fresh first RX does not automatically invalidate the opposite
parity: the current RX engine first returns a descriptor and invalidates the
previously delivered parity on a subsequent opposite-parity read. The original
address witness must not be read as a first-RX execution trace. An absent target
can have been evicted earlier; RX overflow invalidation can also target lines
software never read, since its count comes from packet length. Establishing a
whole NIC execution with sixteen retained colliding lines remains a separate
integration obligation. The replacement contract explicitly permits old cached
PAs; that permission alone is not a concrete runtime trace.

The [evidence manifest](../../model/evidence/steady/directory-rtl-reachability-evidence.json)
records the six passing directory checks, source hashes, and unchanged-variant
regressions. Reproduce via the existing `dcs-pressure` Nix target (now additionally emits
`directory-summary.json` and `directory-events.log`) or:

```sh
nix develop -c python3 tools/progress/check_dcs_pressure.py --directory \
  --output out/progress/dcs-directory-reachable
```

Next work stays on auto-lock: establish or enforce a bound/guarantee ensuring a
way is available for an absent local target, or demonstrate that the actual NIC
sequence always supplies an independent release. No capacity workaround, forced
eviction or production protocol/control-path change has been selected here.

## TLA+ rerun with the RTL allocation contract

The DC model is a composition: `DcsResources` owns transport queues, while
`RetainedCache` owns directory entries, CPU residency, eviction delivery and
retained local locks. `Process` in `CachedProgress` and `CombinedScheduling`
conjoins both modules' actions, so a queued LCI cannot become an ACK if its
absent target needs an unavailable way. The reusable directory component now
names `Full`, `AbsentLockWait` and `CanInvalidate` explicitly. A hit reuses its
entry, an absent target reserves a new entry, and an already locked target cannot
be invalidated again before UL. The resource remains charged through LCIA and
until the modeled unlock reaches the directory. Neither component contains
Lauberhorn domain policy.

[`DcsDirectoryCycle.tla`](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/blob/4cb1e8a/model/steady/DcsDirectoryCycle.tla) composes these
same modules for a from-empty fill/evict/replace/invalidate sequence. At 2 and 16
ways, full-set hits complete and full-set misses fail `Completes`. An explicitly
supplied independent victim restores progress in the recovery configuration.
Safety includes conservation, lock retention until UL and exclusion of a second
invalidation while locked. Individual coherence exchanges are still abstracted;
this model does not import THX FSMs or prove their message/data semantics.

`CachedProgress` now also has `ReachableCache=TRUE`: twelve setup actions fill
target 3, fill colliding line 7, evict/deliver 3, replace it with line 11, and
populate the other sets through the shared cache actions. It begins with an
empty directory. The setup history precedes the two-client execution; it is not
a new assumption that production starts with a clean cache. The old arbitrary
full-cache initial states remain regression cases.

The rerun finds actual **temporal counterexamples**, not just momentary waits:

| Configuration | Checked graph / outcome |
| --- | --- |
| Full-set hit, 2 and 16 ways | Safety and completion pass |
| Full-set absent target, 2 and 16 ways | Safety holds; completion fails |
| Absent target with independent victim | Safety and completion pass |
| From-empty worker/cache composition, auto-lock | All 320 states satisfy safety; `Progress` fails |
| Same from-empty composition, historical nonallocating abstraction | Safety/progress pass, 11,744 states; comparison only, not active DCS evidence |
| Combined scheduler, auto-lock, one destination slot | All 33,740 states satisfy safety; separate complete checks fail `HandoffFinishes` and `StableProgress` |

The worker counterexample ends with worker 1 held, worker 2 waiting for ACK of
LCI address 3, and stable addresses 7/11 occupying its two-way target set. No
release is queued for that set. In the combined one-way model, addresses 9/11
occupy the sets needed by absent targets 1/3. The stable-progress trace leaves
both workers in Run/WaitAck; the handoff trace leaves a worker in Preempt/WaitAck.
The combined model still starts from retained old lines, whereas the worker
composition now reaches its pressure state from empty. Neither trace is a
complete concrete-runtime/THX reachability proof.

Fairness serves eligible arbitration, CPU operation and queued release actions.
It does not force a CPU eviction in a set with no new CPU demand and does not
force the blocked LCI to complete. Consequently the counterexamples persist
under the stated fairness. Separate reachability diagnostics (`NoAbsentLockWait`)
locate the wait early; these prefixes are not themselves proofs of indefinite
blocking. The complete temporal checks above supply that stronger result.

Reproduce all 19 directory/cache outcomes with:

```sh
nix build .#checks.x86_64-linux.scheduling-cache-contracts \
  --out-link out/scheduling/auto-lock-contracts-nix
```

The [current evidence manifest](../../model/evidence/steady/auto-lock-model-evidence.json)
records all 19 expected outcomes and the two historical cache regressions.
The Nix output retains the generated configurations, TLC logs/counterexamples
under `traces/`, and a detailed summary. The runner reports expected violations
explicitly and labels auto-lock versus historical nonallocating configurations.
An expected-violation success means the failure was reproduced. These results
supersede any inference of active-DCS progress from the older nonallocating
positives. They do not fix allocation or complete Goal 2. The broader historical
nonallocating combined temporal runs remain incomplete; these new, smaller
**auto-lock** combined runs completed and found counterexamples.

## Existing DC testbench coverage and ICI entry points

Source audit of pinned DCS `7b5cc87`; these upstream XSim benches were **not rerun**
for this audit. This corrects the impression that the full-set LCI behavior was
previously undocumented: the DC repository already has a regression for it.

There is no current ICI producer in the pinned implementation. The local-command
[decoder](../../vivado/eci/directory-controller-slice/dcs/dcu/decode_eci_req/rtl/decode_eci_req.sv)
emits LC/LCI (and UL on the response channel), not ICI. The `dcu.sv` ROM request
mux selects RDDA, WDDA or the decoded request. The controller's full-set path
stalls; it does not select a victim or inject ICI. ICI exists in generated protocol
definitions/transitions and the generator as a potential internal induced
invalidation, not an exposed application command or implemented autonomous
replacement mechanism. No explicit ICI stimulus was found in the SV benches.

The main [dcuTb](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu/testbench/dcuTb.sv)
actually invokes these relevant tests from its initial sequence:

| Existing test | What its source checks | What it does not establish |
| --- | --- | --- |
| `test_lci_stall_tsu_full` (line 292) | Fills all 16 ways with shared lines; issues absent-tag LCI; supplies V21 for all old tags, then retries LCI and checks LCIA, lock state and UL release. Comments identify a previously encountered overwrite bug when allocating LCI was not classified as allocating. | Progress without externally supplied evictions; autonomous ICI victim selection. |
| `test_stall_tsu_full` (line 1455) | Full-set CPU R12 stalls; explicitly supplies one V21, retries and completes. | Despite a comment saying an induced eviction should be in progress, the test body does not provoke or verify an ICI. |
| `test_lci_lcia_ul`, `test_lci_f21_a21_lcia_ul`, `test_lci_f31_a31d_lcia_ul` and related victim/ACK races | Absent, shared and dirty/exclusive LCI paths, LCIA and unlock; related tests cover reordered victim/ack traffic and response backpressure. | Full-set absent-LCI liveness without a victim. |
| `test_lc_lca_ul`, `test_lc_lca_v21_ul`, `test_lc_f32_a32d_lca_ul`, `test_lc_f32_v21_v32_a11_ul` | Shared/dirty LC, LCA/UL and victim races. | A dedicated full-set **absent LC** regression was not found. |
| `dcu_tsuTb`, `tag_state_ramTb` | Capacity, hit/miss/full reporting and entry removal. | Coupled LC/LCI progress. |
| `eci_cc_tableTb` | Small R12/R23 stimulus. | Exhaustive ROM transitions or explicit clean/dirty ICI paths. |

The bench's send helper stops presenting an event after either ready **or stall**;
it then injects V21 and explicitly retries. Thus the old LCI regression is a
safety/recovery test, not evidence that production will always generate the
needed release. Its checks explain why stalling is the intentional current
behavior: it prevents overwriting an unrelated directory entry.

Our newly run directory test adds bounded persistence with no supplied victim,
full-hit comparison, retained lock occupancy and UL-triggered recovery; the TLA+
checks expose indefinite waiting under the specified environment. The upstream
bench already covers the central full-miss LCI/stall/retry scenario. Neither the
upstream coverage nor our tests establish a working ICI eviction mechanism.
Any such implementation needs new producer/victim-selection tests, clean and
dirty ICI completion tests, locked/transient-victim rejection, preservation of
unrelated entries and shared-path progress while the original LCI waits.

## Allocation-full tracing; eviction policy deferred

Maintainer direction: prioritize observing allocation stalls. Keep auto-lock and
leave automatic ICI eviction undecided. Local LC/LCI requests can themselves fill
all sixteen ways with entries waiting for UL; those entries are not evictable.
Thus forced CPU eviction could help some resident-entry pressure cases but is not
a general guarantee of local allocation. A full-set stall is ordinary backpressure
if a release will arrive; it is not by itself proof of deadlock.

DCS commit `eb64df4` on `trace-directory-allocation` supplies the trace-only
change. The [evidence manifest](../../model/evidence/steady/directory-trace-evidence.json)
records six trace checks plus the existing 17 RTL diagnostic checks and table
comparison, all completed through pinned Nix.

The trace-only DCS change adds `ERR_RTG_ALLOC_FULL` (numeric 7, with `error=0`)
when an allocating directory miss stalls for lack of a free way. The event carries
that request's decoded opcode and full aligned aliased address (decoded back to
PA using the matching trace tooling). Because the controller
has not enabled the ROM on this path, `state=s1__1` and `action=STALL` are explicitly
constructed diagnostic values, not a claimed ROM transition or directory write.
The old trace could use the previous ROM request/action and a stale response
address because the stalled request had not asserted ready. The slice address
selector now uses the request/context header for stalled requests and RDDA/WDDA
as well as accepted requests. No trace layout width or pre-existing enum value
changes; the software metadata adds the new enum label.

Repeated request-stall reports are limited to sixteen for the same header/VC
across controller retry pulses. A different request or successful non-STALL
request service rearms reporting. Gaps between retry pulses no longer clear the
limit. A suppressed event does not mean the stall cleared; this is a bounded
sample, not a stall-duration/retry counter. No new forwarding, victim selection,
allocation, writeback or UL behavior is introduced.

[`DcsTraceTb.sv`](../../hw/test/rtl/dcs_pressure/DcsTraceTb.sv) instantiates the
actual DCU, including decoder, ROM, controller, transaction managers and directory,
with the same test XPM RAM primitive model as the directory regression. It checks:

- Sixteen absent-target LC/LCI operations fill a set entirely with retained locks.
- Additional LC and LCI stall, with the correct decoded request, high address bits,
  nonfatal allocation-full marker and exactly sixteen reports across 66 retries.
- UL frees one entry and permits the waiting LCI to complete.
- Sixteen CPU R12/RDDA fills reach the same full-set marker on an absent LCI.

The shared slice address-selection helper is exercised using the actual DCU
outputs, including a previous response to a different address. The full 32-DCU
trace mux, DMA path and board capture are not instantiated by this test. The
existing Nix `dcs-pressure` target now emits `trace-summary.json` and
`trace-events.log` in addition to its previous diagnostics.

For a later matched-board capture, filter DC events for `ERR_RTG_ALLOC_FULL` and
LC/LCI, then correlate prior LC/LCI acceptance/LCIA and later UL or CPU-victim
traffic using the same PA/set/DCU. This event does not encode occupancy composition;
classifying a full set as CPU-resident, locked or transient requires sufficient
history or additional instrumentation. Missing events, bounded suppression and
capture loss prevent concluding that no release existed from silence alone.
Use the normal F_MAC1 freeze/capture and matched trace-map procedure. No board
capture or timing-validation result is claimed for this change.

## Follow-up: port the existing non-locking ROM

Maintainer direction now reopens non-locking investigation, starting from its
existing generator and ROM. The [evolution and trial-port report](nonlocking-rom-port.md)
records exact historical changes, reproducible candidates, the RA3/simplification
split, and remaining controller and wire-protocol obligations. Earlier decisions
to keep auto-lock describe the production configuration, not a prohibition on
this investigation. No production variant switch has been made.
