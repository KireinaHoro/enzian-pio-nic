# Checkpoint findings — 2026-09-10

Read-only analysis on **enzian-ba2, Vivado 2025.1**, using implemented
`Physopt postRoute` checkpoints. Recipes and report meanings are in
[physical implementation](physical-implementation.md). Board observations are
[recorded separately](../development/hardware-test-results.md); no new board
experiment was performed during this analysis.

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

The first correction should split discard selection, descriptor/token handling,
and retirement/accounting across real handshake/register boundaries, breaking
both forward data and backward ready paths. Register accounting events before
counter arithmetic; derive counter widths from a proved bound on outstanding
admitted descriptors rather than leaving them at 32 bits. Any added stage must
remain visible to `processDraining` until both packet/context ownership are
retired, so pipelining does not reintroduce premature PID/context reuse. Preserve
one-record-per-cycle throughput under stalls. Then rerun stop/reuse, late-discard
and backpressure tests and compare a new routed checkpoint. Do not use timing
exceptions or placement-only tweaks to conceal this dependency chain.

Evidence: `out/physical/b4-512109/out/physical/{summary.json,paths.tsv,
max-detail.rpt,timing.rpt,route.rpt}` from the completed report job. No hardware
programming or board test is part of this timing inspection.
