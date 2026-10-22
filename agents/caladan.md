# Caladan baseline continuation

Maintain all port documentation here in platform. The sibling Caladan repository
keeps source and navigation only. **CS-A64 means Caladan Simple on AArch64**,
using mlx5 directpath, not a Shenango simulation. Upstream x86 behavior remains
preserved; no x86 calibration campaign is requested. An earlier collection used `d6a6be20f78ae0dac14c033cb427126f1f62159b` on `enzian-port`.

Human references: [baseline](../docs/research/caladan-enzian.md),
[ARM differences](../docs/research/caladan-enzian.md#arm-port-differences),
[build/tests/tracing](../docs/development/caladan.md).

## Resident base echo preparation — October 22

The maintainer selected the ongoing dataset as the separate **UDP-spawner**
variant and requested resident workers for base echo. Keep campaign r4 running
on its immutable spawner image; do not deploy or collect resident data yet.

Caladan `enzian-port` `8b7a063` propagates UDP socket RX boundaries through the
trace-only `udp_read_from_traced` API and carries socket TX trace slots to the
existing PMD doorbell/export markers. Microbenchmarks master `84c68c0` defaults
to `executionModel="resident-workers"`; the explicit `"udp-spawner"` option and
Caladan `echoSpawner`/`echoSpawnerTrace` targets preserve the other variant.
The resident pool has `runtime_max_cores()` user threads, created before readiness,
sharing one UDP socket. Request stacks are reused; socket RX copying, queue
locking, wakeups and TX credit accounting remain measured costs. Watchdog and
all three hot kthread settings are retained. Future campaign manifests must set
`caladan_execution_model: "resident-workers"`; model mismatches and mixed archive
series/chunks are rejected. Existing roots omit this key and retain spawner IDs.

Validation in `out/caladan-resident-preparation/validation.json`: four AArch64
builds (both models × probes off/on), shared RPC tests under QEMU, probe-free
symbol gates, 112 campaign tests, Nix composition using the updated lock, and
visual review of spawner-labelled unloaded/partial loaded previews. Both new
server variants compile at O3 with target/LTO flags. Initial parallel compiler
process crashed; bounded-parallelism rebuild succeeded. No native hardware run.

Next: validate resident readiness and worker distribution with real traffic,
confirm shared-socket buffering/loss/restoration, and validate RX/TX boundary
coverage and exported request counts under probes. Explicitly review activity
counter costs and the unchanged watchdog before drawing performance conclusions.
Only then collect a fresh resident dataset; retain spawner data separately and
never relabel those acquisitions as resident.

## Next checks and integration constraints

Stack-allocation failure repair is committed as `62a953e` on `enzian-port`.
`runtime/stack.c` now recognizes raw negative mmap and mprotect errors, matching
the assembly syscall ABI and the existing base memory allocator. The focused
test fails on the original code and passes on native x86 and AArch64/QEMU; it
checks four errno values, guard-failure unmapping, partial-batch rollback and
repeated failure followed by successful reuse. All eight existing ARM CPU
checks and the optimized ARM runtime build also passed. Evidence is
`platform/out/caladan-stack-fix-20261022/`. Built outputs are
`/nix/store/bkswgsvihxb2i89a4lyv30zwzj0vp5yi-caladan-arm-context-checks` and
`/nix/store/97wwgh5rwi77v7nm9v6ij01vf6vlmzq6-caladan-directpath-aarch64-unknown-linux-gnu-0.1`.
The patched static echo variants are built and staged for the authorized full
loaded continuation, with reviewed reuse and a merged comparison archive; see
[active measurements](measurements.md#active-full-hot-campaign--october-21).
The selected Caladan steps are now 20k RPC/s for 8/128 B and 5k for 1432 B,
matching Lauberhorn. Collect all 12 payload/worker cases before assessing whether
that grid is sufficient. The old 10-second 50k point passed, whereas its
60-second confirmations collapsed, so it does not establish sustained capacity.
Keep the inherited watchdog and hot thread settings.

New builds enable ThunderX targeting and LTO; default launches now omit
`no_hw_qdel`. Existing datasets retain their original compiler/configuration
provenance. Before new acquisition, check CX-5 clock-page access, advancing CQ
timestamps and sensible measured queue ages on Enzian. Previous runs with
`no_hw_qdel` do not validate that path. No live deployment changed with this edit.
Validation: optimized ARM off/trace builds and explicit QEMU shared-RPC checks
passed; native x86 traced build/RPC checks and six Caladan campaign tests passed.
The cross backend now invokes its checks explicitly because cross stdenv disables
`doCheck`; prior successful cross-builds alone did not establish test execution.

Hot campaign generation now sets `runtime_kthreads`,
`runtime_guaranteed_kthreads` and `runtime_spinning_kthreads` to the worker count
and records them in runtime metadata. Existing datasets retain their original
settings; do not relabel them as resident-worker results. The maintainer has
authorized a fresh full hot rerun with these settings (see below). Later cold
microbenchmarks must vary all three settings and compare against Lauberhorn;
that sweep still needs implementation. Inspect runtime metadata and residency
evidence from the fresh hot run.

Busy-owner signal/IPI preemption, zero-core wakeup and contention/core
reclamation remain checks for competing-tenant scheduling experiments. The hot
campaign uses one application with a guaranteed worker pool and makes
no contention or reclamation claim. Controller/backend selection and both probe
modes are integrated on platform master; matching Tofino and loaded-concurrency
acceptance gates paper acquisition. Caladan memcached is a main
paper comparison, with implementation/evaluation still pending in this record.
Recheck source revisions and live reservations before resuming. This documentation
migration launches no build, CI, deployment or measurement.

The platform composition manifest still labels acceptance `pending-arm-port`;
that static package field does not summarize the later finite evidence below.
Update it deliberately with packaging changes rather than treating it as a test
receipt. Controller, importer and plots select the canonical `CS-A64` label.

## Provisioning safeguards

The earlier reservation of **zuestoll04** with volume
`caladan-enzian-z04-20261016`, cloned from golden `2025-07-28`, is historical
provisioning evidence, not the selected CX-5 target. It was acquired powered off
with boot bundle `pengxu/lauberhorn-6.8.0-146-ptp/{vmlinuz,initrd.img}`; no Caladan
boot or deployment was performed. No release or reassignment is implied by this
scope update. Recheck its state before cleanup. For a new board use an explicitly
selected golden image and a checked-unused, distinct root-volume name: EMG can
silently reuse names and couple active exports. Never reuse the z04 volume for
another active board. The deploy skill's golden-image/name safeguards remain.

## Focused-check artifact provenance

Final tested image (including the network hold):
`/nix/store/5m0c4lx4yh8hzyfl4i1d99rzmmmnixln-caladan-focused-tests.img`, SHA256
`3b17d60e6df857b7ca106d05eac3a950ff765e05ac793b7c87c20f3b522e95e0`.
The one-/two-kthread layout checks used
`/nix/store/6pbcd0w7v3h3gv1k1f20j98br4zn7jdr-caladan-focused-tests.img`, SHA256
`58dbcaaa123d4591114ee03ea1f36ca55f04d532db499d787a18bf7f362a9da6`.
Builds include all eight QEMU checks; the final native x86 build also passed.
These artifacts were built before committing their source as `586a5a1`.

Local evidence: `../caladan/out/hardware-20261020-01/`, including
`cpu-tests.json`, `module-smoke.json`, `layout-smoke.json`, `peer-ping.json`,
`peer-neighbor.json`, `postflight.json`, `reclaim.json`, image provenance and
build logs. Failed startup diagnostics are retained alongside successful runs.

Postflight stopped owned test processes and the temporary image server,
unloaded both scheduler modules, restored CX-5 down, restored
`nr_hugepages=0` and `shm_rmid_forced=0`, and reclaimed only test-prefixed DPDK
hugepage files (final Hugetlb zero). cpuidle returned to `none`. At that postflight observation the read-only test image was still mounted at
`/nix/store`, with diagnostic files in `/tmp`; later echo cleanup is recorded below.

## Masked-SGI probe

On zuestoll07 CPU 46, a temporary matched-kernel probe masked IRQs
(`DAIF=0xc0`), disabled the architectural timer event stream, and executed
`LDAXR/WFE` on a separately allocated, otherwise untouched page. CPU 45 sent
an asynchronous Linux cross-call SGI after approximately 25 ms and a rescue
SEV at approximately 133 ms. In all three SGI trials the waiter remained asleep
until SEV; the SGI callback ran after IRQ restoration. Three SEV-only controls
also woke at rescue. Thus masked SGIs do not provide the required WFE wakeup
on this ThunderX configuration. The module was unloaded and per-core timer/IRQ
state restored; no new kernel diagnostics appeared. Source/build/results are in
`../caladan/out/wfe-sgi/`, with definitive evidence in `result-3.json`.
Earlier attempts are retained: immediate returns required retry handling and
ordered timestamps, and shared probe bookkeeping caused false monitor wakeups
until the watched word was isolated on its own page.

## Echo and trace evidence

Target: ARMv8 ThunderX + mlx5 directpath, simple policy, with continuous bounded
idle polling retained. Shared workload lives in `../microbenchmarks/server/caladan`.
Canonical documentation now lives in platform.

The adapter uses Caladan's UDP spawner, one user thread per datagram, and
`get_uthread_specific()` for request context. Handler, schema, XDR codecs and
synchronous RPC path are shared with DPDK. Software probes are compiled out.
No kernel socket fallback or substitute scheduling loop is introduced.

October 20 functional check: zuestoll07 DUT/CX-5 `0005:90:00.0`, control44,
dataplane45, two runtime kthreads on46/47, one guaranteed core, BE priority.
Runtime IP `192.168.128.207`, UDP9000. Peer zuestoll14 NICVF data interface
`cpu40g1`, IP `192.168.129.193`, generator lcore16. Static runtime ARP entry
points to peer MAC `0c:53:31:03:01:c1`; generator targets CX-5 MAC
`b8:ce:f6:87:c2:9e`. No root-volume or boot changes.

Shared DPDK generator passed echo0/8/128/1432, each 16 checked requests plus
four warmups. This is a correctness smoke test, not performance data. ARM and
native x86 builds pass shared RPC payload/XID/failure/ownership checks; ARM
checks run under QEMU. The package checks that software trace hooks are absent.

Evidence and one-off scripts: `../caladan/out/echo-integration/`. ARM image:
`/nix/store/7bfpr5f0pq72dimc9dy9vdqf1cg73gn9-caladan-echo.img`;
client image `/nix/store/66m20vqd012adn7aw7as6vaadkndmyg3-lauberhorn-client.img`.
See `hashes.json`, `image-2.json`, `native-2.json`, and extracted final result
JSON files. Native x86 remains a build regression check, not a calibration target.

Multi-request hot-load acceptance uses the shared paced-batch generator and
probe-free server. Busy-owner preemption/core reclamation is separate from that
resident-pool scope. No latency/throughput conclusions follow from these small
functional runs. Recheck live reservations before further board use.

Shared backend source: microbenchmarks `8d87fb0` on `caladan-backend`.

Postflight: the generator's network/isolation changes were restored by the
shared runner (all four case summaries report `restored=true`). DUT server and
IOKernel stopped, modules unloaded, CX-5 returned down, hugepage/sysctl values
restored to zero, no new kernel faults. DUT's previous read-only image mount
was restored; peer's temporary image unmounted; staging HTTP server stopped.
`dut-stop.json` and `peer-stop.json` contain cleanup and client provenance. The
client manifest hashes match the shared schema, codec and generator sources.


## Stage tracing

Trace-enabled runtime/backend and shared collector adapter are implemented.
`echoTraceImage` uses the platform collector hooks now merged into platform
`master` (`3ddf640b`). Select this checkout’s `sw` with `--arg rpcSource
/path/to/platform/sw` when building against these hooks. The importer is
`tools.paper.campaign.import_caladan`; it emits the canonical `CS-A64` label.

RX timestamps follow the mbuf through regular/strided CQ consumption and UDP
spawning; TX uses a persistent request slot through overflow and completes at
mlx5 submission. Shared RPC probes/collector are reused, with user-thread-owned
inline context and short, non-inlined publication guards around pthread TLS.
SIGRTMIN+4 exports quiescent captures; runtime preemption signals are unchanged.
The probe-free build removes packet fields and timestamp calls at compile time.

Evidence: `../caladan/out/echo-trace/` (echo8) and its `0/`, `128/`, `1432/` subdirectories.
On z07/z14, echo8 produced 144 rows (128+16 warmup); other sizes each produced
20 (16+4 warmup). All 204 rows pair exactly with client XIDs, have monotonic
boundaries and conserved CPU partitions, with zero collector drops. Export used
the dedicated real-time signal. Each case restored both boards; no new kernel
faults. `cohort.json` files deliberately have `paper_inputs=false`: no Tofino
capture was collected. Tested image:
`/nix/store/ma9s63qqz7wpphz419ykz4k1bhv4jldd-caladan-echo-trace.img`.

Final builds also add explicit worker IDs and a package manifest with trace mode
and source hashes. `release-build.json` identifies the packaged image and
probe-free backend; `native-final.json` records the x86 trace build. Shared RPC
checks pass on ARM/QEMU and native x86. Collector tests cover original/realtime
signals, overflow and duplicate publication; importer tests reject bad XIDs,
stage ordering and lossy export. No performance conclusion follows from these
functional captures.

The unattended controller now accepts a CS-A64-only matrix and uses the shared
Caladan lifecycle adapter. Tofino off/breakdown acceptance and loaded activity
checks precede the full hot matrix. Busy-owner/core-reclamation stress is
separate from this guaranteed-pool campaign.

All postflight statements above are dated October 20 observations, not current
board state. Preserve original artifacts and failure diagnostics; recheck
[hardware state](hardware-state.md) and reservations before any board action.
The original platform note is retained at `f247539c:docs/research/caladan-enzian.md`;
the imported notes are retained in Caladan Git at `d6a6be2`.

## Unattended hot campaign integration

Platform master now has explicit system selection, the Caladan image composition,
shared role lifecycle, probe-mode import, resident chunking and selected-matrix
plot/finalization checks. The shared backend activity export is committed in
microbenchmarks `dfa04eb`, integrated into `master`. Caladan itself
is `e05e580` on `enzian-port`, the maintainer-selected branch. Platform and
microbenchmarks use `master`; all three are pushed to their private remotes.
A duplicate Caladan `master` ref was created before that branch preference was
clarified; it contains the same commit and is not the active checkout. Campaign Python tests: 80 passed in
`out/caladan-integration/tests-launch.log`; the AArch64 image build includes the
shared RPC/QEMU checks and probe-free symbol gate.

Accepted wire matrix: `out/caladan-wire-acceptance-20261020-r2` (six cohorts,
100 measured + 128 warmup requests each). Both instrumentation modes passed
exact Tofino pairing, trace-loss/partition checks and restoration. Reviewed
previews and PDF/font checks are in `out/caladan-integration/preview-reviewed/`.
Matched hardware manifest: `out/caladan-integration/hardware-fixed.json`.
The first root preserves a rejected traced export: an activity helper inherited
an unblocked trace-export signal. The backend now blocks both export signals
before creating helpers. `transition-review.json` in the new root records the
artifact transition, intact receipt hashes and confirmed restoration. No reset
or FPGA access occurred. The prior successful off8 cohort remains functional
evidence, not paper data. The first root had no controller code snapshot and is
not a source for automatic receipt inheritance.

Placement: IOKernel control42/dataplane43; workers44–47 (one through four).
Only workers are boot-isolated. The generator uses isolated16–47. The additional
IOKernel CPUs must be included in resource accounting. Simple-policy workers
are guaranteed/resident; this does not establish competing-tenant preemption,
zero-core wakeup or reclamation. Counter export includes warmups and runs after
client drain; software timing is compiled out for loaded trials.

Launched full configuration: `out/caladan-integration/full.json`, with six
10,000-request unloaded cohorts and twelve loaded configurations (three payloads,
one through four workers). The frozen grid has 120 initial points, geometric
extensions through overload and sustained confirmations. Full unloaded
collection/plots must finish before loaded acquisition. Loaded acceptance is
enforced by the actual acquisition/import/activity gates; no loaded result is
claimed yet. Keep baseline-only previews in their archive
(`publish_plots: false`) until a combined comparison is reviewed. The original
LH/DPDK paper figures remain in place.

The NIC health guard also rejects missing or changed RX/TX error counters; its
six targeted Caladan tests passed (`out/caladan-integration/tests-health.log`).
Full service: `echo-campaign-122293d0b1a2.service`; root
`out/caladan-hot-full-20261021`. Launch returned successfully. No post-launch
progress polling was performed. Terminal callbacks target the requesting thread;
inspect on callback or user request. The full dataset is not yet claimed complete.

The full campaign is stopped at 12/138 cohorts: six unloaded and six loaded
points, followed by two rejected echo8/one-worker/125,000 RPC/s attempts. Both
had zero handler activity and client replies despite passing generator schedule
and admission checks. The intermediate preflight failure and explicit restoration
review remain in `out/caladan-hot-full-20261021/recovery-live-check/`; both original
failed attempts and review JSON files are preserved. The paper campaign's hardware
hold remains set. Do not resume it unchanged.

Bounded diagnosis in `out/caladan-loaded-diagnosis/` identifies receive-watchdog
starvation. Three fresh-process echo8/one-worker diagnostics used the original
probe-free binary, 32 generator CPUs, one second warmup and three seconds traffic,
plus five seconds of post-drain observation. Read-only `/proc/PID/mem` sampling
at 250 ms captured existing scheduler, RX/TX and handler counters. Symbol addresses
and kthread statistics layout were checked against the exact ELF/disassembly;
snapshots are approximate, not atomic or timing measurements. `analysis.json`
records counts and evidence hashes; `summarize.py`, the one-off helper and run
scripts retain reproduction details. All trials are explicitly non-paper data.

With the watchdog enabled, 50,000 RPC/s also collapsed in this diagnostic: one
handler ran during traffic, while RX continued and the runnable ring stayed full.
At 125,000 RPC/s, zero handlers ran during traffic. After traffic stopped, the
server drained 69,654 requests/replies; its RX hardware-drop counter reached
430,162. Thus zero exported activity in the original short drain window did not
mean no reception or a permanently stuck generator. Earlier success at 50,000
is not a reliable capacity threshold.

The causal comparison changed only runtime configuration (`disable_watchdog 1`),
with the same sampler and binary at 125,000 RPC/s: all 125,000 warmup and 375,000
measured requests completed on time, no generator misses/admission failures or
RX hardware drops, and 500,000 final handler calls/replies. All three role pairs
passed restoration and archive integrity checks. No reset, FPGA access, binary
change or paper-campaign hold release occurred.

Mechanism: `runtime/sched.c` checks the 50 us watchdog before runnable handlers;
`softirq_run_locked()` queues mlx5 receive at the head. Receive batches create up
to 32 UDP handler threads. When RX work repeatedly crosses the watchdog interval,
it keeps taking priority over those handlers. This ordering is inherited from
upstream, not newly introduced by the port. Fresh stack allocation is a possible
amplifier, not separately profiled. The maintainer selected default watchdog
behavior for the paper baseline; do not fix the scheduler or disable the watchdog.
Loaded all-zero activity is now accepted as loss when the existing acquisition
checks pass; contradictory client replies still fail. Grid-wide worker coverage
and positive standalone-readiness/unloaded checks remain required. Qualifying
latency/goodput curves stop at the first >1% loss point; collapse stays in the
loss curve, with no fabricated latency for zero replies.

The original campaign must not resume unchanged. The original failed
attempts remain failed records; no receipts or evidence were relabelled, and the
original hardware hold remains set. Local campaign regression tests passed (82);
fixture plots verified cutoff/recovery/zero-reply behavior, fixed PDF dimensions,
embedded fonts and visual review. The preserved original attempt-2 payloads pass
the corrected local gates as zero goodput/100% loss; no receipt was rewritten.
No hardware run was made for that orchestration change. Historical unloaded
data retains its original configuration and provenance.

Full unloaded chart integration is complete in
`data/paper/echo/hot-unloaded-20261021.tar.gz`: 18 cohorts combining historical LH/DPDK
and CS-A64, 10,000 measured observations per cohort, with raw evidence hashes
verified during archive/render. Both paper PDFs include all three systems;
fixed-column dimensions, embedded fonts, conserved breakdowns and visual review
passed. The current paper source includes both filenames. Loaded acquisition
remains stopped as above; these figures make no loaded-performance claim.

## Fresh hot rerun

The maintainer authorized rerunning all Caladan hot unloaded and loaded data.
Launched `out/caladan-hot-rerun-20261021` as
`echo-campaign-cd7037336846.service`; preparation and frozen inputs are in
`out/caladan-hot-rerun-20261021-preparation/`. No measurements are inherited.
Six unloaded cohorts (10,000 measured requests each) precede the 120 initial
loaded points across three payloads and one through four workers, with geometric
extensions and sustained confirmations handled by the controller.

The image is `/nix/store/1a38dq2mscsl70k05jx7mki3d5xd4rh1-caladan-campaign.img`,
SHA256 `d5fca85d78708544ad8e2bf178201ae722abe3f05476970323fa951177a71ccf`.
It includes ThunderX tuning/LTO; the runner enables hardware queue delay,
sets maximum/guaranteed/spinning counts to workers, and retains the watchdog.
The generator binary is unchanged. Historical switch integration evidence is
hash-validated; every new cohort must pass current acquisition gates.

All 82 local campaign tests passed. Live preflight passed reservations,
isolation, artifact hashes, budgets and callback reuse. The read-only switch
clock check passed; server-facing link state is preserved in preparation.
Live restoration checks verified the previous failure's role archives and idle
hosts without reset; the old campaign's hold and receipts remain unchanged.
The first attempt stopped at chunk 004 of the first unloaded cohort when leaf0
SSH stopped responding; chunks 000–003 completed and cleaned up. Server runtime
metadata confirmed maximum/guaranteed/spinning counts of one. No whole cohort
completed and loaded collection had not started. Restarting only the owned SSH
control service restored access. Its helper now drains terminal output after
authentication (Tofino commit `6715a56`, 58 tests passed); PTY backpressure is a
supported failure mechanism, not a proven diagnosis of this specific stall.

`recovery-switch-export`, `recovery-clock` and `recovery-live-check` preserve the
recovery evidence: no ready/journal/owner files for the failed session, free
capture lock, empty selectors, zero counters, passing clock check, verified role
archive hashes and idle/restored hosts. No switch/board reset or capture-state
clearing was performed. The campaign resumed under the same service and frozen
measurement configuration, retaining attempt-1 and rerunning the incomplete
cohort. Completion is not yet established. Do not poll: inspect on a terminal
callback or user request, and visually review final plots. Baseline-only plots
stay in the campaign archive until combined review.

The requested unloaded-only stop has completed: six full cohorts (8/128/1432
bytes, off/breakdown, 10,000 measured observations each). Final validation at
`stages/26edcf559781b0087596/attempt-1/result.json` passed, all evidence hashes
were rechecked, and both final previews were visually reviewed. Hardware state
is safe. The legacy controller reports `failed` at loaded because the adapter
refused that stage before any acquisition, exactly as requested; its log at
`stages/18f3042c1965452ac95b/attempt-1/log.txt` confirms the intentional stop.
No loaded traffic was started. Preserve the original status/attempt records;
no restart is needed merely to change their label. Keep `control.json` with
`stop_after: unloaded` until the revised loaded grid is explicitly authorized.
