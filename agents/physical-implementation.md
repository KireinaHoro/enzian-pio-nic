# Physical implementation continuation

## Native UDP checksum removal

The user authorized removing native UDP checksum calculation and retaining the
stale floorplan-selector cleanup. Work branch:
`timing/20261026-remove-udp-checksum`, based on `b2e72295`.
No replacement implementation or board operation is part of this change.

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
  `test-report.xml` and logs. No implementation run was launched.

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
