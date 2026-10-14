# Upstream Dandelion baseline on Enzian — October 8

The unchanged pre-Sven process backend and original paper load generator ran
successfully on both existing reserved machines, after the maintainer resumed
board access. **All 8,000 measured requests passed, but the paper's latency
numbers were not reproduced.** No machine was acquired, rebooted, or FPGA
reprogrammed. Neither kernel module state nor FPGA tracing was changed.

Build and run instructions are in [the baseline recipe](../../nix/benchmarks/dandelion-baseline/README.md).
The server is `98c4968017c050391475a20da8e30bbabf6ea618`, immediately before
Sven's port. The original [SOSP artifact](https://github.com/eth-easl/dandelion_sosp_ae)
client is pinned to `2a8e23d0aa641f9de91cbc35723ca2c6cd08536e`.

## Unloaded results

Each row below contains 1,000 sequential 1x1 matmul requests, with no explicit
warmup. The unchanged `rload` uses `closed-unloaded`. Cold and hot refer to
function loading/caching; **both create a fresh process per invocation**.
The context is 8 MiB and the original AArch64 function ELF is unchanged.
Four total upstream cores mean dispatcher CPU 0, frontend CPUs 1–2 and **one
compute engine on CPU 3**, not four compute workers. The server remains resident.

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

## Process timing and the paper

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
span is not introduced solely by Sven's transport/dispatch port. The earlier
port also used a roughly 128 MiB context, rather than this 8 MiB context, so
its 3.410 ms selected engine span is not a controlled before/after comparison.

## Reproduction limits

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

## Build validation and retained evidence

The deployable AArch64 SquashFS is 85,975,040 bytes, SHA-256
`7f071ba2438a6163dde814f4481d3949614774941bd147a88fd651cd024696ac`, at
`/nix/store/fyw8w9kdb9jisq0c64dfp3nz9619kgkk-dandelion-upstream-baseline.img`.
It contains runtime dependencies and no compiler, Lauberhorn driver or FPGA
image. The static worker uses the original artifact's AArch64 flags.

Source audit verified all 125 upstream files unchanged, with only root
Cargo.lock added, and all ten selected original client files unchanged.
Both servers, worker, client, function and wrapper interpreters are AArch64.
The worker has no interpreter/dynamic segment and loads at `0xaaaaaaaa0000`.
The fixture SHA-256 is
`ab570741dcc23e098db3f71c4d686a4bd9e60edd9af66a49c876945f7b9060d0`.
Target CLI checks passed under QEMU and on the machines. Native private-namespace
smoke tests passed 32 original-client plus 16 diagnostic requests. Initial
ptrace-capability and wrapper-interpreter packaging failures were preserved
and corrected before the accepted board cohorts. Shellcheck, Python compilation
and the source/ELF audit passed.

Evidence is retained under ignored `out/dandelion-baseline/` in the primary
platform worktree: `paths.json`, `validation.json`, `image-files.txt`,
`native-smoke-02/summary.json`, and **`board-record/`**. The latter archives
exact orchestration scripts, commands, original CSVs, diagnostic timestamp
strings, server placement/state and clean-stop records for `normal4`, `trace4`,
`peer-normal4`, `peer-trace4`. The gateway copy is
`/scratch/pengxu/dandelion-baseline-20261008`. All four servers started by this
campaign were stopped after their cohorts; the image mounts remain available.

An old stopped, orphaned `mmu_worker` PID 1722 on zuestoll14 was preserved,
including its mappings/status; its shared-memory backing was renamed to
`/dev/shm/preserved_dandelion_baseline_20261008_shm_0` without deleting it or
killing the process. The existing wide-fanout failure capture remains intact.
No FPGA trace controls, driver state, reservations or boot configuration were
changed by this campaign.


## Port parity correction — October 8

The port now builds the process worker separately with the same static-link
and AArch64 image-base flags as this upstream baseline. Its latency client and
functional fixtures register 8 MiB contexts. Atomic latency defaults to 1x1;
`--matmul-size 2` selects the historical report workload, and
`--context-size BYTES` permits explicit context controls. The existing
`dandelion-cohort.sh` now selects size 2 explicitly to preserve its atomic/DAG
workload shapes. Re-register functions before changing context size; skipping
registration retains old registry values. The matmul ELF is unchanged.

Source correction `1a9eb8f6bafef9be497d7677951bac192dfe3f6d` is published on
Dandelion branch `fix/upstream-process-parity`, based on the other thread's
already committed `f95d33d` changes. The platform pins this exact source.
The branch is retained because the source main worktree belongs to that other
thread; it can be merged there without dropping the concurrent changes.
This correction does not change scheduling policy, CPU placement or transports.

Local validation passed: AArch64 release builds, five existing codec/capacity
checks under the cross runner, and 48 checked native requests through real
process execution using the corrected native static worker with the unchanged
upstream HTTP server. The native run used private namespaces and namespace-local
ptrace capability. This validates the worker/bootstrap path, not Lauberhorn
hardware or composition execution at the smaller context size.
The target worker has no ELF interpreter or dynamic segment and loads at
`0xaaaaaaaa0000`; the packaged matmul hash matches the original fixture.

The deployment image reuses the measured runtime closure
`/nix/store/cm5kzyviqllgvfxm1aiz9p8ifkin51v0-lauberhorn-rt-aarch64-unknown-linux-gnu-0.0.1`
and is available at
`/nix/store/0rpis5mwq2dsiayb0rdam575ksgfvly4-dandelion-process-parity.img`.
Its SHA-256 is
`399f629537b272340504d079e25c23ab2e7a5bd8bdc86d634d8459a83a0ced74`.
Build expressions, paths, logs and `validation.json` are retained in
`out/dandelion-parity-results/` in the primary platform worktree.

**Initial local closeout (superseded by the authorized rerun below):** The maintainer assigned the machines to
another thread and explicitly paused machine testing. Before that instruction,
a read-only console preflight found an existing attachment and exited without
running a board command. No new latency result or explanation of the earlier
1.264 ms execution gap is claimed. The next board comparison should use fresh
registrations, matching 8 MiB contexts, static workers and explicit 1x1/2x2
cohorts, with CPU placement and warmup recorded separately.


## Aligned atomic rerun — October 8

After renewed authorization, the aligned image passed a fresh harness-controlled
cold boot and **3,000 measured atomic requests**, plus 240 concurrent residency
warmup requests and 200 serial warmups. Every reply's full matrix, RPC status,
source address and XID passed the client checks. All six QLMs and CCPI/ECI lanes
0–23 passed bring-up on the first attempt. RT bandwidth throttling was disabled.
FPGA 516232, corrected module `af0f64fe`, and the original atomic-four runtime
`ce271135` were reused. The image and static worker are the audited artifacts
above; there is no claim here about the subsequent runtime credit changes.

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

### Evidence and current state

`out/dandelion-aligned-atomic/` contains the manifest, scripts, successful
`trial/summary.json`, `provenance.json`, `comparison.json`, raw request/event
CSVs under `results/`, and updated `figures/atomic1-four` and `atomic2-four`
SVG/PNG/JSON waterfalls. The shared copy is
`/scratch/pengxu/dandelion-aligned-atomic-20261008`. The software export has
3,440 successful request rows and 24,080 events, **zero dropped records**.

The successful aligned server remains running, with software buffers already
exported. FPGA tracing is frozen: loss was clear before freeze, asserted after
freeze; wrap and DMA error stayed clear. The buffer remains on the FPGA and
has not been downloaded or correlated. These breakdowns are independently
validated software timings, with no new FPGA clock-alignment claim. Existing
wide-fanout success/failure captures remain preserved on disk.
