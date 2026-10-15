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
Unloaded measurements use two distinct builds: tracing compiled out with client
RTT only, and tracing enabled with paired MAC/PTP timestamps and a breakdown.
There is no redundant runtime-off control. The off build excludes trace hooks,
storage, PMU support and export threads; FPGA recording and DPDK PTP are off. `echo_hardware.py` connects the controller to the maintained
Enzian harness through `campaign/adapter.py`, `hardware.py`, and the remote role
helpers. Local transport, cleanup and archive fixtures pass; real preflight
passed reservations, isolation, budgets, artifact matching, preservation and
callback delivery. The detached workflow and forced-termination fixture have exercised terminal
callback delivery and the service failure hook. Twenty-three local policy/import/transport tests pass. Both native runtime
variants pass RPC/UDP/activation semantic tests, and binary symbol checks reject
tracing/PMU hooks in the disabled runtime and software/PTP timestamp hooks in
the disabled DPDK responder. Sixteen schema echo pcap cases pass. The revised
controller fixture completes 60 synthetic cohorts (12 unloaded), delivers its
terminal notification and stops before full collection. Revised unloaded plots
pass synthetic rendering, dimensions and font checks. These are workflow tests, not board results.
The first native 8-byte rehearsal cohort has 100 valid measured replies and 16
warmups, complete measured MAC pairing and valid client causal bounds. Its
133-request whole capture (including preparation) has no stored loss markers.
Those early cohorts used runtime-disabled probes, not the new compiled-out
configuration, and remain historical rehearsal evidence. Full rehearsal
acceptance is still pending. The setup neighbor miss is now
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
replace positional COUNT/WARMUP; EAL lcores determine generator CPUs. Use 32
isolated CPUs and identical flows for both systems. Do not set profiling or
FPGA recording. Single-outstanding unloaded acquisition omits the open-loop
variables and the legacy window variable.

Loaded campaigns may select paced batches with `batch_max: 64` and
`batch_target_ns: 250000` in both launch and hardware manifests. These become
`LH_BENCH_BATCH_MAX` and `LH_BENCH_BATCH_TARGET_NS` on the client. The actual
batch size is `clamp(floor(rate * target_ns / (cores * 1e9)), 1, batch_max)`.
Each core submits one mbuf burst per absolute batch opportunity; core schedules
are staggered over the first half of each equal-duration round. The partial
final round gets the same duration as earlier rounds, leaving preparation and
admission margin at the phase boundary. Warmup and measurement
are scheduled separately so a burst never contains both phases. Missed batches
are recorded without catch-up sends; partial TX admission invalidates the point.
This is a paced, bursty workload, not evenly spaced individual arrivals or
Poisson traffic. Record the actual batch size and schedule in every summary.
Both systems use the same policy. Do not combine these loaded points with the
older fixed-spacing cohorts; compatible unloaded evidence can be inherited.

With the default `batch_max: 1`, the legacy fixed-spacing mode is retained.
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
replies, duplicate replies, server rejection replies, missed sends, failed admissions, malformed replies,
actual generator CPUs and NIC counters. Schema 2 also records workload, warmup,
and absolute measured send-start/end timestamps; the importer checks each slot,
phase label, transmit boundary and recorded start lag against that schedule.
New observations declare `tx_phase_boundary: "admission-entry"`: the phase
boundary is `client_tx_admit_enter_ns`, sampled under the TX queue lock directly
before `rte_eth_tx_burst`. The existing `client_tx_submit_ns` remains the
post-call timestamp. A successful call may return just after its phase ends,
but must return before that core's next absolute send opportunity; a longer
stall invalidates the run. Both timestamps are retained, and only accepted
requests enter transmitted counts. Neither timestamp claims to measure wire
departure. RTT boundaries and the fixed scheduled interval denominator are
unchanged. Older observations without this declaration retain their original
post-call boundary validation; failed old observations are reacquired, not
relabelled. CSV statuses are 0 for valid on-time
(with `ok=1`), 2 expired, 3 malformed, 4 late, 5 missed, 6 failed admission,
7 server rejection. An exact matching 24-byte ONC-RPC `SYSTEM_ERR` envelope is
a rejection, not malformed traffic. Other invalid headers or payloads remain
fatal. The per-request `server_rejections` CSV field counts observed rejection
replies (including duplicates); the same summary field sums measured rows only.
Warmup rejection counts remain in the raw CSV. Older records without this field
have zero recorded rejections. Rejections neither abort an open-loop run nor
contribute goodput or latency samples. Loss remains
`(transmitted - on_time) / transmitted`, so a rejected request counts once
toward the 1% cutoff regardless of duplicate error replies. A subsequent valid
successful reply uses its own RX timestamp and may satisfy the deadline;
the earlier rejection count is retained. Rejection counters are preserved in
imported per-trial data for future panel choices.
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

`echoCampaignImage` packages both native application/runtime variants and both
DPDK responders in one hashed image. The native service mounts it in its own
mount namespace; the global matched kernel deployment is unchanged. Hardware
configuration records `native_variants` (app/runtime store paths and executable
hashes), per-mode `server_sha256`, and per-system/per-mode build fingerprints.
Snapshots check the running executable, runtime mapping and image manifest.
The general `trafficGeneratorImage` remains independent of native runtime builds.

## Controller and adapter boundary

`tools/paper/benchmark_campaign.py` owns preflight, build, rehearsal, frozen
rate grids, extensions, sustained confirmation, resume, locks and callbacks.
A JSON launch configuration supplies `adapter` (argv array), `thread`,
`generator_cpus` (1–32 distinct CPU IDs; current campaign uses 16–47), `flows`, `initial_rate`,
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
`build_config_sha256` fingerprint identifying the compiled variant. All runs
require validated client identity, health and restoration. Instrumented runs
also require complete MAC pairs, no stored loss, independent JTAG checks for LH
(or matched PTP module/clock checks for DPDK). Off runs require compiled-out
tracing, frozen FPGA recording, no PTP and no trace readout.
`full-unloaded.json` keeps 12 cohorts: both systems, 8/128/1432-byte bodies,
and two instrumentation modes.
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

All trace lifecycle operations now use the canonical
[Lauberhorn trace infrastructure](../../tools/enzian/trace/README.md).
`Harness.capture` calls the same session API as the manual CLI; `Harness.vio`
uses its recording-only snapshot/freeze/restore controls. The existing CMAC
receiver and JTAG reader remain the only low-level dump implementations. The
paper-specific dump/check wrappers and remote-only VIO script dependency are
retired. Manual validation on the already-frozen 519555 recovery buffer preserved
975,586,752 bytes with matching SHA256 and independent 4 KiB head/tail checks;
controls were restored with recording frozen. This is transport validation, not
a passing benchmark cohort or a claim of loss-free workload traces. Evidence is
`out/echo-redesign/canonical-trace-validation/summary.json`. Acquisition hashes include the shared Python/Tcl and receiver sources.

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
Configuration/build changes require a fresh campaign root. Compatible source fixes
can resume the same root with an explicit, hash-bound recovery review:

```sh
python3 tools/paper/benchmark_campaign.py recovery-plan --root out/echo-CAMPAIGN > out/recovery-review.json
# Fill reason, hashed validation evidence, and a reason/action for every receipt.
python3 tools/paper/benchmark_campaign.py launch --resume --recovery-review out/recovery-review.json --config CAMPAIGN.json --root out/echo-CAMPAIGN
```

The template defaults every completed stage to `rerun`. Mark unaffected acquisition
receipts `reuse` after reviewing the fix and validating any affected analysis
against preserved observations. `evidence` maps absolute validation-artifact paths
to SHA256 hashes; each receipt decision contains its SHA256, action, and reason.
The exact old/new implementation hashes bind the review to the proposed change.
No additional user approval is required for a compatible recovery within the
already authorized campaign scope. Do not mark an affected result reusable merely
because acquisition succeeded; revalidate it or rerun that case.

Resume checks all saved evidence hashes, retains original attempts, archives old
receipts and both revision maps in `recoveries/`, and records acquisition code
hashes in new receipts. Only selected or unfinished acquisition cases run again.
Build/preflight and derived validation/plotting stages rerun after a code change.
The frozen grid is never silently regenerated; a changed rehearsal boundary that
conflicts with it stops for review. Configuration and non-code artifact changes
are rejected. Current reservations are checked again on every resume. Recovery
holds campaign/global locks and cannot modify a running controller's manifest.
Develop fixes outside its worktree until it stops. An unsafe hardware state still
requires evidence preservation, restoration and explicit recovery before preflight;
a code compatibility review does not waive those gates. Failed acquisition attempts
without a completed receipt are retried, not promoted to success. A final archive
includes the recovery history.

## Validation and figures

```sh
nix build .#dev.x86_64-linux.software.trafficGeneratorNative -o out/echo-generator
nix develop -c python3 sw/apps/microbenchmarks/dpdk/echo_replay_test.py out/echo-generator/bin out/echo-schema-replay
nix develop -c python3 sw/apps/microbenchmarks/dpdk/replay_test.py out/echo-generator/bin/bench_dpdk out/echo-replay
nix develop -c python3 sw/apps/microbenchmarks/dpdk/load_replay_test.py out/echo-generator/bin/bench_dpdk out/echo-load-replay
nix develop -c python3 sw/apps/microbenchmarks/dpdk/open_loop_replay_test.py out/echo-generator/bin/bench_dpdk out/echo-open-replay
PYTHONPATH=tools/paper nix develop -c python3 -m unittest campaign.test_campaign campaign.test_unloaded campaign.test_loaded campaign.test_acquire campaign.test_hardware campaign.test_trace_capture campaign.test_recovery -v
nix develop -c python3 tools/paper/plot_echo.py --input CAMPAIGN/loaded.json
nix develop -c python3 tools/paper/plot_echo_unloaded.py --input CAMPAIGN/full-unloaded.json
```

The loaded plotting script uses the shared paper library. It pools on-time RTT
observations across equal-duration repetitions, retains offered-rate ordering
and nonmonotonic goodput, and shows median/P99. Overload plots retain every rate
and trial, with a 1% line. Confirmed peak plots show median and trial range.
Regular arrivals are covered; Poisson arrivals are deferred to memcached.
Unloaded client-RTT CDF and instrumented MAC service-path components belong to
separate cohorts; never assign those components to uninstrumented totals or
claim they independently measure CPU utilization.
For LH, UDP header dispatch can precede the RX CDC last-payload-beat event.
The elapsed-time partition assigns this overlap to host/MAC transport and
starts the remaining RX RPC interval at the later of those two events. The
RX network interval is zero when header decoding overlaps payload transport;
this does not imply that network processing performed no work. Each imported
row retains the original app-clock stages and `rx_streaming_overlap_ns`.
The r4 1432-byte rehearsal exposed a 105 ns overlap in all 116 observations;
its complete capture passed loss and independent head/tail checks. Offline
reimport of all three r4 instrumented sizes passes the corrected ordering,
causal bounds and component conservation checks. The controller failure remains
preserved; this offline validation does not mark that campaign complete.

The local service-crash fixture exercised an actual SIGKILL and ExecStopPost
notification delivery to a file-backed sink. All five plots also passed portable
synthetic-input rendering, exact PDF dimensions and embedded-font checks. These
fixtures are excluded from paper inputs and are not board acceptance.

### Generator isolation and placement transitions

The FPGA emits a 24-byte ONC-RPC `SYSTEM_ERR` response when its 16-entry
reply-context table fills. A targeted 256,292 RPC/s LH echo8/one-worker
reproduction preserved this response for XID 109 (9.23 us RTT), with passing
hardware health and restored peer controls, in
`out/echo-redesign/malformed-reply/reproduction/`. This exposed the former
success-only validator's overload misclassification. The client now accounts
for these rejections separately as described above. The diagnostic run is not
a passing acquisition or capacity result. The corrected client's first targeted
1 s warmup + 1 s measured trial at the same rate completed all 256,292 measured
transmissions: 230,970 on-time successes and 25,322 rejection replies (9.88%
loss), with zero generator misses or malformed replies. Its raw observations,
imported accounting and health checks are in
`out/echo-redesign/malformed-reply/rejection-validation/trial-0/`; it remains
diagnostic evidence, excluded from paper inputs.

The current generator uses CPUs 16–47, leaving 0–15 for housekeeping. Provision
boot isolation through EMG (`isolcpus=nohz,domain,managed_irq,16-47`,
`nohz_full=16-47`, `rcu_nocbs=16-47`, `irqaffinity=0-15`,
`kthread_cpus=0-15`, `rcu_nocb_poll`). Boot flags alone are insufficient:
`tools/enzian/dpdk_run.py` uses `cpu_isolation.py` to move device IRQs,
unbound workqueues and software receive steering away from benchmark CPUs,
verify effective IRQ affinity, and restore saved controls after each role.
IRQ affinity is best effort: an active irqbalance daemon, an unmovable IRQ or
observed IRQ activity is diagnostic, not a reason to invalidate a run.
The runner records interrupt counters before and after the process and restores
temporary controls. Validity depends on delivering the absolute send schedule
through both warmup and measurement: missed opportunities and TX admission
failures still invalidate the point. Corruption, resource, hardware health and
restoration failures retain their existing checks.

The single ThunderX VF exposes eight queues without additional queue-set VFs.
Up to 32 generator threads share eight serialized TX queues; eight threads own
RX polling and forward replies to their request owners. Absolute schedules
remain partitioned across all generator threads. Deadlines are rechecked after
TX lock acquisition; contention is generator failure, never server loss.

Placement/build changes require a new configuration root. To retain reviewed
unloaded rehearsal acquisitions, pass `--inherit-unloaded OLD_ROOT
--inherit-reason REASON` at launch. All twelve receipts and their evidence hashes
are checked, with original code/configuration retained in
`inherited-unloaded.json`. Loaded points are reacquired under the new placement.

The October 15 32-core recovery check passed three separate 1 s warmup + 1 s
measurement trials against the existing one-worker LH 8-byte service at 75,938
RPC/s: 227,814 measured transmissions and on-time replies, zero generator misses,
TX admission failures or NIC RX/TX errors. Actual placement was CPUs 16–47;
isolation restoration and idle-NVMe guards passed each trial. Evidence is in
`out/echo-redesign/generator32/board-check-3/`; these are diagnostic checks,
excluded from paper inputs, not a completed loaded rehearsal or capacity claim.


### Reviewed artifact transitions

A changed server/generator image or acquisition configuration blocks automatic
reuse. The agent reviews the actual measurement impact and may explicitly retain
unaffected passing cohorts; matching historical evidence hashes remain mandatory.
Use a new root to keep both frozen configurations intact:

```sh
python3 tools/paper/benchmark_campaign.py inheritance-plan --root NEW_ROOT --config NEW_CONFIG --inherit-reviewed OLD_ROOT > REVIEW.json
# Complete reason, hashed validation evidence, and reuse/rerun decisions for every receipt.
python3 tools/paper/benchmark_campaign.py launch --root NEW_ROOT --config NEW_CONFIG --inherit-reviewed OLD_ROOT --inherit-review REVIEW.json
```

Only loaded/unloaded passing acquisitions can be inherited; preflight, builds,
validation and plots run again. The source must be stopped, the scope unchanged,
and every review identity and evidence hash must match. The new root retains
`inheritance-review.json` alongside unchanged source receipts pointing to their
original evidence. No failed attempt is promoted or overwritten. Use `--resume`
only if the new root was already prepared with a reviewed hardware-state file.


Low-load points may leave some configured workers idle: Lauberhorn allocates
additional workers as queued demand rises. Activity slots with no observed call
retain zero CPU/TID sentinels; they are not evidence of execution on CPU 0.
Per-point validation checks nonnegative counters and distinct valid placement for
workers that have executed, permitting zero activity for others. Rehearsal/final
validation requires every worker in each system/payload/worker configuration to
be exercised somewhere in the collected grid. Per-worker counts remain preserved;
these checks do not establish simultaneous execution or CPU utilization.
