# Physical implementation continuation

## TCP hardware acceptance: memcached passed, trace DMA read stalled

The user authorized capture updates and real TCP/memcached acceptance. Changes
are merged into master in `/tmp/lauberhorn-rtl-congestion`;
preserve the unrelated main audit checkout. Evidence lives in main
`out/tcp-fmac3-acceptance-20261027/`, especially `result.json`, `record.json`
and `memcached-first/summary.json`. No replacement implementation was launched.

Exact `99fb5fed` hardware and matching TCP-validation image passed one cold-boot
memcached TCP test on zuestoll07 with peer zuestoll14: all QLM8–13 locks and
24 CCPI lanes, module/version gates, binary/text commands, 16 concurrent clients,
split writes, pipelined requests, backpressure and reset recovery. Client and
post-server checks exited zero; no kernel faults reported. Total 326 s. This is
one successful trial, not timing closure or the complete protocol/burst suite.

Trace capture then failed receiving the first read response. The peer received
metadata from 192.168.128.244; FPGA counters recorded one ARP request, one read
command and two transmitted responses (metadata/ARP). State 0xa040 is sendData,
readDesc.ready and TX.ready asserted, readData.valid absent. No trace data arrived.
leaf0 learned 0c:53:31:03:00:f4 on Ethernet108, confirming F_MAC3 mapping. SONiC
simultaneously reports linkdown/no-transceiver, so that status must not be used
to claim a missing cable: real bidirectional packets contradict that diagnosis.
The earlier user cable request is withdrawn; no physical cause is established.

Preserve the programmed board: memcached remains running, trace_stop=1, original
VIO settings restored, writeSlot 19 (1216 bytes), wrapped 0, sampleLost 1, DMA error 0.
No full trace was received, no JTAG fallback and no reset occurred. Local evidence:
`capture-failure/`, `peer-capture-failure.tar` (hash verified),
`trace-diagnostics.json`, `result.json`. Peer originals remain preserved too.
The maintained programmer's existing --capture-ila mode collected a 1024-sample
trace-memory AXI snapshot, with no reset or trace-memory reads. All samples are
stalled: AR count1/address0/len0x12, R count1, AW/B count2; ARctrl2/Rctrl2,
AWctrl1/Wctrl5/Bctrl2. This supports a memory-path nonprogress diagnosis rather
than a disconnected trace link; exact DDR/calibration cause is not established.
See `memcached-trace-axi.csv` and `axi-summary.json`. Full trace recovery needs
DMA/DDR diagnosis; do not retry capture or reset merely on receive timeout.

Capture config selects MAC `0c:53:31:03:00:f4` / IP `192.168.128.244` through
VIO overrides and restores the original identity after capture. The maintained
capture helpers and exact-source register walker fixes are committed. Vivado's
inserted `_psap*` registers must not terminate the source-register walk.
The final implemented map-v2 accounts for 14 active sources at 2 cycles, 66 at 4,
26 at 6, 26 at 8, plus inactive sources 11/24/77 and loss marker 255. The inactive
sources have proved GND output-valid and null latency; decoder rejects their
events. DCP/bit hashes match; retain the rejected first map as failure evidence.
Twenty Python tests plus Tcl walker/naming regressions passed.

Matched image is staged at
`/scratch/pengxu/tcp-fmac3-acceptance-20261027/tcp-validation-99fb5fed.img`, SHA256
`6b9cb90f103c3f2a7d5b85c3997cdc567b1c7bfe6ba18da0787a48490de3bb00`.
Both boards were reserved to pengxu and run kernel 6.8.0-146; stale root hostnames
are not board identity. The current authenticated leaf0 socket is
`/tmp/echo-unloaded-shared-20261019-switch`. The stopped transfer service used gateway
192.168.255.254:18879, root `/tmp/tcp-fmac3-transfer-20261027` (session 1404);
stopped after preserving receiver failure (PID 3158134). Restart explicitly on the same
recorded endpoint if needed after the cable is connected. The requested memcached TCP test is complete. Broader protocol, competing-domain
and repeat-boot validation was not run; it requires preservation of the current
failed trace state first. Do not claim full trace export acceptance.

## Application depth/fanout candidate: routed, trace extraction failed

Exact build revision `99fb5fed7757b0b9ae26ce7f3910f51a1f93f595`; record and
preserved artifacts: main `out/autopipeline-fix-impl-20261027/`. Build ref
`timing/20261027-autopipeline-fix-impl` retained. On enzian-ba2 the root remains
`/tmp/lauberhorn-autopipeline-fix-99fb5fed-20261027`. October 27 **11:36:02 UTC**:
launcher 1701024 and Vivado 1701178 terminated, exact container
0265d834b6a0670f3e7acf5375bfaa284d91b6f079f7d936962229cd68210006 removed,
`build.exit=1`, physical COMPLETE absent. No new callback or build scheduled.

Placement/autopipelining, routing, post-route physical optimization and bitstream
creation completed. The failure is automatic final-checkpoint trace-depth extraction:
`missing trace sink: i_app/i_trace_dma/traceAuto_11`. The after-build report flow
hit the same failure after writing STA/CDC reports. Final-netlist diagnostics find
no surviving source/sink FFs for declared sources **11, 24, 77** (even/odd
`lcl_rsp_wod_i`, `scheduler_deadline0`); 132 other sources retain valid sinks.
The original build produced no implemented map or complete depth TSV. Preserve
`.depths.tsv.tmp` as failure evidence. The later authorized read-only recovery
and matched map are recorded above; the original build remains failed.

All 2715 original output files are copied under `collected/` and hashed in
`collected.sha256`; bitstream/probes/routed-DCP hashes match the remote originals.
Bit SHA256 `6b7975e8ae8540436aa825b1cac8fb396274eb28804b6e7d1d838cb4e38e7e17`;
routed DCP `b4082669b336bc5e2600a427cbb69b68a1e8f664f728456447f66d7461e3bcca`.
Read `digest.txt`, `result.json`, `baseline-comparison.json` and supplemental
`app-top1000/` reports. The maintained digest was read before detailed STA analysis.

Final overall WNS **-0.891 ns**, TNS **-12095.535 ns**, **46732** failing setup
endpoints; WHS **0.000 ns**, WPWS **+0.039 ns**, no hold/pulse violations.
All 1005015 routable nets fully routed, zero route errors. Versus 73c3dc76:
WNS improves 0.228 ns, TNS improves 19916.725 ns, 35528 fewer failing endpoints.
Versus 68ec9af8: WNS improves 0.341 ns, TNS improves 10857.723 ns, 19110 fewer.
Timing is NOT closed. Failing intra-clock domains: app -0.891/-9677.508 ns
(30479 endpoints), clk_sys -0.399/-1730.644 (11699), main CMAC RX
-0.396/-687.385 (4554). Other clock rows have no setup/hold/pulse violations.

Comparable app-clock top-1000 uses same from/to clock, max_paths=1000,nworst=1.
Largest sampled families: odd-DCS internal 544; ONCRPC reply retire/request DB 82;
IP decoder aligner 60; even-DCS AXI to worker0 RX router 52; ownership domainCodes
into traceAuto96/97 65 combined; even-DCS AXI to worker0 TX router 32. Worst path
is reply identity cookie to Scheduler processDraining, -0.891 ns, 12 logic levels.
These are bounded sample counts, not all failing endpoints.

Actual placed cells: trace dump 30700, trace VIO 6315 and trace CMAC 5860 in SLR2;
packet buffer 14997 in SLR0 (nonplaced primitives listed separately). SLR0/1/2
CLB occupancy is 74.00/79.37/66.65%; LUT usage 38.11/44.83/34.81%. Trace CMAC
is CMACE4_X0Y8, GTYE4_CHANNEL_X1Y56..59, matching F_MAC3. Experimental fine
APP_TIMING_FLOORPLAN selectors absent; application ILA and EciThreadClRouter absent.
Native UDP checksum logic absent from exact generated RTL. Other checksum logic
remains (e.g. IP), so a generic checksum-name count is not an absence test.

Constraint checks: no unclocked or unconstrained internal endpoints, no multiple
clocks/invalid generated clocks/loops; F_D4_RESET_N lacks output delay. Bus-skew
minimum reported slack +1.274 ns. Clock-name collision warnings remain, as do 98
soft USER_SLR_ASSIGNMENT partition warnings. CDC critical counts: CDC-1 192,
CDC-10 11, CDC-11 19; warnings CDC-6 73, CDC-15 8311, CDC-26 1971. CDC-11 is
7 higher than both baselines; these are structural findings, not functional proof.
High fanout includes app BUFG reset 30231; DCS even/odd resets 8809/8807;
trace FIFO LUT control 6819; IP aligner ready 1399. Full reports retained.
All read-only analysis processes also terminated; build refs remain retained.
No board operation, toolkit-master change, optimization or replacement authorized.

Previous 44552a84 run failed before placement with NTCN-7: shared group `event`
mixed limits 10 and 5. All processes terminated; 2665 failed-build files including
synthesis/linked/optimized checkpoints and logs preserved and hashed under main
`out/app-depth-impl-20261026/collected`. No bitstream/routed timing/resolved map
was produced. This failure provides no timing or congestion comparison.
Preserve main checkout's unrelated audit branch and all earlier artifacts/refs.
Blocks dependency remains `3d68e3767b6f4b90edcd668bf843504e3738829d`, retained at
private platform ref `deps/blocks-app-depth-fanout`.

Changes target the 200 MHz app clock without relaxed timing exceptions or fine
floorplanning. Every one of the preserved `68ec9af8` app top-1000 paths has an
intervention/disposition in main `out/app-depth-fanout-review/offenders.json`
(26 categories). Counts below are bounded-sample counts, not total violations:

- Scheduler lookup (309): Spinal S2MLink/StageLink boundary; in-flight accounting
  includes two lookup and two DMA input records. Allocation factors common donor
  availability outside priority and uses one-hot payload selection.
- Trace selection (57 plus scheduler/event cones up to 26 levels): banks of eight,
  StagePipeline and balanced masked OR. Event mux freezes after FSM declarations.
- TX handoff (245): clear byte-valid state instead of wide data banks. RX length
  retirement uses logical validity (16), retaining immediate zero visibility.
- RX replay (32 plus routing-heavy selects): synchronous read-first block RAM,
  byte-strobe collision forwarding and bounds calculation during RAM fetch.
- DMA (33): elastic RX admission/classification and TX arbitration; parallel
  resident-slot owner selection. ECI admission and RPC identity/context handling
  compare slots locally; stale context consumption cannot clear a live generation.
- Native UDP (33, including an 18-level path): StagePipeline byte count/mask,
  adjacent-bit contiguity check and remaining-byte trim counter.
- Header injection (9): StagePipeline separates arithmetic and wide assembly.
- Packet-memory arbitration (17): registered AW-route FIFO. DCS R/B crossbar
  skid slices, separate DCS read-response slice (72), registered-output DCS local
  elastic stages (77), and bidirectional Spinal ECI AR/AW boundaries isolate ready.
  Accepted transactions remain owned until responses return upstream.

Retain existing transaction FSMs for packet retirement/AXI sequencing. Existing
BUFGCE app resets/trace snapshot/aligner controls and placement-aware fanout
optimization remain. Old-DCP query confirms CMAC reset loads are outside the app
clock domain; evidence is `out/app-depth-fanout-review/fanout-domains.tsv`.
The analysis process/container terminated; no cleanup is required.

Validation: 16 final application tests and 7 transport tests passed, including
native UDP trace linkage, isolation, collision forwarding, admission, recovery,
backpressure and stale context consumption. Four scheduler tests, three header
tests and 17 trace tests passed with relevant implementations verified identical.
Two DCS tests passed (256 cache lines with stalls/ID wrap; 1000 wide stalled beats).
Retained logs/reports and source-match evidence live beside the exact build record.
Clean-revision bundle/image passed; generated RTL differs from the tested source
only in version CSR. All **1186** remote input hashes match. Port checks: 354 NIC,
497 trace, five 21-bit debug bundles; application ILA absent. Generated replay RAM
is block/read-first/one-cycle; trace template has 135 auto-pipeline transports plus
loss marker and correctly remains unresolved until routed-checkpoint extraction.

A prior test snapshot exposed missing late-declared trace events; fixed and the
unchanged UDP assertion now passes. A disk-full attempt stopped before validation;
only six unreferenced/no-root Nix source-cache copies from this task were removed.
Original data, artifacts and refs were preserved. The later hardware failure and
explicitly authorized corrected run are recorded above.

Results and both baseline comparisons are recorded above. The unresolved trace
map/depths remain an artifact blocker; timing still fails. DUT zuestoll07 still needs separately authorized F_MAC3
trace link/FEC and full export acceptance after a matching bitstream exists.

## Relocation and trace transport in the collected build

The F_MAC3 relocation and app ILA removal are included after completion of the
application-clock pipelining/fanout pass described above.
Trace CMAC uses SLR2 GT quad 233, CMACE4_X0Y8, polarity 1100. Dump processing,
RX/TX FIFOs, resets and trace-status VIO join trace DMA/DDR in SLR2. Packet-buffer
aligner, DMA and RAM/controller move together to the existing broad SLR0 region.
No new fine-grained pblocks; source-side trace sampling remains distributed.

`ila_app`, its dedicated counter/BD monitor ports and manual `app_ila.tcl` are
removed. Recover their archived source from `dc6ba3ef:vivado/eci/tcl/app_ila.tcl`
and `dc6ba3ef:vivado/eci/bd/design_1.tcl`. Other ILAs/VIO and DDR tracing remain.
Port checks retain generated NIC/trace correspondence and state packing, and
reject reintroduction of the retired application ILA. Logical trace MAC/IP
defaults remain unchanged; use board-specific overrides at deployment.

**DUT is now zuestoll07.** After a future authorized build, test F_MAC3 link/FEC
and a complete frozen UDP trace export with matching bitstream/probes/trace map,
plus bounded JTAG integrity comparison. Confirm its switch port and identity;
do not reuse zuestoll14 mappings. Historical F_MAC3 intermittency was observed
on zuestoll14, not established on zuestoll07. No board test has run for these changes. The collected implementation failed trace extraction as recorded above; further
builds require a new request.

Source-only validation before this implementation: `nix build .#eciVivadoInputs` and
`.#checks.x86_64-linux.trace-autopipeline` / `.#checks.x86_64-linux.workflow-tools`
passed (`--no-link`). Simulation checks every 0..10 inserted-stage case with
payload/valid gaps and repeated reset. Five Python tests cover resolution,
artifact hashes, rejected maps and timestamp wrap; the Tcl walker fixture checks
inserted stages and rejection of logic/cycles/excessive depth. Generated RTL
has all 135 map endpoints and expected attributes; 354 NIC / 497 trace ports
and five 21-bit bundles match the wrapper. Logs: `/tmp/trace-autopipeline-final.log`
and Nix derivation logs (simulation `7wf6yajb`, workflow `36lnz2dh`, bundle
`nacvl1pd`). Those source-only checks did not launch Vivado or access a board.

Trace event transport now uses resetless source/sink FF banks with payload and
valid in one Vivado auto-pipeline group (limits 0/5/10 additional stages).
Source banks have distinct soft USER_SLR_ASSIGNMENT groups; sink banks join
SLR2. KEEP hierarchy and SHREG_EXTRACT=NO request FF transport preservation, but
the final optimizer removed three endpoint banks as recorded above. A downstream startup mask flushes uninitialized valid
bits. The whole i_trace_dma wrapper remains unconstrained.

The generated map is explicitly unresolved. The build Tcl and checkpoint-report
flow traverse final source-to-sink FF chains, require equal surviving bit depths
including valid, and emit the final map plus per-bit depth TSV beside the
bitstream. Maps include checkpoint/template/evidence/bitstream hashes; decoding
rejects unresolved maps and capture checks the bitstream match. Correction
covers this app-clock transport only, not variable async-FIFO delay. Actual insertion and placement are recorded above; automatic map extraction
failed and produced only partial depth evidence.

## Native UDP checksum removal: routed, setup timing fails

The one authorized manual Docker run used exact revision
`68ec9af8185b1d833dc10cca7c587db2817c1765`. Terminal state verified October 26
at 14:28 CET: `build.exit=0`, physical `CHECKPOINT_REPORTS_READY`, original
launcher 1304519 and build/report Vivado processes gone, both containers gone.
Supplemental read-only analysis also completed and its Vivado 1633524 exited.
No timers remain. No replacement build, board operation, toolkit-master edit
or further optimization was performed or authorized. The merged build ref
`timing/20261026-udp-no-checksum-impl` can now be removed.

- Preserved full/partial bitstreams and probes, seven top-level checkpoints,
  generated trace map, logs and final reports under the main checkout's
  `out/udp-no-checksum-impl-20261026/collected/`. All eleven bit/probe/checkpoint
  hashes match ba2. `SHA256SUMS` covers collected and supplemental evidence;
  `record.json` retains exact inputs, tests, provenance and baseline paths.
  Remote root remains `/tmp/lauberhorn-udp-no-checksum-68ec9af8-20261026`.
- Read the maintained digest first: final Physopt postRoute WNS **-1.232 ns**,
  TNS **-22953.258 ns**, **65842** failing setup endpoints. Compared with final
  `73c3dc76`: WNS worsened by 0.113 ns; TNS improved by 9059.002 ns and failures
  fell by 16418. WHS +0.001 ns and WPWS +0.039 ns, zero hold/pulse failures.
  All 1045128 routable nets are routed, zero routing errors. Build success is
  not timing closure and no functional acceptance was attempted.
- All-clock tables: application 200 MHz WNS -1.232 ns, TNS -18663.656 ns,
  46662 failing endpoints; clk_sys WNS -0.597 ns, TNS -4289.601 ns, 19180
  failures; clk_gt -> clk_sys WNS -0.003 ns, TNS -0.011 ns, four failures.
  Other reported clock pairs have no setup violations. Internal unconstrained
  endpoints are zero; one missing output-delay constraint remains as in baseline.
  Bus-skew checks pass. CDC structural counts remain 288/75/11/12/8461/1971
  for CDC-1/6/10/11/15/26; these are not proved functional defects. Exceptions:
  811 false paths, three clock groups, no multicycles; max-delay datapath-only
  constraints increased from 246 to 247. Zero unconstrained endpoints does not
  establish exception correctness.
- `app-top1000/` uses the same application-clock from/to selection and one path
  per endpoint as baseline. Candidate endpoint-family sample counts: scheduler
  321, TX routers 245, DCS 148, trace 108, other 79, RX routers 54, DMA 32,
  crossbar 11, RPC 2. These are bounded sample counts, not total failures/TNS.
  Worst trace-DMA saved-port-valid -> frame FIFO path: -1.232 ns, 22 levels,
  5.894 ns data delay (1.341 logic, 4.553 routing). Baseline/new sample comparison
  is in `app-top1000/family-counts.json`; exact paths are retained.
- Verified absence of APP_TIMING_FLOORPLAN markers in inputs/build log and all
  15 experimental pblocks in the actual checkpoint; 11 shell/coarse pblocks
  remain. Native UDP checksum pipeline/module and obsolete EciThreadClRouter
  selector are absent from exact inputs. Actual membership counts/ranges and
  per-pblock reports are in `app-top1000/regions.tsv` and `pblock-*.rpt`.
  Host-interface region: 44.46% LUT, 17.42% registers. SLR0/1/2 CLB occupancy:
  65.50/89.67/47.78%; LUT utilization 35.31/57.49/27.41%. SLR1 remains crowded.
- High-fanout report: three reset nets (38456/38453/34211) and trace FIFO control
  (10179) already use BUFGCE. CMAC reset net reaches 4232 loads; RX writeWord
  reaches 3107; trace frame-FIFO nextPort reaches 2523. RTL cleanup did not
  eliminate these fanout hotspots. No further intervention is authorized.
- Launch/preflight verified NativeUdpSim, full ECI elaboration, matching image,
  all 1183 input hashes, and clean RTL correspondence (only revision CSR differed
  from the tested snapshot). Preserve the unrelated audit checkout. This handoff
  is maintained on master in `/tmp/lauberhorn-rtl-congestion`.

Implemented checksum removal:

- Removed the native UDP pseudo-header/payload checksum accumulator and its
  seed/drain FSM stages, plus the now-unused `PacketChecksum` component/test.
  RX accepts UDP checksum fields without validation; TX continues emitting zero.
  IPv4 header checksums and native UDP length/shape/admission checks remain.
  Reserved rejection bit 7 remains zero, preserving diagnostic field positions.
- Removed only the obsolete `EciThreadClRouter_logic_*` floorplan selector that
  matched no cells. No placement regions were added.
- Updated native UDP integration coverage for corrupted nonzero checksum
  acceptance, zero checksum acceptance and zero TX checksum; malformed IP/UDP
  lengths and bad IPv4-header checksum remain rejection cases.

- Validation passed: `NativeUdpSim` end-to-end regression (one test covering
  datagram lengths 0–1472, malformed input, unchecked checksum acceptance,
  ownership, replay and segmented replies), plus full `eciVivadoInputs`
  elaboration and wrapper/debug-port checks. Generated RTL has no
  `NativeUdpDecoder_logic_checksumPipeline` or `PacketChecksum` module.
  Evidence: main checkout `out/udp-no-checksum-20261026/validation.json`,
  `test-report.xml` and logs. The separately authorized run is recorded above.

## RTL congestion implementation: failed initial routing

The user subsequently authorized one implementation run and timing collection
for the floorplan revert and RTL simplifications. Exact hardware revision:
`fdc689c5afd25098c7ad37f3af51b261b09a7796`. Build ref:
`timing/20261025-rtl-impl` (private push with CI skipped); keep it until every
build/report process terminates. This is a manual Docker run, not GitLab CI.
No board programming/acceptance, toolkit-master changes, optimization or
replacement build is authorized by the callback. Preserve the unrelated
`rx-trampoline-instruction-audit` main checkout; maintain this handoff on master
in `/tmp/lauberhorn-rtl-congestion`.

Terminal result verified October 26 at 03:58 CET:

- The exact run exited 1 at 00:39:43 CET during `route_design -directive
  AggressiveExplore`. Launcher 1022093, main Vivado 1022226 and synthesis
  1029152 are gone; Docker `1e072f3746a8` no longer exists and no Vivado
  process remains. `build.exit=1`; `physical/COMPLETE` is absent. No timers
  remain, and no replacement build was launched.
- Initial route verification found one unroutable native UDP checksum carry
  connection: `partials_3_reg[15]_i_20/CO[7]` to
  `partials_3_reg[18]_i_21/CI`, `SLICE_X101Y135/COUT` to `SLICE_X101Y136/AX`.
  Vivado reports that the driver cannot reach interconnect within five hops.
  This is a placement/carry-connectivity failure, not evidence of a logical
  checksum defect. Global congestion is level 6 (64x64), timing congestion
  level 7 (128x128); the placer also warned of high congestion.
- The 15 experimental APP_TIMING_FLOORPLAN groups are absent from inputs and
  build output. The older floorplan selector at line 153 for
  `EciThreadClRouter_logic_*` is empty (Vivado 12-1433); execution continued
  through placement. Its causal relationship to the carry failure is unproved.
- No routed DCP, bitstream, probes or final physical reports were produced.
  Thus there is no final all-clock setup/hold/pulse/CDC or app top-1000 result
  to compare with baseline `73c3dc76` (digest re-read: WNS -1.119 ns,
  TNS -32012.260 ns, WHS +0.001 ns, WPWS +0.039 ns). Intermediate slack is
  not a legal routed result. Actual region utilization and high-fanout reports
  were not generated; retained placed checkpoints permit later diagnosis.
- Preserved 212 files in the main checkout's `out/rtl-impl-20261025/collected/`,
  including logs, reports, five top-level checkpoints and the matching trace
  map. All five checkpoint hashes match ba2; `SHA256SUMS` and
  `failure-summary.json` record evidence. Original remote root remains
  `/tmp/lauberhorn-rtl-impl-fdc689c5-20261025`; preserve all artifacts.
- Clean bundle/image provenance and the three targeted simulations were verified
  at launch/preflight; all 1,183 remote hashes matched. Exact paths and validation
  scope remain in `out/rtl-impl-20261025/record.json`. The merged build ref can
  be removed now that every process terminated. Stop; no optimization, rebuild
  or board operation is authorized by this results callback.

Implemented RTL-only follow-up (merged as `fdc689c5`):

- Restored `vivado/eci/xdc/floorplan.xdc` exactly to `c95de205`, removing all
  15 added application hard pblocks while retaining the earlier coarse placement
  and September ECI crossing constraints. Retained the candidate's RTL stages
  and subsequent regression-fixture fixes.
- RX replay-mask updates are local to each word, eliminating the dynamic
  read/modify/write mux feeding the whole mask bank. Per-word validity replaces
  clearing 1,792 mask bits with clearing 28 bits per router. First writes replace
  stale masks; descriptor turnover and descheduling still invalidate replay.
- RX byte bounds use a shared remaining-length subtraction and 64 registered
  byte-valid bits in the existing alignment stage. Replay/masking consumes those
  bits in the next stage, with no additional latency or ownership/drain changes.
- Validation: targeted Nix/Mill `RxPayloadIsolationSim`, `RxWritebackSim` and
  `GsyncReleaseSim` pass (3 tests). The expanded isolation test covers partial
  write accumulation, zero strobes, new-packet/deschedule invalidation, lengths
  0/1/2/63/64/65/66/127/128 and stalled AXI responses. Full `eciVivadoInputs`
  elaboration passes NIC/trace/ILA/debug port checks. Generated Verilog confirms
  bank-local mask updates. No physical timing or congestion improvement has yet
  been measured for this RTL.

- Read-only placed-checkpoint review of `1ebe5c26` completed on ba2 with
  `tools/physical/checkpoint.tcl`; read its maintained digest before details.
  Evidence and validation logs: `out/rtl-congestion-20261025/` in the main
  checkout (`physical/summary.json`, `physical/high-fanout.rpt`, `validation/`).
  RX write-address bits drive 3,097–3,247 loads and replay selection reaches
  1,472–1,477 loads in that old netlist; bank-local mask updates target one
  contributor, but the wide replay RAM remains. Other hotspots include the
  trace FIFO control (10,314), CMAC reset logic (4,233), and aligner ready nets
  (1,396). Do not duplicate/reset-delay these controls without transaction and
  CDC analysis. The three largest reset nets already use BUFGCE resources.
- The old placed checkpoint has estimated WNS -7.719 ns and WHS -0.250 ns;
  it is mostly unrouted and is not comparable to the final `73c3dc76` baseline.
  Worst sampled logic includes reply-context retirement and scheduler deadline
  selection. Further pipelining there changes allocation/retirement or claim
  timing and is deferred rather than delaying immediate fault fencing. Neither
  these estimates nor the high-fanout report measures the new RTL. The implementation
  above failed before producing a routed result.

## Previous application-clock build: failed routing

The October 25 user request resumes application timing work after the TCP
simplification merged into master. Implement all remaining sampled path-family
fixes and finer placement, validate, launch **one** hardware build on ba2 with a
scheduled callback, then stop. Changes are merged; the one build failed routing.
That launch authorization is exhausted; the subsequent RTL-only request is
recorded above. No board acceptance or toolkit-master changes were part of
this launch task. Branch: `timing/20261025-app-closure`, based on
`c95de205`. Earlier paused-scope notes describe the September experiments only.

Baseline: `73c3dc76`, final checkpoint under
`/tmp/lauberhorn-phase4-73c3dc76-20261025/build/eci/vivadoProject.dest/` on ba2.
Live readback confirmed `build.exit=0` and physical `CHECKPOINT_REPORTS_READY`.
The maintained checkpoint helper already produced the preserved reports;
`tools/physical/summarize.py out/phase4-manual-73c3dc76/collected/physical --limit 3`
reports WNS -1.119 ns, TNS -32012.260 ns, 82260 setup failures, WHS +0.001 ns,
no hold failures, WPWS +0.039 ns and no pulse-width failures. Application-only
1000-path evidence is in `out/phase4-manual-73c3dc76/app-top1000/`.
This predates the merged TCP simplification and does not measure current source.

Plan and coverage (counts are the bounded endpoint sample, not total failures):

- RX routers (415), scheduler (128): eliminate repeated owner-code comparisons
  from fatal-release/readiness by capturing the immutable scheduler slot with
  owner installation. Preserve same-cycle fault fencing and assert slot/code
  agreement. Then check remaining deadline selection/capture and RX clearing
  paths; do not merely delay security/fault controls.
- Crossbar (182): split RX shift, replay and length masking from the crossbar
  return path. Preserve AXI IDs/last, stalled payload stability, writeback replay,
  quiescence and owner drain accounting when adding stages.
- TX routers (74), DMA (20): inspect source-matched endpoint validation/result
  feedback and descriptor arbitration; pipeline complete transactions or capture
  validation operands before wide checks. Current TCP removal may already remove
  some baseline chains; account for that explicitly.
- Trace (48), IP/RPC and other paths: inspect header shifters, checksum launch,
  reply-context validation and output ready chains. Use handshake-aware stages
  and retained packet/context identity. Changes in dependencies require their
  own feature refs; do not touch toolkit master.
- CMAC reset fanout (43), DCS (26), and routing-heavy families above: inspect
  actual cell placement/utilization and constrain finer communicating groups
  within application regions. Validate every selector on a real netlist, resource
  fit and conflicts with existing pblocks/static cells. No false paths or clock
  relaxation. Retain clk_sys -0.421 ns as an additional baseline failure outside
  the requested application-clock focus.
- TCP (15) and IP RX validation: map the removed checksum/validation instances
  against current source; retain transport regression coverage for the shared IP
  validation replacement.

Implemented source changes in hardware candidate `1ebe5c26`:

- Ownership captures the trusted slot alongside the lifetime; sticky-fault lookup
  selects that slot without delaying fencing. Installation asserts slot/code agreement.
- RX alignment, replay/masking and AXI response delivery occupy separate FSM
  stages, retaining the address/ID/last/owner until acceptance.
- Deadline selection samples generation/PID on the claim edge and captures the
  wide descriptor in a following state. CSR command generation responses split
  slot selection from increment and forward the pending result to reads without
  withdrawing an already stalled AXI response.
- TX descriptor arbitration has a FULL stream boundary. Both packet RAM AXI
  ports have channel slices separating arbitration/DMA from RAM decode.
- RPC local/remote metadata and route are captured before forwarding; remote
  payload waits for metadata acceptance. The actual baseline TX-result and IP
  checksum paths traversed this address-comparison/ready chain (not removed TCP).
- IPv4 TX registers the checksum header. Native UDP removes the duplicate IP
  checksum (shared IpDecoder already validates it) and stages pseudo-header seed
  terms. TCP header injection and trace output have FULL stream boundaries.
- Reply-context validation compares locally per slot before reduction.
- Floorplan groups cover workers, ownership/scheduler, crossbar plus wrapper read
  pipeline, packet RAM, CSR, DMA, IP/Ethernet, UDP, RPC, TCP/dispatch, CMAC control,
  trace dump, and each DCS response pipeline. Initial soft-group probe exposed
  hierarchical-shell warnings: this flow requires hard pblocks. The failed candidate
  set IS_SOFT false explicitly; final probe established sites and primitive capacity.

Evidence and launch preparation:

- Hardware candidate `1ebe5c26e8d5b4e137c7716427da1a34b860953f` is committed.
  Follow-up `38126dde` only initializes packet-type widths in four standalone
  router fixtures; all four pass. Branch is pushed privately with CI skipped.
- Final hard-region probe completed with `APP_TIMING_GROUPS_DISJOINT_READY`.
  All 15 selectors matched, with nonempty slice sites and expected parents;
  no peer nesting. Reports: `out/app-timing-20261025/groups-disjoint/`.
  Primitive capacities fit (maximum LUT 57.13%, packet RAM 32/36 BRAM).
  Baseline occupied CLB footprint exceeds several new regions and needs repacking;
  global clock capacity is not represented by report_utilization. Placement and
  clock feasibility must be established by the new build.
- Final CSR/policy regression passes all four tests, including success/stale/
  invalid commands and an AXI read held stalled across a command. Log:
  `/tmp/app-timing-csr-check-fixed.log`.
- Standalone GSYNC, RX payload isolation, RX writeback and TX payload isolation
  pass (four tests): `/tmp/app-timing-router-fixtures.log`.
- Recovery stream/in-flight, execution watchdog and reply contexts pass (four
  tests): `/tmp/app-timing-recovery-checks.log`.
- Trace buffers and Ethernet trace dump pass all 17 tests:
  `/tmp/app-timing-transport-trace.log`.
- Bulk ECI check completed: 52 passed, one ignored, four standalone fixture
  aborts (corrected run above passed), and one `pipelined-lockup` replay timeout.
  Log: `/tmp/app-timing-validated-checks.log`. Its frozen source is
  `/nix/store/7w909zb4ispg1d8ll0r24yvm3i0azb84-source`; RTL differs only in the
  final DeadlineRegisters forwarding, covered by final CSR tests. Exact hashes
  and comparison: `out/app-timing-20261025/validation-snapshots.json`.
- Replay diagnosis: candidate and parent c95de205 send 107 packets, receive 104,
  and retain exactly indices 75,80,104. Each is a 256-byte captured frame with
  IPv4 length 283 (requiring 297 frame bytes). Existing shared IPv4 validation
  correctly drops these; the old replay test expected all captures returned.
  Evidence: `out/app-timing-20261025/replay-diagnosis.json`. Same-seed diagnostics
  both completed with the same timeout: exec 37262/26271, logs
  `/tmp/app-timing-replay-{candidate,parent}.log`, setupSeed=-1219094768,
  simSeed=-2062986175. The corrected test asserts truncations never reach the
  host and checks IPv4 drop accounting. All nine corrected replay cases passed
  with that seed: `/tmp/app-timing-replay-corrected.log`, output
  `out/app-timing-replay-corrected`.
- Independent transport/trace check passed: eight transport/RPC tests and 17
  trace tests, `/tmp/app-timing-transport-trace.log`. No new RTL change was
  needed for these tests or replay expectations.
- Earlier superseded snapshots are not passing gates. One exposed zero IPv4 TX
  checksums after the extra stage; checksumReady/header.fire gating fixes that.
  The final bulk and transport snapshots include that fix. No hardware run was
  launched while these simulation issues were being resolved.
- Clean matching bundle and validation image built successfully from `1ebe5c26`:
  `out/app-timing-clean-inputs` and `out/app-timing-clean-inputs-1`.
  Embedded image manifest has the exact revision and local=false; its RTL matches
  the bundle. Generated NIC/trace ports and all debug probes match the wrapper.
  All 1183 bundle hashes were verified after staging on ba2 at
  `/tmp/lauberhorn-app-timing-1ebe5c26-20261025/inputs`.
- Exact artifact hashes, baseline, paths and callback scope are in
  `out/app-timing-20261025/record.json`. The one-off `launch.sh` uses the maintained
  Docker runner. `schedule.py` was dry-run reviewed for this thread
  (`01a1729c-f2e1-7f00-a2b4-5dbef0da662b`): preflight after 30 minutes, then results
  five hours from that check, then 30-minute rechecks if live. No replacement
  build, board access or further optimization is authorized by the callback.

Terminal result (checked October 25 at 17:30 UTC):

- Exact candidate `1ebe5c26e8d5b4e137c7716427da1a34b860953f` exited 1 during
  `route_design -directive AggressiveExplore`. Launcher 801843 and main Vivado
  801977 exited; container `113bac7e8358` no longer exists and no related build
  processes remained. No callback timers remain and no replacement was launched.
- The placer warned of high congestion; initial router congestion reached level
  7 (128x128). Route verification reported **56,099 unrouted signals** and
  **47,058 node overlaps**. DFX verification named partially conflicted native
  UDP aligner and CMAC RX ILA nets; overlap examples also include DCS-even logic.
  The exact contribution of individual pblocks has not been isolated.
- All 15 new floorplan groups had nonempty selector matches in the actual build.
  This establishes selection, not routability. Baseline primitive capacity fit
  did not establish routing capacity. Actual region utilization/membership beyond
  selector matches remains available for later inspection in the placed DCP.
- Router intermediate WNS was -13.419 ns, but routing was illegal: this is not a
  valid final timing result or a comparable replacement for baseline routed WNS
  -1.119 ns. No bitstream, probes .ltx, routed DCP, physical/COMPLETE or final
  timing/CDC reports were produced. Consequently the maintained final digest,
  all-clock setup/hold/pulse-width comparison and routed app top-1000 comparison
  cannot be produced for this failed run. Do not substitute pre-route estimates.
- Preserved 135 files under `out/app-timing-20261025/collected/`: build/launcher
  logs, reports, matching trace map and five synthesized/linked/optimized/placed
  checkpoints. All five checkpoint hashes match ba2. `SHA256SUMS`,
  `remote-checkpoints.sha256` and `failure-summary.json` record evidence.
  Original remote tree remains `/tmp/lauberhorn-app-timing-1ebe5c26-20261025`.
- Source and test fixes were merged through `6f79fc35`; subsequent handoffs do
  not alter that candidate. The merged timing branch can now be removed because
  all its processes have terminated. Preserve unrelated experiment/audit refs.
- The original launch scope is finished. The separately authorized RTL-only
  implementation is recorded above. No automatic replacement build, board
  programming or acceptance is authorized.

SSH, Nix, Git metadata and systemd user operations require sandbox escalation.
Use the maintained Docker bundle workflow because CI is unavailable. One-off
analysis and raw reports remain under ignored `out/` or `/tmp`.

## Retained September scope

Platform master adopted TX placement/refinement and high-VC RX staging.
Selected revision `1c72ea0` reached WNS −0.007 ns with 17 failing endpoints;
this is not timing closure. DCS/low-VC/combined experiments were left unmerged.
Toolkit commits must remain on separate feature branches/private refs pending
colleague upstream approval; never merge or push them to toolkit master.
See the [selected result and limits](../docs/hardware/physical-implementation.md#selected-september-17-result-and-limits).
The closeout did not establish new master timing closure or change the static shell.

Recorded board revisions also failed timing:

| Revision | Recorded timing / functional evidence |
| --- | --- |
| `5cda901` | F_MAC1 CMAC trace path; 300 RPCs and three full 322 MB dumps; WNS −0.062 ns |
| 516232 | WNS −0.102 ns; 893 failing setup endpoints |
| 518689 | WNS −0.950 ns |

See [CMAC board evidence](hardware-state.md#cmac-trace-readout-evidence)
and the [measurement evidence](measurements.md). Functional acceptance is not
routed timing closure. Before any authorized resumption, inspect actual refs and
saved batch results; do not infer current job state from this handoff.

## Retained experiment inputs

At September 17 closeout, toolkit `c92f0d9` was retained on
`timing/20260916-rx-elastic` and private platform ref
`timing/20260916-eci-toolkit-rx-elastic`. Low-VC `a194f3a` remained separate under
`timing/20260917-low-vc-stage` / private ref
`timing/20260917-eci-toolkit-low-vc-stage`. Platform experiment refs were
`timing/20260917-dcs-placement`, `timing/20260917-low-vc-stage` and
`timing/20260917-round2-combined`. Recheck actual refs before use; preserve
unmerged work and do not push to the colleague's toolkit master.

The authorized local batch was `lh-round2-20260917.service` on ba2, ordered
all, DCS, low-VC, TX refinement. Inputs:
`/tmp/lh-round2-inputs-20260917-{all,dcs,low-vc,tx-refine}`; outputs:
`/tmp/lh-round2-20260917-{all,dcs,low-vc,tx-refine}`, with `.console.log` and
`.exit-status` files. At closeout combined was finished, DCS running and the
other two queued. These are dated observations, not live service status.
On an explicitly requested resumption, inspect saved statuses and manifests in
`out/physical/round2-20260917/` before deciding whether any result is missing.
No new run or polling is authorized by this handoff.

Toolkit RX regression notes retain the pre-existing RSTP packetizer failure and
low-latency-mode GHDL driver issue; tested BRAM-mode staging does not resolve
those owner-side issues. Use current validation/CI procedures, not the retired
September timers or failed-submission commands. Original experiment chronology
is retained at `5e114527:docs/hardware/physical-experiments.md`.
