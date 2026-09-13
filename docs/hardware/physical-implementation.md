# Physical implementation: checkpoint-first timing closure

Use this for routed timing failures; use the [hardware routine](../development/hardware-test.md)
for reset/program/load/RPC tests. A successful CI job or RPC run does not establish
setup/hold closure. The [checkpoint findings](physical-findings.md) distinguish
measured paths from possible explanations of board failures.

## Small-context analysis loop

1. Retain the exact routed DCP, hardware commit and submodule commits, generated
   RTL/XDC, static-shell release, Vivado version and final reports. Compare the
   same implementation stage: router estimates and post-route physopt STA differ.
   `tools/enzian/ci_artifacts.py JOB --token-file ../.gitlab_token --download NEW_DIR --checkpoint`
   fetches the DCP with hardware artifacts using read-only requests; do not poll
   CI unless requested. Keep the token local, not on the Vivado host.
2. Run the read-only helper once per checkpoint on ba2. It opens the DCP without
   rerouting or changing constraints. Use a new output directory:

   ```sh
   rsync -rlt tools/physical/ enzian-ba2:/tmp/lauberhorn-physical-tools/
   ssh enzian-ba2 '/opt/Xilinx/2025.1/Vivado/bin/vivado -mode batch -nojournal -nolog \
     -source /tmp/lauberhorn-physical-tools/checkpoint.tcl \
     -tclargs /tmp/lauberhorn-physical/2812450/routed.dcp /tmp/sta-UNIQUE_RUN \
     > /tmp/sta-UNIQUE_RUN.log 2>&1'
   rsync -rlt enzian-ba2:/tmp/sta-UNIQUE_RUN/ out/physical/sta-UNIQUE_RUN/
   python3 tools/physical/summarize.py out/physical/sta-UNIQUE_RUN
   ```

   Check for `CHECKPOINT_REPORTS_READY` in the log; an incomplete directory is
   not a completed analysis. Vivado can take minutes just to restore this DCP.
   Analysis uses 2025.1 (checkpoint producer); JTAG programming uses 2023.2 to
   match the hardware server. No reservation or board reset is needed for STA.
3. Read the bounded digest first. `summary.json` retains exact representative
   pins and per-clock representatives. Add `--baseline OLD/summary.json` for
   metric deltas; `--limit 3` bounds console output further. `paths.tsv` retains 200 worst setup and 200 worst hold paths, one per
   endpoint. Family counts are **samples**, never total failing endpoints/TNS.
   Global worst paths can hide other clocks or families.
   If CI stops the report hook early, the summarizer can still digest an existing
   `timing.rpt` without `paths.tsv`, explicitly marked PARTIAL. Do not interpret
   missing CDC/path samples as zero violations. Use a duration string such as
   `RUNNER_AFTER_SCRIPT_TIMEOUT: "20m"`; this runner rejects bare `"1200"`.
4. Read only the relevant path in `max-detail.rpt` or `min-detail.rpt` and the
   associated rows of `analysis.rpt`. Follow up with a bounded endpoint selection:
   append `200 'i_app/NicEngine_inst/*/D'` to the Tcl arguments, quoting the glob
   so the remote shell does not expand it. Narrow further using exact pins from
   the TSV. For clock-specific analysis, use `get_timing_paths -to [get_clocks ...]`
   in the same Vivado session. Avoid printing full timing/CDC reports into context.
5. Map the exact instance/register back to matching-commit generated RTL, then
   its Spinal plugin, SV/VHDL module and constraint selector. Search `hw`,
   `vivado/eci/rtl` first, then the named submodule. Do not assume current source
   describes an old DCP. Preserve a single representative path per family.
6. Change one cause, elaborate/simulate it, and compare **all** clocks, setup,
   hold, pulse width, endpoint counts, utilization and route status at the same
   stage. Keep the new checkpoint even if WNS worsens: it can expose another
   bottleneck. Finally run repeated verified-reset hardware trials with matching SW.

`timing.rpt` includes unconstrained-path checks; `route.rpt` checks routing;
`clocks.rpt`, `exceptions.rpt`, `bus-skew.rpt`, `cdc.rpt` retain constraint/CDC evidence;
`utilization.rpt` bounds hierarchy resource inspection. A zero unconstrained count
is not proof that exceptions are correct. Inspect exceptions and CDC crossings
before trusting an apparently clean slack number.

## Choose a source-level intervention

| Measured path | First experiment | Preserve/check |
| --- | --- | --- |
| Many LUT levels, modest net delay | Split decode/priority/mux/arithmetic across a handshake boundary; parallelize/bank lookup where possible | Metadata/data alignment, arbitration policy, response identity |
| Few LUTs, long nets or SLR crossings | Place communicating registers closer; add an elastic boundary near the crossing | Throughput and backpressure, partition pins and fixed shell placement |
| High fanout enable/ready/reset | Local replication, register control distribution, break combinational ready chains | Reset synchronization/release, no dropped/duplicated transfer |
| BRAM-to-wide mux | RAM output register, bank/localize consumers, register mux output | Added read latency and address/tag tracking |
| CDC or ignored/unconstrained endpoint | Validate clock relationships and CDC structure before optimization | FIFO/synchronizer semantics; do not replace CDC with ordinary registers |
| Hold failure after setup fix | Re-run minimum-delay STA and supported hold repair | Do not trade away setup or introduce RTL delay chains |

In Spinal, choose the pipeline direction deliberately: a forward/data stage and
backward/ready stage solve different paths. Inspect the installed Stream helper
implementation before selecting `m2sPipe`, `s2mPipe` or a full elastic stage.
Pipeline payload, valid, last/keep, descriptor IDs and ownership information as
one transaction; exercise sustained traffic, random stalls and reset. A bare
`RegNext(payload)` is not a ready/valid pipeline. Refer to [validation](../development/validation.md)
for ECI, bypass, replay and nested RPC suites.

In SV, `rtl/dcs_cdc.sv::cross_chan` already chooses pipeline-before-CDC versus
CDC-before-pipeline with `FIRST_CDC`. That also chooses the clock domain and
physical side of its registers. Coordinate changes with the `slr_auto_src/dest`
selectors in `xdc/floorplan.xdc`; check that each intended selector matches
cells in the implemented netlist. Pipeline all independent AXI channels with a
protocol-aware slice; maintain IDs, burst boundaries and outstanding responses.

The ECI gateway/transport is VHDL, not generated Spinal RTL. Its
`bus_buffer(FULL=false)` has a combinational forward bypass;
`FULL=true` stores forward data but still has an output buffer-select mux.
Changing this generic alters latency/backpressure and credit buffering. Read
`tlk_credits.vhd` and test credit exhaustion/recovery, not only one successful packet.

## Floorplanning and constraints

`vivado/eci/xdc/floorplan.xdc` assigns decoders/encoders to SLR0, host-interface
logic to a region spanning CLOCKREGION Y5–Y9, DCS halves to separated regions,
and selected crossing registers to source/destination regions. The name
`eci_gateway_pblock` does not imply the entire gateway is assigned: inspect the
actual `add_cells_to_pblock` selectors. Check utilization, actual LOCs, pblock
membership and fixed cells before tightening regions. Overconstraining a crowded
region can turn a local improvement into routing congestion elsewhere.
Check wildcard matches when adding ports: `i_app/*x_rst_sync` also captures
`trace_dump_tx_rst_sync` and assigns it to SLR0, despite trace TX consumers near
SLR2. The [four-run comparison](physical-experiments.md#completed-implementation-comparison)
measures the resulting synchronous reset routing bottleneck.

For routed experiments, first copy the DCP and change a small placement hypothesis;
never overwrite the baseline or the static-shell release. A path ending in fixed
static transport may require an application boundary stage or a rebuilt shell;
application-only optimization cannot freely move all its cells. Avoid broad
DONT_TOUCH/KEEP additions that obstruct replication and retiming.

A synchronous 3.103 ns ECI path needs a real timing fix. Do not false-path it or
add multicycle exceptions solely because software accesses it infrequently.
Multicycle timing requires a proved launch/capture enable protocol and correct
hold constraints. Asynchronous paths require proper CDC structures and applicable
CDC constraints, not broad exceptions to make the summary green.

The current flow in `static-shell/impl_app_opt.tcl` runs several physical
optimization directives before routing and one after routing. Repeating more
strategies blindly is expensive. First retain per-stage reports and identify
whether the same family dominates placement and routing. CI currently can emit a
bitstream with failing timing; an eventual release gate should require completed
routing and clean setup/hold/pulse-width checks, while retaining failed artifacts
for diagnosis. Exploratory builds should report their failure explicitly.

References: AMD [timing-path objects](https://docs.amd.com/r/2024.1-English/ug835-vivado-tcl-commands/get_timing_paths)
and [design analysis](https://docs.amd.com/r/2024.1-English/ug835-vivado-tcl-commands/report_design_analysis)
describe bounded path queries and physical path characteristics. Use documentation
matching the actual Vivado release when extending commands.
