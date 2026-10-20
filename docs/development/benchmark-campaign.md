# Hot echo benchmark campaign

The workload is defined by `../microbenchmarks/bench.x`: `echo0` is
void to void; `echo8`, `echo128` and `echo1432` return fixed opaque application
bytes. `rpcgen` produces runtime codecs; `echo_registry.py` derives procedure,
size and codec registration from the same schema. The ONC-RPC XID identifies client
requests, including size zero; existing internal RPC IDs pair FPGA events. Adder acceptance and legacy loopback
measurements retain their original semantics and provenance.

## Board setup and acceptance

Build the matched DUT and generator images with the [development workflow](workflow.md).
Use [validation](validation.md#board-validation) for EMG provisioning, image
staging, boot/module/ABI gates, automated acceptance and interactive target
commands. Campaign orchestration below adds workload scheduling, switch capture,
resume and measurement validation to those shared board procedures. Recheck
reservations and preserve existing failures before a deployment or reset.

## Baseline selection and launch gates

The benchmark configuration selects Lauberhorn, DPDK on the ConnectX-5 in
zuestoll07, and caladan-simple on that same ConnectX-5. Caladan-simple hot cases
require an accepted port. LH and DPDK use zuestoll07 sequentially (`dut` and `dpdk_dut` both name
zuestoll07); zuestoll14 is the separate traffic generator. Both DUT links are
measured at their server-facing leaf0 ports. These names describe the configured
topology, not live reservations; verify reservations and placement at preflight.
Never relabel NICVF observations as ConnectX-5 results. Full collection requires
the hash-bound real-workload capture acceptance described below.

## DPDK traffic generator

The separate client runs `bench_dpdk` from `../microbenchmarks/client/`. It
constructs Ethernet/IPv4/UDP/ONC-RPC packets and polls the NIC directly through
a DPDK PMD. The common engine owns request IDs, pacing, warmup, timeouts,
response validation and observations; schema-derived codecs supply the echo
payloads. The same generator drives both Lauberhorn and DPDK responders.
The client needs neither a programmed FPGA nor the Lauberhorn kernel module.

Unloaded acquisition uses one outstanding request and Tofino server-facing
wire timestamps. Loaded acquisition uses response-independent paced batches
across generator CPUs and aggregate client RTT observations. Warmup is excluded
from reported distributions but retained in detailed records where required for
correlation. Request IDs distinguish matching replies from stale or unrelated
traffic; failed requests and admission failures remain explicit outcomes.
Use the campaign controller below to select the workload and observation mode.

The generator takes explicit client/server IP addresses, UDP port and next-hop
MAC; it does not implement ARP, DHCP, ICMP or routing. Provision the client
through `emg`, deploy the packaged generator, and bind only its data VF. Keep
management/root storage on its existing interface. The
[VF runner](../../tools/enzian/dpdk_run.py) snapshots and restores the driver,
addresses, routes, hugepage settings and VFIO mode, and reports restoration in
`summary.json`. Verify generator CPU isolation separately from DUT worker-core
placement. Build commands, standalone invocation and packet-replay tests are in
the [generator instructions](../../../microbenchmarks/client/README.md).

ThunderX NICVF requires the patched PMD receive-filter setup: `ADD_MCAST`
populates the Linux PF CAM list before `SET_XCAST` applies mailbox mode flags.
The runner uses legacy hugepage allocation for physically contiguous DMA rings
in PA/no-IOMMU mode and disables telemetry. The historical physical validation
used Ubuntu 6.8.0-146 and patched DPDK 23.11.4; the corresponding Nix packages
pinned DPDK 26.03. Preserve kernel, PMD and NIC identity in measurement provenance;
these NICVF requirements and observations do not describe the ConnectX-5 baseline.

### Generator timing and calibration

Client RTT spans encoding/framing, PMD enqueue, receive polling and response
validation. Enqueue and receive timestamps are software boundaries. The
client-software metric sums encoding, enqueue and validation **per request**;
summarize that sum rather than adding percentiles. Detailed records retain
warmup and failures, and recording avoids file writes inside the measured RPC.
The [measurement methodology](../research/hot-measurement.md) defines the
legacy raw/corrected timestamp accounting; the active campaign boundaries are
specified below.

Hardware loopback checks the client's own NIC path. The October 4 64-byte UDP
echo calibration measured raw median RTTs of 2.080–2.140 µs and P99s of
2.420–2.440 µs in three isolated-CPU trials. It excludes the server and external
switch path. Gateway echo includes the gateway's software response. Neither
calibration supplies a constant network cost to subtract from RPC results, and
pcap replay supplies functional checks rather than physical latency evidence.
The [calibration ledger](../../data/eci/board-evidence/dpdk-client-20261004.json)
retains configurations, sample counts, hashes and distributions. Isolation and
telemetry changed together, so the comparison does not isolate either effect.

## Shared echo server implementation

The [DPDK baseline design](../research/dpdk-baseline.md) explains the comparison
with LH and its distinction from Caladan directpath.

LH and DPDK share the synchronous runtime request, handler and reply path
(`sw/rt/rpc_{request,call,reply}.h`), the `oncrpc.c` XDR adapter, schema-generated
codecs, and the separately compiled `echo_handler.c`. Both allocate/decode arguments, dispatch the registered
handler, marshal directly into their TX buffer and free the decoded arguments.
DPDK supplies a program/version/procedure table and Ethernet/IP/UDP/RPC envelope
processing beneath this interface. Its sole server loop always requests one RX
packet, in both loaded and unloaded operation. Only profiling, output and run
termination differ. Do not reuse measurements from a different server workload
as this baseline; transport and codec changes require explicit measurement review.
Responder/runtime/codec builds use `-O2` and the same Enzian CPU tuning. The
generator remains separately optimized; its batching does not select a different
server implementation.

## Canonical measurement contract

See [hot measurement methodology](../research/hot-measurement.md) for the
boundary definitions and queue instrumentation requirements. Unloaded CDF and
breakdown totals are Tofino wire-to-wire; breakdowns subtract CPU durations to
obtain one combined CPU–wire residual. No mlx5 hardware timestamping is used.
Loaded cases retain uninstrumented client RTT with no packet trace capture.

Normal acquisition keeps FPGA recording frozen and collects no FPGA MAC or
NIC PTP timestamps.
The [DPDK readiness workflow](validation.md#dpdk-baseline-readiness) exercises software stages and
loaded RTT independently of switch capture. The unloaded adapter uses
[campaign/tofino.py](../../tools/paper/campaign/tofino.py) to bridge the sibling
`tofino-hw-timestamping/tools/capture-session.py run` automation. That CLI owns
staging, arming, readiness, finish, export and pairing; the platform adapter
must not reproduce its remote commands. It invokes the ordinary client role
through a local generator adapter using `WIRE_CONFIG`, `WIRE_LEDGER` and
`WIRE_SESSION_ID`. The DPDK responder is ready before the session starts;
Lauberhorn uses its resident worker. Failed sessions retain the helper's
`export.json`, `run-status.json` and logs. Recovery is an explicit operation
through the same helper after confirming the sender stopped. The maintained pairer supplies accepted nominal-ns MAC intervals. Absolute
calibration is metadata, not a latency veto. Full collection requires a passing
real-workload unloaded rehearsal with matching acceptance hashes.

### Full unloaded collection

Use `scope: "unloaded-full"` for only the full unloaded dataset: both systems,
8/128/1432-byte echo, probes off and breakdown, 10,000 measured observations per
cohort with 128 warmups per capture chunk. It starts directly without a rehearsal
or loaded grid, validates/publishes the CDF and breakdown, sends the terminal
callback and stops. Existing real-workload wire acceptance remains required.

### Bounded unloaded capture integration

The hardware manifest's optional `tofino` object supplies `repository`, `host`,
`control_socket`, `expected_artifact_sha256`, `expected_controller_sha256`,
`ports` keyed by `LH`/`DPDK`, `rpc_selectors` keyed by payload size, `gap_ns`,
and an explicit `exclusive_tx_fifo_ack: true` after checking for competing readers.
Obtain selectors from the current `bench.x` schema, and verify both server-facing
ports on leaf0. Pin actual installed controller hashes, not only the container
image tag. Use the `tofino-capture` skill for access and qualification evidence.
An absent authenticated SSH control socket is a preflight failure.

For an explicitly authorized fresh deployment, set `initial_state: "fresh"`
and omit `previous_capture` and `previous_capture_summary`. This starts with
new hardware acquisition instead of comparing another board's archived recorder
against the DUT. Preserve any existing failure before selecting this mode.
Resume still checks the campaign's recorded hardware state; fresh mode never
clears an unsafe resume state. Existing preserved-recorder launches retain
their live VIO and archive-hash checks.

Use controller `scope: "unloaded-rehearsal"` for the bounded switch integration:
12 cohorts (DPDK then LH, all three sizes, off/breakdown), each with 128 warmups
and 100 measured requests. It uses the same locks, stage receipts, resume,
deadlines and terminal callbacks as other campaigns. Validation produces the
unloaded CDF and breakdown, checks PDF dimensions/fonts, repeats the switch clock
rate check and stops; no loaded cases or full collection follow. Rehearsal data
is excluded from paper inputs. Chart presentation remains uncommitted.

For an explicitly requested short unloaded rehearsal, the existing
`echo_hardware.py HARDWARE unloaded REQUEST RESULT` stage supports one capture
chunk per cohort, at most 1,024 requests including warmups. Use one resident
worker, 128 warmups and a recorded fixed gap (the backend's synthetic acceptance
used 10 ms). The ordinary hardware adapter still owns boot, software roles,
health checks, archival and restoration. The session CLI arms capture before the client runs,
writes finish after traffic stops, exports and pairs with the actual client ledger. No synthetic
responder or FPGA/NIC timestamp path is involved. Loaded roles never arm capture.
Full unloaded cohorts use `campaign/chunked.py`: eleven chunks of 896 measured
requests and one of 144, each preceded by 128 warmups. The server stays resident
through switch export/rearm; DPDK accepts a new consecutive XID sequence only at
an explicit 1,024-request boundary. Raw chunk observations retain local wire
XIDs; merged rows carry both chunk identity and a chronological observation ID.
Warmups remain archived and are excluded individually from plots. Full launch
requires hash-bound `wire_acceptance` from a passing unloaded rehearsal. Full collection runs directly from an existing frozen `grid.json` or an explicit
complete `grid` configuration (system, size, workers, increasing integer rates).
It never launches a preliminary rehearsal. Rehearsal scopes are explicit tools.
Full unloaded CDF/breakdown plots pass validation before loaded collection starts.
Each completed loaded point refreshes partial latency/goodput, loss and available
sustained-peak charts. `unloaded-plots.json` and `loaded-plots.json` point to the
latest immutable previews; final completeness checks still run at the end.

Every accepted chunk requires exact counts, full XIDs, single-outstanding
client order, complete capture/export/cleanup, matching artifact/controller
identity and journal digest. The adapter accepts schema-3 pairs with
`latency_accepted: true`, `units: "ns"` and `ns_per_tick: 1`, and retains raw
observations and calibration metadata. It imports `wire_ns` through the normal
flow/XID join and computes the combined CPU–wire residual for instrumented runs.
`hardware_timing_validated: false` and `absolute_wire_calibrated: false` describe
external calibration status; they do not reject the accepted switch interval.
Bounded integration trials remain `paper_inputs: false`. On capture or cleanup
failure preserve the session and recover through the maintained session CLI;
never signal syncd or reset the switch to clear a capture.

The adapter stages `vivado/eci/program_fpga.tcl` from the frozen repository
revision under a content-hashed filename and passes the configured DUT cable.
It does not execute programming wrappers embedded in old boot-case manifests.
Fresh programming initializes the recorder armed; shared trace controls handle
subsequent freeze and diagnostic preservation.

## Neighbor preparation

After each native cold boot, install the known permanent peer MAC in Linux and
send one separate echo to trigger the normal FPGA neighbor-cache fill. This
priming call has a bounded one-second deadline and its own validated archive;
it precedes 16 setup calls at the ordinary 10 ms deadline, all warmups, and all
measured requests. Priming is not a benchmark observation. Its possible Linux
bypass reply is preserved as preparation provenance, never assigned a fabricated
MAC pair. Every later setup/warmup/measured reply requires validated XID/payload
accounting; external capture must provide its own complete pairing. Loaded runs retain the 10 ms deadline and no trace
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
budget (at most 256 GiB). Detailed mode budgets a timestamp record for every
scheduled request and reserves output at 256 bytes per request; aggregate mode
uses the bounded-window budget described below. File or tracking exhaustion is terminal. Missing replies do not stall
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
Actual offered load and goodput
use the fixed measured send interval; warmup is excluded. A generator miss in
either warmup or measurement invalidates the acquisition.

Use `LH_ECHO_BATCHED=1`, `LH_ECHO_SOFTWARE_CONTROL=1` and a fresh
`LH_ECHO_STOP_FILE` for a loaded DPDK responder. The campaign creates that
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

## Compact loaded observations

Set `aggregate: true` in both launch and hardware manifests to select
`LH_BENCH_AGGREGATE=1`. This requires paced batches and exports schema 3.
The send schedule, full payload validation, RX-burst RTT endpoint and 10 ms
response deadline are unchanged. Unloaded measurements retain detailed rows.
Detailed loaded CSV mode remains available with `aggregate: false` for diagnosis.

Each generator retains timestamps for 30 ms, with capacity for 50 ms of sends
plus two batches. It validates phase, schedule, admission and return boundaries
online before retiring records. RX-to-owner forwarding exceeding 10 ms invalidates
the point: this bound ensures a reply processed after timestamp retirement cannot
have been on time. A one-byte status ledger per scheduled XID retains exact late,
duplicate and rejection accounting after retirement. Exhaustion is terminal and
never throttles offered traffic. All storage is allocated and prefaulted first.
The resource budget includes this ledger, bounded timestamps and histograms;
it no longer reserves a full timestamp record and CSV row per request.

Warmup and measurement counters remain separate. The measured on-time RTT
histogram uses 100 ns buckets through the deadline, 100,001 64-bit counters per
core (25.6 MB across 32 cores), merged after draining. Only occupied buckets are
exported. Percentiles use bucket midpoints (clamped at the deadline), with at most
50 ns quantization error; trial pooling sums counts before weighted linear
percentile calculation, without expanding synthetic request rows. The client
also exports start-lag count/sum/max and at most three diagnostic rows per core
(first measured, largest lag, largest on-time RTT), which can repeat an XID.
These rows are diagnostic examples, not a representative latency sample.

The summary, diagnostic rows, build hashes and acquisition evidence are the raw
aggregate-mode observations. Preserve older detailed acquisitions unchanged.
Changing observation mode requires the existing explicit compatibility review;
compare representative loaded cohorts before merging their scientific results.

Fresh full-paper acquisitions use aggregate observations. Historical detailed
rehearsal cohorts require explicit compatibility review before reuse; short
comparison trials do not establish statistical equivalence or sustained peak
capacity.

Loaded orchestration reuses its verified frozen recorder state only within the
same systemd service invocation and exact hardware configuration. The receipt is
hash checked; boot paths discard it, service restarts re-freeze, and failed stages
remain unsafe. Loaded roles never change FPGA controls, so they do not run a
redundant final `preserve` session. Both unloaded modes also keep FPGA recording
frozen and perform no normal trace readout.
After all role archives pass local validation, server and client temporary-file
cleanup runs concurrently through separate canonical console sessions. Failures
from either cleanup still fail the cohort. `timings.json` records elapsed stage
times (including nested roles and roles-plus-cleanup) for subsequent comparisons.
These orchestration changes do not change the generator, send schedule, latency
boundaries or per-trial NIC restoration.

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
require validated client identity, health and restoration. Both unloaded modes
require complete external wire-duration pairs and compiled instrumentation
provenance. CDF probes are compiled out; breakdown probes include queue, network,
RPC and handler intervals. Both disable NIC timestamping and FPGA recording.
Loaded runs require compiled-out software probes and no trace readout.
`full-unloaded.json` keeps 12 cohorts: LH/DPDK, 8/128/1432-byte bodies,
and two instrumentation modes. Caladan joins after its port is accepted.
The client validates wire XIDs; LH CPU rows use exact single-outstanding order
and causal duration bounds, without modifying the hardware trace format.
Preflight must check reservations, artifact
matching, budgets, isolated CPUs, prior-state preservation and actual callback
delivery. The final stage must archive raw observations and provenance, validate
all cohorts, generate figures, validate dimensions/fonts and supply previews.

Grid cohorts use 2-second warmup, 10-second measurement, one trial;
sustained candidates use 2+60 seconds and one trial. Rehearsal uses
1+1 seconds for loaded cohorts and 16+100 requests for unloaded gates. It
brackets workers 1 and 4 geometrically through the first valid >1% loss
point. Workers 2/3 start from conservative interpolated brackets; every full
grid extends geometrically until it crosses 1% or fails a resource/generator
gate. All grid points are retained. Candidate ranking uses goodput within the
contiguous prefix before the first rate where any repetition exceeds 1% loss;
failed sustained confirmations step down through that candidate order.

Exceptional Lauberhorn boot/module failure preservation uses the canonical
[trace infrastructure](tracing.md). Normal measurements
only freeze recording; neither unloaded nor loaded cases dump FPGA traces.
The hardware adapter argv is `python3 tools/paper/echo_hardware.py HARDWARE.json`.
Its manifest names immutable build products, artifact hashes, the matched boot
case and trace map, reservations/endpoints, RAM/storage budgets, and a dedicated
transfer directory. Build commands realize the frozen products; source-defined
Nix builds establish them before configuration is frozen. The adapter stages
images with streaming SHA256 verification. DPDK receives a fresh harness boot
on the ConnectX-5 host without FPGA programming or native workers. Native loaded cohorts reuse a resident pool only while
size and worker count match; configuration changes use a verified cold boot.
Normal runs never dump traces. Exceptional Ethernet capture failures retain partial evidence and return failure without
a full JTAG fallback or a hardware reset. Independent 4 KiB JTAG head/tail
checks validate successful Ethernet captures. A recognized Vivado shutdown
segfault after a completed trigger permits receiver validation; it never
substitutes for a successful receiver, exact length, and matching SHA256.

Remote role outputs are removed only after their local archive and member hashes
are verified. Loaded CSVs are compressed after acquisition. Peer archives use lossless gzip
level 1 to keep multi-gigabyte exports within the role deadline; existing archives
are never silently overwritten. Compression is outside the measurement interval. The final archive uses
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
Configuration/build changes require a fresh campaign root. Source-only edits resume normally without implementation-hash approval.
For selective invalidation, an optional explicit review is available:

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
Explicit recovery reviews rerun derived stages; ordinary resume reuses intact receipts.
The frozen grid is never silently regenerated. Configuration and non-code artifact changes
are rejected. Current reservations are checked again on every resume. Recovery
holds campaign/global locks and cannot modify a running controller's manifest.
An unsafe hardware state still
requires evidence preservation, restoration and explicit recovery before preflight;
a code compatibility review does not waive those gates. Failed acquisition attempts
without a completed receipt are retried, not promoted to success. A final archive
includes the recovery history.

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

Completed `unloaded-full` campaigns can be promoted to `full` through the explicit
`inheritance-plan` / `--inherit-reviewed` workflow. Review all acquisition receipts
and retain the matching full unloaded cohorts; regenerate derived stages and
collect fresh loaded observations. This does not launch another rehearsal.

## Validation and figures

```sh
nix build .#dev.x86_64-linux.software.trafficGeneratorNative -o out/echo-generator
nix build .#dev.x86_64-linux.software.dpdkServerNative -o out/echo-server
nix develop -c python3 ../microbenchmarks/tests/echo_replay_test.py out/echo-generator/bin out/echo-server/bin out/echo-schema-replay
nix develop -c python3 ../microbenchmarks/client/replay_test.py out/echo-generator/bin/bench_dpdk out/echo-replay
nix develop -c python3 ../microbenchmarks/client/load_replay_test.py out/echo-generator/bin/bench_dpdk out/echo-load-replay
nix develop -c python3 ../microbenchmarks/client/open_loop_replay_test.py out/echo-generator/bin/bench_dpdk out/echo-open-replay
PYTHONPATH=tools/paper nix develop -c python3 -m unittest campaign.test_campaign campaign.test_unloaded campaign.test_loaded campaign.test_histogram campaign.test_acquire campaign.test_hardware campaign.test_trace_capture campaign.test_recovery -v
nix develop -c python3 tools/paper/plot_echo.py --input CAMPAIGN/loaded.json
nix develop -c python3 tools/paper/plot_echo_unloaded.py --input CAMPAIGN/full-unloaded.json
```

The loaded plotting script uses the shared paper library. It pools on-time RTT
observations across equal-duration repetitions, retains offered-rate ordering
and nonmonotonic goodput, and shows median/P99. Overload plots retain every rate
and trial, with a 1% line. Confirmed peak plots show the single sustained trial; no between-trial variation is measured.
Current loaded campaigns cover paced batch arrivals; the earlier fixed-spacing
measurements remain separate. Poisson arrivals are deferred to memcached.
Unloaded CDF and breakdown use separate cohorts under the
[wire-to-wire contract](../research/hot-measurement.md). Do not assign
instrumented components to uninstrumented totals or describe elapsed service
components as CPU utilization. Legacy MAC importers remain provenance tools;
they do not satisfy the wire-duration gates.

### Generator isolation and placement transitions

Well-formed ONC-RPC `SYSTEM_ERR` rejections are recorded separately and count
as unsuccessful requests for the loss threshold. They do not invalidate an
otherwise healthy overloaded acquisition.

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

Low-load points may leave some configured workers idle: Lauberhorn allocates
additional workers as queued demand rises. Activity slots with no observed call
retain zero CPU/TID sentinels; they are not evidence of execution on CPU 0.
Per-point validation checks nonnegative counters and distinct valid placement for
workers that have executed, permitting zero activity for others. Rehearsal/final
validation requires every worker in each system/payload/worker configuration to
be exercised somewhere in the collected grid. Per-worker counts remain preserved;
these checks do not establish simultaneous execution or CPU utilization.

Plotting and controller source hashes are provenance only, not execution or resume
gates. Raw evidence and acquisition artifacts remain checked. Controller edits
require restarting its Python process; plot edits apply to the next plot stage.
Validated progress PDFs are atomically published to `../paper/plots/`.
