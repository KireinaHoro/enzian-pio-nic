# Caladan baseline continuation

Maintain all port documentation here in platform. The sibling Caladan repository
keeps source and navigation only. **CS-A64 means Caladan Simple on AArch64**,
using mlx5 directpath, not a Shenango simulation. Upstream x86 behavior remains
preserved; no x86 calibration campaign is requested. Current source at collection
was `d6a6be20f78ae0dac14c033cb427126f1f62159b` on `enzian-port`.

Human references: [baseline](../docs/research/caladan-enzian.md),
[ARM differences](../docs/research/caladan-arm-port.md),
[build/tests/tracing](../docs/development/caladan.md).

## Next checks and integration constraints

Busy-owner signal/IPI preemption, zero-core wakeup and contention/core
reclamation remain checks for competing-tenant scheduling experiments. The hot
campaign uses one application with a resident guaranteed worker pool and makes
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
microbenchmarks `dfa04eb` on its existing `caladan-backend` branch. Caladan itself
is `e05e580` on `enzian-port`. Campaign Python tests: 80 passed in
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
