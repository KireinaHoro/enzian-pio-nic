# Physical implementation experiments — 2026-09-10

**All four relaunched pipelines passed**, collected 2026-09-13. Bitstreams and
routed checkpoints are retained locally. None meets setup timing; these are
implementation results, not new board tests. See
[checkpoint findings](physical-findings.md) for the motivating measurements.

| Experiment | Platform commit | Pipeline | Hardware job |
| --- | --- | --- | --- |
| control | `5d0692a4` | [510097](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/510097) | [2817813](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2817813) |
| tx-output-fifo | `62deb5b8` | [510099](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/510099) | [2817821](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2817821) |
| priority-tree | `a616eba2` | [510098](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/510098) | [2817817](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2817817) |
| tx-slr2 | `7234c181` | [510100](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/510100) | [2817825](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2817825) |

## Completed implementation comparison

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

**Next concrete constraint issue:** `floorplan.xdc` matches
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

## Report hook recovery

The runner logged `Ignoring malformed RUNNER_AFTER_SCRIPT_TIMEOUT timeout: 1200`
and terminated every after-script at its default timeout. Use **`"20m"`**, not
`"1200"`; this correction is staged locally in all four experiment worktrees.
No rebuild was launched for report recovery. Retained CI `timing.rpt` and route
reports are valid, but missing COMPLETE/paths/CDC means the extra analysis was
incomplete. `summarize.py` now handles that case explicitly and emits a partial
digest from the retained timing summary.

Archives, CI traces and partial reports are under
`out/physical/experiments-20260910/relaunch1/JOB/`. Recovery uses the same DCPs
on enzian-ba2 under `/tmp/lauberhorn-sta-20260913/`, without rerouting.
All four recoveries completed on September 13; read `JOB/recovered/summary.json`
first. Their global timing metrics exactly match the retained CI reports. Each
retains 200 setup and 200 hold paths plus CDC/bus-skew diagnostics. CDC counts
remain broadly similar: the output-FIFO variant shifts 577 reports from CDC-15
to CDC-26; CDC-11 is 12 in control and 13 in the variants. These structural
diagnostics are not a demonstration of new or resolved functional failures.

## Independent changes and provenance

Branches use prefix `timing/20260910-`:

- `control`: shared extractor fix, extra trace regression gates and retained STA reports.
- `trace-tx-output-fifo`: enable only the trace TX async FIFO's output FIFO to
  break CMAC-ready propagation into the RAM reader.
- `trace-priority-tree`: use a hierarchical lowest-set-bit mask instead of
  subtraction-based priority logic in blocks' `TraceBufferDMA`.
- `trace-tx-slr2`: assign the trace TX FIFO to the existing SLR2 region, near
  CMAC; fail explicitly if the XDC selector finds no cells.

Dependency fix `7174002eee48dfbb91eaa61cc732181ebf8007e1` is retained in the
**same private GitLab platform project**, branch
`timing/20260910-blocks-extractor-fix` (dependency-only CI skipped). Priority uses
merge `9ce5bedcafb6de140b67e1c443a36761f06e4a2b` on
`timing/20260910-blocks-priority-tree`.
All four experiment branches temporarily fetch blocks from the private platform
project. Nothing was pushed to GitHub. The main worktree's upstream URL remains
unchanged; publish the dependency fix to its normal upstream or preserve its
private source URL before making that gitlink a permanent platform change.

## Local validation and CI gates

The original seeded partial-header failure reproduced locally. The fix:

- drives standalone tests' `outputAck` for payload and header-only/partial packets;
- counts incomplete packets in their discard state, where no acknowledgment exists;
- preserves the dispatch barrier, tested by delaying acknowledgment;
- tests deterministic short/header-only boundaries, including multi-beat headers
  followed by valid traffic.

Checks actually run locally: seven focused extractor tests; all 17 blocks tests
on both the fixed baseline and combined priority variant; all 29 fast ECI tests
(five suites) on the fixed baseline; seven trace-responder tests on the combined
variant. All passed. Logs: `out/physical/extractor-fix/`.

Hardware CI still waits for fast ECI, trace and applicable full blocks tests.
Failure artifacts now match Mill's actual `out/**/simWorkspace/` paths and retain
transcripts/waveforms. CI retains generated RTL/XDC, routed DCP, bitstream/LTX,
final timing/utilization and `out/physical/` analysis. The report hook has a
20-minute allowance; missing COMPLETE means report collection failed even if the
hardware job succeeds, since after-script failures do not fail a GitLab job.

Compare trace TX data/ready/reset paths, priority/capture paths, ECI paths, setup
endpoint counts, hold, bus skew and CDC—not only global WNS. Embedded commit CSRs
still differ and can perturb synthesis. Repeat promising results, then run
verified-reset board tests with matching software. Existing trace-responder tests
do not directly exercise the modified async FIFO under CMAC stalls; targeted
FIFO/reset testing remains necessary before claiming its functional validation.
Upstream `verilog-axis/tb/axis_async_fifo` tests parameterize OUTPUT_FIFO_ENABLE.

## First submission: why every hardware job was skipped

| Experiment | Old pipeline | Failed blocks job | Skipped hardware job |
| --- | --- | --- | --- |
| control | 510064 | 2817646 | 2817647 |
| tx-output-fifo | 510065 | 2817650 | 2817651 |
| priority-tree | 510066 | 2817654 | 2817655 |
| tx-slr2 | 510067 | 2817658 | 2817659 |

Fast ECI and trace jobs passed in all four. The full blocks suite failed long-header
and Ethernet tests with 40,000-simulation-time-unit timeouts, and short-packet
counter checks with `0 did not equal 1`. The unchanged control failed too.
Standalone tests never drove the extractor's required outputAck; incomplete
packets bypassed the state that incremented their counter. Adding blocks-tests
to hardware `needs` exposed this baseline problem and blocked Vivado. The original
platform hardware job depended only on fast ECI.

Original logs/job metadata: `out/physical/experiments-20260910/failures/`.
Original artifact globs missed files below Mill's worker sandbox; that is also
corrected in the relaunched branches. No test gate was removed.

## Scheduled collection — no polling loop

The obsolete 21:38 timer was stopped. Its replacement,
`lauberhorn-ci-timing-20260910-relaunch1.timer`, runs once at
**2026-09-10 23:52 CEST**, four hours after relaunch. That pass found builds
still running. The user notified completion on September 13, triggering one new
collection pass; all four pipelines and hardware jobs succeeded.

It invokes `tools/physical/collect_ci.py` using the four exact commits in
`out/physical/experiments-20260910/cases-relaunch1.json`. One status pass records
pipeline and hardware states, saves failed test-job logs if present, downloads
available terminal hardware-job archives, extracts reports, and writes snapshots
under `out/physical/experiments-20260910/relaunch1/`. Pending builds are recorded
and left alone. There is no automatic rescheduling, flashing or LLM-session
wake-up; the timer depends on this host/user manager remaining available.
The GitLab token is read locally and used only for GET requests.

```sh
systemctl --user list-timers lauberhorn-ci-timing-20260910-relaunch1.timer
journalctl --user -u lauberhorn-ci-timing-20260910-relaunch1.service --no-pager
# Read saved results after the scheduled pass:
cat out/physical/experiments-20260910/relaunch1/latest.json
# A later manually scheduled/authorized pass, if needed:
python3 tools/physical/collect_ci.py out/physical/experiments-20260910/cases-relaunch1.json \
  --token-file ../.gitlab_token --output out/physical/experiments-20260910/relaunch1
```

The collector supports `--snapshot-only` for initial job discovery. Existing
archives are reused by job ID. DCPs/bitstreams stay in each job's `artifacts.zip`;
extracted `reports/summary.json` is the first file to read.

## September 13: Nix pipeline and reset-placement comparison

The new campaign holds the locked Nix test/RTL/software build and artifact-only
Vivado flow constant ([CI contract](../development/ci.md)). The control retains
the old placement and TX FIFO setting; the other cases use exact instance
selectors to keep main CMAC resets in SLR0 and trace CMAC resets with the TX FIFO
in SLR2. The combined case also enables the trace TX FIFO output stage.
The priority-tree experiment is not incorporated.

Local validation passed: 29 fast ECI tests, 17 blocks tests, 12 trace tests, and
the standalone FIFO bench with both output-stage settings under stalls/reset.
The matching deployment SquashFS and portable Vivado input bundle built with Nix.
Vivado project/IP creation passed on ba2 inside the pinned hosted tools container
using only the bundle and the mounted Vivado 2025.1 installation. These checks
do not establish routed timing closure or board RPC correctness.

The first three pipelines failed before tests: Nix's independent Git cache
could not authenticate the private blocks revision, despite successful runner
checkout. Logs are in `out/physical/experiments-20260913/results/`; original
commits are preserved in `cases.json`. The fix trusts the runner checkout and
provides a GitLab-scoped credential helper using the ephemeral CI job token.
A local helper check verified host scoping and absence of the token value from
configuration. The read-only personal API token is not used by this helper.

Replacement runs pushed September 13 at 10:48 CEST:

| Case | Commit | Pipeline | Hardware job |
| --- | --- | --- | --- |
| Control | `02c44fb` | 510565 | 2822463 |
| TX FIFO + trace resets in SLR2 | `ddc37d3` | 510567 | 2822477 |
| SLR2 placement + output FIFO | `f3e53d9` | 510566 | 2822470 |

Initial snapshot: pipelines running/queued, hardware not yet started.
Manifest: `out/physical/experiments-20260913/cases-relaunch1.json`;
results: `out/physical/experiments-20260913/relaunch1/`.
The obsolete collector timer was stopped. Replacement
`lauberhorn-ci-nix-20260913-relaunch1.timer` collects once five hours after
relaunch (approximately 15:49 CEST), without polling or automatic rescheduling.
No new timing or hardware-test result is available yet.

## September 15: completed prewarmed-Nix master builds

Collected once after notification. All three hardware jobs succeeded and retained
COMPLETE STA reports from Vivado 2025.1. These are not board-test results.

| Job | Commit | WNS ns | TNS ns | Failing setup endpoints | Trace TX slack ns |
| --- | --- | ---: | ---: | ---: | ---: |
| [2824618](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2824618) | `c5373ce` | -0.174 | -190.802 | 3249 | +0.106 |
| [2824636](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2824636) | `f6bf910` | -0.396 | -1543.965 | 11631 | +0.170 |
| [2824643](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2824643) | `faf6790` | -0.205 | -306.122 | 4559 | +0.297 |

All have zero failing hold/pulse endpoints and zero routing errors. Minimum hold
slack is +0.002/+0.003/+0.002 ns. CDC counts are identical across these runs,
including 288 CDC-1, 10 CDC-10 and 13 CDC-11 diagnostics; they are not CDC-clean.
Trace TX values are the intra-clock `txoutclk_out[0]_1` representatives.

The combined reset placement/output-FIFO changes have moved the observed critical
paths away from trace TX. This is encouraging, but is not an isolated measurement
of either change: toolchain and generated commit constants also changed since the
September 10 experiments. Between these three revisions, tracked changes are only
CI/docs; embedded commit CSRs still perturb synthesis. The 0.222 ns WNS spread
and 3249–11631 failing endpoints prevent attributing timing differences to the
minimal container or declaring reproducible closure.

### Next experiments, in priority order

1. **TX placement, before a protocol change.** Latest worst path is
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
   the current Spinal simulation gates do not establish toolkit correctness.
3. **Keep secondary clocks/families visible.** Application-clock slack is
   +0.002/-0.175/+0.001 ns. The failing run has AXI AW-valid to PacketBuffer BRAM
   address; the first run still has the 22-level trace priority path at +0.002 ns.
   Revisit Spinal AXI address/control pipelining only against a representative path,
   preserving AW/W association and responses. The old priority-tree candidate is
   not a proven global improvement. DCS FIFO-control to SLR slice CE also reaches
   -0.174 ns; inspect `rtl/dcs_cdc.sv` and crossing placement before changing CDC.

First compare narrow path families on the existing DCPs using ba2, then submit
independent placement and RX-pipeline CI branches after their respective checks.
Use the exact same Vivado input bundle for a repeatability baseline: rebuilding
at another commit changes CSR constants even without datapath edits. Keep the
bundle's real revision marker and matching software; do not disable deployment
revision checks. Report WNS/TNS, endpoints, all clock representatives, hold and
CDC alongside each candidate. No new implementation or board run was launched
for this review.

Local evidence: `out/physical/review-20260915/JOB/` contains timing, 200 setup/hold
path samples, detailed setup paths, CDC, route, bus-skew and generated summary.
The GitLab hardware artifacts retain the DCPs and bitstreams. Read each summary
before requesting more paths; a global 200-path sample is not a family-wide TNS.
