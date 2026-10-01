# Directory pressure: replacement initiation and delivery

Source audit, bounded model checks and targeted RTL simulation, 2026-10-01.
**An absent local invalidation on a full directory set is an unresolved progress
case in the selected design.** The corrected model produces a counterexample;
the actual controller/protocol table reproduces the allocation dependency.
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

[`DirectoryAdmission.tla`](../../model/steady/DirectoryAdmission.tla) checks this
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
[`RetainedCache`](../../model/steady/RetainedCache.tla) now exposes
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
nix develop -c python3 tools/progress/directory_witness.py model/steady/selected-8k.json
```

Both Nix targets completed successfully: eight model outcomes (three conditional
positives, three deliberately broken contracts, and two genuine design progress
counterexamples) and six RTL checks. The corrected retained-cache graph has 296
distinct states, satisfies its resource-accounting invariant, and fails Progress.
The 12 Python capacity/accounting tests and both historical nonallocating-cache
positives also passed. Full combined scheduling checks were not rerun.

TLC (`tlaplus18`) and Verilator are pinned by Nix. The
[evidence manifest](../../model/steady/directory-pressure-evidence.json) distinguishes
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

The alternative generated `dc_stmc_auto_no_lock` protocol is a concrete candidate,
not yet selected for production. The extended controller test now also runs this
table: an absent LCI completes with the full flag asserted, returns to `s1__1`,
and retains no directory charge. The same arbitration/data-gate tests still pass;
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
`nonlocking-summary.json` (four nonlocking checks), and `lock-tables.json`.
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

[`AxiReloadGate.tla`](../../model/steady/AxiReloadGate.tla) checks the narrow
replacement contract: one held door read followed by a reload of either of two
retired lines (control/overflow), arbitrary invalidation and acknowledgment order,
and publication only after both acknowledgments. There are no local directory
locks. Serial read service prevents stale-generation refills; eligible release
service permits completion. Weak fairness applies only to enabled invalidation,
acknowledgment, publication and serial read service. Deliberately allowing a
reload to bypass the held read violates `NoStaleRefill`; making LCIA service wait
for the read queue to empty violates `Completes`. Both negative outcomes are
expected diagnostic failures. All three checks completed with pinned TLC (24 states for the positive case).
The [evidence manifest](../../model/steady/nonlocking-evidence.json) records
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
