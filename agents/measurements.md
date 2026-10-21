# Measurement continuation

## Timestamp accounting implementation — October 21

Hot unloaded accounting version 3 separates allocation, codec decode/encode,
TX-buffer reservation, backend lookup, reply preparation and named runtime
handoffs for LH, DPDK and CS-A64. See the
[exact timestamp/formula contract](../docs/development/rpc-breakdown.md).
Platform pins microbenchmarks master `067d50d`; Caladan's existing packet probes
supply its queue/network/thread-ready boundaries, with no runtime-tree change.
Historical paper datasets and temporary preview outputs retain their original
accounting; version 3 cannot be mixed with legacy coarse RPC breakdown cohorts.

Local validation: 89 campaign unit tests; LH AArch64 runtime trace/off builds;
Caladan AArch64 trace/off builds with shared RPC tests in both modes under QEMU;
native and AArch64 DPDK trace/off builds, with native shared RPC and mlx5 probe
checks. Compiled-out symbol gates pass. Local build receipts and test logs are
in ignored `out/rpc-breakdown-timestamps/`; these are software checks, not new
acquisition evidence.

**Do not launch or resume campaigns:** timestamp implementation was requested
without collection. The later reservation-only request reacquired zuestoll14;
see [hardware state](hardware-state.md#october-21-generator-reacquisition). No hardware access or acquisition
was performed for this change. Fresh matched unloaded acquisitions are needed
to produce version-3 numerical comparisons once collection is authorized;
the loaded grid still needs maintainer revision. Existing stop-after-unloaded
campaign control remains in place.

## Loaded chart accounting

The completed loaded source is `out/echo-loaded-shared-20261019/loaded.json`:
24 LH/DPDK cohorts, all three payload sizes and all four worker counts. Use
full-matrix validation (no `--partial`) when regenerating the paper figures.
`out/echo-full-20261019/loaded.json` is an older LH-only partial file and must
not replace the combined figures. The inventory points to the completed source.

Loaded percentiles use all transmitted requests, with deadline failures at
infinity and inverse-CDF nearest rank. The latency curve draws an upward
infinity ray at the last point before the worst-trial 1% loss cutoff. Revised
statistics and reviewed previews are in `out/loaded-loss-aware-full/`. No new
measurements were taken. Local campaign suite: 90 tests pass, including the
exact 1% loss boundary and zero-reply P99.

## Echo contract and artifact constraints

Active paper microbenchmarks use schema-defined echo RPCs at 8/128/1432
application bytes, loaded and unloaded. Follow the [canonical contract](../docs/development/benchmark-campaign.md)
and [campaign skill](../tools/paper/skills/benchmark-campaign/SKILL.md).

| Measurement | Software probes | Collection | Reported interval |
| --- | --- | --- | --- |
| Unloaded CDF | Compiled out | Tofino server-facing MAC pairs; FPGA recording frozen, NIC PTP off | Wire-to-wire |
| Unloaded breakdown | Enabled | Separate Tofino cohort; FPGA recording frozen, NIC PTP off | Wire-to-wire plus CPU durations and combined residual |
| Loaded | Compiled out | No switch capture, FPGA readout or NIC PTP | Client RTT |

Follow the [wire methodology](../docs/research/hot-measurement.md). The older
FPGA-MAC/PTP acquisition contract applies only to its recorded campaigns below.
Full collection requires hash-bound real-workload wire acceptance; inspect that
receipt before launch rather than treating a dated blocker as live status.

Use the paper's commented echo filenames for replacements and retain active PDFs until validated
replacements exist. Leave presentation changes uncommitted as instructed;
check their actual worktree before editing. Recheck reservations and
[hardware state](hardware-state.md) before board access.

## Echo validation evidence

The campaign guide at `a19331dc:docs/development/benchmark-campaign.md` records
the following compact-observation validation (no new acquisition was run during
this documentation migration):

Targeted board validation passed nine 1-second-warmup/1-second-measured cohorts
(seven DPDK, two LH), with no generator misses, admission failures, malformed
replies or health/restoration failures. At 6,568,491 RPC/s, the fresh detailed
control took 358 seconds including import and retained 411 MB; two aggregate
trials took about 85 seconds and retained 20 KB each. All requests were on time.
Detailed median/P99 were 72.58/122.55 microseconds; aggregate medians were
73.25–73.55 and P99s 123.55–125.25 microseconds. These short comparisons establish
similar behavior and export savings, not statistical equivalence or sustained
peak capacity. The LH overload check accounted for all 256,292 transmissions as
176,487 successes plus 79,805 explicit rejections, with a 78.55 microsecond P99,
matching the earlier detailed P99. Original raw evidence and artifact identities
remain in `out/echo-redesign/histogram-resolution/`; `comparison-summary.json`
records the diagnostic scope and the final maximum-XID stride correction.
Historical detailed rehearsal cohorts remain eligible for explicit reviewed
reuse; full paper acquisition will use fresh aggregate observations.

The same guide records local controller and rendering fixtures:

The local service-crash fixture exercised an actual SIGKILL and ExecStopPost
notification delivery to a file-backed sink. All five plots also passed portable
synthetic-input rendering, exact PDF dimensions and embedded-font checks. These
fixtures are excluded from paper inputs and are not board acceptance.

On resumption, inspect the preserved validation artifacts and current frozen
configuration before selecting reuse or fresh acquisition. These fixtures do
not substitute for real-workload switch acceptance. Dated boot placement is in
[hardware state](hardware-state.md#october-18-echo-topology-observation).

## Recorded latency evidence

- October 5 buffered DPDK hot/cold cohorts: 4,000 measured RPCs; kernel-off RTT
  medians 12.24 µs hot / 43.36 µs cold. Hot CPU-work analysis and reproducible
  static counting are recorded in the [consolidated evidence](../docs/research/roadmap.md#evidence-and-remaining-claims)
  and [516232 comparison](../data/eci/board-evidence/dpdk-buffered-hot-cold-516232-20261005.json).
- October 10 inline-TX correction on unchanged 518689 hardware: 4,000 measured
  hot/cold RPCs plus 32 warmups, one control forward/ack and no overflow
  invalidation per measured TX. Kernel-off hot RTT 12.760 µs; MAC latency
  4.165 µs versus old 3.700 µs baseline. Conserved FPGA-clock comparison
  attributes about 63 ns mean growth outside host turnaround and 402 ns to
  host/link turnaround. [Measurement and comparison](../docs/research/hot-path-performance.md#contiguous-layout-regression-analysis--october-10).
- October 11 pipeline 518836 passed all gates and two matched RPC cold boots.
  Joined memcached RTT tests passed 1,536 traced requests and an identical
  tracing-off control, following an earlier 1,536-request traced cohort.
  Software medians were about 12 µs RX-to-response preparation and 2.7 µs TX
  preparation. Python generation dominated client RTT. Recorder timestamps
  after an input FIFO distort burst intervals without sample loss.
  [Evidence and limitation](../docs/applications/memcached.md#joined-rtt-measurements-and-recorder-limitation).
- The vanilla Linux FPGA-bypass/netdev comparison passed 384 traced and 384
  control requests. Sustained GET MAC p50 was 74.65 µs versus native 18.97 µs,
  subject to the same legacy timestamp limitation. This is not a CPU-NIC
  baseline. [Comparison](../docs/applications/memcached.md#linux-bypass-control).

## Remaining checks

Pipeline 518922 (`cc19c428`) corrects the recorder and passed two matched
cold-boot RPC trials (200 calls), with zero recorded trace loss markers.
[Acceptance](../docs/applications/memcached.md#joined-rtt-measurements-and-recorder-limitation)
does not supply corrected memcached burst measurements.

On resumption, collect matched memcached measurements with the corrected
recorder before precise burst-breakdown claims, and establish the system-event
CDC timestamp uncertainty bound. Use RAM-backed peer output: the five-instance
extension stopped at 2,688 requests on client ENOSPC and is a failed campaign,
with frozen trace and software records preserved. Full pool-size/performance
measurements require separate acceptance; the finite switching results are in
[runtime/application context](runtime-applications.md).

## October 4 traffic-generator validation reference

Client: Ubuntu `6.8.0-146-generic`, patched DPDK 23.11.4, ThunderX
`0002:01:00.2` / `cpu40g1`, 40 Gb/s, PA/no-IOMMU, Linux CPU 6. Management/root
storage stayed on `cpu40g0`. The isolated configuration uses NO_HZ_FULL and
moves ordinary scheduling/IRQs away from CPU 6; DPDK telemetry is disabled.

All numbers in this table are **raw**, with no clock-probe subtraction.
Loopback echoes a 64-byte UDP payload through the client's own NIC; it does
not traverse a Lauberhorn server or measure link/switch/transceiver costs.

| Configuration | Measured / warmup | Median E2E | P99 E2E | Maximum E2E | Median client software | P99 client software |
|---|---:|---:|---:|---:|---:|---:|
| Initial loopback | 10,000 / 100 | 2.140 µs | 4.840 µs | 36.850 µs | — | — |
| Isolated trial 1 | 100,000 / 1,000 | 2.080 µs | 2.430 µs | 7.790 µs | 0.570 µs | 0.580 µs |
| Isolated trial 2 | 100,000 / 1,000 | 2.080 µs | 2.420 µs | 7.780 µs | 0.570 µs | 0.580 µs |
| Isolated trial 3 | 100,000 / 1,000 | 2.140 µs | 2.440 µs | 7.940 µs | 0.560 µs | 0.580 µs |

Client software is a **per-request sum** of encode, PMD enqueue and validation,
not a sum of percentiles. The polling interval includes NIC latency and polling
observation delay. All attempts succeeded; warmup is explicitly marked and
excluded only from reported distributions. First/last-quarter corrected medians
are respectively 2.050/2.030, 2.070/2.020 and 2.090/2.090 µs. Residual tails remain;
do not replace the distribution by a constant or subtract this echo calibration
from a differently sized RPC workload.

A gateway UDP echo test also passed **10,100/10,100 exchanges** over the real
switch path. Its raw median was 35.580 µs, P99 63.231 µs; the gateway's software
response is included, so those values are functional evidence, not a fixed
network-cost calibration. Each test restored the data VF. A final short test
also checked exact IPv4/broadcast, MAC and MTU restoration.

Raw CSVs, summaries and the standalone `client-calibration.pdf`/`.png` are under
`out/benchmark-zs07/`; remote originals are under
`/scratch/pengxu/dpdk-benchmark/`. CSV hashes and compact distributions are
retained in the [calibration ledger](../data/eci/board-evidence/dpdk-client-20261004.json). Different sample counts and both CPU isolation
and telemetry changes distinguish the initial and final configurations; this
is not an isolated causal estimate of the effect of one kernel option.

Physical testing used the Ubuntu-native patched DPDK 23.11.4 binary. Native
x86 and cross AArch64 Nix packages pinned 26.03; their final expressions built
against pinned nixpkgs after full flake snapshotting exhausted storage. The
cross build is not physical execution of that package. Five pcap replay cases
(add, multiply, wrong result, stale ID, timeout) passed on patched Enzian and
x86 packages; eight trace-accounting and five summary tests also passed.
These are recorded October 4 results, not tests run during the documentation
scrub. No FPGA was programmed for client validation. Full historical record:
`e7820314:docs/development/dpdk-benchmark-results.md`. The obsolete server-side
2F2F comparison plan is not part of generator validation or a new campaign task.

## Hot-path instruction and layout analysis continuation

The maintainer promoted the static instruction audit into
[hot-path performance analysis](../docs/research/hot-path-performance.md), with
future optimization of exact instruction counts and hardware/software cache-line
layout. Establish a current exact-binary baseline before attributing savings;
the October 5 `7bc9302b` add counts do not describe the current echo workload or
transport. Keep hardware cycle/layout proposals connected to the software ledger.
This documentation change launches no builds or board campaign; timing work
remains subject to its existing pause.

The historical audit passed both complete selected-path manifests and four
counter tests. Its read-only cross-built resolver probe used the same Nix glibc;
an initial Ubuntu-libc Python probe was rejected for libc selection, retaining
only its public vDSO. No traffic or reset was performed for that probe. Evidence:
`out/static-hot-count/`, shared `/scratch/pengxu/static-hot-count/`, and the
[summary](../data/eci/board-evidence/hot-static-516232-summary.json). Full original
record: `9ece0489:docs/development/hot-static-instructions.md`.

## October 16 DPDK baseline readiness

Recorded inventory: zuestoll07, PCI `0005:90:00.0`, netdev `enP5p144s0np0`,
MAC `b8:ce:f6:87:c2:9e`, kernel `6.8.0-146-generic`, firmware `16.35.8002`,
100 Gb/s. These are dated observations, not live reservations or current inventory.

The twelve-case zuestoll07 matrix passed: 256 measured + 16 warmup requests
for each unloaded payload/mode, and 25,000 measured loaded requests per
payload/worker configuration (100 kRPC/s, 50 ms warmup + 250 ms measurement).
All 150,000 measured loaded requests returned on time, with zero generator
misses or TX admission failures. Each four-worker case recorded 6,250 measured
calls on each worker. All six unloaded cases completed their 256 measured calls,
with ordered queue probes in all three instrumented cases. Every role restored
its NIC/resources. Total case execution was 110.4 seconds, excluding staging.

The instrumented RX-queue medians were 200/200/210 ns and TX-submission medians
410/405/430 ns for 8/128/1432 bytes. These include probe overhead and only
establish working software interval capture; they are not unloaded wire latency
or full-dataset results. This matrix collected no Tofino durations and tested no Caladan runtime.

Local checks passed: 51 campaign unit tests, 16 schema packet replays, seven
open-loop replay scenarios, native wire/schema/aggregate/probe-state checks,
and runtime regressions including compiled-out tracing and sanitizer checks.

Evidence: `out/mlx5-readiness/acceptance.json`, `validated-v2.json`, and the complete
`mlx5-ready-01.tar.gz` role archive. Image SHA256: `61367fc00f65379416de0168776a6bd74653afdf4b930a5876d8e523d1107dfb`.
Archive SHA256: `401ecdd1a2abbb52c15fc335aad58070c18290d960e3824b1dacc772e559f324`.
The archive manifest records both PMD package paths and each role's executable
hash, arguments, raw observations, NIC counters and restoration results.

## October 5 PMU campaign provenance

DUT zuestoll14, DPDK client zuestoll07, all 3,048 requests correct on CPU44 / worker0
(3 × 1,000 measured plus 16 warmups each). One measured `add` thread and a separate
four-thread `mul` domain were registered; no flush traffic or deliberate reschedule
was issued. Kernel activation tracing was off. All 3,048 buffered rows exported
once, zero drops. TAD/kernel health passed. The matched hardware was pipeline
516232 SHA `6f5fa076adbad2002681d3051c7056522929b7a1`, with corrected module
`af0f64fe`; timing remains open. No FPGA or scheduling changes were made. Tracing remained armed through module
load and was frozen after the campaign. The final FPGA `sample_lost` flag was
set (DMA error zero), so this is not a loss-free FPGA capture; PMU attribution
uses the loss-free runtime records and does not depend on FPGA trace events.

Accepted cache/cycle tables and the instruction-counter failure are in the
[hot-path analysis](../docs/research/hot-path-performance.md#october-5-pipeline-516232).
The loss-free runtime CSV is the PMU attribution source; do not label the FPGA
capture loss-free. Later hardware observations supersede this campaign's final
frozen-state note.

Local records: `out/pmu-hot-516232/data/{server/server.csv,per-request.csv,pmu-summary.json}`;
independent probes and matched boot evidence: `out/pmu-hot-516232/`.
[Tracked summary](../data/eci/board-evidence/hot-pmu-516232-20261005.json).
Before accepting instruction counts in future work, reproduce known-instruction
loops across memory footprints and adjacent-snapshot monotonicity using both
userspace and kernel perf reads. Isolate PMU configuration, hardware semantics
and kernel behavior; the old observations establish no named erratum or root
cause. Changing counter-register access alone already failed as a remedy.
No new profiling run is implied by this evidence migration.

## Historical trace-readout throughput observation

The former tracing reference reported 6.9 MiB/s payload throughput for its
single-outstanding 1408-byte Python reader, versus roughly 0.01–0.02 MiB/s for
the observed Vivado/Tcl JTAG reader. This is dated implementation throughput,
not CMAC line rate, a current performance result, long-duration reliability or
timing closure. Original note: `73c84805:docs/development/tracing.md`.

## Retired latency campaign narratives

Superseded June adder, October 4 streaming, October 5 buffered/GSYNC and October
10–14 null-RPC campaign narratives are retained in Git at
`65ebaf92:docs/research/latency-measurement.md`. Their tracked data and reproduction
inputs remain unchanged. The October 10 regression analysis remains in
[hot-path performance](../docs/research/hot-path-performance.md#contiguous-layout-regression-analysis--october-10).
The old untraceable 87.290 µs example is not evidence. Reanalysis of June 20 CSVs
excluded duplicate/startup request 0 and covered IDs 1–9999 (corrected RTT P50
73.320 µs); no matching historical map was established, so it supplies no FPGA
TX comparison. Do not revive dated board state or superseded plot inventories.

Remaining attribution work: isolate the candidate timeout-sweep contribution to
cold tails with a direct span; establish disabled-probe overhead with matched
controls; use explicit packet identities for concurrent correlation. Historical
sentinel-ID recovery is a bounded compatibility rule, not a general solution.
Check current source before treating any historical trace-ID fix as outstanding.

## Memcached capture preservation and comparison follow-up

The main paper comparison is native Lauberhorn versus Caladan memcached, not the
Linux-over-FPGA-bypass control. Preserve the application version, maintenance
scope, operation mix, generator behavior and core budgets when designing it.
Use the [application methodology](../docs/applications/memcached.md#evaluation-methodology).

The October 11 failed signal-export cohort retains logs, RTTs and its
231,372,608-byte trace under `out/memcached-breakdown/`. The later five-instance
extension stopped on client ENOSPC after 2,688 requests; its 477,408,384-byte
window and five software traces are preserved, not a passing campaign. Use
RAM-backed peer output for a repeat and verify matched corrected-recorder
artifacts. Pipeline 518922 completed RPC acceptance; this does not close the
memcached burst/CDC uncertainty checks above. No historical PID, reservation or
frozen-pointer note is a current board-state claim.
