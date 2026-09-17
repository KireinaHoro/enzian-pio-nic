# Checkpoint findings — 2026-09-10

Read-only analysis on **enzian-ba2, Vivado 2025.1**, using implemented
`Physopt postRoute` checkpoints. Recipes and report meanings are in
[physical implementation](physical-implementation.md). Board observations are
[recorded separately](../development/hardware-test-results.md); no new board
experiment was performed during this analysis.

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

Do not promote the combined candidate. Retain the DCS placement and low-VC
stage as targeted candidates, subject to the complete comparison and toolkit
owner approval. Next RTL experiments should shorten TX crossbar-to-credit
arbitration and RX output-queue control while preserving stalls, channel
metadata, packet locks and credits. Separately split trace selection/event mux
across cycles while preserving timestamps, loss reporting and acceptance;
review ILA trigger comparisons without silently discarding debug capability.
The pending TX-only and existing local runs are needed before blaming a
particular increment for the combined application-clock regression.

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

## Specific bottlenecks and first experiments

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
- First isolated experiment: set `outputFifoEnable = true` **only on
  traceDumpTxFifo**. Its registered half-full signal then controls the RAM reader;
  CMAC ready remains local to the output FIFO. Alternatively insert a full elastic
  AXI-stream slice in `traceDumpTxClock`, at the CMAC boundary. Do not use an
  application-clock slice on the FIFO's read side.
- Floorplan experiment, separately: place that output stage/read-side logic near
  the trace CMAC in SLR2. Current XDC constrains `i_trace_dma/traceDma`, **not**
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
- First experiment: compare `firstV2(Vec(savedPorts.map(_.valid)).asBits)` with the
  existing priority mask while preserving lowest-index priority. This is a
  dependency change in `deps/blocks`, not a generated-Verilog edit.
- Next experiment: register selection and selected event before writing the frame
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
- First experiment: localize the output buffers near their fixed transport
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

Next board discriminator: repeat the failing September build with the **same**
verified reset procedure as the passing build, recording first I/O transactions
and reset/clock state. For March RPC failures, capture ARP/portmapper/application
packets and trace milestones to separate network/bypass, dispatch and reply
progress before attributing a timeout to STA. Existing alternating tests do not
provide those captures, and reset dwell changed before them.

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

## Evidence and live experiments

Complete local reports: `out/physical/{2383312,2811847,2812450}/`, each with
`summary.json`, path TSV, full timing/CDC/route/bus-skew reports. Corresponding
ba2 directories are `/tmp/lauberhorn-sta-JOB-v2`. Earlier `*-first` reports were
interrupted by a corrected report-option error and are superseded.
CI experiments and the scheduled artifact collector are tracked in
[the experiment ledger](physical-experiments.md). Source interventions above are
hypotheses under test, not measured improvements.
