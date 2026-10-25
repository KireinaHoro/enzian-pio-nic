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

Implemented source changes, still uncommitted pending validation:

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

Evidence / live work:

- `out/app-timing-20261025/` contains one-off probes and the draft callback helper.
  Initial placement probe completed, `placement.tsv` remote basename copied as
  `app-timing-placement.tsv`; all 1000 baseline endpoints were mapped including
  the small CSR, packet-memory and top-level read-enable groups.
- Initial group check (session 42361) completed. Reports in `groups/` include
  soft-property warnings and zero reported availability for nested worker groups;
  these are NOT accepted validation of the final hard constraints.
- Final disjoint hard-group probe completed with
  `APP_TIMING_GROUPS_DISJOINT_READY`. All 15 selectors matched with nonempty slice
  sites and expected SLR/hostif parents; no peer nesting. Reports and resource
  checks are in `out/app-timing-20261025/groups-disjoint/`. Primitive capacities
  fit (maximum LUT 57.13%, packet RAM 32/36 BRAM). Baseline occupied CLB footprint
  exceeds several new regions, requiring repacking; this is not a placement proof.
  Global clock availability is excluded by report_utilization; its BUFG rows
  cannot establish clock capacity. New placement/clock feasibility remains a build gate.
- Current bulk Nix regression/input build exec session `98362` is live, log
  `/tmp/app-timing-validated-checks.log`, links `out/app-timing-validated-checks*`.
  Includes fast-tests-eci, transport-pipeline, scheduler-policy, trace-tests and
  eciVivadoInputs. Its source is `/nix/store/7w909zb4ispg1d8ll0r24yvm3i0azb84-source`.
  All current Scala matches it EXCEPT final DeadlineRegisters response forwarding
  and the added DeadlineRegistersSim. Final constraints also postdate this bundle.
- Final CSR/policy check passed all four tests (session 93693), log
  `/tmp/app-timing-csr-check-fixed.log`, output `out/app-timing-csr-check-fixed`.
  It covers final response forwarding and stalled-read regression, including
  successful, stale and invalid commands. The first fixture used two slots
  and failed elaboration; corrected to three slots, matching production shape.
  Snapshot comparison and current RTL hashes are recorded in
  `out/app-timing-20261025/validation-snapshots.json`.
- Earlier ownership/rtl/final jobs (12196, 59762, 5933) were explicitly interrupted
  as source changes accumulated. Snapshot 75141 exposed an actual IPv4 TX checksum
  failure: the extra checksum stage let the injector accept a header with checksum
  zero. Fixed by checksumReady gating and header.fire advancement. Its four TX
  failures are preserved in `/tmp/app-timing-complete-checks.log`; it was stopped.
  Current 98362 includes that fix. No interrupted run is a passing gate.
- RpcLocalRouteSim checks both routes under metadata/payload stalls and changes
  IP address/input metadata after acceptance. It is staged so Nix includes it.
- DeadlineRegistersSim checks success/stale generation readback and holds an
  unrelated AXI read stalled while a command executes; valid/data must persist.
- No hardware build launched, no callback scheduled. `schedule.py` is a draft
  one-off helper for this thread (`01a1729c-f2e1-7f00-a2b4-5dbef0da662b`), with
  30-minute preflight, then five-hour results, then 30-minute rechecks. It requires
  final `record.json`. `codex queue --help` and local user timers were checked;
  no Lauberhorn timers were active. Callback scope is timing/artifact collection,
  no automatic rebuild or board access.

Remaining: finish checks/fix failures, prove final group selector/capacity validity,
review all source/docs, commit/merge/push per AGENTS with CI skipped, build clean
matching eciVivadoInputs and tcpValidationImage, verify hashes/provenance, stage
and launch exactly one Docker hardware build, verify actual Vivado start, schedule
and verify 30-minute callback, record launch evidence, stop. Preserve baseline
and experiment refs; no toolkit source has changed.

Operational notes: SSH and Nix require sandbox escalation (sandbox SSH reports
config ownership, Nix cannot connect to daemon); escalated SSH successfully
read ba2. Git metadata is read-only in sandbox; escalated git switch created the
branch. Use maintained Docker bundle workflow because the TCP continuation
records CI unavailable; recheck relevant state before launching. Existing manual
launch and callback examples live in `out/phase4-manual-73c3dc76/`. Keep one-off
analysis under ignored out/ or /tmp, and complete matched clean-revision artifact
preparation and callback verification before ending the launch task.

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
