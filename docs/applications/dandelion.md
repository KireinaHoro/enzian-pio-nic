# Dandelion

**Bracketed for the paper: Dandelion is no longer an active paper goal. Its
process-sandbox overhead is too high for Lauberhorn to make a meaningful
end-to-end difference.** The retained integration and bounded measurements below
explain that decision; they do not authorize further implementation or campaigns.

In the aligned 1×1 experiment, setup and process execution account for about
96.2% of mean RTT. The composition experiments demonstrate finite nested-RPC
progress and parallel execution, but establish no controlled end-to-end speedup
over upstream Dandelion. Lightweight-isolation/CHERI gains remain projections,
not measured Enzian results. See [paper goals](../research/paper-goals.md).

## Integration and execution

The platform consumes the maintained Dandelion input's `package.nix`, pinned by
`flake.lock`, and supplies its matching installed runtime and target packages.
It carries no downstream application patch stack. The maintained source is the
[transferred repository](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/benchmarking/dandelion).
The student's older `../sven-dandelion/` checkout is provenance, not the current
application build source.

HTTP registers functions/compositions. One ONC-RPC endpoint accepts a name and
BSON/XDR input; the registry resolves the invocation before execution admission.
Atomic functions execute inline. Compositions defer with owned resolved state,
submit ready DAG nodes through nested futures, join results and release successors.
Local edges use [FPGA internal loopback](../software/nested-rpc.md#internal-local-rpc).
Logical engine slots remain distinct from Linux CPUs; process execution uses
actual worker placement. Process isolation includes creation, mappings, ptrace
and waiting, not only function instructions.

The Rust/C boundary uses explicit codec capacities and owned allocation/free
hooks. The request context is opaque; the adapter does not interpret it. Endpoint
storage stays stable through outstanding operations. Current shutdown/failure
semantics follow the [scheduler reset boundary](../software/scheduler.md#failure-and-reset-boundary),
not the historical student's cleanup assumptions.

The runtime retains outgoing admission credit until a future is consumed, with
16 live calls per process. The graph walker stops on `EAGAIN`, consumes a completion
and resumes submission. Explicit local-loopback `SYSTEM_ERR` is retried for these
pure computation fixtures; other failed futures are drained and fail composition.
This is a temporary bounded-table/UDP workaround, not general reliability or
safe retry semantics for side-effecting communication.

`FORWARD` offers local reply relay without retaining a parent fiber, but Dandelion
is not automatically optimized to select it. Direct downstream-to-original-client
tail calls are not implemented and are not fully designed.

## Build, configuration and validation

Use the [development workflow](../development/workflow.md) for Nix-built images
and [validation](../development/validation.md#board-validation) for board deployment,
artifact matching and automation. The following are Dandelion-specific entry points;
no board result is implied by a build:

```sh
nix build .#checks.x86_64-linux.runtime-tests \
  .#dev.x86_64-linux.software.dandelion .#dandelionImage
python3 sw/apps/dandelion/check-fixture-results.py
```

`dandelionImage` includes the matching runtime, kernel, application,
`server-tests` harness and snapshot's prebuilt AArch64 function fixtures. The
Rust/C ABI assertions compile for the supported 64-bit layout. The Python check
independently calculates fixture answers; it does not execute Dandelion.

The package accepts `lauberhornRuntime` through `platform.callPackage`, installs
executables in `bin`, assets in `share/dandelion`, and defaults to
`dandelion_lauberhorn`. Its wrappers default `PROCESS_WORKER_PATH` to the installed
worker and `DANDELION_LAUBERHORN_CONFIG` to the installed configuration. Both can
be overridden. For board execution, set the latter to a writable-copy
configuration whose `daddr` equals the configured NIC IPv4 address. The default
is `192.168.129.129`; composition edges use internal FPGA loopback. Run the
packaged test harness from the peer with the environment described in its
`share/dandelion-tests/config.sh`, adjusting `LAUBERHORN_IP`, `DANDELION_SERVER` and
`DANDELION_TEST_BINARY_PATH` to the deployment if overriding the packaged default.
The test package is `dandelion.interactiveTests.smoke`; the image explicitly
selects it as `dandelion-tests` under `/nix/store/lauberhorn/apps`. Run serially
because tests share function/registry names:

```sh
lh-test run dandelion-tests -- --test-threads=1
```

The harness contains matmul, matmac, the matmul/matmac DAG, keyed sharding and
nested compositions. The nested case also runs four concurrent invocations
with different expected answers. Sharding verifies the full output multiset,
including multiplicity. Registry HTTP traffic targets the DUT, while RPCs are
sent by the second Enzian. A successful build is not a passing hardware test.

Endpoint teardown requires joined workers and no unresolved requests. Failed
teardown retains Rust storage; device close fences the domain and retains
unresolved resources until coherence reset. Complete process recovery is unsupported in the current scheduler. See the
[endpoint contract](../software/nested-rpc.md#registered-endpoints) and
[remaining transport checks](../../agents/runtime-applications.md#nested-rpc-follow-up).

Set `DANDELION_STATE_DIR` to a dedicated writable function registry directory
(default `/tmp/dandelion_server`); server cleanup removes that directory. Redirect
logs outside the store. Packaged immutable assets do not depend on the working
directory. Image construction uses the shared manifest and helper contract.


`dandelion.codecTests` runs the bounded encoder regression under target emulation.
The independent fixture calculator checks expected answers; it does not execute
Dandelion. Run the packaged board suite serially because registry names are shared.
Set `LAUBERHORN_CLIENT_IP` when management/data routes coexist (default `0.0.0.0`).
HTTP controls use 5-second connect and 30-second total timeouts; UDP replies use
5 seconds. These bound fixtures, not application execution policy.

The process worker is statically linked with the AArch64 image-base convention
`0xaaaaaaaa0000`. Fixtures use 8 MiB contexts. Atomic latency defaults to 1×1;
`--matmul-size 2` selects the earlier report workload and `--context-size BYTES`
sets an explicit context control. Re-register functions when changing context
size; skipping registration retains prior registry values. The maintained
`dandelion-cohort.sh` selects size 2 explicitly.

For waterfalls, enable request/event tracing before starting workers and size
buffers for warmups and every cohort. Export once after traffic drains and require
both loss-free manifests; see [runtime tracing](../development/runtime-tracing.md).
`DANDELION_TRACE_IDS=1` preserves tagged BSON in a tracing-disabled wire-equivalent
control. Timestamp-pair minima are not total instrumentation overhead.

## Composition measurements

### Workloads and hardware

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
RT bandwidth throttling is disabled. No FPGA, address, timing or DESCHED change
was made for this experiment. This routed image does not meet timing closure.

Dandelion `17fbaffbc268610211b1c8469f0b90a1aacf8337`, runtime
`ce27113515daf395be9573de333977e7ee1a18a1`, saved 516232 generated headers.
The cross-built application, fixtures and closure are deployed as a Nix image;
neither board builds software. The Nix recipe filters source with
`cleanSourceWith`. The client is the checked Rust UDP socket client, **not DPDK**.
Process isolation includes process creation, mappings, ptrace and wait; the
millisecond execution bars are not matrix arithmetic alone.

### Allocation and measured RTT

The measured FPGA scheduler expands a resident service only when its queue is
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

### Tracing-disabled control and figures

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

### Instrumentation and interpretation

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

The historical FPGA fits also retain the [recorder/CDC limitations](../hardware/tracing.md#timestamp-capture-point-and-legacy-queue-bias).
Their feasible brackets are conditional alignment estimates, not independently
calibrated one-way latency. Software-only waterfalls do not depend on that fit.

### Reproduction and evidence

Use the [hardware harness](../development/validation.md#board-validation), matched artifacts,
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

### Wide fan-out and bounded backpressure

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

The admission/retry contract described above allowed this finite graph to complete.
The [lookup scaling and spill proposal](../proposals/lookup-spill.md#completion-capacity-and-delivery)
addresses the shared table-pressure mechanism. It does not make this two-request
probe evidence of deep-nesting progress or safe retries of side-effecting work.

Evidence under `out/dandelion-resume/`: `bounded-smoke/summary.json`,
`bounded-results/`, `bounded-trace/`, `bounded-hardware.json`,
`bounded-worker-proof.json`, `bounded-provenance.json`. The five-lane waterfall
(parent fiber plus four worker lanes) is `figures/fanout32-bounded.{svg,png,json}`;
use `dandelion_waterfall.py --worker-lanes` with the validated hardware join.
Native debug/release runtime tests, all wire reply statuses, ready-future credit
retention, 32 concurrent submitters, AArch64/QEMU retry-policy checks, deployment
builds and nine analysis tests passed. No hardware redesign was required.


## Upstream process baseline

The server is `98c4968017c050391475a20da8e30bbabf6ea618`, immediately before
Sven's port. The original [SOSP artifact](https://github.com/eth-easl/dandelion_sosp_ae)
client is pinned to `2a8e23d0aa641f9de91cbc35723ca2c6cd08536e`.

### Unloaded results

Each row below contains 1,000 sequential 1x1 matmul requests, with no explicit
warmup. The unchanged `rload` uses `closed-unloaded`. Cold and hot refer to
function loading/caching; **both create a fresh process per invocation**.
The context is 8 MiB and the original AArch64 function ELF is unchanged.
Four total upstream cores mean dispatcher CPU 0, frontend CPUs 1–2 and **one
compute engine on CPU 3**, not four compute workers. The server stayed resident during these cohorts.

| Server / client | Cold mean / median (µs) | Hot mean / median (µs) |
| --- | ---: | ---: |
| zuestoll14 / zuestoll07 | 3144.160 / 3034 | 2856.062 / 2855 |
| zuestoll07 / zuestoll14 | 3146.959 / 3052 | 2852.838 / 2858 |

These are the original client's **HTTP response-header latencies**, ending
when `request.send().await` returns, before reading/checking the body. They
are not full-response RTT or the paper's server-only total. Cold means retain
the initial roughly 103 ms outlier; no samples were discarded. Every CSV row
was checked for status 200, timeout/error flags and expected sample count;
a zero client exit code alone is insufficient. The original client checks
output length and the final matrix element.

Both hosts are 48-core ThunderX Enzian machines. Traffic used ordinary Ethernet
on `cpu40g0`, 192.168.192.14 and 192.168.192.7, port 8080. zuestoll14 runs
6.8.0-64-generic and retained its Lauberhorn module; zuestoll07 runs
6.8.0-146-generic with **no Lauberhorn module loaded**. The matching result
on zuestoll07 establishes that the loaded driver is not necessary for the
observed millisecond latency. It does not identify the cause of that latency.
CPU placement, kernel, executable shared memory and frequency-policy state
are preserved with the run records.

### Process timing and the paper

Separate timestamp-enabled server cohorts used a diagnostic client, with
1,000 requests each for 1x1 and 2x2 on each machine. It checked every matrix
element and extracted the unchanged server's response-embedded timestamps.
These are distinct cohorts from the original-client measurements above.

| Mean interval (µs), 1x1 | zuestoll14 | zuestoll07 |
| --- | ---: | ---: |
| Function parsing | 170.010 | 168.988 |
| Context loading | 141.647 | 139.885 |
| Input transfer | 17.938 | 18.949 |
| EngineStart → EngineEnd | 2145.418 | 2156.709 |
| Server entry → FutureReturn | 2618.733 | 2623.716 |

The engine spans for 2x2 were 2143.910 and 2159.578 µs respectively. The
negligible size effect points to process lifecycle overhead dominating these
tiny functions, rather than matrix arithmetic. This is not a finer attribution
among spawn, mapping, ptrace, scheduling and wait costs.

The original paper, Table 1 (printed p. 952), reports process-backend means
on Morello: marshal 12, disk load 54, input transfer 6, execute 371, output 9,
other 34, total 486 µs. Its text also reports a 539 µs process total with
Linux 5.15. Our engine span is about 5.8 times its execute row, but hardware,
kernel, server revision and dependency/toolchain versions differ. A direct
comparison of the HTTP client mean to the 486 µs server total would mix
measurement boundaries.

The pre-port source timestamps are Rust-side in
`machine_interface/src/function_driver/thread_utils.rs`:
`LoadStart` precedes `function.load`; `TransferStart` precedes constructing
input sets/transferring their items; `EngineStart` and `EngineEnd` bracket
`engine_state.run` (and its result debug statement). The process engine
includes launching the static worker, mapping, ptrace control and waiting,
not just the instructions in the matrix function ELF. Function parsing is
recorded separately in `function_driver/functions.rs`.

The existing atomic-four port waterfall is **2x2**, with four resident worker
slots but one atomic request executing on CPU 44, after 100 warmups. Its
“function setup” is trace events 102→103, and “process execution” is 103→104;
see `tools/enzian/dandelion_waterfall.py`. The original ELF and core process
implementation largely survived Sven's port; request/result transport and
worker dispatch changed. This unchanged baseline shows that the large process
span is not introduced solely by Sven's transport/dispatch port. The earlier measured
port also used a roughly 128 MiB context, rather than this 8 MiB context, so
its 3.410 ms selected engine span is not a controlled before/after comparison.

### Reproduction limits

The public artifact contains `rload`, `closed-unloaded` and a Figure 6 process
load-sweep recipe, but does not advertise an exact Table 1 script. Our choice
of 1,000 requests and 1,000 cold names is explicit, not a recovered paper preset.
The March 2026 pre-Sven server is newer than the paper's server revision. Its
workspace has no root Cargo.lock; this package adds a newly frozen lock while
leaving all upstream source files unchanged. Rust 1.98.1 and the platform's
pinned Nixpkgs/Crane are recorded in the image manifest.

This revision's `/stats` formatter hides timestamp numbers in `UnsafeCell`
debug output and differs from the artifact's older parser. The separate
diagnostic reads numeric response timestamps without patching either upstream
program. No EndService point exists, so the paper's output interval and total
cannot be reconstructed exactly. Timestamp values are truncated upstream to
integer microseconds. Do not combine intervals from separate cohorts as an
exact end-to-end decomposition.

The Figure 6 128x128, 97%-hot, 180-seconds-per-rate throughput sweep is packaged
but **not run**. This campaign covers unloaded process matmul only, not the
full paper or its CHERI/Wasm/KVM results.

Baseline image SHA-256:
`7f071ba2438a6163dde814f4481d3949614774941bd147a88fd651cd024696ac`.
Source audit retained all 125 upstream files and ten selected original client
files unchanged; only a root dependency lock was added. Evidence, commands,
original CSVs and diagnostic strings are under `out/dandelion-baseline/board-record/`
(`normal4`, `trace4`, `peer-normal4`, `peer-trace4`), with build/source audits in
`out/dandelion-baseline/` and a shared copy at
`/scratch/pengxu/dandelion-baseline-20261008`. All 8,000 measured requests passed;
the paper's latency numbers were not reproduced.

## Aligned atomic comparison

The aligned port used Dandelion `1a9eb8f6`, runtime `ce271135`, FPGA 516232 and
corrected module `af0f64fe`. Its image SHA-256 is
`399f629537b272340504d079e25c23ab2e7a5bd8bdc86d634d8459a83a0ced74`.
It passed 3,000 measured atomic requests plus 240 concurrent residency warmups
and 200 serial warmups, checking the full matrix, status, source and XID.
It predates the later runtime credit changes used by the wide graph; these are
not one matched campaign.

Each cohort has 1,000 measured requests. The first starts with a fresh registry
and no explicit client warmup. Then twelve concurrent clients × twenty requests
make four workers resident, followed by 100 serial warmups per measured cohort.
All use 8 MiB contexts, the static worker and unchanged function ELF.

| Cohort | RTT mean / median (µs) | Setup mean (µs) | Process execution mean (µs) |
| --- | ---: | ---: | ---: |
| 1x1, initial serial, no warmup | 3147.822 / 3174.897 | 733.062 | 2288.474 |
| 1x1, four resident workers, prewarmed | 2772.709 / 2771.143 | 432.098 | 2236.109 |
| 2x2, four resident workers, prewarmed | 2776.033 / 2774.558 | 431.970 | 2238.570 |

The initial serial cohort retains a 129.575 ms maximum; no request was trimmed.
Its setup median is 887.815 µs and process median 2183.690 µs. It is not valid
to silently substitute the prewarmed cohort for a no-warmup upstream comparison.
Initial serial requests all ran on CPU44. The prewarmed 1x1 requests ran
990/9/1 times on CPUs44/45/46; 2x2 ran 990/10 on CPUs44/45. Final thread placement
and the concurrent warmup confirm resident workers on CPUs44–47. Four resident
workers do not mean four workers execute a single atomic function.

### Comparison with the original atomic-four run

Means over 1,000 requests, both 2x2 and prewarmed on zuestoll14:

| Component | Original port (µs) | Aligned port (µs) | Difference (µs) |
| --- | ---: | ---: | ---: |
| Function setup | 435.388 | 431.970 | −3.418 |
| Process execution | 3407.754 | 2238.570 | −1169.184 |
| Request decode | 9.828 | 9.834 | +0.006 |
| Result encode | 7.919 | 8.133 | +0.214 |
| RX-unblock → TX-return | 3882.680 | 2710.598 | −1172.082 |
| Client RTT | 3950.322 | 2776.033 | −1174.289 |

The 29.7% RTT reduction is almost entirely within process execution; setup
barely changed. Static linking and context size changed together, so this
comparison alone does not isolate their individual effects. This is a separate
boot/cohort, not an interleaved randomized experiment.

### Comparison with unchanged upstream

On the same Enzian, means for 1x1:

| Interval | Upstream (µs) | Aligned port, prewarmed (µs) | Difference (µs) |
| --- | ---: | ---: | ---: |
| Parsing + context load + input transfer / port setup | 329.595 | 432.098 | +102.503 |
| Process execution | 2145.418 | 2236.109 | +90.691 |

The engine gap is now 4.2%, versus the earlier roughly 59% excess in the 2x2
comparison. The 2x2 upstream engine mean is 2143.910 µs versus aligned 2238.570
µs. Port setup includes engine selection/locking and surrounding preparation,
so the setup correspondence is approximate. Upstream timestamps came from
separate no-explicit-warmup diagnostic cohorts on CPU3; port workers use
CPUs44–47. Do not interpret the residual as transport overhead or subtract the
original client's HTTP-header timings from the port's full-response RTT.

Within aligned 1x1, setup contributes 15.6% and process execution 80.6% of mean
client RTT. Other measured means are RX return 1.006, decode 9.829,
entry/classification 2.231, encode 6.725 and TX call 4.012 µs. RX-unblock to
TX-return is 2707.298 µs; 15.287 µs inside it is outside the named phase spans.
The remaining 65.411 µs between this server span and client RTT includes
transport/client/boundary costs; it is not isolated network time.

Evidence is under `out/dandelion-aligned-atomic/`: manifest, scripts,
`trial/summary.json`, `provenance.json`, `comparison.json`, request/event CSVs and
`figures/atomic1-four` / `atomic2-four` waterfalls. The shared copy is
`/scratch/pengxu/dandelion-aligned-atomic-20261008`. Export contains 3,440 successful
rows and 24,080 events with zero drops. These breakdowns are software timings;
the aligned capture supplied no new FPGA correlation or clock-alignment claim.

## Upstream baseline build and run

### Pinned inputs and scope

- Server: `eth-easl/dandelion` commit
  `98c4968017c050391475a20da8e30bbabf6ea618`, immediately before Sven's port.
- Client: `eth-easl/dandelion_sosp_ae` commit
  `2a8e23d0aa641f9de91cbc35723ca2c6cd08536e`, unchanged `client/` Rust sources
  and its original Cargo.lock.
- Toolchain: the platform's pinned Nixpkgs and Crane from `flake.lock`.
- Server dependencies: this directory's Cargo.lock, generated against the
  upstream workspace. That revision has **no root Cargo.lock**; the old
  `server/Cargo.lock` does not describe its current workspace dependencies.
  This is a newly frozen dependency resolution, not recovered paper versions.

No Rust source, manifest, function ELF, process isolation, scheduling or
serialization code is patched. The prepared server source differs only by the
addition of the root Cargo.lock. Both binaries are release builds. Server
features are `mmu,reqwest_io`; the diagnostic variant additionally uses
`timestamp,archive`. The artifact's older `controller` feature does not exist
in this revision.

The worker is built separately, using the artifact's AArch64 flags:
`-C target-feature=+crt-static -C link-arg=-Wl,-fuse-ld=lld,--image-base=0xaaaaaaaa0000`.
`PROCESS_WORKER_PATH` is supplied by an installation wrapper, which can be
overridden explicitly for a separate control. The function ELF is the existing
`test_elf_mmu_aarch64_matmul` fixture, copied without rebuilding or modifying it.

The public artifact README supplies Figure 6/8/10 experiments. Its Figure 6
design runs 128x128 matmul, 97% hot function loads, 10,000 cold names, 180 seconds
per rate, coarse/fine rate steps 200/10. Each call still gets a fresh process.
Its source exposes `closed-unloaded`, which we use for a **Table-1-like** 1x1,
100% cold-load experiment. Choosing 1,000 samples and 1,000 cold names is our
explicit local preset, not a recovered Table 1 run configuration. Only the
process-backend matmul workloads are packaged here, not all paper applications.

The artifact points at the older `debug/hybrid_connection_upgrade_absolute`
branch; the public server also has `archive/sosp2025`. The requested March 2026
pre-port commit is **not claimed to be the exact paper revision**. Comparisons
on Enzian also differ from Morello/CloudLab hardware and kernel versions.

### Local build

From the platform repository root:

```sh
nix build --impure --file nix/benchmarks/dandelion-baseline/default.nix \
  image --out-link out/dandelion-baseline-image -L
nix eval --impure --json --file nix/benchmarks/dandelion-baseline/default.nix \
  --apply 'f: builtins.mapAttrs (_: v: toString v) (builtins.removeAttrs (f {}) ["shell"])' \
  > out/dandelion-baseline-paths.json
python3 nix/benchmarks/dandelion-baseline/verify.py out/dandelion-baseline-paths.json
```

`image` uses the same Nix `make-squashfs.nix` store-closure format as previous
Enzian application images. It contains server variants, a static worker, the
original client, matmul fixture, and a runner/diagnostic runtime. It does not
include a Lauberhorn module, runtime, FPGA image, or compiler.

For local x86-64 protocol smoke tests, use `--arg native true`; those results
are not Enzian performance evidence. The native build uses the matching
unmodified x86-64 function fixture and static worker.

```sh
nix build --impure --file nix/benchmarks/dandelion-baseline/default.nix \
  --arg native true runner --out-link out/dandelion-baseline-native
nix eval --impure --json --file nix/benchmarks/dandelion-baseline/default.nix \
  --apply 'f: builtins.mapAttrs (_: v: toString v) (builtins.removeAttrs (f {native=true;}) ["shell"])' \
  > out/dandelion-baseline-native-paths.json
# The local check isolates both fixed server paths and all network traffic.
# CAP_SYS_PTRACE exists only inside the new user namespace; host policy stays intact.
nix develop --impure --file nix/benchmarks/dandelion-baseline/default.nix shell -c \
  bwrap --unshare-all --uid 0 --gid 0 --cap-add CAP_SYS_PTRACE --die-with-parent \
  --ro-bind / / --dev /dev --tmpfs /dev/shm --tmpfs /tmp --proc /proc \
  --bind "$PWD/out" "$PWD/out" \
  python3 "$PWD/nix/benchmarks/dandelion-baseline/smoke.py" \
    "$PWD/out/dandelion-baseline-native-paths.json" "$PWD/out/dandelion-baseline-smoke"
```

This requires local user namespaces and CPUs 0–3 in the allowed affinity mask.
Do not relax host ptrace policy if the local environment cannot run the check;
retain the error as an infrastructure limitation.

### Board commands

Recheck reservations/state, preserve existing captures, and use the repository's deployment/console harness to mount the
closure. Provisioning remains exclusively through `emg`. Do not run the
artifact's CloudLab/Ansible setup on Enzian: it installs/builds software on
targets and changes machine configuration.

The runner accepts explicit commands and has no SSH or provisioning logic:

```sh
# DUT: foreground server and fresh working directory.
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline server NEW_STATE_DIR 4
# Peer: original rload, one outstanding request, 1x1, 1,000 cold requests.
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline unloaded DUT_IP NEW_RESULTS_DIR
# Peer: original Figure 6 process-backend sweep (use a fresh server registry).
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline figure6 DUT_IP NEW_RESULTS_DIR
```

For the Figure 6 configuration, use total CPU count 16, matching the artifact's
16-core host, with two frontend cores and one dispatcher core; that leaves 13
compute engines. A total count of four leaves one compute engine. These are
**absolute upstream CPU IDs 0..N-1**, not Lauberhorn workers 44..47. Upstream
controls placement; do not rewrite it or silently equate total cores with
compute-worker count. Choose an appropriate idle target before running.
The server requires executable `/dev/shm`; the deployment procedure must
check that prerequisite and preserve its original mount configuration.
Upstream uses fixed `/tmp/dandelion_server` and `/dev/shm/shm_*` names and removes
them on exit. A fresh working directory does not isolate these paths. The runner
refuses existing state; run only on an otherwise idle target, with no concurrent
Dandelion instance. Native smoke checks use private mount/network namespaces.
Use the ordinary Ethernet HTTP path on port 8080, independent of Lauberhorn.

Retain image/binary hashes, kernel, CPU model/frequency policy, CPU affinity,
command lines, stdout/stderr, and original latency CSVs. Inspect every CSV for
non-200 status/timeouts and verify the expected request count before accepting
results: the unchanged client can exit successfully after HTTP errors.
Never overwrite an existing results directory.

### Diagnostic timestamps

The original client times HTTP response headers. The separate diagnostic client
checks every matrix element, preserves response-embedded timestamps and times the
full body; neither modifies upstream code. Keep these separate cohorts and the
measurement limitations described above.

```sh
# DUT: fresh diagnostic server and registry.
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline server-timestamps NEW_STATE_DIR 4
# Peer: separate diagnostic client; not the paper's latency experiment.
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline diagnostic \
  --host DUT_IP --out NEW_DIAGNOSTIC_DIR --samples 1000
```

## Deferred application work and shared mechanisms

Dandelion-specific extensions remain bracketed with the application:

- **Messages beyond one MTU:** chunk BSON inside small ONC-RPC transactions using
  logical message ID, total length, sequence/index and opaque bytes. Reassemble
  requests/results/nested calls before BSON decoding or execution. Preserve the
  single-packet fast path, explicit bounds, expiry and duplicate handling. Retire
  chunk transactions promptly and keep logical-message state in software; initial
  scope excludes retransmission and must fail incomplete messages without partial
  execution. This is an application protocol, not IP fragmentation or generic
  large ONC-RPC support.
- **Communication nodes:** map a node to asynchronous nested RPC, suspend its
  composition and resume dependent nodes on completion. A compute → remote RPC →
  parallel compute → join example would exercise this mapping. It does not imply
  arbitrary HTTP compatibility. Side-effecting operations cannot inherit the
  pure-task retry assumption.

Dandelion could use [lookup scaling/spill](../proposals/lookup-spill.md) to relieve
shared routing-table pressure, and [request-context metadata](../proposals/request-context-metadata.md)
if an application needs peer information. These proposals own reusable mechanisms;
this document owns their Dandelion use cases. No shared mechanism is implemented
merely because a Dandelion workload could benefit from it.

## Student provenance and interpretation

Sven Glinz's bachelor thesis, *Porting Dandelion to the Lauberhorn smart NIC*
(February–August 2026), used report `ec49e8a`, platform `9e25181` and application
`69c3088`, based on upstream `98c4968`. The report and old sources live in
`../sven-dandelion/`. The evaluation used a kernel software NIC/scheduler mock,
with RX/TX ioctls and Linux networking, not the coherent FPGA datapath.

Reported 120–140 kRPS for one adder and about 60 kRPS for seven applications on
four cores, roughly 9% lower atomic latency, and 2%/4% slower depth-2/depth-4
compositions are mock-only, workload-specific results. Protocol and scheduling
changes are confounded; projected FPGA throughput is not a measurement.
The old callback/EXEC_FIBER API is superseded by the managed-outcome contract.
No fully designed direct-reply tail-call protocol was recovered from the report.
The current generic `FORWARD` API is a separate local-relay mechanism.

The full Dandelion paper suite and CHERI/Wasm/KVM reproduction are outside this
bounded integration. CHERI projections would need comparable component boundaries
and hardware assumptions; a full-invocation paper number cannot be treated as
pure sandbox cost and combined with our transport latency.
