# Hot echo benchmark campaign

The new workload is defined by `sw/apps/microbenchmarks/bench.x`: `echo0` is
void to void; `echo8`, `echo128` and `echo1432` return fixed opaque application
bytes. `rpcgen` produces runtime codecs; `echo_registry.py` derives procedure,
size and codec registration from the same schema. The ONC-RPC XID is the only
request identifier, including size zero. Adder acceptance and legacy loopback
measurements retain their original semantics and provenance.

## Implementation and acceptance status

The schema, responders, duration-based generator, selection policy, raw loaded
importer and detached controller are implemented. Local wire, scheduling,
complete-loss, induced-generator-stall and legacy replay tests have passed.
The controller's hardware acquisition adapter and unloaded echo breakdown importer
are not yet connected. No new board rehearsal or full echo campaign has run.
The plotting entry point has not yet been validated against a real full echo
cohort; existing active figures remain in place. Do not launch a controller with
an invented passing adapter or use the historical add data as echo evidence.

## Generator contract

Keep the existing positional invocation for endpoint and output selection. For
loaded runs set `LH_BENCH_RATE` (aggregate requests/second),
`LH_BENCH_WARMUP_MS`, `LH_BENCH_DURATION_MS`, and `LH_BENCH_FLOWS`.
Set the positional timeout to `10000` microseconds. In this mode duration/rate
replace positional COUNT/WARMUP; EAL lcores determine generator CPUs. Use eight
isolated CPUs and identical flows for both systems. Do not set profiling or
FPGA recording. Single-outstanding unloaded acquisition omits the open-loop
variables and the legacy window variable.

The aggregate schedule is `start + floor(slot * 1e9 / rate)`; core `i` owns
slots `i, i+cores, ...`. Replies and TX stalls never reset it. A slot missed by
one local interarrival interval is recorded as a generator miss; no queued
catch-up batch is transmitted. RTT starts before encoding and ends at the
RX-burst observation, before forwarding to the owner and validation. Scheduled
start and lag are separate CSV fields. Reply classification uses that original
RX timestamp even if owner processing is delayed. The 10 ms deadline is
relative to call entry; the final drain does not extend the send interval.
A bounded additional 10 ms drain observes late replies; later arrivals remain
unobserved losses, not fabricated late replies.

Tracking is allocated and prefaulted before NIC setup, with a 4 GiB limit and no
XID wrap. Output space is reserved before acquisition at 256 bytes per scheduled
request. File or tracking exhaustion is terminal. Missing replies do not stall
the schedule. `samples.csv.open.json` records on-time replies, observed late
replies, duplicate replies, missed sends, failed admissions, malformed replies,
actual generator CPUs and NIC counters. CSV statuses are 0 for valid on-time
(with `ok=1`), 2 expired, 3 malformed, 4 late, 5 missed, 6 failed admission.
Loss is `(transmitted - on_time) / transmitted`. Actual offered load and goodput
use the fixed measured send interval; warmup is excluded. A generator miss in
either warmup or measurement invalidates the acquisition.

Use `LH_NULLRPC_BATCHED=1`, `LH_NULLRPC_SOFTWARE_CONTROL=1` and a fresh
`LH_NULLRPC_STOP_FILE` for a loaded DPDK responder. The campaign creates that
file after client draining, allowing clean counter export without requiring
all sent requests to arrive. A 120-second bound remains as an orphan safeguard.
`LH_BENCH_ACTIVITY_FILE` on the LH responder enables a fresh shared activity
file with one 64-byte record per worker: little-endian uint64 call count, int32
CPU, int32 TID, 48 padding bytes. Snapshot differences between quiescent
cohorts establish worker activity, not successful completions. Leave this
variable unset for unloaded software-uninstrumented measurements.

## Controller and adapter boundary

`tools/paper/benchmark_campaign.py` owns preflight, build, rehearsal, frozen
rate grids, extensions, sustained confirmation, resume, locks and callbacks.
A JSON launch configuration supplies `adapter` (argv array), `thread`,
`generator_cpus` (eight distinct CPU IDs), `flows`, `initial_rate`,
`max_extensions` (1–30), and `deadlines` in seconds for `preflight`, `build`,
`loaded`, `unloaded`, `rehearsal-validation` and `finalize`. An optional `notify`
argv defaults to `codex queue`.

The adapter receives `STAGE REQUEST.json RESULT.json`. It must use maintained
EMG/harness acquisition commands and emit a result with `passed: true` and
`files`, mapping absolute evidence paths to SHA256 hashes. Exit nonzero and
preserve evidence on failure. The controller has additional explicit stage gates
in `Campaign.stage`; these are assertions to implement and verify, not fields
that can be set merely because a subprocess exited zero. Loaded stages return
raw `csv`, `summary`, actual placement/flows and per-worker activity. Unloaded
stages require complete MAC pairs, XID correlation, no stored loss, independent
JTAG checks, health and restoration. Preflight must check reservations, artifact
matching, budgets, isolated CPUs, prior-state preservation and actual callback
delivery. The final stage must archive raw observations and provenance, validate
all cohorts, generate figures, validate dimensions/fonts and supply previews.

Grid cohorts use 2-second warmup, 10-second measurement, three repetitions;
sustained candidates use 2+60 seconds and three repetitions. Rehearsal uses
1+1 seconds for loaded cohorts and 16+100 requests for unloaded gates. It
brackets workers 1 and 4 geometrically through >1% loss and another overload
point. Workers 2/3 start from conservative interpolated brackets; every full
grid extends geometrically until it crosses 1% or fails a resource/generator
gate. All grid points are retained. Candidate ranking uses goodput within the
contiguous prefix before the first rate where any repetition exceeds 1% loss;
failed sustained confirmations step down through that candidate order.

Once a concrete adapter and its rehearsal gates are ready:

```sh
python3 tools/paper/benchmark_campaign.py launch --config CAMPAIGN.json --root out/echo-CAMPAIGN
# Only when requested by the user, or after a terminal callback:
python3 tools/paper/benchmark_campaign.py status --root out/echo-CAMPAIGN
# Explicit compatible resume, retaining failed attempt directories:
python3 tools/paper/benchmark_campaign.py launch --resume --config CAMPAIGN.json --root out/echo-CAMPAIGN
# Retry only callback delivery:
python3 tools/paper/benchmark_campaign.py notify --root out/echo-CAMPAIGN
```

The detached user service has a stop hook for unexpected controller termination.
Durable notification state prevents ordinary duplicate delivery; failed delivery
schedules another notification attempt, never acquisition. No intermediate agent
polling after launch. Read atomic `status.json` and structured `events.jsonl`.
Code/configuration changes require a fresh campaign root. Completed stages are
reused only with matching hashes; current reservations are checked again on resume.

## Validation and figures

```sh
nix build .#dev.x86_64-linux.software.trafficGeneratorNative -o out/echo-generator
nix develop -c python3 sw/apps/microbenchmarks/dpdk/replay_test.py out/echo-generator/bin/bench_dpdk out/echo-replay
nix develop -c python3 sw/apps/microbenchmarks/dpdk/load_replay_test.py out/echo-generator/bin/bench_dpdk out/echo-load-replay
nix develop -c python3 sw/apps/microbenchmarks/dpdk/open_loop_replay_test.py out/echo-generator/bin/bench_dpdk out/echo-open-replay
PYTHONPATH=tools/paper nix develop -c python3 -m unittest campaign.test_campaign -v
nix develop -c python3 tools/paper/plot_echo.py --input CAMPAIGN/loaded.json
```

The loaded plotting script uses the shared paper library. It pools on-time RTT
observations across equal-duration repetitions, retains offered-rate ordering
and nonmonotonic goodput, and shows median/P99. Overload plots retain every rate
and trial, with a 1% line. Confirmed peak plots show median and trial range.
Regular arrivals are covered; Poisson arrivals are deferred to memcached.
Unloaded MAC CDF and instrumented service-path elapsed components belong to
separate cohorts; never assign those components to uninstrumented totals or
claim they independently measure CPU utilization.
