# Null-RPC paper measurements

## Latency CDFs

`hot-latency.csv` and `cold-latency.csv` are verbatim client files from
`out/nullrpc-inline/hot-off2.csv` and `out/nullrpc-inline/cold-off.csv`.
Both contain 1,000 successful measured requests; hot additionally contains 16
marked warmups, while cold has none.
The manifest pins their SHA-256 hashes. No raw samples were reconstructed from
percentiles. RTT is `client_call_exit_ns - client_call_enter_ns`, converted from
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

Reproduce both CDFs entirely from tracked data:

```sh
nix develop -c python3 tools/paper/plot_nullrpc.py --charts cdf
```

Outputs are `../paper/plots/nullrpc-{hot,cold}-latency-cdf.pdf`. Expected p50/p99
RTTs: hot **12.760/13.640 µs**, cold **44.440/53.8614 µs**. The script verifies
hashes and counts, removes only warmup, and renders the full empirical CDF.

## Load measurements

Load acquisition is in progress on branch `paper/nullrpc-microbenchmarks`.
`manifest.json:load_runs` is empty until validated measurements are imported.
The plotter refuses to manufacture the three missing figures.

The new client records an explicit monotonic observation window in each
`CLIENT.csv.load.json`. Achieved throughput is successful measured completions
per elapsed second, including drain time. The window excludes warmup. In
per-request eviction mode it includes preparation; its RTT does not. A windowed
hot run is closed-loop and must not be described as an open-loop offered-rate
experiment. Worker scaling requires observed worker allocation as well as the
configured pool size. Plotting provenance must retain generator window/pacing,
actual worker evidence and instrumentation settings for each run.
