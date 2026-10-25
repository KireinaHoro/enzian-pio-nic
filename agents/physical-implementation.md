# Physical implementation continuation

## Active application-clock closure round

The October 25 user request resumes application timing work after the TCP
simplification merged into master. Implement all remaining sampled path-family
fixes and finer placement, validate, launch **one** hardware build on ba2 with a
scheduled callback, then stop. No board acceptance or toolkit-master changes are
part of this launch task. Branch: `timing/20261025-app-closure`, based on
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
  hierarchical-shell warnings: this flow requires hard pblocks. Current source
  sets IS_SOFT false explicitly; final probe established sites and primitive capacity.

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

Remaining: merge/push with CI skipped; launch the staged candidate exactly once, verify actual Vivado start,
install and verify the 30-minute callback, record evidence and stop. Hardware has
not launched and no callback is installed yet. Preserve the timing ref until all
build processes terminate. Do not change toolkit master.

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
