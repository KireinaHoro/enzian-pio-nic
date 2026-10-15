# Hot echo benchmark campaign

The new workload is defined by `sw/apps/microbenchmarks/bench.x`: `echo0` is
void to void; `echo8`, `echo128` and `echo1432` return fixed opaque application
bytes. `rpcgen` produces runtime codecs; `echo_registry.py` derives procedure,
size and codec registration from the same schema. The ONC-RPC XID identifies client
requests, including size zero; existing internal RPC IDs pair FPGA events. Adder acceptance and legacy loopback
measurements retain their original semantics and provenance.

## Implementation and acceptance status

The schema, responders, duration-based generator, selection policy, raw loaded
importer and detached controller are implemented. Local wire, scheduling,
complete-loss, induced-generator-stall and legacy replay tests have passed.
The unloaded importer validates separate MAC and instrumented cohorts, including
matched controls. `echo_hardware.py` connects the controller to the maintained
Enzian harness through `campaign/adapter.py`, `hardware.py`, and the remote role
helpers. Local transport, cleanup and archive fixtures pass; real preflight
passed reservations, isolation, budgets, artifact matching, preservation and
callback delivery. A detached local fixture completed 66 synthetic cohorts,
delivered one terminal callback, and stopped before the full grid; the separate
forced-termination fixture exercised the service failure hook. Twenty-one local
policy/import/transport tests pass. These are workflow tests, not board results.
The first native 8-byte rehearsal cohort has 100 valid measured replies and 16
warmups, complete measured MAC pairing and valid client causal bounds. Its
133-request whole capture (including preparation) has no stored loss markers.
Full rehearsal acceptance is still pending. The setup neighbor miss is now
primed separately. The second attempt's Ethernet receiver completed, but Vivado
segfaulted on shutdown and the old controller incorrectly entered full JTAG
fallback. The complete Ethernet file was recovered and hash-verified before
interrupting that fallback; failures remain in `out/echo-rehearsal-20261015*`.
LH correlation retains the existing internal RPC IDs, paired within
each request lifetime; single-outstanding order and causal bounds associate
those pairs with XID-validated client observations. No trace format or RTL
change is needed. No complete board rehearsal or full echo campaign has passed.
The plotting entry point has not yet been validated against a real full echo
cohort; existing active figures remain in place. Do not launch a controller with
an invented passing adapter or use the historical add data as echo evidence.

The currently authorized execution stops after the unattended rehearsal passes.
It emits `rehearsal-complete` and four validated plot previews; it never freezes
a full grid or acquires full cohorts. A later full campaign requires an explicit
`scope: "full"` configuration and a new campaign root. An omitted scope also
stops at rehearsal. Completed rehearsal callbacks and failure hooks are terminal
without accidentally restarting acquisition.

Unloaded CDFs and breakdowns use the same 8/128/1432-byte echo bodies as loaded
cases, with one worker and one outstanding request. Zero-body echo remains a
wire/dispatch validation case. A null RPC still includes dispatch and protocol
processing; payload-bearing calls additionally exercise payload decoding and
reply preparation. The largest body is 1432 bytes: 40-byte RPC call + 8-byte UDP
+ 20-byte IPv4 headers fill the 1500-byte MTU.

## Neighbor preparation

After each native cold boot, install the known permanent peer MAC in Linux and
send one separate echo to trigger the normal FPGA neighbor-cache fill. This
priming call has a bounded one-second deadline and its own validated archive;
it precedes 16 setup calls at the ordinary 10 ms deadline, all warmups, and all
measured requests. Priming is not a benchmark observation. Its possible Linux
bypass reply is preserved as preparation provenance, never assigned a fabricated
MAC pair. Every later setup/warmup/measured reply requires complete native MAC
pairing for unloaded cohorts. Loaded runs retain the 10 ms deadline and no trace
readouts. The DPDK client already addresses the responder using its explicit MAC.

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

Tracking is allocated and prefaulted before NIC setup, with a default 4 GiB
limit and no XID wrap. `LH_BENCH_TRACKING_MIB` sets the explicit preflight-checked
budget (at most 256 GiB); sustained trials must budget their full schedule. Output space is reserved before acquisition at 256 bytes per scheduled
request. File or tracking exhaustion is terminal. Missing replies do not stall
the schedule. `samples.csv.open.json` records on-time replies, observed late
replies, duplicate replies, missed sends, failed admissions, malformed replies,
actual generator CPUs and NIC counters. Schema 2 also records workload, warmup,
and absolute measured send-start/end timestamps; the importer checks each slot,
phase label, transmit boundary and recorded start lag against that schedule. CSV statuses are 0 for valid on-time
(with `ok=1`), 2 expired, 3 malformed, 4 late, 5 missed, 6 failed admission.
Loss is `(transmitted - on_time) / transmitted`. Actual offered load and goodput
use the fixed measured send interval; warmup is excluded. A generator miss in
either warmup or measurement invalidates the acquisition.

Use `LH_NULLRPC_BATCHED=1`, `LH_NULLRPC_SOFTWARE_CONTROL=1` and a fresh
`LH_NULLRPC_STOP_FILE` for a loaded DPDK responder. The campaign creates that
file after client draining, allowing clean counter export without requiring
all sent requests to arrive. The client publishes a drained marker before CSV
serialization, so stop delivery never waits for large output files. A 120-second
bound remains as a responder orphan safeguard.
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
`max_extensions` (1–30), `scope` (`rehearsal` by default, or explicitly `full`), and `deadlines` in seconds for `preflight`, `build`,
`loaded`, `unloaded`, `rehearsal-validation` and `finalize`. An optional `notify`
argv defaults to `codex queue`. Supply `ENZIAN_BMC_PASSWORD` in the launch
environment so cold boots cannot prompt; the service inherits it and any SSH
authentication socket. While geometric bracketing is unfinished, status `total`
is the finite maximum cohort budget; terminal status records the exact count.

The adapter receives `STAGE REQUEST.json RESULT.json`. It must use maintained
EMG/harness acquisition commands and emit a result with `passed: true` and
`files`, mapping absolute evidence paths to SHA256 hashes. Exit nonzero and
preserve evidence on failure. The controller has additional explicit stage gates
in `Campaign.stage`; these are assertions to implement and verify, not fields
that can be set merely because a subprocess exited zero. Loaded stages return
raw `csv`, `summary`, actual placement/flows and per-worker activity. Unloaded
stages return `cohort` from `campaign.import_unloaded`, augmented with a
`build_config_sha256` fingerprint excluding instrumentation toggles. They require
complete MAC pairs, validated identity correlation, no stored loss, independent
JTAG checks for LH (matched PTP module/clock checks for DPDK), health and restoration. `full-unloaded.json` keeps the 18 distinct
cohorts: both systems, 8/128/1432-byte bodies, and three instrumentation modes.
Internal FPGA RPC IDs pair trace events within request lifetimes; the client validates wire XIDs. Preflight must check reservations, artifact
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

The hardware adapter argv is `python3 tools/paper/echo_hardware.py HARDWARE.json`.
Its manifest names immutable build products, artifact hashes, the matched boot
case and trace map, reservations/endpoints, RAM/storage budgets, and a dedicated
transfer directory. Build commands realize the frozen products; source-defined
Nix builds establish them before configuration is frozen. The adapter stages
images with streaming SHA256 verification. DPDK receives a fresh harness boot
without native workers. Native loaded cohorts reuse a resident pool only while
size and worker count match; configuration changes use a verified cold boot.
Normal loaded runs never dump traces. Ethernet capture failures retain partial evidence and return failure without
a full JTAG fallback or a hardware reset. Independent 4 KiB JTAG head/tail
checks validate successful Ethernet captures. A recognized Vivado shutdown
segfault after a completed trigger permits receiver validation; it never
substitutes for a successful receiver, exact length, and matching SHA256.

Remote role outputs are removed only after their local archive and member hashes
are verified. Loaded CSVs are compressed after acquisition. The final archive uses
relative paths and lossless raw observations; echo compressed data uses Git LFS.
Presentation PDFs stay in the campaign directory until visual review, leaving
existing active figures intact. A failed finalization gets a fresh attempt
directory on explicit resume; it never overwrites an earlier archive.

Launch after local validation and live preflight:

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
PYTHONPATH=tools/paper nix develop -c python3 -m unittest campaign.test_campaign campaign.test_unloaded campaign.test_loaded campaign.test_acquire campaign.test_hardware campaign.test_trace_capture -v
nix develop -c python3 tools/paper/plot_echo.py --input CAMPAIGN/loaded.json
nix develop -c python3 tools/paper/plot_echo_unloaded.py --input CAMPAIGN/full-unloaded.json
```

The loaded plotting script uses the shared paper library. It pools on-time RTT
observations across equal-duration repetitions, retains offered-rate ordering
and nonmonotonic goodput, and shows median/P99. Overload plots retain every rate
and trial, with a 1% line. Confirmed peak plots show median and trial range.
Regular arrivals are covered; Poisson arrivals are deferred to memcached.
Unloaded MAC CDF and instrumented service-path elapsed components belong to
separate cohorts; never assign those components to uninstrumented totals or
claim they independently measure CPU utilization.

Sixteen local Python regression tests and 20 native replay cases pass.
The local service-crash fixture exercised an actual SIGKILL and ExecStopPost
notification delivery to a file-backed sink. All five plots also passed portable
synthetic-input rendering, exact PDF dimensions and embedded-font checks. These
fixtures are excluded from paper inputs and are not board acceptance.
