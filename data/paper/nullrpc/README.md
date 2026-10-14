# Null-RPC paper measurements

## Current figures: October 13, pipeline 518922

All four current charts use matched hardware/runtime
`cc19c4285148db65c5b5517f6a02abd04b7f9142` (pipeline 518922). This was the latest
successful hardware build at acquisition; the read-only CI query is retained.
The client source is `4e1372c0`; its deployed SHA-256 is
`9da72e4c465e992326898e7339d3da306eb7a80992796acdf560efc83daf98fa`.
No FPGA, kernel or server runtime implementation was changed for these runs.
Timing closure is not implied.

Across four matched cold boots, the campaign passed **185,000 measured requests,
2,304 warmups and 400 setup calls**, plus 160,000 validated eviction-preparation
RPCs. Every requested worker served measured requests in every scaling trial.
All four complete captures have matching request/reply counts and zero stored
loss markers (347,704 RPCs including preparation). The final runtime has four
resident workers on CPUs 44–47; traces are frozen and the one-shot runtime export
is consumed. Recheck reservations before reuse.

The one-worker campaign passed **95,000 measured requests + 1,152 warmups**,
following a 100-RPC setup check. The runtime exported all **96,252** rows with
zero drops. Hardware health checks found no TAD interrupt/timeout or new kernel
fault. The complete frozen trace contains exactly 256,252 admitted RPCs and
normal replies (including 160,000 eviction-preparation RPCs), with zero stored
loss markers. Kernel instrumentation was off; runtime buffering and FPGA tracing were
on. The DUT used Linux 6.8.0-64-generic and the DPDK peer 6.8.0-146-generic.
The static peer neighbor is required because the DPDK client does not answer ARP.

- Hot CDF: all 10,000 measured requests with one outstanding request. Client-observed
  RTT p50/p99 is **12.650/16.590 µs**; the maximum **48.520 µs** remains included.
- Cold CDF: 1,000 measured requests with eviction preparation before each RPC.
  RTT p50/p99 is **43.515/48.490 µs**, maximum **110.790 µs**.
- Hot throughput–latency: six closed-loop windows, 1/2/4/8/12/16 outstanding
  requests, each with 128 warmups and 10,000 measured replies. Achieved throughput
  rises from **75.765** to approximately **156.136 kRPC/s**. The plot shows the
  median and 99th percentile RTT at each achieved rate.
- Cold throughput–latency: five 1,000-request cohorts with minimum pacing gaps
  0/0.5/1/2/5 ms and one outstanding measured RPC. Two 16-request mul bursts with
  100 µs flush-handler work precede every add request. Throughput uses the whole
  recorded measurement interval, **including eviction preparation and pacing**;
  RTT excludes both. Maximum achieved cold throughput is **1.066 kRPC/s** for
  this workload, not a claim about maximum activation-only service capacity.
  Every cold measured RPC has a distinct runtime activation. The same logical
  thread may visit different Linux CPUs across activations; CPU count is not
  worker-pool size.
- Worker scaling: three 10,000-request cohorts per pool size at window 16, with
  128 warmups per cohort. All four pool sizes passed. Median trial throughputs are
  **156.135, 311.762, 466.850, and 624.687 kRPC/s** for 1–4 workers.
  The plot requires every requested worker to serve measured requests, each in
  one resident activation on a distinct CPU. Its center is the median trial
  throughput and error bars show the full observed trial range, not confidence
  intervals.

These are finite, single-client closed-loop measurements. They do not establish
open-loop queueing/SLO behavior, avoid coordinated omission, or independently
establish that a throughput plateau is server-limited. Each worker-pool size
uses one cold boot; repeated cohorts on that boot are not independent boots.
CDFs and load points share the latest hardware. Current latency is
`client_rx_burst_ns - client_call_enter_ns`: from just before request encoding to
observation of the RX burst containing the validated response. It includes
encoding/enqueueing and polling, but excludes later response validation and
buffer cleanup. Both client modes record this same boundary; their final
`client_call_exit_ns` markers occur on opposite sides of buffer cleanup, so those
markers are retained but not mixed in the current figures. This is neither a
hardware wire timestamp nor a calibrated one-way network measurement. No clock
probe cost is subtracted. The older baseline remains below.

`manifest.json` pins input hashes and observation windows. Each `.csv.gz` stores
verbatim client or runtime rows with deterministic gzip metadata. Per-run
`acquisition/` records retain runner status/restoration, preparation state,
observation-window accounting, acquisition plans and hardware/client provenance.
Import uses all successful measured rows, rejects failed runs/duplicate IDs and
joins each drained cohort to its runtime requests; no percentile reconstruction
or tail clipping is used. The hardware capture stays in ignored output storage,
with its hash/status tracked; RTT and throughput regenerate from Git alone.

```sh
nix develop -c python3 tools/paper/plot_nullrpc.py --charts cdf
nix develop -c python3 tools/paper/plot_nullrpc.py --charts throughput
nix develop -c python3 tools/paper/plot_nullrpc.py --charts all
```

PDFs go to `../paper/plots/` and are committed there. Acquisition/build/import
instructions are in [the plotting workflow](../../../tools/paper/README.md).
The exact hardware setup and ordered load plans are under `acquisition/`.
Recheck reservations, use new output labels and preserve existing traces before
replaying any plan. The original missing-neighbor pilot and a 64-outstanding
capacity-overload warmup remain documented in `acquisition/failure-provenance.json`;
neither contributes a plotted sample. The latter full frozen capture is preserved
with checksum, and its bounded burst has 64 RX, 22 normal RPC admissions/replies,
and 64 TX events, consistent with context-capacity rejection.

## Preserved October 10 latency inputs

`hot-latency.csv` and `cold-latency.csv` are verbatim client files from
`out/nullrpc-inline/hot-off2.csv` and `out/nullrpc-inline/cold-off.csv`.
Both contain 1,000 successful measured requests; hot additionally contains 16
marked warmups, while cold has none.
The manifest pins their SHA-256 hashes. No raw samples were reconstructed from
percentiles. These historical figures use the original full-call endpoint, so
their numbers are not a like-for-like improvement comparison with the current
RX-observation CDFs. RTT is `client_call_exit_ns - client_call_enter_ns`, converted from
nanoseconds to microseconds; no clock-overhead subtraction is applied.

The acquisition ran on October 10, 2026, using pipeline **518689**, hardware
`45cbfe52`, software `8a1521ee`, one measured add worker, and a separate four-worker
mul domain for cold preparation. Kernel tracing was off; buffered runtime and
FPGA tracing remained on. The workload uses one outstanding request. These are
historical latency measurements, not measurements on the newest hardware and
not evidence of saturation throughput. The cold preparation completed two
16-request mul bursts before each measured add request, outside its timed RTT.
The existing analysis established one activation per cold request.

The [full report](../../eci/board-evidence/dpdk-inline-hot-cold-518689-20261010.json)
and [exact acquisition/build inputs](../../eci/board-evidence/dpdk-inline-hot-cold-518689-20261010-inputs/README.md)
retain deployment provenance, cold validation, failure recovery, instrumentation
and timing limitations. The pinned build recipe is
`tools/enzian/nullrpc_inline_build.nix`. Do not replay historical acquisition paths
on live services; use fresh labels and the reservation/preservation procedure in
`docs/development/hardware-test.md`.

Reproduce the historical CDFs entirely from tracked data:

```sh
nix develop -c python3 tools/paper/plot_nullrpc.py --charts cdf \
  --manifest data/paper/nullrpc/historical-manifest.json --output out/historical-cdf
```

Outputs are `out/historical-cdf/nullrpc-hot-cold-latency-cdf.pdf`. Expected p50/p99
RTTs: hot **12.760/13.640 µs**, cold **44.440/53.8614 µs**. The script verifies
hashes and counts, removes only warmup, and renders the full empirical CDF.
