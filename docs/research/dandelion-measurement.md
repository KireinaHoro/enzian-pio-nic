# Dandelion nested-composition measurements — October 8

The atomic and local-loopback correctness gates passed on Enzian. Buffered
parent/child instrumentation then exposed an important allocation distinction:
declaring four worker threads does not immediately make all four resident.
Serial warmup leaves one core assigned; a bounded sustained concurrent warmup
activates all four. These are separate measured conditions, not equivalent runs.

## Workloads and hardware

Use Sven's report workloads: 2×2 atomic matmul, depth 2 (three independent
matmuls feeding one matmac), and depth 4 (a chain of four matmuls). The latency
client names the latter two `graph` and `graph_2`, respectively. Each cohort has
100 warmups and 1,000 measured requests, with every returned matrix checked.
The earlier integration-test DAG is a different fixture and is only a
correctness gate. The concurrent chained fixture also checks distinct inputs.

DUT: zuestoll14, workers Linux CPUs 44–47 (NIC datapaths 1–4). Client/capture:
zuestoll07. FPGA: pipeline 516232, SHA
`6f5fa076adbad2002681d3051c7056522929b7a1` (prepare job 2866838, hardware job
2866839, report job 2866840); corrected module overlay
`af0f64fe07fa3e2ef6bbfd619423d4bda027b615`, kernel 6.8.0-64-generic.
RT bandwidth throttling is disabled. No FPGA, address, timing or PREEMPT change
was made for this experiment. This routed image does not meet timing closure.

Dandelion `17fbaffbc268610211b1c8469f0b90a1aacf8337`, runtime
`ce27113515daf395be9573de333977e7ee1a18a1`, saved 516232 generated headers.
The cross-built application, fixtures and closure are deployed as a Nix image;
neither board builds software. The Nix recipe filters source with
`cleanSourceWith`. The client is the checked Rust UDP socket client, **not DPDK**.
Process isolation includes process creation, mappings, ptrace and wait; the
millisecond execution bars are not matrix arithmetic alone.

## Allocation and measured RTT

The current FPGA scheduler expands a resident service only when its queue is
half full (`2 * fill >= 16`). A serial client and three-child fanout never meet
that threshold. The first depth-2 trace therefore executes every child on CPU
44, despite four registered threads. A one-request burst from twelve shell
processes was insufficient because their startup/gate timing spread arrivals.
Twelve concurrent clients with twenty checked atomic requests each sustained
enough load to activate CPUs 44–47. This bounded warmup is excluded from the
measurement cohort; each client has only one outstanding request.

RTT in microseconds, tracing enabled; percentiles use linear interpolation:

| Resident workers | Workload | P50 | P90 | P95 | P99 | P100 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | Atomic | 3914.14 | 4553.59 | 4565.28 | 4626.82 | 4855.42 |
| 1 | Depth 2 | 15794.19 | 15823.85 | 15834.29 | 15859.70 | 15903.57 |
| 1 | Depth 4 | 15753.45 | 15784.41 | 15795.48 | 15825.51 | 16801.96 |
| 4 | Atomic | 3947.33 | 3967.15 | 3976.91 | 3999.30 | 5129.12 |
| 4 | Depth 2 | 8508.60 | 8556.92 | 8569.79 | 8592.80 | 8628.35 |
| 4 | Depth 4 | 15944.41 | 15994.97 | 16009.44 | 16044.90 | 16122.38 |

For representative depth-2 request 201027, relative to parent RX unblock:

| Action | CPU | Start (µs) | End (µs) |
| --- | ---: | ---: | ---: |
| Submit three children | 44 | 68.71 | 106.26 |
| Child 1 process execution | 45 | 542.27 | 4175.03 |
| Child 2 process execution | 46 | 609.88 | 4229.20 |
| Child 3 process execution | 47 | 656.50 | 4309.30 |
| Collect first three results | 44 | 4225.30 | 4368.55 |
| Submit final matmac | 44 | 4369.50 | 4389.87 |
| Matmac process execution | 45 | 4893.41 | 8341.05 |
| Collect final result | 44 | 8392.97 | 8402.51 |

RTT is 8508.59 µs; DUT RX-unblock to TX-return is 8442.62 µs. All 1,000 depth-2 requests show three overlapping child process spans; all
1,000 depth-4 requests have peak child concurrency one. The final matmac depends
on all three initial results. The depth-4 graph is
inherently sequential. Do not add overlapping child, parent-wait and fiber-active
spans together as an E2E breakdown.

## Tracing-disabled control and figures

The same application/image, tagged BSON workload, four-resident-worker warmup
and 100/1,000 cohorts passed after another verified cold boot, with both software
trace buffers disabled. Worker placement on CPUs 44–47 was checked before traffic.
This is a separate boot/cohort, not an interleaved randomized overhead experiment.

| Workload | Tracing-off P50 (µs) | P90 | P95 | P99 | P100 | On minus off median |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Atomic | 3894.97 | 3910.62 | 3915.78 | 3927.14 | 3970.12 | +52.36 µs (1.34%) |
| Depth 2 | 8394.17 | 8430.42 | 8442.41 | 8464.26 | 9481.65 | +114.43 µs (1.36%) |
| Depth 4 | 15732.90 | 15773.02 | 15785.96 | 15810.36 | 15876.83 | +211.51 µs (1.34%) |

These differences include instrumentation and between-run variation; they are
not a proven per-event cost or worst-case overhead bound. Do not subtract them
from individual spans. The tracing-off depth-2 maximum exceeds the tracing-on
maximum, illustrating why this pair does not establish a universal tail bound.
All nine cohorts (9,000 measured requests, plus warmups) returned checked results.

Generated figures and their machine-readable selected spans/distributions remain
under ignored `out/dandelion-resume/figures/`:

- `atomic-four.{svg,png,json}`: atomic baseline with four resident workers.
- `depth2-four.{svg,png,json}`: three parallel matmuls followed by matmac.
- `depth4-four.{svg,png,json}`: the dependent chain.
- `depth2-four-{launch,collection,return}.{svg,png}`: detailed phase views.
- `depth2-baseline.{svg,png,json}`: the original one-worker serialization.

Each figure uses an actual median-nearest request. Source events identify the
return to runtime, fiber admission, defer queue, preparation before first child,
submission, execution, collection and final TX. The black underline shows when
that fiber is running; it overlaps phase bars and is not an additional duration.
Arrows connect submission start to child RX unblock, and child TX start to
parent collection; transmission can complete before the submitting call returns.

## Instrumentation and interpretation

Runtime and application events carry explicit request IDs, child IDs, context
tokens, CPU/TID and `CLOCK_MONOTONIC_RAW` timestamps. Per-thread buffers are
allocated and prefaulted before traffic; CSV formatting and export happen only
after the workload, on SIGUSR2. Event recording performs no per-request file I/O,
allocation or shared lock. Minimum timestamp-pair overhead is 60 ns on this DUT;
that is not a bound on the cost of a complete event or the whole instrumented
request. The tagged-wire control above measures cohort-level differences.

The one-worker export contains 12,106 request rows and 172,782 events including
smoke tests. The four-worker export contains 12,354 rows and 174,150 events
including warmup. Both exports report zero drops. The plotter chooses an actual
request nearest median client RTT, rather than stacking independent component
medians. Separate launch, collection and final-return views show small phases.
Unattributed runtime/coordination gaps remain visible. Process setup and execution
are instrumented boundaries, not a complete kernel/process-launch breakdown.

The FPGA join uses exact per-worker descriptor/commit counts and order, context
tokens for incoming requests and XIDs for nested completions. A constrained
affine fit places TX commit inside software TX entry/return and descriptor send
inside RX entry/unblock. It reports feasible cross-clock uncertainty rather than
assuming a fixed RX latency. The FPGA clock is 5 ns per cycle; this does not imply
5 ns accuracy when aligning it with CPU software timestamps. MAC events have no
packet ID: join through packet-tagged Ethernet events and require a unique nearby
MAC event. Cached decodes are tied to trace/map SHA-256; plots validate software
input hashes against the hardware join.

The decoder rejects loss markers. The first capture had a clear loss flag until
freeze. The four-worker window begins after an intentional stop/resume, so its
sticky loss flag was already set; it cannot certify absence of subsequent loss.
The decoded-window check passed with no loss markers. All 20,900 RX descriptors
and 20,900 TX commits match software counts/order across four cores. The clock
fit uses those TX brackets plus 12,100 request RX brackets: cross-clock uncertainty
is ±972 ns (one-worker baseline ±961 ns), with 10 ns timestamp quantization slack.
The observed timestamp-pair minimum is 60 ns; software intervals do not inherit
the FPGA alignment uncertainty. Software waterfalls are independently validated
from their loss-free exports.

## Reproduction and evidence

Use the [hardware harness](../development/hardware-test.md), matched artifacts,
and a fresh output directory. Preserve captures before any reset. The evaluated
manifests, build expressions, deployment identities and compact summaries are in
`out/dandelion-resume/`; the application source/package changes live directly in
the transferred Dandelion repository, not a platform patch stack.

After registration, [dandelion-cohort.sh](../../tools/enzian/dandelion-cohort.sh)
provides the tested bounded `warmup` and `measure` steps with explicit image
paths and board addresses. Confirm actual placement after warmup. Keep tracing
armed during module load; record the measurement-window pointer after warmup.
Freeze after traffic drains, then export software and preserve the complete
window over F_MAC1. Inspect compact summaries, not routine raw console logs.

Analyze with [dandelion_hardware.py](../../tools/enzian/dandelion_hardware.py)
(`--server`, `--events`, all three `--clients`, `--trace`, matched `--map`, fresh
`--decoded`, `--out`), then
[dandelion_waterfall.py](../../tools/enzian/dandelion_waterfall.py) once per client
cohort, adding `--hardware` for the validated join. Use the Nix Python environment
with NumPy, SciPy and Matplotlib. JSON exports include component distributions,
selected IDs, input hashes and limitations.

Raw evidence locations:

- `cold-atomic/summary.json`, `cold-results/*-summary.json`: atomic/DAG/concurrent
  chain correctness before instrumentation.
- `instrumented-smoke/summary.json`, `measurement-results/`: first cohorts.
- `parallel-smoke/summary.json`, `parallel-results/`: four-resident-worker cohorts.
- `control-smoke/summary.json`, `control-results/`, `control-statistics.json`: tracing-off
  boot, placement and cohorts.
- `baseline-hardware.json`, `four-hardware.json`: checked joins, counts, hashes
  and clock fits; seven correlation/clock/phase unit tests passed.
- `measurement-window.log`, `measurement-trace/preservation.json`: complete
  1,968,793,792-byte baseline FPGA window, SHA-256
  `d8e456bf36440029d2cf3b10e0a20115e7224535db6028379d8504b9e4302898`.
- `four-window.log`, `four-trace/`: complete 2,826,965,120-byte four-worker
  measurement window at byte offset 3,632,295,744, SHA-256
  `d456d2d5bfd4211e5423d99fda324b4663b3ab3a0e8faa4f14766b9ff1a1533d`.

The original frozen failure and startup reset-required captures remain preserved
separately. Full exit/kill recovery, loaded multi-domain behavior, sharding
performance, DPDK application traffic, process-launch optimization and comparisons
against the student's software-mock timings are outside this finite result.

At the earlier cohort closeout the tracing-off Dandelion server remained running on zuestoll14.
Its FPGA trace is frozen, unwrapped, with no DMA error and loss clear before
freeze (`control-freeze.log`). This successful control buffer remains on the
FPGA and has not been exported/analyzed; the two instrumented measurement
windows are fully preserved on shared storage and locally. Recheck reservations
and state before further board access.

## Wide fan-out and temporary UDP backpressure — October 8

The follow-up `fanout32_sum` graph has 32 independent 2×2 matrix squarings and
31 balanced binary sum-reduction nodes. Reduction uses the existing matmac
fixture (`I × left + right`), for 63 child calls in one composition. Both checked
requests returned all four matrix elements equal to 64. This is a finite
correctness/parallelism probe with two samples and no warmup, not a latency
percentile campaign.

| Request | RTT (ms) | Child executions on CPUs 44 / 45 / 46 / 47 | Peak simultaneous children | Busy replies retried |
| --- | ---: | --- | ---: | ---: |
| 800001 | 86.678 | 18 / 16 / 15 / 14 | 4 | 3 |
| 800002 | 86.296 | 15 / 17 / 16 / 15 | 4 | 2 |

Both parents entered on CPU44. In request 800002, the parent suspended at
107569519550 ns and resumed at 107586176260 ns on the DUT's MONOTONIC_RAW
clock. CPU44 executed child 4294967398 from 107570267270 to 107573800060 ns,
then more children during the same wait. Thus the waiting parent's core really
executes compute. The parent fiber later resumes on other workers too; the
waterfall's parent row denotes a logical fiber, not a permanently reserved CPU.

The software exports contain 129 successful request rows (one atomic smoke,
two roots, 126 children), 2,231 buffered events, and zero dropped records.
The selected FPGA window excludes the smoke. The checked join covers 128
requests, 259 RX descriptors and 259 TX commits, with exact per-worker counts.
Conditional clock alignment uncertainty is ±981 ns, with 10 ns quantization
slack. The decoder found no trace loss markers. The VIO loss flag was clear
before freeze and asserted after freeze; the DMA error and wrap flags stayed
clear. Full window: 68,986,240 bytes, SHA-256
`287a98a78e9c9588c5ac9686b0ea82132d10699907621fb5b39e5f40430c5dd4`.

Provenance: same FPGA 516232 and corrected module as above; Dandelion
`f95d33d05d7ce0091962758b78e425a2ced60458`, runtime code `445e67d1` (image built
before its comment-only workaround wording). Cross-built application image:
`/nix/store/cg63f1vd54a9fdahl96pxkd1vy8jhjij-dandelion-instrumented.img`.
The subsequent upstream-process-parity Dandelion revision `1a9eb8f` descends
from this source, but its changed process packaging/context sizing was not used
for these two measurements. Do not attribute these timings to that newer pin.

Two failures preceded this result. The old dispatcher aborted when an incoming
reply-context rejection completed a child without result data. Preserving
SYSTEM_ERR and requeuing the local-loopback shard fixed that panic, but an
unbounded submit burst still lost 13 completions at the process queue. Hardware
had retired outgoing entries before software consumed their replies. The runtime
now holds admission credit through future consumption, limiting live calls to
16 per process; the existing graph walker stops on EAGAIN and resumes after a
completion. Only explicit loopback SYSTEM_ERR is retried; other failed futures
are drained and fail the composition.

This is a **temporary workaround for UDP and small hardware tables**, not the
intended architecture. See the existing
[table-size restrictions and scaling/spill plan](../hardware/lookup-spill.md#temporary-udp-evaluation-workaround--october-8).
Many limits should eventually be lifted. Neither this cap nor retrying SYSTEM_ERR
provides general UDP reliability or safe retries for arbitrary side-effecting
handlers, concurrent external traffic, or deeply nested compositions.

Evidence under `out/dandelion-resume/`: `bounded-smoke/summary.json`,
`bounded-results/`, `bounded-trace/`, `bounded-hardware.json`,
`bounded-worker-proof.json`, `bounded-provenance.json`. The five-lane waterfall
(parent fiber plus four worker lanes) is `figures/fanout32-bounded.{svg,png,json}`;
use `dandelion_waterfall.py --worker-lanes` with the validated hardware join.
Native debug/release runtime tests, all wire reply statuses, ready-future credit
retention, 32 concurrent submitters, AArch64/QEMU retry-policy checks, deployment
builds and nine analysis tests passed. No hardware redesign was required.

At this follow-up's closeout the bounded Dandelion server remains running on
zuestoll14, with software already exported and FPGA tracing frozen. The complete
selected FPGA window is saved locally and on shared storage. Recheck state and
reservations before reuse; do not reuse this already-exported software buffer
for another claimed loss-free measurement.
