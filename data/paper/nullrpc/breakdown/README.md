# Median MAC latency breakdown

This chart uses two horizontal bars and four additive categories: handler,
RPC runtime, network stack, and CPU–MAC. `summary.json` is generated directly
from archived observations by `tools/paper/plot_nullrpc_breakdown.py`.

Each bar averages the components of the two central measured requests after
sorting by MAC latency, with request ID breaking ties. Its sum therefore equals
the conventional sample median. These are representative median-case components,
not marginal component medians. Both central requests have the same total in
these datasets. All 10,000 measured rows are validated before selection; 128
warmups are excluded. No tail filtering or timestamp-overhead correction occurs.

| Category (µs) | Lauberhorn | DPDK, profiled |
| --- | ---: | ---: |
| Handler | 0.235 | 0.000 |
| RPC runtime | 1.475 | 0.250 |
| Network stack | 0.210 | 0.270 |
| CPU–MAC | 2.225 | 1.760 |
| Total | 4.145 | 2.280 |

## LH RPC runtime detail

For the same two central requests (2298 and 2306), the 1.475 µs RPC runtime
segment consists of 0.310 µs in the unmarshal stage, 0.295 µs in the marshal
stage, 0.795 µs in surrounding CPU runtime, and 0.075 µs in FPGA RPC processing.
The XDR-containing stages sum to 0.605 µs (41% of this segment), but do not isolate
pure XDR routines: decode includes argument allocation, and marshal includes
inline TX-buffer reservation. Probe overhead remains included. A narrower
pure-XDR comparison requires new probes inside these existing boundaries.
The plotter emits these additive details as `rpc_detail_ns` in `summary.json`.

## Boundaries and interpretation

**Lauberhorn** reuses pipeline 518922's one-worker hot CDF cohort, with runtime
and FPGA tracing enabled. Runtime rows are joined by acquisition ordinal and
request ID to the paired MAC records. The archived stage markers come verbatim
from the hash-validated complete capture; each required stage must occur exactly
once, in order, inside each request's MAC interval. All FPGA differences use the
same app clock at 5 ns/tick, adjusted for the recorded trace-source pipelines.

- Handler: `server_handler_exit_ns - server_handler_enter_ns`. This includes
  the existing handler tracing/dispatch boundary cost, not just arithmetic.
- CPU RPC/runtime: `server_tx_enter_ns - server_rx_exit_ns - handler`.
  This includes dispatch, XDR, tracing and inline TX-buffer reservation; the
  available probes cannot isolate pure XDR from that surrounding runtime work.
- FPGA RPC: `RxRpcEnqueueToHost - UdpDecoder` plus
  `UdpEncoder - TxHostReqAccepted`, added to CPU RPC/runtime above.
- Network: `UdpDecoder - RxAfterCdcQueue` plus
  `TxBeforeCdcQueue - UdpEncoder`. This is elapsed pipeline time, including
  stalls such as reply payload availability; it is not exclusive hardware
  resource occupancy.
- CPU–MAC: total paired `RxCmacEntry -> TxCmacExit` minus the other categories.
  It includes scheduling, ECI/cacheline transport, host work outside the CPU
  RPC span, MAC-facing queues/CDC and remaining NIC work. No cross-clock epoch
  fit is needed; only within-clock durations are subtracted.

**DPDK** uses a fresh October 14 cohort, with paired ThunderX MAC timestamps and
`LH_NULLRPC_PROFILE=1`. The six software probes use `CLOCK_MONOTONIC_RAW`; packet
RX observations are retained even if the next request arrives while draining the
preceding TX timestamp. The CPU interval runs from successful RX-burst return to
immediately before the TX-burst attempt. PMD enqueue and any retry belong to the
residual. Software and hardware clocks supply nanosecond durations, not a shared
epoch. The earlier injected 20 µs delay control established interval sensitivity;
this is not an independent frequency calibration.

- Network: time around `parse_udp` plus time around `make_udp`.
- RPC: the remaining CPU interval. This includes RPC validation/encoding,
  dispatch, timestamp bookkeeping, buffer preparation and any wait for the
  preceding timestamp completion after early RX observation.
- Handler: zero because this fixed-input responder validates operands 7 and 11
  and emits constant 18. There is no separately executed handler call. This is
  not a claim that a general application handler costs zero.
- CPU–MAC: paired PTP MAC interval minus the complete observed CPU interval.
  This includes RX/TX PMD work, DMA/completion visibility and polling delay,
  NIC processing and queueing. It is not an isolated interconnect latency.

Raw probe costs remain in their enclosing categories. Minimum adjacent-clock
probe time was 40 ns. A same-binary probe-off control passed 10,000 measured +
128 warmup requests with MAC P50 **1.858 µs**, versus **2.280 µs** profiled.
The 422 ns median difference is an observed sequential instrumentation effect,
not a per-component correction. The earlier CDF remains the original **1.843 µs**
DPDK cohort. Do not assign these components to that uninstrumented total.

## Acquisition, validation and reproduction

`acquisition.json` records image/source hashes, core placement, build revision,
control results, trace provenance and the failed disk-backed staging attempt.
No responder ran in that failed attempt. The image was restaged in RAM and a
fresh `pilot2` label used. Pilot2 passed 100 measured + 16 warmups. Both full
cohorts passed every request with zero RX/TX errors or missed packets; all six
client/server runners restored their NIC/resource state. No FPGA reprogramming,
DUT reset or Lauberhorn service deployment occurred. Both machines remain
reserved; services exited. Recheck reservations/state before reuse.

Build the matched image with `nix build .#trafficGeneratorImage`. After reservation
and kernel/module preflight, stage it at
`/dev/shm/dpdk-breakdown-20261014.img` on both machines. Run `acquire-dpdk.sh`
as root inside a private mount namespace, first on the DUT:

```sh
unshare -m bash acquire-dpdk.sh server hot 10000 128 1
# Wait for hot-ready, then on the peer:
unshare -m bash acquire-dpdk.sh client hot 10000 128 1
```

The last argument enables responder profiling. The control uses label `control`
and argument `0`; pilot uses `pilot2 100 16 1`. Paths, addresses and labels are
campaign-specific: use fresh names and recheck actual board state for reruns.
Download the completed RAM directories into local `dut/` and `peer/` directories.

```sh
nix develop -c python3 tools/paper/import_nullrpc_breakdown.py \
  --trace out/paper-microbenchmarks/w1-dump/trace/trace-dram.bin \
  --dpdk out/mac-latency-breakdown/dpdk
nix develop -c python3 tools/paper/plot_nullrpc_breakdown.py \
  --summary data/paper/nullrpc/breakdown/summary.json
```

The plotter validates input hashes, exact request/warmup coverage, successful
runs/restoration, stage ordering, client elapsed-time bounds, nonnegative
components and MAC-interval conservation for every measured request.
The fixed-canvas vector PDF is `../paper/plots/nullrpc-hot-latency-breakdown.pdf`.
It is included in `evaluation.tex`.
