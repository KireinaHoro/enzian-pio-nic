# Simplifying the DCS ROM for Lauberhorn

## Motivation: eliminate head-of-line blocking

Lauberhorn needs independent workers and bypass traffic to progress even when
another worker stalls. Removing all sources of head-of-line (HoL) blocking
requires addressing both transport dependencies and directory capacity. The old
auto-lock protocol creates directory pressure: an absent local invalidation
allocates an entry and retains it until unlock. At a full set, that operation
can wait without any CPU demand that would generate an eviction in the same set.
An otherwise independent worker can therefore stall behind retained entries.

The selected Lauberhorn-specific ROM removes this lock allocation, restricts
local operations to LCI, and uses exclusive RA3 replies. Coordinated directory
write gating and response ordering are essential parts of the change. This
resolves the old absent-LCI allocation dependency; it does not establish that
all shared queues, writeback paths or CPU/link resources are free of HoL blocking.
The [remaining progress obligations](#remaining-progress-obligations) describe
that boundary. The [evaluation](#evaluation-of-the-lauberhorn-specific-rom)
measures the integrated implementation's area and timing effects.

The topic covers [the selected interface](#source-and-interface),
[directory pressure and ordering](#directory-pressure-and-the-nonlocking-contract),
[THX provenance](#thx-informed-generation), [A22 evidence](#a22-capture-evidence),
[build and verification](#build-integration-and-selected-rom-checks), and
[area/timing evaluation](#evaluation-of-the-lauberhorn-specific-rom).

## Source and interface

The selected ROM source in the DC submodule is
[`dc_stmc_lauberhorn/eci_stmc_auto.pl`](../../vivado/eci/directory-controller-slice/gen_dc_protocol/dc_stmc_lauberhorn/eci_stmc_auto.pl).
It starts from the existing non-locking Perl generator, imports Jasmin's corrected
remote equations (including A22 races), changes R12 replies to RA3, and restricts
local operations to LCI. It does not synthesize the ROM directly from THX JSON;
see [THX-informed generation](#thx-informed-generation).

There is no LC/F32, LR/LW, autonomous ICI, or UL. The application interfaces have no UL ports, router lookup or mux/demux;
LCIA completes the local invalidation. The selected decoder rejects LC and UL.
A22/HAKS remains accepted. Clean HAKD is classified as A31 when the retained
forward slot identifies an F31 for the same address; otherwise it remains A21.
Dirty HAKD uses A31d and completion waits for write-data acknowledgment.

The directory write enable requires a lookup hit, or a valid next
state and a free way. Thus an absent LCI cannot overwrite an arbitrary full-set
way, while hit invalidations still remove their entry. Removing locks does not
remove software/AXI ordering obligations: withheld reload data must enforce the
2F2F transfer boundary, including previously accepted requests and speculation.

## Size

| Machine | States | Permitted transitions | Stall entries | Forbidden entries | Input/state entries |
| --- | ---: | ---: | ---: | ---: | ---: |
| Previously selected auto-lock | 78 | 261 | 692 | 997 | 1950 |
| Selected LCI-only non-locking | 14 | 35 | 50 | 167 | 252 |

Permitted includes valid no-action transitions. These are table entries, not
synthesis area or timing measurements. Earlier audits counted the per-state
default branch too: 2028 entries for the old ROM and 266 for the selected ROM under that convention. The table above
counts explicit input/state pairs only. The converter also injected an unused
F32 enum, which the selected build removes. States fall by 82%, permitted transitions
by 87%, and input/state entries by 87%. This is specialization to Lauberhorn's
protocol, not equivalence for arbitrary memory-backed DC clients.

## Directory pressure and the nonlocking contract

### CPU misses and local invalidations differ

The directory has 8192 sets, 16 ways and 128-byte lines. Its intended CPU-miss
admission argument assumes matching CPU/directory set mappings, a CPU way
reserved before emitting a miss, directory charges backed by CPU allocations or
outstanding releases, and CPU capacity no greater than directory capacity.
Under those assumptions, a CPU miss at a full set implies an old entry has a
release obligation. It does not prove eventual delivery of that release.
See [DirectoryAdmission](../../model/tla/steady/DirectoryAdmission.tla) and the
[RTL geometry](../../vivado/eci/directory-controller-slice/dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv).

An FPGA invalidation does not reserve a CPU way. In the old auto-lock ROM, an
absent LCI allocates `s1pUL__1`, acknowledges LCIA and retains the entry until UL.
A full-set miss stalls without selecting a victim. A worker accessing one RX
parity can need an invalidation in the other parity's set; demand in the first
set need not evict anything in the second. Fair delivery cannot help if no
release was generated. Local locks can also occupy all ways, so forced CPU
eviction alone is not a general allocation guarantee.

The [address witness](../../tools/progress/directory_witness.py) finds sixteen
old-domain RX parity-1 lines for physical worker 0 in RTG set 517, using domain
codes `0, 210, 292, 502, 512, 722, 804, 1014, 1024, 1234, 1316, 1526, 1536, 1746, 1828, 2038`.
Domain 2048's absent target `0x40200380` is also in set 517; its doorbell
`0x40200300` is in set 516. This is an address-capacity witness, not a captured
runtime history. First RX does not automatically invalidate opposite parity:
the engine invalidates previously delivered parity on a subsequent read;
overflow invalidation can also target bytes software never read.

### Safe directory writes

Simply swapping the old auto-lock table for its nonlocking counterpart is unsafe.
The old controller could acknowledge a nonallocating absent LCI and write an
invalid next state into an arbitrary way returned for a full miss, destroying an
unrelated entry. The selected [DCU write gate](../../vivado/eci/directory-controller-slice/dcs/dcu/dcu/rtl/dcu.sv)
requires a lookup hit, or a valid next state and a free way. Hit invalidation
removes its matched entry; miss-to-invalid writes nothing; miss-to-valid requires
capacity. Lookup context must survive output backpressure.

The [historical unsafe-write diagnostic](../../model/evidence/steady/nonlocking-write-hazard-evidence.json)
records the rejected wholesale-swap behavior. Its expected diagnostic PASS means
that unsafe behavior was reproduced, not that it is the selected implementation.

### Response ordering replaces post-invalidation locks

The old lock protected the interval between coherence completion and UL, not the
whole packet-processing operation: the NIC enqueued UL after each LCIA. The
selected protocol instead depends on response ordering. RX and TX serialize
reads behind an AXI queue; a held opposite-parity control read prevents later
reloads from completing, and publication waits for invalidation completion.
TX also waits for submission/result before releasing its read. Holding AXI R
delays acquisition without a post-LCIA directory lock.

[AxiReloadGate](../../model/tla/composition/AxiReloadGate.tla) checks a held door
read followed by control/overflow reloads, arbitrary invalidation/ack ordering,
and publication after both acknowledgments. The positive bounded case has 24
states; bypassing the held read violates `NoStaleRefill`, and requiring an empty
read queue before LCIA service violates `Completes`. See the
[evidence](../../model/evidence/steady/nonlocking-evidence.json).

That model starts with the door already at the active AXI head. Earlier requests,
in-flight responses, speculative accesses, permission changes without a new AXI
read and stop/kill paths require the broader [AXI response contract](../formal/axi-response-contract.md)
and [layered coherence checks](../formal/model-structure.md#layers-and-boundaries). Software serialization
alone does not exclude speculative coherent requests.

### Remaining progress obligations

Separate DCU input classes and stall-rotating arbitration allow release traffic
to bypass a stalled request class. They do not provide independent end-to-end
queues for every worker. Slice ingress is shared per class; the write-data gate
has one busy slot per DCU but cannot skip a head targeting a busy slot. A controller
can also wait for outgoing-header readiness. Establish finite service of blocked
heads under actual writeback, VC and credit contracts, or an independent escape
path. The single-line THX FSM omits write buffers and LFBs and cannot prove this.

No autonomous ICI victim-selection mechanism is supplied by the selected ROM.
General LC/F32 behavior is outside its interface. Removing local locks neither
proves arbitrary CPU-request admission nor replaces whole-system progress and
isolation arguments. See [coherence progress](queue-pair-design.md#motivation-avoid-head-of-line-blocking).

## Build integration and selected-ROM checks

```sh
nix build .#dcsRom --out-link out/progress/lauberhorn-rom
nix build .#checks.x86_64-linux.dcs-rom-thx
nix build .#checks.x86_64-linux.dcs-lauberhorn
nix build .#checks.x86_64-linux.dcs-pressure
nix build .#eciVivadoInputs --out-link out/progress/lauberhorn-vivado-inputs
nix develop -c mill --no-daemon gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci
```

[Nix generation](../../nix/hardware/dcs-rom.nix) pins Perl/autovivification and the
existing converter/templates, fixes the generated timestamp, and exports CSV,
SV, source hashes, statistics and trace enums. No generated CSV/SV is tracked in
the generator source directory. The real-DCU simulation consumes this derivation;
Vivado receives it through the checked portable input bundle and `--dcs_rom`.
The project enables `DCS_LAUBERHORN`. Trace maps use the generated enum values.
The Scala NIC simulator is LCI-only, with no lock/UL state, and selects exclusive read replies;
it remains an abstraction of the DCS RTL.

The [THX check](../../tools/progress/check_lauberhorn_rom.py) composes the generated
CSV with the unchanged extracted remote FSM, restricting only FPGA spontaneous
operations to EW/LCI. It checks the two documented stable-request diagnostics
(IE_d and IS_d in remote E), and rejects other diagnostics. It also checks no F32
output and retains A22 table support. This bounded legacy checker uses set-valued
messages and incomplete deadlock detection: passing is compatibility evidence,
not a single-line correctness or shared-resource liveness proof.

The [real DCU bench](../../hw/test/rtl/dcs_pressure/DcsLauberhornTb.sv) checks:

1. 32 absent LCIs complete without UL or directory writes.
2. R12/RDDA fills all 16 ways of one set with exclusive CPU lines.
3. An absent LCI completes against that full set under 40 cycles of output
   backpressure without modifying the directory.
4. Every resident tag subsequently invalidates through F31 and clean HAKD,
   without UL; this also checks preservation of the full set's contents.
5. Dirty HAKD waits through delayed WDDA before LCIA.
6. The selected decoder rejects LC and UL.

It uses actual DCU/controller/TSU/tag RAM and a simulation XPM primitive. It does
not cover whole-slice queues, all response races, hardware arbitration fairness,
or THX replacement. The historical auto-lock pressure suite remains a regression
and counterexample diagnostic for the old configuration.

The [evidence manifest](../../model/evidence/steady/lauberhorn-rom-evidence.json) records
generated-artifact hashes and checks run.

UL is absent from application VHDL signals and ILA probes; the generic DCS block's
legacy local-response pins are tied inactive at its boundary. Completion trace
names are `CtrlInvalidated` and `DataLciaDone`; analysis accepts historical names
when reading old maps. The Nix bundle checks NIC/trace-DMA ports against VHDL and
ILA declarations against connections. Use [validation](../development/validation.md)
for local checks and matched-board acceptance. The [RX writeback contract](eci-implementation.md#rx-writeback-and-permission-changes)
explains why exclusive replies require write service; preserved board results
are in the [hardware evidence](../../agents/hardware-state.md#rx-writeback-regression-evidence).


## Evaluation of the Lauberhorn-specific ROM

Read-only comparison on October 2, 2026, of existing post-route checkpoints:

| Build | Revision | Hardware job | Selected DC |
| --- | --- | --- | --- |
| Last board-working F_MAC1 baseline | `5cda9017fdae1177c002ad0ba51ec3176019c917` | [2852730](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2852730) | Auto-lock, DC `7b5cc87` |
| Simplified endpoint, before RX write sink | `2699f580a6c14fb00fa8cff3857dc5b83f890a89` | [2861585](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2861585) | LCI-only, DC `40530da`, UL removed |

Both reports are complete, Vivado 2025.1, `Physopt postRoute`, on the same
xcvu9p device. No synthesis, placement, routing, constraints or timing optimization
was changed for this analysis. The RX write-sink build is not part of this comparison.

### Area

LUT counts include logic LUTs, LUTRAM and SRLs as reported by Vivado. The DCS
core row sums `i_app/dcs_{even,odd}/i_dcs`; the wrapper row additionally includes
CDC/AXI/channel queues. Hierarchy rows overlap and must not be added together.

| Scope/resource | Auto-lock | Simplified | Change |
| --- | ---: | ---: | ---: |
| Both DCS cores, LUTs | 103,134 | 87,016 | −16,118 (−15.63%) |
| Both DCS wrappers, LUTs | 122,230 | 106,994 | −15,236 (−12.47%) |
| Whole application, LUTs | 360,767 | 342,951 | −17,816 (−4.94%) |
| Whole design, LUTs | 407,916 | 390,100 | −17,816 (−4.37%) |
| Both DCS cores, FFs | 120,548 | 124,534 | +3,986 (+3.31%) |
| Both DCS cores, RAMB36 | 128 | 128 | Unchanged |
| Whole application, FFs | 490,571 | 490,919 | +348 |
| Whole application, RAMB36 / RAMB18 | 725 / 37 | 701 / 36 | −24 / −1 |

The whole-application BRAM reduction is outside the DCS cores: trace DMA loses
16 RAMB36 and the ILA hierarchy loses eight RAMB36 plus one RAMB18. This includes
removing UL trace probes, not shrinking the directory's BRAM footprint.

These are implementation snapshots, not an isolated ROM-only A/B experiment.
The changes include the smaller ROM, supporting controller/decoder changes,
allocation trace instrumentation, and UL removal from NIC/control/routing and
trace paths. Placement and physical optimization also change other hierarchy
counts. Do not attribute the entire application delta, or increased DCS FFs,
to the state table alone. The [82% state / 87% transition reduction](#size)
is a structural count, not an equivalent percentage reduction in silicon area.

#### Where the LUT reduction is reported

Across all 64 DCUs, `ip_evts_arb1` falls from **24,350 to 9,344 LUTs**
(−15,006, −61.63%). The contained `core_dcu_inst` hierarchies instead rise from
**32,348 to 34,095 LUTs** (+1,747, +5.40%) and from 22,784 to 26,496 FFs.
These are different, non-overlapping subtrees inside each DCU top.
The new build adds retained request identity for allocation tracing and corrected
forward-response decoding as well as simplifying the protocol.

The old `cc_rom_inst` subtrees are separately reported (4,927 LUTs total); the
new report no longer retains separate area rows for them, although ROM-named
registers remain in timing paths. Optimization has redistributed logic across
hierarchy. Absence of a row does **not** mean a zero-area ROM. This comparison
supports the area saving of the integrated change and identifies its reported
location; it cannot isolate a table-only LUT reduction or attribute all arbiter
savings to one source edit.

### Whole-design timing

| Metric | Auto-lock | Simplified |
| --- | ---: | ---: |
| WNS | −0.062 ns | −0.288 ns |
| TNS | −4.622 ns | −130.626 ns |
| Failing setup endpoints | 184 | 2,341 |
| WHS | +0.002 ns | +0.001 ns |
| Failing hold / pulse-width endpoints | 0 / 0 | 0 / 0 |

There is **no whole-design critical-path improvement** in this pair: WNS worsens
by 0.226 ns. The old worst path is ECI TX-crossbar → link credits (eight logic
levels, 2.897 ns data path); the new one is NIC scheduler core-PID mapping →
queue RAM address (14 levels, 5.214 ns). These are different clocks/path families,
so their data-path delays are not a like-for-like speed comparison. The new
ECI-clock worst slack is −0.152 ns. Neither build closes setup timing.

### DCS-local timing from both routed checkpoints

`tools/physical/dcs-detail.tcl` selects sequential cells below each `i_dcs` and
reports the worst internal path, any path into that set, and any path out of it.
It also collects detailed hierarchy utilization. This avoids interpreting a
bounded global top-200 sample as the DCS worst path.

| DCS setup scope | Auto-lock slack | Simplified slack | Change |
| --- | ---: | ---: | ---: |
| Even slice, internal / into | +0.007 ns | +0.014 ns | +0.007 ns |
| Even slice, out of core (including AXI wrapper) | +0.007 ns | −0.157 ns | −0.164 ns |
| Odd slice, internal / into / out of core | +0.006 ns | −0.198 ns | −0.204 ns |

The baseline internal paths are request-stage valid → DCU request-pipe data, one
logic level with 97% routing delay (5.212 ns even, 5.306 ns odd).
The new even internal path is read-response ID → data-store write address
(4.816 ns, zero logic levels); its worst outgoing path reaches the AXI wrapper's
message-register enable (5.107 ns, seven levels).

The new odd critical path is:

```
DCUS[2]/ip_evts_arb1/arb_2ip_s1/eci_hdr_reg_reg[61]
  → event/decode/transaction-context/controller logic
  → core_dcu_inst/cc_rom_inst/next_action_reg_reg[2]
```

It has **13 logic levels, 5.055 ns data delay**: 1.243 ns logic and 3.812 ns
routing. It traverses two CARRY8s and the transaction-manager/control cone; this
is not an isolated measurement of lookup delay inside the smaller table.
All these DCS paths use the 5 ns application clock. Slack includes clock skew,
uncertainty and endpoint setup, so a lower raw data delay on a different path
need not improve worst slack. **There is no DCS-wide critical-path saving in
these snapshots either.** A controlled comparison would be needed to separate
protocol, decode/instrumentation, synthesis and placement contributions.

### Reproducing the evaluation

[Compact evidence](../../data/eci/physical-evidence/dc-simplification-20261002.json)
records checkpoint/report hashes, exact pins and hierarchy counts. Raw CI
reports/checkpoints and the new detail reports are retained under
`out/physical/dc-simplification/{2852730,2861585}/`; remote copies are at
`enzian-ba2:/tmp/lh-dc-comparison-20261002/`.

Read the normal `tools/physical/summarize.py` digest first. Then on ba2, with
Vivado 2025.1, run the read-only detail helper against each retained routed DCP:

```sh
vivado -mode batch -nojournal -nolog -source tools/physical/dcs-detail.tcl \
  -tclargs /path/to/routed.dcp /path/to/new-detail-directory
```

No new hardware implementation or board test was launched for this comparison.
The simplified pre-sink image failed the previously documented RX writeback
trial. Neither area savings nor complete checkpoint reports establish functional
acceptance or timing closure.

### Subsequent RX-sink build

The separately collected pipeline 515802 (`a51139e`, hardware job 2863307)
reports WNS/TNS −0.005/−0.005 ns, one failing setup endpoint, WHS +0.004 ns and
no hold/pulse-width failures. It also [passed the board regression](../../agents/hardware-state.md#rx-writeback-regression-evidence).
This newer implementation includes RX write service and a new place/route result;
it does not turn the earlier comparison into an isolated ROM timing experiment.
No new targeted DCS-path comparison was run on this checkpoint.

## THX-informed generation

The selected generator starts from the existing nonlocking source, imports
Jasmin's corrected remote equations, changes R12 replies to RA3 and specializes
local operations to LCI. The historical intermediate candidates below explain
that derivation; they are not alternative selected configurations.

### Generation chain

1. [Extracted remote FSM](../../deps/thx-fsms/legacy/remote_fsm.txt) describes
   single-line observable THX behavior. The JSON version is under
   `specifications/remote_fsm.json`.
2. [check_DirC_csv.py](../../deps/thx-fsms/legacy/check_DirC_csv.py) maps DC symbols
   onto THX symbols, reads a DC CSV, reconstructs its stable-to-stable histories,
   and explores its composition with that extracted remote FSM.
3. [eci_stmc_auto_corrected.pl](../../deps/thx-fsms/legacy/eci_stmc_auto_corrected.pl)
   is Jasmin's corrected version of the existing DC generator. Commit `7f9333b`
   (Jasmin Schult, 2025-10-21) adds both it and the checker; the legacy README
   explicitly describes that relationship.
4. Running the corrected Perl generator produces `ECI_CC.csv`. The existing DCS
   `cc_rom_from_cc_tbl.pl` and templates turn that CSV into the package and SV ROM.

This is **THX-informed correction of the existing equation generator followed by
compatibility checking**, not direct ROM synthesis by the JSON-to-TLA generator.
No checked-in automatic transformation from THX JSON to the corrected Perl
source was found. We can reproduce the artifacts and checks, but not claim to
have recovered every manual reasoning step or the exact historical command log.

### Why A22 is retained

The checker maps `V32 -> ES`, `V32d -> ES_d`, and `A22 -> FAS`. Inspection of all
outputs in the extracted remote JSON finds **zero ES, zero ES_d, and ten FAS
transitions**. Jasmin's [message table](../../deps/thx-fsms/figures/link-msgs.png)
identifies FAS as HAKS. Her corrected generator:

- Removes V32/V32d and their remote equations.
- Retains A22, unlike the simplified DCS ROM on `replyRA3`.
- Adds races from `2_A32d` involving V31 with A22, or V31d/WDDA with A22,
  including subsequent requests. The generator includes a repeated dirty
  equation family; reproducing it unchanged works.

A concrete extracted transition is a CPU in S awaiting its upgrade response:
`S[(LE, SE_d)] + FS_d -> ... + FAS`. Thus A22 cannot simply be declared absent
from the extracted FSM. Whether a restricted application never generates F32
is a separate interface restriction. The unrestricted legacy checker exercises
LC and LR, both of which can generate F32.

After adding RA3, the corrected locking ROM has **47 states/1128 explicit entries**.
Against the evolved DCS RA3+simplified ROM (47/1081), it adds exactly the 47 A22
entries, with **no differences on shared entries**. This gives a precise
reconstruction of what is missing from that simplified artifact.

### Reconstructing the intermediate candidates

[reconstruct_thx_rom.py](../../tools/progress/reconstruct_thx_rom.py) runs the
historical generation audit, then generates Jasmin's original, its RA3 port and
a port onto the **existing non-locking generator**. The latter copies only her
`rmt_evts` and `rs_eqns` blocks into that generator and changes its 17 R12 terminal
equations to RA3; local non-locking semantics remain intact.

```sh
nix develop -c python3 tools/progress/reconstruct_thx_rom.py \
  --output out/progress/thx-rom-reconstruction
```

Pinned Perl/autovivification, Python and Verilator suffice. Generated CSV, ROM,
package, logs and detailed summary stay under ignored `out/`. The
[evidence manifest](../../model/evidence/steady/thx-rom-reconstruction-evidence.json)
records revisions, input hashes, counts and unique diagnostics.

| CSV checked against extracted THX | States / explicit ROM entries | Checker reports |
| --- | --- | --- |
| Original locking | 78 / 2028 | 22 problems |
| Simplified locking, RA2 | 48 / 1104 | 32 problems |
| Simplified locking, RA3 | 47 / 1081 | 38 problems |
| Simplified non-locking, RA3 | 43 / 946 | 35 problems |
| Jasmin corrected, RA2 | 48 / 1152 | 4 documented stable-request warnings only |
| Jasmin corrected, RA3 | 47 / 1128 | Same 4 warnings only |
| Existing non-locking + Jasmin corrections + RA3 | 43 / 989 | Same 4 warnings only |

The four warnings are IS_d or IE_d arriving while the directory is in stable
remote S or E. The script's final note explicitly permits mismatching stable
upgrade/request warnings because an eviction/downgrade can still be in flight.
We retain these diagnostics rather than report zero problems. The simplified
RA3 locking case additionally reports unhandled FAS during LC/LR histories.
All three corrected candidates pass standalone ROM lint. All 989 non-locking
entries match corrected-locking shared entries after erasing the post-LCA/LCIA
lock marker.

The legacy checker exits zero even when it reports problems; the wrapper parses
and checks its output. It uses set-valued messages (collapsing duplicates), a
15-step reconstruction bound and selected spontaneous events. Its
`has_transitions` flag is set before checking that a transition exists, so its
"deadlock" count is not a complete deadlock test. Zero deadlock reports must not
be advertised as liveness proof. It does not model finite shared resources,
concrete wire decoding, payload values, full NIC behavior or modern AXI fencing.
These results reproduce its intended compatibility diagnostic, not discharge all
single-transaction or system progress obligations.

### Wire mapping and specialization

F31 maps to FE_d in the checker and to FLDX on the wire, with HAKD completion.
F32 can also elicit HAKD, so unrestricted LC support would require outstanding
forward context to distinguish A31/A32 and their data-bearing variants. The
selected ROM excludes LC/F32 and retains contextual classification of clean
HAKD; dirty completion waits for WDDA. Abstract-table compatibility alone is
not a proof of concrete decoder behavior.

### Historical port comparisons

| Commit | Change and port implications |
| --- | --- |
| `9c9abae` | Baseline: locking 78 states/2028 explicit entries; non-locking 72/1800. Shared entries agree after erasing the post-LCA/LCIA lock marker. |
| `a818e5d` | Changes 24 remote equations ending `R12, 2pRA2` to `R12, 3pRA3`. An absent R12 now performs RDD, waits in `s1__3pRA3`, and RDDA sends RA3, ending in state 3. Existing R13/RA3 machinery is reused. Also changes one equation's **starting state** from 3 to 2: `2, V32, V21, R12, 3pRA3`. This extra change generates unexpected V32 acceptance and states such as `s1__3pRA3_R12`; its rationale is unrecorded. Do not include it in a pure exclusive-reply port. Historical generated table: 84/2184. |
| `0443f18` | Removes SEND_RA2 handling from controller and outgoing-header generator. This is outside the ROM; only appropriate once the selected table no longer emits RA2. That historical baseline still had RA2 handling. |
| `4bc6183` | Installs a simplified **RA2** generated locking ROM: 48/1104. We reproduced it exactly at the explicit-transition level by removing V32, V32d and A22 remote events and all remote equations mentioning them from the old generator. No replacement state-machine algorithm is needed. |
| `cf9b1cf` | Copies the simplified table/package into `dcs/dcu` and comments out decoder cases for VICC without/with data (V32/V32d) and HAKS (A22). Thus simplification includes a wire-input contract change, not just removal of unreachable enum names. |
| `acbf558` | Merges simplification into `replyRA3`; generated table combines simplification with RA3 (47/1081). It does not merely take the RA2 table from the other parent. |
| `2d77ada` | Updates locking generator/CSV to remove those events/equations. Its regenerated explicit transitions match the merged RA3+simplified ROM. The earlier anomalous V32 equation disappears with the other V32 equations. Most remaining script diff is whitespace; generation algorithm is unchanged. |

The simplification also changes 24 entries on surviving state/request pairs to
`NOT_ALLOWED`. Examples: `s1__3 + V21` previously waited for V32;
`s1pCI__1_A31d + A21` previously waited for V32; several R23 requests previously
stalled. The [machine-readable evidence](../../model/evidence/steady/rom-evolution-evidence.json)
lists every changed shared entry and counts removed entries. Therefore the
simplification is a restriction of accepted histories, not a demonstrated
semantics-preserving minimization. RA3 alone is not evidence for that restriction:
LC/LR can still downgrade a remote line via F32. The maintainer attributes this work to Jasmin. Subsequent reconstruction
confirms V32/V32d absence but finds A22/FAS in her extracted FSM and corrected
generator; see [why A22 is retained](#why-a22-is-retained).

### Trial ports from the existing non-locking source

The [audit utility](../../tools/progress/audit_rom_evolution.py) preserves the
existing non-locking local seeds, actions and generator algorithm. It applies
only the remote-equation edits above, uses its existing templates/CSV-to-ROM
converter, and writes candidates under ignored `out/`. No generated production
file is hand-edited.

| Non-locking candidate | States | Explicit entries | Change from original |
| --- | ---: | ---: | --- |
| Original | 72 | 1800 | Reproduces existing ROM |
| RA3 only | 71 | 1775 | One surviving entry changes (`s1__1,R12`); obsolete 2pRA2 state disappears |
| Simplification only, RA2 | 44 | 968 | 832 entries removed; 24 surviving entries change |
| RA3 + simplification | 43 | 946 | 854 entries removed; 25 surviving entries change |

All 946 candidate entries in the combined port match the evolved locking table's
shared entries after replacing next-state `s1pUL__` with `s1__`. The separate
simplification-only comparison matches 968 entries. These checks establish
mechanical portability of these changes onto the existing non-locking source.
They do not prove equivalence on lock states, legal THX histories, payload values,
resource progress, numeric enum encodings or safe integration.

All eight regenerated historical/candidate ROMs pass standalone Verilator lint.
Reproduction uses pinned Perl + autovivification and Verilator in `nix develop`:

```sh
nix develop -c python3 tools/progress/audit_rom_evolution.py \
  --output out/progress/rom-evolution
```

Local DCS Git history including the named commits is required (a shallow checkout
may need those commits fetched). Each output directory retains generator, CSV,
package, ROM and generation/lint logs. Summary records full revisions, generator
hashes and symbolic differences. Generated date comments are not compared.

## A22 capture evidence

The October 1, 2026 audit found no A22/HAKS in the inspected captures. This does
not establish unreachability: FAS appears in the extracted FSM and its earliest
available revision. The evidence is retained to distinguish observed workload
behavior from the supported protocol.

### Capture scope

[scan_a22_traces.py](../../tools/progress/scan_a22_traces.py) checks:

- Ten retained `data/eci/sys_trace/*/events.tar.gz` archives: **161,565,268 CSV
  event rows**, zero HAKS. These include iperf timeouts, interrupt choking,
  FLDX/invalidation-control variants and RPC/bypass failures. These are existing
  decoded exports; this scan does not regenerate them from raw data or establish
  their historical map provenance/completeness anew.
- **85 parsed DCS/ECI CSVs**, 36,942 rows, under `data/eci/dcs_trace`: zero A22
  request labels or HAKS wire labels. Copies/stages can overlap; these counts
  are not distinct transactions.
- The **322,322,624-byte F_MAC1** capture from revision `5cda901` and the recovered
  **211,086,144-byte F_MAC3** capture from `c5be158`: zero HAKS among either
  presented or accepted incoming ECI response samples, across app/sys stages
  and both slices. These are two frozen histories, not separate counts for the
  three identical F_MAC1 reads.

The full DMA files contain 20,145,164 and 13,192,884 128-bit sample slots. Incoming
response samples are selected using the matching maps' source/channel fields;
MRSP opcode 7 (bits 63:59) identifies HAKS. Interrupt, local-command, request,
forward and data payload sources are not misinterpreted as MRSP headers. Counts
include duplicated observations across app/sys stages and are not transaction
counts. Bubble markers are distinguished from nonzero sample-loss records.

Raw-capture hashes match the existing board evidence:

- F_MAC1: `b7532515fae2e5d4b500c302eb55d4bd75f2a80312130d9e18dc7532cb951c68`
- Recovered F_MAC3: `671ee92e64b5d03fabca4e97fc5efbf6ddb5fa0df1eae4af23a408a488cede5e`

Both maps are byte-identical to the corresponding archived CI hardware maps,
SHA-256 `705a7fc5f1d16499bf849e2583c5249335fd4001155ca011f387977e99dd64fe`.
See [board provenance](../../agents/hardware-state.md#cmac-trace-readout-evidence).
No new board run or remote capture was performed. Raw-only historical captures
without the selected decoded exports were not exhaustively re-decoded; this is
not a claim about every trace ever collected.

### Available THX history

The October 1 audit inspected all six commits reachable from its local refs:

| Revision | Date | Relevant contents |
| --- | --- | --- |
| `b269076` | 2025-06-19 | Initial README only; no FSM or DC generator |
| `982d521` | 2025-06-20 | First legacy and JSON remote FSMs; both contain ten FAS-output transitions |
| `e46f276` | 2025-06-20 | Event descriptions; FAS transitions unchanged |
| `7f9333b` | 2025-10-21 | Jasmin adds checker and corrected DC generator; generator retains A22 (16 occurrences), excludes V32/V32d declarations |
| `a97682d` | 2026-05-04 | TLA generator added; relevant FSM/DC sources unchanged |
| `a137b82` | 2026-05-06 | TLA transition sorting; relevant FSM/DC sources unchanged |

There was **no A22-free corrected generator or FAS-free remote FSM in that
inspected history**. This does not rule out an earlier unpublished experiment.
The DCS simplification committed in May 2025 predates the first checked-in THX
FSM; Git alone does not reconstruct the rationale of that earlier artifact.
Maintainer attribution of that work to Jasmin is retained. Her later checked-in
correction is the reproducible source for retaining A22.

### Reproduce the capture audit

```sh
nix develop -c python3 tools/progress/scan_a22_traces.py \
  --output out/progress/a22-traces
```

The [evidence manifest](../../model/evidence/steady/a22-trace-history-evidence.json) lists
individual paths/counts, raw hashes, response-opcode counts, source-level counts
and historical revisions. Local ignored capture archives are required to repeat
the full scan; unavailable DMA files are explicitly reported as skipped.
The result complements the [THX reconstruction](#thx-informed-generation):
we have extracted-FSM and compatibility-check evidence for A22, but no observed
A22 in this inspected Lauberhorn capture corpus.

## Historical auto-lock counterexamples and regression coverage

The following checks characterize the previous auto-lock protocol and explain
the selected change. Expected failures are counterexamples to that protocol,
not failures of the selected LCI-only ROM.

[`DcsDirectoryTb.sv`](../../hw/test/rtl/dcs_pressure/DcsDirectoryTb.sv) instantiates the actual `dcu_controller`, historical auto-lock table, `dcu_tsu`,
`tag_state_ram` and `ram_tdp`, at 128 sets/DCU, 16 ways and registered
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
is real in the auto-lock RTL, not an artifact of forcing an impossible full-set flag.
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
regressions. Reproduce via the `dcs-pressure` Nix target, which emits
`directory-summary.json` and `directory-events.log`, or:

```sh
nix develop -c python3 tools/progress/check_dcs_pressure.py --directory \
  --output out/progress/dcs-directory-reachable
```

### Bounded model evidence

`RetainedCache` separates directory entries, CPU residency, eviction delivery and
retained locks from `DcsResources` transport queues. `AllocateOnInvalidate=TRUE`
models auto-lock admission; FALSE models a nonallocating endpoint but does not
by itself prove correspondence to the selected RTL. Fairness serves eligible
operations and queued releases; it does not invent CPU evictions.

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
composition reaches its pressure state from empty. Neither trace is a
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

The [auto-lock evidence manifest](../../model/evidence/steady/auto-lock-model-evidence.json)
records all 19 expected outcomes and the two historical cache regressions.
The Nix output retains the generated configurations, TLC logs/counterexamples
under `traces/`, and a detailed summary. The runner reports expected violations
explicitly and labels auto-lock versus historical nonallocating configurations.
An expected-violation success means the failure was reproduced. These historical results establish the auto-lock obstruction within their
finite bounds; they do not establish progress for the selected replacement.

### Upstream auto-lock bench coverage

These are source-inspected historical upstream tests, not newly executed results.

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
needed release. Its checks explain why stalling is the intentional auto-lock
behavior: it prevents overwriting an unrelated directory entry.

The directed directory test adds bounded persistence with no supplied victim,
full-hit comparison, retained lock occupancy and UL-triggered recovery; the TLA+
checks expose indefinite waiting under the specified environment. The upstream
bench already covers the central full-miss LCI/stall/retry scenario. Neither the
upstream coverage nor these tests establish a working ICI eviction mechanism.
Any such implementation needs new producer/victim-selection tests, clean and
dirty ICI completion tests, locked/transient-victim rejection, preservation of
unrelated entries and shared-path progress while the original LCI waits.

### Allocation-full tracing

The allocation diagnostic uses `ERR_RTG_ALLOC_FULL` (numeric 7, with `error=0`)
when an allocating directory miss stalls for lack of a free way. The event carries
that request's decoded opcode and full aligned aliased address (decoded back to
PA using the matching trace tooling). Because the controller
has not enabled the ROM on this path, `state=s1__1` and `action=STALL` are explicitly
constructed diagnostic values, not a claimed ROM transition or directory write.
The slice address selector uses the request/context header for stalled requests and RDDA/WDDA
as well as accepted requests. Use the matching trace-map enum values.

Repeated request-stall reports are limited to sixteen for the same header/VC
across controller retry pulses. A different request or successful non-STALL
request service rearms reporting. Gaps between retry pulses do not clear the
limit. A suppressed event does not mean the stall cleared; this is a bounded
sample, not a stall-duration/retry counter. The marker reports stalls; it does not initiate forwarding, eviction or release.

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
existing Nix `dcs-pressure` target emits `trace-summary.json` and
`trace-events.log` in addition to its previous diagnostics.

For an auto-lock capture, filter DC events for `ERR_RTG_ALLOC_FULL` and
LC/LCI, then correlate prior LC/LCI acceptance/LCIA and later UL or CPU-victim
traffic using the same PA/set/DCU. This event does not encode occupancy composition;
classifying a full set as CPU-resident, locked or transient requires sufficient
history or additional instrumentation. Missing events, bounded suppression and
capture loss prevent concluding that no release existed from silence alone.
Use the normal F_MAC1 freeze/capture and matched trace-map procedure. The auto-lock test scenarios above do not describe selected-ROM local allocation.

## Diagnostic reproduction

The historical controller/table checks use controlled surroundings; expected
faults count as successful diagnostics only when the intended failure is found.
See [the initial diagnostic manifest](../../model/evidence/steady/directory-pressure-evidence.json)
and [allocation tracing evidence](../../model/evidence/steady/directory-trace-evidence.json).

```sh
nix build .#checks.x86_64-linux.scheduling-cache-contracts --out-link out/scheduling/cache-contracts-nix
nix build .#checks.x86_64-linux.dcs-pressure --out-link out/progress/dcs-pressure-nix
nix develop -c python3 tools/scheduling/check_models.py --cache-contracts-only --output out/scheduling/cache-contracts --timeout 300 --workers 4 --heap-mib 2048
nix develop -c python3 tools/progress/check_dcs_pressure.py --output out/progress/dcs-pressure
nix develop -c python3 tools/progress/directory_witness.py model/data/selected-8k.json
```

```sh
nix develop -c python3 tools/scheduling/check_models.py \
  --case axi-reload-gate --case bad-axi-reload-bypass --case bad-axi-release-gate \
  --output out/scheduling/axi-reload-gate --workers 2 --heap-mib 1024
```
