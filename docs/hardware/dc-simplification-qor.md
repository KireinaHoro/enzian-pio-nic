# DC simplification: routed checkpoint comparison

Read-only comparison on October 2, 2026, of existing post-route checkpoints:

| Build | Revision | Hardware job | Selected DC |
| --- | --- | --- | --- |
| Last board-working F_MAC1 baseline | `5cda9017fdae1177c002ad0ba51ec3176019c917` | [2852730](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2852730) | Auto-lock, DC `7b5cc87` |
| Simplified endpoint, before RX write sink | `2699f580a6c14fb00fa8cff3857dc5b83f890a89` | [2861585](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2861585) | LCI-only, DC `40530da`, UL removed |

Both reports are complete, Vivado 2025.1, `Physopt postRoute`, on the same
xcvu9p device. No synthesis, placement, routing, constraints or timing optimization
was changed for this analysis. Pipeline 515802's RX write-sink build is not part
of this comparison; its scheduled collection remains separate.

## Area

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
to the state table alone. The [82% state / 87% transition reduction](lauberhorn-rom.md#size)
is a structural count, not an equivalent percentage reduction in silicon area.

### Where the LUT reduction is reported

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

## Whole-design timing

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

## DCS-local timing from both routed checkpoints

`tools/physical/dcs-detail.tcl` selects sequential cells below each `i_dcs` and
reports the worst internal path, any path into that set, and any path out of it.
It also collects detailed hierarchy utilization. This avoids interpreting a
bounded global top-200 sample as the DCS worst path.

| DCS setup scope | Auto-lock slack | Simplified slack | Change |
| --- | ---: | ---: | ---: |
| Even slice, internal / into | +0.007 ns | +0.014 ns | +0.007 ns |
| Even slice, out of core (including AXI wrapper) | +0.007 ns | −0.157 ns | −0.164 ns |
| Odd slice, internal / into / out of core | +0.006 ns | −0.198 ns | −0.204 ns |

The old internal paths are request-stage valid → DCU request-pipe data, one
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

## Reproduction and disposition

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
acceptance or timing closure. The separate RX-fix CI/board follow-up remains
scheduled; timing optimization remains paused.


### Subsequent RX-sink build

The separately collected pipeline 515802 (`a51139e`, hardware job 2863307)
reports WNS/TNS −0.005/−0.005 ns, one failing setup endpoint, WHS +0.004 ns and
no hold/pulse-width failures. It also [passed the board regression](../development/hardware-test-results.md#rx-writeback-fix--2026-10-02).
This newer implementation includes RX write service and a new place/route result;
it does not turn the earlier comparison into an isolated ROM timing experiment.
No new targeted DCS-path comparison was run on this checkpoint.
