# Physical implementation and timing evaluation

Use this for routed timing failures; use the [hardware routine](../development/validation.md#board-validation)
for reset/program/load/RPC tests. A successful CI job or RPC run does not establish
setup/hold closure. The [checkpoint findings below](physical-implementation.md#comparable-final-timing) distinguish
measured paths from possible explanations of board failures.

## Scope and interpretation

The [continuation context](../../agents/physical-implementation.md) records the
active application-clock work and retained experiment refs. The selected September 17
candidate reached −0.007 ns with 17 failing setup endpoints. That is a result
for revision `1c72ea0`, not timing closure or a measurement of current master.
The comparisons below use their named source/tool/checkpoint inputs. Later
functional board results do not inherit those timing numbers.

The recorded campaign was application-only: the static-shell release and fixed
transport were unchanged. Changing the shell requires a separate scope decision.

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

The application RTL separates deadline selection from descriptor capture, RX
payload alignment from replay masking and response delivery, and TX arbitration
from descriptor validation. Each FSM retains ownership until the corresponding
completion handshake. RPC routing captures metadata and the local/remote choice
before forwarding; remote payload waits for metadata acceptance. IPv4 TX header
construction is registered before checksum reduction, with the acceptance pulse
delayed by the same checksum latency. Worker fault routing uses the immutable
scheduler slot captured with the lifetime at installation, preserving immediate
sticky-fault fencing without repeated lifetime comparisons.
Native UDP relies on shared IPv4 validation and does not calculate UDP checksums.
Packet-memory AXI slices isolate arbitration
from RAM address/write-enable decode. Deadline command responses separate slot
selection from generation increment and forward the pending result to CSR reads.

RX replay masks update locally per word. A validity bit invalidates each word
on packet retirement/descheduling, and the first subsequent write replaces its
old byte mask. This keeps stale replay bytes inaccessible without clearing the
whole byte-mask bank. Payload bounds use one remaining-length calculation and
register the byte-valid mask before replay merging.

Placement retains the coarse SLR/host-interface regions and established ECI
crossing constraints. Worker, scheduler and crossbar logic are
not divided into additional disjoint hard regions. Primitive capacity alone
does not establish routing capacity for their wide interconnect.

`vivado/eci/xdc/floorplan.xdc` assigns decoders/encoders to SLR0, host-interface
logic to a region spanning CLOCKREGION Y5–Y9, packet-buffer alignment/DMA/RAM
to SLR0, DCS halves to separated regions,
and selected crossing registers to source/destination regions. The name
`eci_gateway_pblock` does not imply the entire gateway is assigned: inspect the
actual `add_cells_to_pblock` selectors. Check utilization, actual LOCs, pblock
membership and fixed cells before tightening regions. Overconstraining a crowded
region can turn a local improvement into routing congestion elsewhere.
Check wildcard matches when adding ports. The old `i_app/*x_rst_sync` selector
also captured `trace_dump_tx_rst_sync` into SLR0. Current constraints use exact
main/trace instances. F_MAC3 trace CMAC, dump processing, FIFOs, resets and
status VIO share SLR2 with trace DMA and DDR; source-side sampling stages remain
near their producers. Packet-memory AXI pipeline boundaries separate its SLR0
placement from SLR1 host logic. These broad assignments add no fine-grained
application pblocks. The application `ila_app` and its manual helper are removed;
CMAC ILAs, other debug cores and the static ECI edge ILA remain.

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

## Evaluation provenance

The following are recorded Vivado 2025.1 `Physopt postRoute` results, analyzed
read-only on enzian-ba2. They are not new runs or board acceptance. Preserve
source revisions, generated RTL, static-shell and tool inputs when reproducing
comparisons. Embedded revision CSRs change synthesis inputs even when datapath
sources are unchanged. A bounded path sample cannot establish family-wide TNS.

The later [Lauberhorn ROM evaluation](lauberhorn-rom.md#evaluation-of-the-lauberhorn-specific-rom)
compares auto-lock and LCI-only directory implementations separately.

## Comparable final timing

| CI job / commit | Final WNS / TNS (ns) | Failing setup endpoints | WHS / THS (ns) |
| --- | --- | --- | --- |
| 2383312 / `4fa10def` | −0.164 / −165.535 | 2,859 | +0.004 / 0 |
| 2811847 / `93f4c1da` | −1.813 / −12585.313 | 41,485 | 0.000 / 0 |
| 2812450 / `aa32b1af` | −1.051 / −2772.992 | 14,633 | 0.000 / 0 |

The previously quoted −1.314 ns for 2812450 and −2.073 ns for 2811847 were
router estimates, not their final post-route physopt results. Passing 301 add calls does not make this design timing
clean. The following paths show why whole-design WNS is not a useful predictor
of whether the first static-shell register read or a particular RPC succeeds.

## Historical bottlenecks and intervention rationale

Source descriptions in this section refer to the named checkpoints. The
interventions explain the comparisons below; they are not outstanding campaign
instructions or descriptions of current RTL.

**Trace CMAC backpressure, 2812450: −1.051 ns.** The source shown as `<hidden>`
is a CMACE4 primitive's `TX_RDYOUT`, not a missing clock or unidentified software
component. It drives `i_trace_dma/traceDumpTxFifo/rd_ptr_reg_reg[4]/CE`.
The path has one LUT, 3.710 ns data delay (2.961 ns routing), and an SLR2→SLR1
crossing. CMAC is at `CMACE4_X0Y8`; the read pointer is at `SLICE_X32Y581`.
This is a long ready path, not a deep arithmetic datapath.

- Source: `hw/src/lauberhorn/LauberhornTraceDma.scala`, `traceDumpTxFifo.m_axis >> traceDumpTxAxis`.
  The wrapper `deps/blocks/blocks/src/jsteward/blocks/axi/AxiStreamAsyncFifo.scala`
  defaults `outputFifoEnable=false`.
- RTL: `deps/blocks/deps/verilog-axis/rtl/axis_async_fifo.v` forwards
  `m_axis_tready_out` to `m_axis_tready_pipe` when the output FIFO is disabled;
  that controls the RAM/read-pointer pipeline.
- Isolated intervention: set `outputFifoEnable = true` **only on
  traceDumpTxFifo**. Its registered half-full signal then controls the RAM reader;
  CMAC ready remains local to the output FIFO. Alternatively insert a full elastic
  AXI-stream slice in `traceDumpTxClock`, at the CMAC boundary. Do not use an
  application-clock slice on the FIFO's read side.
- Floorplan experiment, separately: place that output stage/read-side logic near
  the trace CMAC in SLR2. The measured revision's XDC constrains `i_trace_dma/traceDma`, **not**
  sibling `traceDumpTxFifo`. Check actual occupancy before adding a pblock.
  More RAM output pipeline stages alone do not break the backward ready path.
- Validate stalls, sustained frames, last/keep, reset and trace dump byte contents;
  reroute and check both data and ready paths. A new stage needs measured placement,
  not just a successful elaboration.

**Trace source selection, 2812450: −0.228 ns at 200 MHz.**
`savedPorts_1_valid_reg` reaches `traceDma/frameFifo/.../DINADIN[23]` through
18 logic levels including eight CARRY8s, followed by muxing. Data delay is
4.939 ns, including 4.000 ns routing. The source is in the trace collector,
not the RPC scheduler.

- Source: `deps/blocks/blocks/src/jsteward/blocks/misc/TraceBufferDMA.scala`:
  `nextPort = OHToUInt(OHMasking.first(savedPorts.map(_.valid)))`, then indexed
  event selection and direct `frameFifo.io.push << frameSource`.
- Installed Spinal `Utils.scala::OHMasking.first` implements
  `input & ~(input - 1)`: the report's carry chain is consistent with this wide
  priority-selection cone. `firstV2` provides a hierarchical Boolean alternative.
- Intervention considered: compare `firstV2(Vec(savedPorts.map(_.valid)).asBits)` with the
  existing priority mask while preserving lowest-index priority. This is a
  dependency change in `deps/blocks`, not a generated-Verilog edit.
- Pipeline alternative: register selection and selected event before writing the frame
  FIFO; retain timestamp/source/event/loss metadata together. Keep selected data
  stable during stalls and clear the saved valid bit only when the staged record
  is accepted. A stage only *after* the full priority+mux cone may leave that cone
  critical; split selection from event muxing if needed.
- Run `TraceBufferDMATests`, trace-dump tests and a trace-content comparison.
  Check loss accounting/order under overflow; changing tracing must not corrupt
  the evidence used to debug the NIC.

**ECI application/static boundary remains marginal after buffering.**
March's worst path is `link1_tlk_credits/out_lo_word_b_reg[24]` to static
`tx_block_out_t_reg[Data][6][24]`: −0.164 ns, four LUTs, 87% routing delay.
Of its worst 200 sampled setup endpoints, 170 are within the two full RX link
hierarchies; improving just that one TX path will not close March.
September 2812450's `link1_out_lo_buffer/.../first_buf_reg` to the same static
transport family is −0.506 ns, four LUTs, 90% routing delay.

- `vivado/eci/eci-toolkit/hdl/eci_gateway.vhd` changed all four TX output buffers
  from `FULL=false` to `FULL=true` in `f78940b`. March predates this change.
  Full buffering removes the forward bypass, but `bus_buffer.vhd` still muxes
  two stored words using `first_buf` before the static mux/packer.
- Intervention considered: localize the output buffers near their fixed transport
  interfaces; inspect both links and low/high channels. If mux delay remains,
  use a protocol-correct registered-output elastic buffer at the boundary.
  Recheck credit reservations for any additional in-flight beats.
- The XDC variable named `eci_gateway_pblock` assigns selected **DCS crossing
  registers**; it does not place all of `i_eci_gateway`. Do not infer placement
  from the variable name. Static cells cannot all be moved by an app-only build.

**DCS ready crossing, 2811847: −0.779 ns.**
`dcs_odd/i_cross_rsp_wd_slave/i_cdc/.../ram_full_i_reg` drives the preceding
register slice's `slr_auto_dest/.../mesg_reg_reg[485]/CE`. It is a synchronous
`clk_sys` backpressure path, despite its source being in a CDC FIFO. The path
crosses SLR0→SLR1, has one LUT, 2.943 ns routing and a 253-load final enable net.
The CDC full register is at `SLICE_X15Y266`; the slice endpoint is at
`SLICE_X102Y354`. Review the destination slice's actual placement and ready
fanout before changing `dcs_cdc.sv::FIRST_CDC` or adding CDC exceptions.
Both September CI traces also report `Place 30-1882`: inability to obey
USER_SLR_ASSIGNMENT for `dcs_even/i_cross_rsp_wd_master` source/destination groups.
Those warnings concern the even half, not proof of this odd-half path's cause.
Audit IP SLR assignments together with XDC pblocks; register-slice IP presence
alone does not establish an effective physical crossing pipeline.

2811847's app-clock worst path instead runs from `pendingLostCount` to the trace
capture registers' enables (−0.569 ns, 92% routing). Its worst 200 setup paths
all concern trace TX FIFO data/control/reset, as do 2812450's worst 200. A priority
selector fix can expose or leave this separate capture-enable bottleneck.

## What this says about the board failures

The failing shell-version read at `0x97effffffff8` is served by
`static-shell/eci-toolkit/hdl/eci_io_bridge_lite.vhd`, independently of the NIC's
CSR bank. `static-shell/eci-transport/hdl/eci_platform.vhd` multiplexes its low-VC
responses with application traffic before the transport. Therefore investigate
transport reset/readiness and I/O request/response progress for that oops;
optimizing trace WNS alone is not an explanation or a demonstrated fix.
Both ECI links being in RUN establishes link state, not successful I/O completion.

March and September also differ functionally: the application toolkit moved from
`bd0c590` to `9dd94de`. In addition to the June buffering rewrite, `9dd94de`
changes credit widths, reservations and pipelined debit accounting. Missing those
fixes is a plausible contributor to March's RPC stalls, **not a proved cause**.
All three tested builds use static-shell gitlink `02f19869`.

The two September commits have no diff in `hw`, `vivado`, `deps`, `build.mill`
or `flake.nix`; they do differ in software. However, `build.mill::gitHash` passes
the commit into generated RTL, and `GlobalCSRPlugin` implements it as constants.
Thus these are not necessarily byte-identical synthesis inputs. Hold that value
and the tool/static inputs constant when measuring implementation reproducibility;
retain the real source revision separately in provenance. Do not remove hardware
revision checking from deployment to simplify a timing experiment.

These captures do not isolate reset procedure from build effects. The
alternating board trials lacked first-I/O/reset-state captures, and reset dwell
changed between them. RPC failures also need packet and transport milestones to
distinguish network, dispatch and reply progress before assigning a cause to STA.
The unresolved board observation is retained in
[hardware context](../../agents/hardware-state.md#early-boot-and-artifact-evidence).

## Constraint audit limits

March has no routing errors and no reported bus-skew violations, but CDC is not
clean: 288 CDC-1 critical rows have hidden IP endpoints, seven CDC-10 rows concern
logic before transceiver reset synchronizers, and twelve CDC-11 rows concern
reset fanout into separate synchronizers. These need IP/reset-topology review,
not automatic waivers or a claim of 307 demonstrated hardware bugs. The timing
summary reports no unconstrained internal endpoints; that does not validate the
758 false-path constraints, three clock groups or 79 datapath-only max delays.

All three checkpoints have zero reported routing errors and no reported bus-skew
violations. September adds CDC-26 clock-enable-controlled multi-bit diagnostics;
the two September CDC count summaries are identical. These reports do not prove
reset correctness or explain why one build's first I/O access failed.

Original checkpoint reports are in `out/physical/{2383312,2811847,2812450}/`,
including summary, timing/path, CDC, route and bus-skew reports. Earlier interrupted
reports are superseded by these complete analyses.

## Trace FIFO, priority and placement comparison

The September 10 candidates isolate output-FIFO enable, priority-mask logic and
trace-TX placement against a shared extractor-corrected baseline. The priority
variant changes lowest-set-bit selection in `TraceBufferDMA`; the placement
variant constrains the trace TX FIFO near the historical F_MAC3 CMAC in SLR2.
Those historical coordinates are not a prescription for the current broad SLR2 placement.

| Experiment | Platform commit | Pipeline | Hardware job |
| --- | --- | --- | --- |
| control | `5d0692a4` | [510097](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/510097) | [2817813](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2817813) |
| tx-output-fifo | `62deb5b8` | [510099](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/510099) | [2817821](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2817821) |
| priority-tree | `a616eba2` | [510098](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/510098) | [2817817](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2817817) |
| tx-slr2 | `7234c181` | [510100](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/510100) | [2817825](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2817825) |

Final routed checkpoint STA, Vivado 2025.1; higher WNS / less-negative TNS is better:

| Experiment | WNS ns | TNS ns | Failing setup endpoints | Worst trace TX path |
| --- | ---: | ---: | ---: | --- |
| control | -1.052 | -2200.204 | 12478 | CMAC ready → FIFO read-pointer CE |
| tx-output-fifo | -0.826 | -2083.024 | 9384 | synchronized reset → output FIFO pointer R |
| priority-tree | -1.441 | -4207.810 | 18238 | CMAC ready → FIFO read-pointer CE |
| tx-slr2 | -0.517 | -895.252 | 7739 | synchronized reset → FIFO read-pointer R |

All four pass fast ECI, trace and full blocks CI gates, report zero routing errors,
and have WHS/THS 0/0 with no failing pulse-width endpoints (WPWS +0.039 ns).
SLR2 placement improves WNS by 0.535 ns and reduces |TNS| by 59.3%; the output
FIFO improves WNS by 0.226 ns. These are single implementations, with differing
embedded commit constants; repeat before claiming reliable improvement.

The priority change removes the carry chain on the representative saved-port
selection → frame BRAM path: 24 levels including 15 CARRY8 become 13 levels,
no carry cells. Yet slack improves only -0.433 → -0.376 ns: route delay increases
3.987 → 4.382 ns while logic delay falls 1.156 → 0.746 ns. It addresses logic
depth but does not solve placement or the unrelated global CMAC-ready bottleneck.

**Reset-placement finding:** `floorplan.xdc` matches
`i_app/*x_rst_sync` into SLR0, including `trace_dump_tx_rst_sync`. The improved
variants expose that reset crossing as their worst path. In tx-slr2, the source is
SLICE_X46Y299 in SLR0 and destination SLICE_X41Y616 in SLR2: 3.281 ns route,
97% of data delay. In tx-output-fifo, source SLICE_X61Y299 drives an output FIFO
pointer at SLICE_X20Y599 with 3.612 ns route (98%). This is synchronous reset
setup timing, not an asynchronous path to waive. Narrow the main-port reset
selector and place the trace-port synchronizers with their respective consumers;
compare TX-SLR2 plus reset placement before combining unrelated changes.
Recovered worst-path samples also show CMAC-ready at -0.515 ns and FIFO-internal
paths at -0.510 ns in tx-slr2, essentially tied with reset. Reset placement alone
will not substantially improve global WNS unless these paths improve too. In the
output-FIFO variant, CMAC-ready still reaches its output read-pointer CE at
-0.826 ns: the change breaks propagation into the RAM reader, not every ready path.

Other bottlenecks remain: tx-slr2's application clock is limited by Scheduler
corePidMap → queueMem address (-0.352 ns); ECI RX FIFO/extractor is -0.331 ns;
main NIC CMAC-ready → TX FIFO CE is -0.309 ns. Fixing the trace reset alone
therefore cannot establish full timing closure.

Complete recovered reports: `out/physical/experiments-20260910/relaunch1/JOB/recovered/`.
Their global metrics match the retained CI timing reports. Output-FIFO diagnostics
shifted 577 reports from CDC-15 to CDC-26; CDC-11 was 12 in control and 13 in the
variants. These structural diagnostics do not demonstrate functional failures.
The later combined reset/output-FIFO flow was tested with both FIFO settings
under stalls/reset; timing comparisons remain scoped to the table revisions.

## September 15 prewarmed-Nix comparison

| Job | Commit | WNS ns | TNS ns | Failing setup endpoints | Trace TX slack ns |
| --- | --- | ---: | ---: | ---: | ---: |
| [2824618](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2824618) | `c5373ce` | -0.174 | -190.802 | 3249 | +0.106 |
| [2824636](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2824636) | `f6bf910` | -0.396 | -1543.965 | 11631 | +0.170 |
| [2824643](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2824643) | `faf6790` | -0.205 | -306.122 | 4559 | +0.297 |

All have zero failing hold/pulse endpoints and zero routing errors. Minimum hold
slack is +0.002/+0.003/+0.002 ns. CDC counts are identical across these runs,
including 288 CDC-1, 10 CDC-10 and 13 CDC-11 diagnostics; they are not CDC-clean.
Trace TX values are the intra-clock `txoutclk_out[0]_1` representatives.

The combined reset placement/output-FIFO changes moved the observed critical
paths away from trace TX. This is not an isolated measurement
of either change: toolchain and generated commit constants also changed since the
September 10 experiments. Between these three revisions, tracked changes are only
CI/docs; embedded commit CSRs still perturb synthesis. The 0.222 ns WNS spread
and 3249–11631 failing endpoints prevent attributing timing differences to the
minimal container or declaring reproducible closure.

### Measured paths motivating TX placement and RX staging

1. **TX placement, before a protocol change.** Job 2824643's worst path is
   `i_eci_gateway/link2_out_hi_buffer/i_buffer/gen_full.first_buf_reg_replica_9`
   to static transport `tx_block_out_t_reg[Data][2][57]`, slack -0.205 ns.
   Its 3.228 ns data path has only three LUT levels: 2.880 ns routing, including
   a 1.393 ns application-to-static boundary net. The source is SLICE_X114Y530,
   mux SLICE_X103Y537, destination SLICE_X149Y537. Inspect dynamic legal sites and
   utilization near each fixed link transport, then test a small per-link region
   for the output buffers/muxes in `vivado/eci/xdc/floorplan.xdc`. Do not move the
   whole gateway into the DCS crossing pblock merely because its variable is named
   `eci_gateway_pblock`. Check both links and high/low VCs, not just this endpoint.
   `eci-toolkit/hdl/eci_gateway.vhd` already uses `FULL => true`; in
   `bus_buffer.vhd` this still selects between two data registers at the output.
   Another FULL buffer is not automatically a registered final output. If placement
   is insufficient, test an output-registered elastic buffer with sustained traffic,
   stalls and `s_hold` semantics, preserving all channel metadata.
2. **RX FIFO-to-packetizer pipeline.** All runs expose high-VC RX FIFO BRAM to
   packetizer data/CE paths; they reach -0.396 ns and eight logic levels in 2824636.
   In `eci-toolkit/hdl/eci_rx_hi_vc_extractor.vhd`, the 417-bit FWFT FIFO feeds a
   six-to-three-word phase mux directly into `eci_rx_hi_vc_packetizer.vhd`, whose
   message-length/position logic drives buffer enables and data selection.
   Test an elastic stage after the six-to-three-word split, carrying all three
   words, size and length together. Advance the split phase only on acceptance;
   preserve credit accounting and beat order. Do not blindly change the XPM read
   latency while retaining FWFT/empty-based valid logic. Add focused VHDL tests
   across message lengths, split phases, back-to-back packets and downstream stalls;
   the Spinal simulation gates alone do not establish toolkit correctness.
3. **Keep secondary clocks/families visible.** Application-clock slack is
   +0.002/-0.175/+0.001 ns. The failing run has AXI AW-valid to PacketBuffer BRAM
   address; the first run still has the 22-level trace priority path at +0.002 ns.
   Revisit Spinal AXI address/control pipelining only against a representative path,
   preserving AW/W association and responses. The old priority-tree candidate is
   not a proven global improvement. DCS FIFO-control to SLR slice CE also reaches
   -0.174 ns; inspect `rtl/dcs_cdc.sv` and crossing placement before changing CDC.

Reports: `out/physical/review-20260915/JOB/`; the hardware archives retain DCPs
and bitstreams. These historical paths motivated the following candidates.

## TX placement and high-VC RX staging

### TX candidate and completed checks

Read-only Vivado 2025.1 analysis on ba2 of job 2824643 confirms link 1's fixed
transport endpoints around SLICE_X148Y464 and link 2's around SLICE_X149Y537.
Only the four application output buffers are constrained: link 1 to
X120–141/Y450–509; link 2 to X120–141/Y510–539 plus X120–168/Y540–559.
Vivado found 1320/1520 slice sites, **zero sites outside the dynamic partition and
zero static cells** in either candidate region. The buffer resources require
353/371 LUTs and 1320/1336 registers respectively. These checks establish legal
placement scope, not QoR improvement; upstream credit/ready paths must also be
checked after routing. TX does not modify eci-toolkit.

The BRAM-mode extractor registers all three words, size, length and valid between
the six-to-three split and packetizer. The split advances on elastic-stage
acceptance; stalled records remain stable. Receive credits remain tied to
downstream channel consumption. Low-latency mode keeps its existing bypass.

The focused GHDL regression passed on both unpipelined and pipelined extractors:
8 cases, VCs 2–5, two seeds, 288 messages per case, all nonempty normal-message
masks, CAS traffic, sparse lanes, sustained arrivals, credit-window stalls,
random/long backpressure, data/metadata order, stable outputs and exact credit
counts. The Nix `eci-toolkit-rx` check also passed and gates this candidate's CI.
The FIFO model validates protocol behavior, not vendor XPM timing/CDC; Vivado
still elaborates the unchanged vendor FIFO.

The prerequisite range correction allows `buf_copy_start=3` when count=0; it
performs no indexed read and retains the same two-bit width. Tests separately
reproduce a **pre-existing RSTP packetizer failure**, whose repair is deferred to
the owner. Low-latency GHDL elaboration has a pre-existing unresolved-driver issue;
that mode is not claimed as tested. Details and reproducers are in the toolkit
feature branch's `tests/rx/README.md`.

Toolkit high-VC revision `c92f0d9` was adopted with the platform TX refinement;
upstream ownership and retained private refs are recorded in
[continuation context](../../agents/physical-implementation.md). The low-latency
mode and pre-existing RSTP issues above are outside the tested BRAM-mode claim.

The local TX placement build `c4174e5` had application/trace-TX slack
+0.042/+0.344 ns, WHS +0.003 ns and pulse slack +0.039 ns. Targeted queries
of all four output-buffer-to-static families found positive slack: link1
high/low +0.247/+0.109 ns and link2 high/low +0.061/+0.131 ns. This is stronger
than absence from a global worst-path sample, but remains one implementation.
Its worst RX/control path was −0.122 ns, TX classification/arbitration −0.121 ns,
and crossbar-to-credit data/enable −0.119/−0.116 ns. Reports:
`out/physical/resume-20260916/local-tx/tx-boundary-results/`.

## Completed TX/RX comparison — September 17

The individual and combined builds below completed implementation and retained
complete physical reports. No setup-closed result was obtained.

| Candidate | Revision | Flow / hardware job | WNS ns | TNS ns | Failing setup endpoints |
| --- | --- | --- | ---: | ---: | ---: |
| TX placement | `c4174e5` | local ba2 | -0.122 | -75.192 | 1660 |
| TX placement | `abe57e4` | CI 2832030 | -0.222 | -381.286 | 4600 |
| RX pipeline | `720bd1b` | local ba2 | -0.066 | -16.698 | 585 |
| RX pipeline | `eeeabf4` | CI 2832041 | -0.220 | -169.050 | 2590 |
| Combined | `f6516f4` | local ba2 | -0.011 | -0.104 | 16 |
| Combined | `f6516f4` | CI 2833087 | -0.057 | -7.584 | 426 |

All six have zero failing hold/pulse endpoints and zero routing errors, and
identical CDC diagnostic counts (not CDC-clean). Combined minimum hold slack is
+0.002 ns and pulse slack +0.039 ns in both flows. Application-clock setup is
+0.006 ns local / 0.000 ns CI; trace TX is +0.083 / +0.062 ns. These margins
are small. **No candidate achieves setup closure.**

The combined CI archive's `git-revision`, `rtl-derivation`, and `flake.lock`
match the local Nix bundle byte-for-byte. The RTL derivation is
`2k90ml72h7d3ckwgpwzfg52nrbmckk0n-lauberhorn-hw-rtl-config.drv`.
Both reports use Vivado 2025.1 and have identical endpoint totals. This supports
comparing the same revision/generated-RTL recipe across flows; the CI archive
retains only those three input metadata files, not the entire input bundle.
The 0.046 ns WNS difference shows the local 11 ps miss is insufficient margin
for a robust result; the cause of implementation variation is not isolated.
Earlier individual local/CI pairs also differ in embedded revision constants.

### Residual paths in the combined checkpoint

- **DCS response crossing control:** odd `i_cross_rsp_wd_slave` FIFO full flag to
  SLR register-slice FIFO CE is -0.011 ns local / -0.052 ns CI, with one logic
  level. Inspect placement/fanout and the existing CDC/register-slice structure
  before altering protocol or CDC. Eight of the sixteen local failing endpoints
  are in this family; 75 of the worst 200 sampled CI paths are in it.
- **Low-VC RX:** FIFO BRAM to extractor data is -0.011 / -0.052 ns; valid-to-CE
  paths between extractors and static ingress-to-low-FIFO controls also remain.
  The local sample contains seven failing low-VC endpoints. The new elastic
  stage targets high-VC RX; its old FIFO-to-packetizer path is absent from the
  combined worst-200 samples, which does not establish a family-wide margin.
- **TX still needs margin:** CI link2 high output buffer to static transport is
  -0.057 ns (the same representative endpoint is +0.002 ns locally). TX
  crossbar-to-credit paths reach -0.054 ns with nine logic levels. The original
  TX placement run's positive boundary margins do not generalize to every run.
  Inspect the combined endpoint placement and arbitration/credit path before
  choosing further placement changes versus an elastic stage.
- **Static/debug residual:** one local endpoint is static RX transport to its
  edge ILA at -0.005 ns. Keep it visible in global closure reporting; changing
  the static shell remains outside this campaign's application-only scope.

The local bounded sample contains all 16 failing endpoints (8 DCS, 7 low-VC RX,
1 static debug). CI's 200-path sample covers only part of its 426 failing
endpoints; sample counts are not total family counts or family TNS. Preserve
hold/secondary-clock margins while addressing the recurring families, then
validate the next actual candidate in both flows. Toolkit-owner approval is required before upstreaming the private RX dependency.

Evidence: `out/physical/resume-20260916/ci/{2832030,2832041}/reports/`,
`out/physical/combined-20260917/relaunch1/2833087/reports/`, and
`out/physical/combined-20260917/local/physical/`. Earlier local reports remain
under `out/physical/resume-20260916/local-{tx,rx}/physical/`.

## DCS, low-VC and TX-refinement comparison

All four candidates start from TX/high-VC baseline `f6516f4`; there was no new
unchanged-baseline run in this round. The static shell and ECI protocol were
unchanged. Different candidate revisions alter embedded CSR constants, so these
are practical ablations rather than identical-input placement-seed experiments.

| Increment over `f6516f4` | Branch | Revision | CI pipeline / hardware job |
| --- | --- | --- | --- |
| DCS destination placement | `timing/20260917-dcs-placement` | `b5c38a1` | 511987 / 2833774 |
| Low-VC RX isolation | `timing/20260917-low-vc-stage` | `c44887b` | 511988 / 2833785 |
| TX link2 placement refinement | `timing/20260917-tx-refine` | `1c72ea0` | 511990 / 2833807 |
| All three increments | `timing/20260917-round2-combined` | `759a9ab` | 511989 / 2833796 |

### Candidate scope and validation

DCS: the odd response-with-data FIFO full flag was at X76Y275 in SLR0, while its
SLR-slice destination was at X114Y360 in SLR1. Read-only routed inspection found
all 2268 destination primitives assigned only to `pblock_dynamic`, despite the
intended broad SLR constraints. The candidate explicitly confines this
`i_cross_rsp_wd_slave/i_pipe/*slr_auto_dest*` hierarchy to
`SLICE_X60Y240:SLICE_X118Y299`, using a hard pblock and a nonempty-selector check.
Vivado validated 3300 legal slice sites, zero sites outside dynamic and zero
static cells. A second read-only check of the linked pre-optimization checkpoint
confirmed the destination selector is present when implementation constraints
load (2245 primitives at that stage). No CDC logic, clocks or timing exceptions
were changed.

Low VC: toolkit feature `timing/20260917-low-vc-stage`, commit `a194f3a`, adds
`eci_rx_lo_vc_pipeline` between each VC6–12 FIFO and its downstream channel in
`eci_link_rx`. A 455-bit elastic input stage separates FIFO output from word
selection; a two-entry output queue with registered occupancy removes downstream
crossbar ready from extractor control. Data, lane mask and VC remain aligned;
credits still return only when the downstream channel consumes a word. The
existing extractor, lite link variant and high-VC pipeline are unchanged. The
new stages use initialization consistent with the existing resetless datapath;
link-down flushing is not newly provided. No interface/cache-line ABI changes.
The toolkit commit is published only in the private platform repository as
`timing/20260917-eci-toolkit-low-vc-stage`, not the owner colleague's upstream.
This low-VC candidate was not adopted; upstream approval remains separate.

The committed Nix RX gate passed: 8 existing high-VC cases plus 28 low-VC
baseline/candidate cases. Each low case covers 508 frames / 1792 words, every
nonempty seven-lane mask, VC6–12, two seeds, long/random downstream stalls,
back-to-back inputs, stable stalled output, exact data/VC/size and returned
credits. Production `eci_link_rx` integration also compiled with GHDL. These
are functional tests, not physical timing/CDC proof.

TX: the combined CI's worst output-buffer path enters static size decode at
X145Y498, south of the previous region. The candidate moves/narrows the entire
link2 high/low buffer region to `SLICE_X128Y480:SLICE_X141Y539`, keeping payload,
size and valid together and leaving link1 unchanged. Vivado validated 840 legal
slice sites, zero sites outside dynamic and zero static cells for 1993 existing
buffer primitives. It does not pipeline crossbar arbitration; those paths must
remain visible when judging the result.

### Completed round-2 results

| Candidate | Revision / hardware job | WNS ns | TNS ns | Failing setup endpoints |
| --- | --- | ---: | ---: | ---: |
| DCS placement | `b5c38a1` / 2833774 | −0.101 | −38.321 | 993 |
| Low-VC RX | `c44887b` / 2833785 | −0.052 | −4.166 | 262 |
| All increments, CI | `759a9ab` / 2833796 | −0.460 | −360.504 | 3437 |
| TX refinement | `1c72ea0` / 2833807 | −0.007 | −0.079 | 17 |
| All increments, local | `759a9ab` / ba2 | −0.345 | −192.544 | 2218 |

All completed runs report zero hold/pulse violations and routing errors. The
DCS/low-VC/combined CI minimum hold slacks are +0.001/0.000/0.000 ns;
TX refinement is +0.005 ns. CDC diagnostics are not clean: low-VC candidates
reduce CDC-26 from 1971 to 1966, without establishing CDC correctness. Local
combined application/ECI slack is −0.345/−0.076 ns. Both combined results support
selecting the narrower candidate; they do not isolate a single cause of the
regression. Complete hardware reports were recovered despite two report-job
startup failures, so those failures do not invalidate the timing measurements.

## Round 2 checkpoint review — September 17

Read-only Vivado 2025.1 analysis on ba2 of hardware jobs 2833774 (DCS),
2833785 (low-VC RX), and 2833796 (combined). The checkpoints are the completed
CI artifacts, not new implementations. Detailed evidence is in
`out/physical/round2-20260917/deep/JOB/`: `failing-paths.tsv` enumerates one
worst setup path per failing endpoint, `family-paths.tsv` compares targeted
cones, and `congestion.rpt` / `slr-utilization.rpt` retain physical diagnostics.

All three analyses exited zero and their complete negative-path enumerations
match the timing-summary endpoint totals exactly (993/262/3437). Summing
rounded TSV slacks differs slightly from Vivado TNS; retain the timing summary
as authoritative. All have zero routing errors and zero hold/pulse violations;
CDC diagnostics remain, as recorded in the experiment ledger.

| Targeted worst slack, ns | DCS 2833774 | Low VC 2833785 | Combined 2833796 |
| --- | ---: | ---: | ---: |
| Odd DCS full flag → destination slice | +0.549 | +0.394 | +0.584 |
| Low-VC FIFO → extractor/stage | -0.101 | +0.085 | +0.107 |
| Within/between low-VC extractor/queue hierarchies | -0.016 | -0.031 | -0.043 |
| TX crossbar → credit buffers | -0.101 | -0.035 | -0.055 |
| Link2 high output → static transport | -0.039 | -0.039 | +0.025 |
| Trace DMA internal | +0.017 | +0.012 | -0.460 |
| Application ILAs, all clocks | +0.016 | -0.052 | -0.459 |

Low-VC-only has 262 failing endpoints: 255 clk_sys and seven RX-clock.
The worst is the `ila_cmac_rx` match-unit-3 comparator, `probeDelay1_reg[5]`
to termination register, -0.052 ns, 33 CARRY8 plus SRLC32E. The TX link2
size-bit-576 path to static transport is -0.039 ns. 129 endpoints run from
static RX ingress to low-VC FIFOs (worst -0.038 ns); ten are internal low-VC
paths (worst -0.031 ns at the new queue occupancy register). Eleven are TX
crossbar-to-credit paths, and 34 are odd DCS response-master upsizer reset
paths (worst -0.032 ns). A static-shell-only edge ILA path remains -0.005 ns,
outside application-only scope. The original odd-slave DCS target happens to
pass +0.394 ns despite its destination still being in SLR1; the dedicated
placement candidate improves margin and controls that placement explicitly.

DCS-only has 993 failing endpoints, all on clk_sys. Of these, 596 run from
TX crossbar input buffers into link credit buffers (361 link2, 235 link1).
The representative path is input channel 4 `data_buf1_reg[583]` to link2
high-credit `data_buf2_reg[318]/CE`, -0.101 ns, eight LUT levels and 85.7%
routing. The unmodified low-VC path also reaches -0.101 ns: link2 VC12 FIFO
BRAM to extractor `output_reg[data][0][1]/CE`. Meanwhile the DCS target passes
+0.549 ns with all 2243 placed destination primitives in SLR0. Thus its global
regression does not invalidate the targeted placement fix.

The combined build has 3437 failing endpoints: 2927 on the application clock
and 510 on clk_sys. By destination hierarchy, 1518 are NIC logic, 568 ILAs,
359 trace logic, 437 ECI gateway and 555 elsewhere. This is a broad regression,
not a build that will close by fixing only its worst trace path.

Specific combined paths:

- **DCS target passes +0.584 ns:** odd response-with-data FIFO `ram_full_i_reg`
  to `slr_auto_dest/.../mesg_reg_reg/CE`. All 2242 placed destination primitives
  are now in SLR0 and the explicit destination pblock. This confirms the intended
  placement effect; it does not clear other DCS families.
- **Low-VC FIFO ingress passes +0.107 ns:** link2 VC10 FIFO BRAM to
  `frame_data_reg[210]/D`. The remaining queue-control path is link1 VC7
  `count_reg[1]` to link2 VC7 `front_reg[16]/CE`, **-0.043 ns**, seven LUT
  levels, 2.531 ns routing out of 2.984 ns. The stage isolates the extractor,
  but cross-link arbitration still reaches the output queue's enable.
- **TX output boundaries pass:** link1 high/low +0.196/+0.071 ns; link2
  high/low +0.025/+0.270 ns. Upstream TX crossbar-to-credit logic still fails
  at -0.055 ns. Tightening output placement does not pipeline that cone.
- **Trace DMA -0.460 ns:** `savedPorts_6_valid_reg` to frame FIFO BRAM
  `DINADIN[21]`, 25 levels (15 CARRY8), 5.041 ns data delay, 80.4% routing.
  `TraceBufferDMA.scala` computes priority selection and indexed event mux into
  the FIFO in one cycle. Most trace DMA cells remain in SLR2; clock-path SLR
  labels alone must not be mistaken for a data-path SLR crossing.
- **Application ILA -0.459 ns:** `ila_app` match unit 150, `probeDelay1_reg[4]`
  to match termination register, 33 CARRY8 plus SRLC32E, 85.8% routing.
  Extra input pipeline stages alone would not split this internal comparator.
  NIC failures also reach -0.433 ns in the ONC RPC call decoder.

Congestion reports describe different implementation stages. In the combined
checkpoint, placer-final congestion is south/long level 5 at
`CLEL_R_X36Y388:DSP_X51Y415`; router-initial congestion is south/long level 6
at `CLEL_R_X43Y383:LAG_LAG_X57Y542`, attributed mainly to NIC (42%), app ILA
(15%) and ECI gateway (11%). DCS-only has level-5 initial hotspots, including
ECI RX near Y511–542 and NIC/ILA near Y156–195. The later global-router log
reports only 1x1 or 2x2 tile hotspots, with effective levels at most 2 in all
three builds. Low-VC-only lists no placer-final or router-initial congestion
windows at the report threshold; its SLR1 CLB occupancy is 72.34%. These are not the same metric or stage as checkpoint congestion
history. Combined SLR1 CLB occupancy is 71.04%, versus DCS-only 75.16%; SLL
usage is similar (SLR1–2 45.28%/45.53%, SLR0–1 51.34%/51.03%). The evidence
supports placement/routing interaction as a hypothesis, not a proved causal
link or a claim that local congestion is absent.

The targeted DCS placement and low-VC ingress changes improved their intended
paths, while the combined build regressed broadly. Residual TX arbitration,
RX queue-control, trace-selection and ILA comparator cones are distinct targets.
The evidence does not identify which increment caused the combined application
regression. DCS/low-VC/combined candidates were not adopted.

## Selected September 17 result and limits

The platform selected TX refinement over the TX/high-VC RX baseline,
`1c72ea0` (pipeline 511990, hardware job 2833807). It did not achieve setup
closure. The final 200-path sample contains all 17 failing endpoints:

- Eight wholly static-shell paths: seven link1 RX block-to-FIFO enables at
  -0.007 to -0.006 ns, and one edge ILA input at -0.005 ns. Static-shell changes
  remain outside this application-only campaign.
- Four low-VC RX endpoints: two VC7 valid-to-data enables at -0.007 ns and
  two VC12 FIFO-to-extractor paths at -0.003/-0.001 ns.
- One link2 high TX size-decode boundary at -0.006 ns.
- Two high-VC packetizer enables and two TX crossbar-to-credit endpoints,
  each -0.001 ns.

The static-shell endpoints are outside the application-only scope. Trace DMA
selection/event-mux depth and internal ILA trigger depth remain margin risks even
though they are not the worst failures in this selected build. A new merge or
revision CSR needs its own physical result; these values do not transfer to
current master or later functional board tests.

Evidence: `out/physical/round2-20260917/ci/2833807/reports/` and
`out/physical/round2-20260917/local/all/physical/`. Deep targeted results are in
`out/physical/round2-20260917/deep/JOB/`, with full failing-endpoint enumerations
and physical diagnostics. Resume constraints and retained branches belong to
[agent context](../../agents/physical-implementation.md).

## B4 recovery regression — pipeline 512109, September 18

[Pipeline 512109](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/512109)
built `cdf2121` (hardware job 2834747, report job 2834748). The complete canonical
checkpoint reports describe **Physopt postRoute**, Vivado 2025.1. Successful CI
and bitstream generation do not establish timing closure.

| Clock/domain | WNS (ns) | TNS (ns) | Failing setup endpoints |
| --- | ---: | ---: | ---: |
| Whole design | −1.441 | −8939.668 | 28222 |
| NIC application, 200 MHz | −1.441 | −6433.588 | 13269 |
| ECI `clk_sys` | −0.494 | −2492.498 | 14839 |
| Main CMAC RX | −0.272 | −13.581 | 114 |

Hold and pulse-width failure counts are zero (minimum slack +0.001 ns and
+0.039 ns respectively). All routable nets are routed, with zero routing errors;
the timing report reports zero unconstrained internal endpoints. These facts do
not cancel the setup violations. CDC diagnostics remain separately recorded.

The worst path starts at
`Scheduler_logic_procDb/rxPush_pip_node_3_calcIdx_IDX_reg[0]_replica_1` and ends at
`Scheduler_logic_inTransit_1_reg[23]`. It has **18 logic levels, 6.190 ns data
delay: 1.478 ns logic and 4.712 ns routing**, against a 5 ns requirement.
The routed cone passes through scheduler rejection selection, the new six-input
discard round-robin arbiter (`streamArbiter_13`), selected buffer-size/ready
logic, and per-process counter update arithmetic. The report includes a 1.319 ns
net into the counter-update cone. The parallel context-retirement path ends at
`OncRpcReplyEncoder_logic_contexts/active_13_reg`: −1.441 ns, 18 levels,
6.256 ns, including a 1.945 ns, fanout-132 discard-token net.

Source correspondence:

- `Scheduler.scala`: `pushResult` reject decision directly drives `pushDiscard`;
  `discarded` directly receives the arbiter output. `pushResult.fire` also drives
  departure accounting for every process. The newly added `inTransit` registers
  are 32 bits, with same-cycle arrival/departure arithmetic.
- `DmaControlPlugin.scala`: selected buffer size controls `discarded.ready`;
  `discarded.fire` and selected request token directly drive context retirement.
  Pipelining **only the packet-buffer return** does not cut these earlier
  selected-data-to-ready or token-retirement paths.
- `RpcReplyContexts.scala`: token lookup/generation validation and active-bit
  updates consume the new combinational discard path.

Of the bounded 200 worst setup paths (one per endpoint), **180 end at inTransit
counters and 11 at reply-context active bits**. This is sample evidence, not a
partition of all failing endpoints/TNS. It identifies B4 reclamation/accounting
as the dominant new cone; the watchdog deadline comparator is not the dominant
endpoint in this sample. Existing ECI/RX violations also remain; fixing this cone
alone cannot be called whole-design closure.

The correction therefore needed real handshake/register boundaries through
selection, descriptor/token handling and retirement/accounting, cutting both
forward data and backward ready. Registered accounting and bounded counter widths
address the arithmetic cone. Every added stage must remain visible to
`processDraining` until packet/context ownership retires; otherwise timing
pipelining can reintroduce premature reuse. Stop/reuse and backpressure tests
check that contract. The measured correction is below.

Evidence: `out/physical/b4-512109/out/physical/{summary.json,paths.tsv,
max-detail.rpt,timing.rpt,route.rpt}` from the completed report job. No hardware
programming or board test is part of this timing inspection.

## B4 first correction — pipeline 512199, September 18

Pipeline 512199 (`754ecb4`, hardware job 2835579, report job 2835580) passed
all CI jobs. Complete final post-route reports show:

| Metric | 512109 | 512199 |
| --- | ---: | ---: |
| Whole-design WNS (ns) | −1.441 | −0.382 |
| Whole-design TNS (ns) | −8939.668 | −111.505 |
| Failing setup endpoints | 28222 | 1400 |
| NIC 200 MHz WNS (ns) | −1.441 | −0.382 |
| NIC 200 MHz TNS (ns) | −6433.588 | −108.749 |
| NIC failing setup endpoints | 13269 | 1274 |

Hold/pulse violations remain zero; minimum slacks are +0.003/+0.039 ns.
CDC diagnostic counts are unchanged. The long in-flight-counter chains no longer
appear in the bounded 200 worst-path sample; this alone does not prove every
counter endpoint has positive slack. ECI moved to −0.050 ns / −2.757 ns with
126 failing endpoints despite unchanged ECI sources/constraints; do not confuse
placement/routing variation with an ECI source fix.

The new worst path is `Scheduler_logic_discardStage/head_reg[0]_replica` to
`OncRpcReplyEncoder_logic_contexts/active_10_reg`: **−0.382 ns**, five levels,
5.164 ns data delay, **4.831 ns routing**. The ring-head selection feeds context
validation logic placed alternately under host-interface and decoder hierarchies;
the detailed path contains several approximately 0.9–1.1 ns nets crossing between
these regions. All 16 context active bits occur in the sample. Additional B4
paths include producer selection to discard owner capture (−0.241 ns, 11 levels)
and core PID selection to watchdog deadline enable (−0.205 ns, eight levels).

A separate cookie-register → AXI CSR response path has −0.375 ns and 18 levels.
Targeted inspection of the exact DCP on ba2 resolves it as reply validation →
admission → `RecoveryInFlight.busy` → process-drain CSR selection, not the older
indexed request-table diagnostic readback. It includes an SLR0→SLR1 crossing.
The second correction registers per-process drain status before CSR selection,
holding it busy while admission is enabled to prevent an old idle sample from
escaping on disable. Immediate arrivals remain included in each status sample.
Other sampled paths include existing trace and DCS logic, outside the B4 fix.

Reports: `out/physical/b4-512199/out/physical/`. No board test or timing-closure
claim follows from this improvement.
Targeted evidence: `out/physical/b4-512199/ba2-targeted/`.

## B4 pipelining mechanisms and evidence boundary

The first correction placed a two-record registered buffer after discard
arbitration and registered admission/departure events before per-process
arithmetic. The bound was nine in-flight records (four bits): two per RPC
decoder FULL stage, one active DMA record, lookup pipeline capacity and one
delayed-departure allowance. Immediate/staged arrivals keep drain status busy.
These changes preserve ownership until retirement; adding a pipeline must not
permit premature process/context reuse. The measured 512199 comparison above
shows improvement, not closure.

The subsequent implementation keeps the buffer output in a fixed register bank,
validates discard generations locally at each context slot, and registers
producers before arbitration. It also registers watchdog samples and per-process
drain status before indexed CSR selection. Enabled processes report busy; disabled
processes include retained owners and immediate arrivals, with a conservative
extra cycle before completion. The watchdog sample delay follows its best-effort
contract and introduces no extra control TX. See
[`RecoveryStreamBuffer.scala`](../../hw/src/lauberhorn/RecoveryStreamBuffer.scala),
[`Scheduler.scala`](../../hw/src/lauberhorn/Scheduler.scala) and
[`RpcReplyContexts.scala`](../../hw/src/lauberhorn/net/oncrpc/RpcReplyContexts.scala).

Each correction's recorded local regression passed 17 tests across six suites,
with two empty tests ignored: recovery/accounting, watchdog, reply contexts,
nested and ordinary RPC. Logs are `out/physical/b4-timing-fix/regression.log` and
`out/physical/b4-timing-fix-2/regression-final.log`. The second patch's local
simulation is not a routed improvement measurement; this comparison records no
post-second-patch timing result. Existing trace/DCS/ECI paths remain outside
these B4 interventions.
