# Physical implementation experiments — 2026-09-10

## Round 2 first CI collection — September 17

One collection after the maintainer requested pipelines 511987–511990:

| Pipeline | Candidate | Hardware / reporting status | WNS ns | TNS ns | Failing setup endpoints |
| --- | --- | --- | ---: | ---: | ---: |
| 511987 | DCS placement | hardware 2833774 success; report 2833775 failed | -0.101 | -38.321 | 993 |
| 511988 | Low-VC RX | hardware 2833785 success; report 2833786 failed | -0.052 | -4.166 | 262 |
| 511989 | All increments | hardware 2833796 and pipeline success | -0.460 | -360.504 | 3437 |
| 511990 | TX refinement | hardware 2833807 still running | pending | pending | pending |

Both failed report jobs stopped before their scripts on ba7: registry connection
refused / authentication endpoint HTTP 502 while pulling the pinned Nix image.
These are registry availability failures, not missing hardware reports or the
previous `when: always` scheduling problem. Hardware artifacts contain COMPLETE
physical reports; the collector successfully generated their summaries locally.
Only report-job retries are needed to repair those pipeline statuses.

Previous combined CI `f6516f4` was WNS -0.057 ns, TNS -7.584 ns, 426 failing
endpoints. Low-VC-only is the most promising completed increment: clk_sys is
-0.039 ns, while a 34-level RX-clock ILA path sets global WNS -0.052 ns. DCS-only
regresses global timing, dominated by TX crossbar-to-credit logic at -0.101 ns.
All increments together regress substantially: trace DMA valid-to-frame-FIFO
BRAM is -0.460 ns (25 logic levels), with ILA and NIC decode paths also failing
on the application clock; clk_sys is -0.059 ns. Do not promote this combination
or attribute the regression to one increment before the pending TX result and
local comparisons. These are single implementations with distinct revision CSRs.

All three completed candidates have zero failing hold/pulse endpoints and zero
routing errors. WHS is +0.001 / 0.000 / 0.000 ns respectively. CDC diagnostic
counts match the previous baseline except CDC-26 drops 1971→1966 in candidates
with low-VC isolation; this is not a CDC correctness claim. Reports and failed
job logs are under `out/physical/round2-20260917/ci/`. No new hardware runs or
retries were launched by this status check.


## Round 2 ablations and combination — September 17

The maintainer requested individual next changes and their combination in the
same batch. All four start from combined TX/high-VC RX revision `f6516f4` (local
WNS -0.011 ns, CI -0.057 ns); there is no new unchanged-baseline run.

| Increment over `f6516f4` | Branch | Revision | CI pipeline / hardware job |
| --- | --- | --- | --- |
| DCS destination placement | `timing/20260917-dcs-placement` | `b5c38a1` | 511987 / 2833774 |
| Low-VC RX isolation | `timing/20260917-low-vc-stage` | `c44887b` | 511988 / 2833785 |
| TX link2 placement refinement | `timing/20260917-tx-refine` | `1c72ea0` | 511990 / 2833807 |
| All three increments | `timing/20260917-round2-combined` | `759a9ab` | 511989 / 2833796 |

Initial one-shot snapshot: all pipelines created, checks running or pending,
hardware waiting on prerequisites. No timing result exists yet. Preserve these
unmerged experiment branches. The static shell and ECI protocol remain unchanged.

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
Owner approval remains required before adoption on master.

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

### Local flow and collection

Portable bundles are built with explicit immutable Git revisions, matching the
CI candidates. The ba2 local batch is ordered **all, DCS, low-VC, TX refinement**,
with sequential full Nix/Docker builds to limit resource contention. Each runs
through synthesis, implementation, bitstream and physical reports, with its own
console log and exit status. All four bundles built and were verified against
their exact revisions. The local queue launched as
`lh-round2-20260917.service` on ba2. Inputs are
`/tmp/lh-round2-inputs-20260917-{all,dcs,low-vc,tx-refine}`; outputs are
`/tmp/lh-round2-20260917-{all,dcs,low-vc,tx-refine}`, with `.console.log` and
`.exit-status` appended for each run. The queue continues to the next candidate
if a build fails and retains each status. No board is programmed.
Manifest and evidence: `out/physical/round2-20260917/` (`cases.json`, `ci/`,
`bundle-build.log`, `bundles.json`, placement inspection and functional logs).
Collect CI once on completion notification; no polling or new board run.
Different candidate revisions change embedded CSR constants, so treat the
single-factor comparisons as practical ablations, not perfectly controlled
placement-seed comparisons. Within each candidate, local and CI use the same
revision and Nix RTL recipe. Require setup/hold/pulse, all clocks, CDC and routing
checks before any closure claim.


## Completed TX/RX comparison — September 17

Collected once on maintainer request. Individual TX/RX pipelines 511729/511730
and combined retry 511864 all succeeded, including their regression gates.
The three local Docker builds exited zero through bitstream generation and
retained COMPLETE physical reports. The first combined pipeline 511830 remains
failed before hardware; its test-startup fix is recorded below.

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

### Remaining work, based on combined routed paths

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
validate the next actual candidate in both flows. No new PnR or board run was
launched for this review. Toolkit-owner approval is still required before
adopting the unmerged RX dependency.

Evidence: `out/physical/resume-20260916/ci/{2832030,2832041}/reports/`,
`out/physical/combined-20260917/relaunch1/2833087/reports/`, and
`out/physical/combined-20260917/local/physical/`. Earlier local reports remain
under `out/physical/resume-20260916/local-{tx,rx}/physical/`.


## Combined CI failure and recovery — September 17

Initial combined revision `820da59`, pipeline 511830, never reached hardware:
`blocks-tests` job 2832734 failed the trace DMA “writes events to DRAM” test.
All other regression gates, including toolkit RX, passed. The missing physical
metadata error in report job 2832743 was consequential.

The recorded seed reproduces a test-startup overflow: stimulus accumulates during
synchronous reset. Blocks fix `cc4b0a5` waits for reset-qualified sampling before
producers start, retaining traffic generation and all loss/data assertions.
It changes no RTL and is published only on private dependency branch
`fix/20260917-blocks-trace-dma-test`. The complete five-test DMA suite passed with
the failing seed and three additional seeds (20 cases total). See the
[validation note](../development/validation.md#trace-dma-reset-startup-regression-september-17).
Failure, instrumented reproduction and fixed-run logs are retained under
`out/physical/combined-20260917/dma-fix/`. The replacement run retains the same
TX placement, RX pipeline and static shell; revision CSR constants still change.


The full committed Nix blocks gate also passed (17 tests). Replacement revision
`f6516f4` was submitted as [pipeline 511864](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/511864), hardware job 2833087.
Initial snapshot: prerequisite checks running, hardware awaiting prerequisites.
The exact same revision's Nix bundle was staged and launched on ba2 via
`lh-timing-combined-20260917.service`. Inputs:
`/tmp/lh-timing-inputs-20260917-combined`; outputs:
`/tmp/lh-timing-local-20260917-combined`; console and exit status use that output
prefix with `.console.log` and `.exit-status`. No timing result exists yet.
The new manifest is `out/physical/combined-20260917/cases.json`; the failed
submission is preserved in `cases-first-submission.json`. Collect once on
completion notification; no CI polling loop was launched. The test-only fix
was adopted on master; the TX/RX experiment and toolkit feature remain unmerged.


## September 17: combined TX placement and RX pipeline

Branch `timing/20260917-tx-rx-combined` starts from RX candidate `eeeabf4`
and adds the exact TX floorplan from `abe57e4`. Both CI infrastructure fixes
(native deployment-script construction and shared Vivado license defaults) are
retained. No additional toolkit RTL changes are introduced: dependency `c92f0d9`
remains on its separate private feature branch, pending toolkit-owner approval.
The combined candidate remains unmerged and uses the unchanged static shell.

Both original local Nix/Docker runs completed through bitstream generation with
exit zero. TX (`c4174e5`) reports WNS/TNS −0.122/−75.192 ns and 1,660 failing
setup endpoints. RX (`720bd1b`) reports −0.066/−16.698 ns and 585 failing setup
endpoints. Both have zero hold/pulse failures and identical CDC diagnostic
counts; RX's reported minimum hold slack is 0.000 ns. Neither closes setup.
These are independent candidates with different embedded revision constants;
their improvements cannot be assumed additive or reproducible.

The combined CI run retains the RX VHDL prerequisite and existing regression
checks. Collect its routed setup/hold/pulse, clock, CDC and routing reports before
judging improvement. Local evidence is under
`out/physical/resume-20260916/local-{tx,rx}/`; combined submission metadata is
under `out/physical/combined-20260917/`. No board test is implied.


Submitted revision `820da59`: [pipeline 511830](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/511830), hardware job 2832742. Initial snapshot: regression checks running, hardware awaiting prerequisites; no failures. Nix RX regression check passed locally before submission.


## Active campaign: September 16 ECI TX/RX candidates

Timing work resumed from master `2890f3c` after the Nix refactor. The maintainer
requested actual candidates through both local Nix/Docker builds and CI; the
previously proposed identical-input baseline repeats were canceled. One baseline
attempt stopped during project/IP creation and supplies no timing evidence.

| Candidate | Platform branch / commit | CI pipeline / hardware job |
| --- | --- | --- |
| TX placement | `timing/20260916-tx-placement` / `abe57e4` | [511729](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/511729) / 2832030 |
| RX elastic stage | `timing/20260916-rx-elastic` / `eeeabf4` | [511730](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/511730) / 2832041 |

Both candidates remain **unmerged**. Both local runs completed; see the September 17 results above.
No new board result is available. Static-shell v0.1.5 and its DCP remain unchanged.
The September 15 reports below remain the historical comparison baseline.

### First local result and CI packaging recovery

The canonical Nix/Docker flow completed synthesis, implementation, bitstream/LTX
creation and COMPLETE checkpoint reports for TX revision `c4174e5`, exiting zero.
Its completion event started the local RX run at original revision `720bd1b`.

| Local TX result | Value |
| --- | ---: |
| WNS / TNS | −0.122 / −75.192 ns |
| Failing setup endpoints | 1,660 |
| WHS / failing hold endpoints | +0.003 ns / 0 |
| WPWS / failing pulse endpoints | +0.039 ns / 0 |
| Routing errors | 0 |
| Application-clock / trace TX slack | +0.042 / +0.344 ns |

Compared with job 2824643, WNS improves 0.083 ns and |TNS| falls 75.4%, but this
is a single implementation with different embedded commit constants. **Setup
closure is not achieved.** Worst sampled paths are RX FIFO/control at −0.122 ns,
TX crossbar input high/low classification to arbitration mask at −0.121 ns,
and crossbar-to-credit-buffer data/enable paths at −0.119/−0.116 ns. RX pipelining
alone may therefore leave TX arbitration/credit paths critical.

A completed, bounded query of all four output-buffer-to-static-transport families
confirms positive setup slack: link 1 high/low **+0.247/+0.109 ns**, link 2 high/low
**+0.061/+0.131 ns**. This verifies that the targeted boundary paths pass in this
implementation, rather than inferring it from their absence in the global sample.
Reports: `out/physical/resume-20260916/local-tx/tx-boundary-results/`.
CDC diagnostic counts exactly match job 2824643; they remain structural findings,
not proof of CDC correctness.

The first CI pipelines (511631/511633) passed every regression gate, including
RX VHDL, but failed deployment preparation in jobs 2831283/2831304: the script-only
`lh-test` derivation incorrectly required an AArch64 builder. Their hardware jobs
were skipped; report-job missing-artifact errors were consequential. Fix
`f98e23c` writes the script on x86_64 while retaining the AArch64 interpreter and
runtime packages, and asserts the builder architecture during evaluation. The
full local image build and 3,497-link/ELF checks passed; the helper's x86_64
builder and AArch64 Bash were inspected explicitly. See the [CI note](../development/ci.md#deployment-script-builder-architecture).

The table above records the latest CI submissions with both packaging and license fixes.
Local inputs remain their original candidate revisions; no local PnR was restarted.
Consequently local and replacement-CI RTL are **not identical-input repeats**:
the new commit changes embedded CSR constants despite unchanged timing source.
Keep that limitation when assessing reproducibility. Exact original/replacement
manifests and failed logs are under `out/physical/resume-20260916/`.

### Second CI failure: license endpoint, now corrected

Pipelines 511715/511716 passed every test and deployment preparation, confirming
the packaging fix. Vivado synthesis then failed on ba1/ba4 in jobs 2831913/2831924
with `Common 17-345` (no Synthesis/xcvu9p license); no routed checkpoint exists.
The report-job failures are secondary, not additional timing failures.

The pinned image's only license server, `2100@hacc-lic-01.inf.ethz.ch`, failed a
read-only connection check. Local Docker inherited the host's longer license
search path, including the working `8181@lic-xilinx.ethz.ch` server. Fix `98921da`
sets a shared CI/local default with ETH first and HACC as fallback, while honoring
explicit host overrides. It passed the Nix workflow check (ShellCheck and 12
tests) and a real xcvu9p synthesis in a fresh bridge-network container using the
pinned image, with `LICENSE_SYNTHESIS_PASS` and exit zero. See the
[license recovery note](../development/ci.md#license-server-recovery-september-16).

The table now lists the resubmissions with this fix. `cases-packaging-relaunch.json`
preserves the previous revisions; the active `cases.json` drives the existing
04:31 CEST one-shot collector. Local RX remains running and was not restarted.
No RTL, toolkit, static shell, license files or runner configuration changed for
this recovery. Toolkit-owner approval remains outstanding.

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

### RX candidate and toolkit ownership

Toolkit branch `timing/20260916-rx-elastic` contains prerequisite regression
commit `c60ca71` and pipeline commit `c92f0d9`, based on `9dd94de`. Toolkit master
is untouched. For CI access, the commits are retained only in the **private
platform project**, branch `timing/20260916-eci-toolkit-rx-elastic`; the platform
RX experiment temporarily points its toolkit submodule URL there. Nothing was
pushed to the colleague-owned toolkit remote. **Owner approval is required before
adopting these toolkit changes into platform master.** Preserve the branches for
that review, regardless of timing outcome.

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

### Local Docker runs and collection

Both clean candidate bundles built through Nix. On ba2:

- TX inputs `/tmp/lh-timing-inputs-20260916-tx`, output
  `/tmp/lh-timing-local-20260916-tx`, service `lh-timing-tx-20260916.service`;
  the canonical Docker runner completed and returned exit status zero.
- RX inputs `/tmp/lh-timing-inputs-20260916-rx`, output
  `/tmp/lh-timing-local-20260916-rx`. The event-based
  `lh-timing-rx-20260916.path` starts its service when TX writes its exit-status
  file; RX is now running. Local runs are sequential to limit memory contention with CI.
- Each uses its original committed candidate input revision, the pinned
  Docker image and Vivado 2025.1. The local runner retains build exit status and
  invokes checkpoint reporting after routing. Compare local versus replacement CI results
  with the revision/CSR caveat above; do not confuse these with baseline repeats.

Evidence and exact CI manifest: `out/physical/resume-20260916/`. The initial
snapshot found TX running and RX pending, without failed jobs; hardware jobs
were still waiting on prerequisites. No CI polling loop was started.
`lh-timing-collect-20260916.timer` was stopped after the CI failures. Replacement
`lh-timing-collect-20260916-relaunch1.timer` on the development host collects once
at **September 17, 04:31 CEST**, using `collect-once.sh` in that evidence directory.
It fetches CI archives and local reports, then generates digests. A notification
can justify an earlier manual collection. The timer does not wake an LLM session
or submit another experiment; pending jobs are left pending.

Next: read all four report digests, compare setup/hold/pulse width, routing,
clock/path families and CDC. If each isolated change helps its intended family,
prepare a combined experimental branch; keep toolkit-owner approval outstanding.
Reliable closure requires clean routed results across the relevant runs, not a
successful CI status or one improved global WNS. Board validation remains later.

## Resume point: PnR/QoR work deferred for Nix refactoring

Updated 2026-09-15. The Nix packaging/shared interactive-test prerequisite is
complete: see [the validation record](../development/nix-refactor-validation.md)
and [the new workflow](../development/interactive-testing.md). Board tests used
matched historical CI hardware; they do not validate final-master timing or
hardware. No new timing candidate or PnR run was launched during the refactor.

When resuming timing work:

1. Read [the September 15 results and ranked experiments](#september-15-completed-prewarmed-nix-master-builds).
   Baselines are jobs 2824618, 2824636 and 2824643; reports are in
   `out/physical/review-20260915/JOB/`. Start with `summary.json`, then inspect
   one representative per family. DCPs remain in the linked CI artifacts.
2. Keep **all proposed changes in the application/dynamic partition**:
   TX placement in `vivado/eci/xdc/floorplan.xdc`; RX extractor/packetizer or
   channel buffering in `vivado/eci/eci-toolkit/hdl/`; secondary NIC pipeline
   work in `hw/` and application crossing logic in `vivado/eci/rtl/dcs_cdc.sv`.
   The similarly named `vivado/eci/static-shell/eci-toolkit/` is a different copy:
   do not edit it for these experiments. Preserve static-shell v0.1.5 and its DCP.
3. TX paths end in the fixed static transport. First inspect legal dynamic sites
   and try per-link output-buffer placement near that boundary. A static-shell
   change is a separate future decision if application-side fixes are insufficient;
   it is not part of the agreed first experiments.
4. Test RX elastic pipelining independently, with focused VHDL handshake/credit
   validation before expensive implementation. Keep TX placement and RX pipeline
   on separate goal branches. Recheck the Nix handoff after refactoring so generated
   RTL, dependency gitlinks, headers and deployment software still match.
5. Run checkpoint analysis with Vivado 2025.1 on **enzian-ba2**. Use the documented
   bounded queries and identical-input repeatability baseline. Submit CI only after
   local checks; each implementation takes about 3–4 hours. Collect once after
   notification or a one-shot timer, never poll. Board validation is a later step
   using the verified-reset routine and matching software.

The latest builds have positive trace TX slack but negative ECI setup slack;
none establishes timing closure or board correctness. Preserve that distinction
when resuming. The September 13 experiment status below is historical; those
three replacement hardware jobs were ultimately skipped before implementation.

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
