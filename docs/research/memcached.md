# Native UDP memcached

The transport and upstream adapter integration merged into platform `master`
in `6b489de8`. Continuing board acceptance is on platform branch
`feat/memcached-board-validation`; the application remains on memcached branch
`feat/lauberhorn-native-udp`.
Acceptance is pending; build and simulation results do not establish board
compatibility, oversubscription performance or timing closure.

The application starts from upstream
`0e6ba4da1fcaa3bf93f588d6d1144c9ecb231f9e`, immediately before the PIONIC changes
in the existing `../memcached-legacy` repository. The `pionic` branch is preserved.
The platform pins the application commit in `flake.nix` and `flake.lock`, calls
its root `package.nix` with the matched `lauberhornRuntime`, and builds
`memcachedDeployFs` through the same deployment environment as Dandelion.
The image manifest identifies application, runtime, module, RTL and headers.

## Transport and ownership

A kernel-reserved IPv4 UDP port belongs to one domain. A kernel socket without
reuse options reserves it against Linux bindings; the shared listener allocator
rejects collisions with ONC-RPC listeners and other endpoints. Privileged ports
require privilege. Registration occurs before worker registration. Handles are
monotonic and are not recycled at exhaustion. Source address is the configured
NIC IPv4 address; source port comes from the endpoint table. Neither can be
specified in an application TX descriptor.

RX includes the endpoint handle, remote IPv4 address/port, local address/port,
exact payload length and unchanged payload. ECI converts numeric addresses and
ports to host order. RX data is copied to worker-local storage and borrowed until
the next receive; retaining it requires an application copy. TX takes endpoint,
destination and payload. The endpoint owner is compared with the trusted domain
installed on the physical worker, never with a domain supplied by userspace.
There are no UDP sessions, peer handles or response retirement operations.

The host descriptor discriminator grows to four bits. Existing scalar and inline
ONC-RPC offsets are preserved through padding changes; generated Mackerel and
configuration headers must come from this hardware revision. Old images and new
headers are incompatible. The 8 KiB alias map, parity exchange and PREEMPT/yield
contract are unchanged.

The native RX buffer validates IPv4 version, fixed header length, header checksum,
fragment flags/offset, destination, UDP/IP lengths and nonzero UDP checksums before
host admission. IPv4 UDP checksum zero is permitted. Payloads are 0–1472 bytes;
Ethernet padding is removed. Malformed/oversized input is drained and rejected.
No IP reassembly or options are supported. Unmatched UDP ports retain bypass;
ONC-RPC listeners retain their existing path. UDP TX checksum is zero, as allowed
for IPv4. Zero-length datagrams still transmit a UDP header.

TX is synchronous through local admission and packet-buffer consumption. On
success, caller storage is reusable; this does not establish network delivery.
Definite retry retains caller ownership. Indeterminate errors must not trigger an
automatic retry. Multiple responses can follow one request without session state.
The adapter retains upstream response/item/iovec ownership until each native
segmented datagram has been consumed. Backpressure must settle before blocking RX.

## Worker and maintenance boundary

Each instance declares 1–W initialized request workers. Multiple instances may
declare more than W in aggregate. Main/clock and background maintenance threads
run on ordinary CPUs outside the isolated physical-worker pool. Workers complete
connection, logger and LRU-bump initialization before registration and bootstrap
parking. PREEMPT immediately follows the existing yield path. RX NACK only
returns control to an assigned worker; it cannot activate a parked worker.

The adapter keeps upstream parsing, request IDs, framing, native UDP segmentation,
item storage and the connection state machine. A `HAVE_LAUBERHORN` build always
uses native UDP: disable TCP with `-p 0`, set `-U PORT`, and use `-t N`. There is
no environment switch or socket UDP fallback. Proxy/extstore are excluded.
Socket UDP controls use a separate build without Lauberhorn support; the pinned
upstream reference remains available through `memcachedReference`.

Pre-size `hashpower` for the bounded dataset, above the item-lock hash power and
with headroom below expansion's 1.5-items-per-bucket trigger. Record initial and
final `hash_power_level`, `hash_is_expanding`, item count and process status.
Unexpected expansion is a failed scope check. `pause_threads` in native mode
fails explicitly before sending notifications or waiting for parked workers.
The main clock, LRU maintenance, expiry crawler and slab maintenance are ordinary
threads; request-independent maintenance is not relocated onto request workers.

Worker-notification-dependent administration, crawler dump/watch connections,
warm restart, idle connection timers and graceful worker teardown are outside
acceptance. Do not infer unrestricted maintenance from bounded GET/SET success.
Use the existing [fenced process lifecycle](../software/process-recovery.md) and
[Enzian harness](../development/hardware-test.md); killing instances does not make
resources safely reusable without the documented coherence reset.

## Acceptance and measurements

Require native UDP ownership, length/checksum, zero-length, malformed-input,
backpressure and repeated-response tests plus ONC-RPC/bypass regressions. Compare
commands with the pinned upstream server: GET/SET, expiration, CAS,
append/prepend, no-reply and segmented responses. Check hash scope before and
after every run. Board results require matched CI artifacts and fresh reservation
checks. Preserve probe failures before reset and report STA without timing work.

Measure single instances with pool sizes 1–W; W+1 instances with homogeneous pool
sizes 1–W; and mixed pools cycling through 1–W. Use steady, burst and idle-to-active
traffic; GET-only and 95/5 GET/SET; uniform and skewed keys; 64-byte and 1-KiB
values. Sweep offered load through saturation with 10 s warmup, 30 s measurement
and three repetitions. Generator saturation is a separate limit and must not be
reported as server capacity.

Report per-instance and aggregate goodput, RTT percentiles, errors/timeouts,
packet loss and actual Linux CPU/physical-worker assignments. Record main and
background CPU separately. Preserve manifests, seeds, raw measurements and compact
summaries. Latency breakdown requires correlated NIC arrival, scheduler queueing,
assignment/yield, host receive, execution, TX admission and NIC egress, with the
[established clock uncertainty](latency-measurement.md) and tracing-off controls.
Uncorrelated timestamps must not be subtracted across hosts or FPGA clocks.

## Deferred wake and pause designs

Hash expansion is the concrete HOST_WAKE motivation: the assoc thread queues a
pause notification to every worker and waits. A worker parked in kernel yield
cannot consume its libevent notification. Finite RX polling does not solve this.
HOST_WAKE, epoll subscriptions and voluntary host-only yield remain follow-on
work, with no new API, hardware field or stub in this implementation.

Tentative sketches retain one epoll instance per worker for host notifications
and a scheduler-aware pause protocol which releases an assignment while awaiting
resume. Neither sketch is a settled interface. Unresolved generic semantics are
targeted activation, voluntary relinquishment, eligibility accounting, pause/resume
races, notification persistence through reassignment and teardown. Resolve these
before enabling unrestricted hash expansion or worker-dependent maintenance.

## Reproduction tools and current evidence

Build with `nix build .#memcachedDeployFs`. CI publishes this as
`out/memcached-deploy.img` beside the ordinary RPC image from the same revision.
`memcached-adapter` is a required preparation gate. The image contains
`apps/memcached` and `apps/memcached-tools` under `/nix/store/lauberhorn`, including
Python and command wrappers; it does not depend on a copied host executable.
`nix build .#memcachedReference` builds the exact pinned upstream socket server
for local comparisons.

After reservation checks and matched harness boot/configuration, use the packaged
`memcached-matrix --workers 4 --rates RATE... --output MATRIX.json`. Rates are
explicit experiment inputs: extend them until saturation is observed, while
checking generator pacing/admission misses. The nine pool configurations produce
648 cases per offered rate, including repetitions and workload variants. Each
case is initially marked unrun. On a fresh initialized DUT, launch one case with:

```sh
/nix/store/lauberhorn/apps/memcached-tools/bin/memcached-launch \
  --manifest MATRIX.json --case 0 --output NEW_SERVER_DIR \
  --ordinary-cpus 0-43 --worker-cpus 44 45 46 47
```

This records application commands, PIDs, declared workers, CPU mapping and the
full deployment manifest. Verify the actual module worker mapping and isolated
CPU configuration against that explicit mapping. Launch success is not readiness:
run `memcached-compatibility --host FPGA_IP --port PORT --output NEW_JSON` from
the reserved client for each endpoint. Then run `memcached-benchmark --manifest
MATRIX.json --case INDEX --host FPGA_IP --output NEW_CLIENT_DIR --preload` and
sample the recorded PIDs with `memcached-cpu_sample`. Keep instances alive across
load/workload cases with the same pool configuration; changing pools requires the
fenced lifecycle and matching harness recovery, never ordinary worker recycling.
The launcher does not kill processes or reset hardware on a partial failure.

The bounded dataset defaults to 10,000 keys per instance and hashpower 18; values
are non-expiring for capacity measurements. Both initial and final hash statistics
are retained. CPU samples separate main/request/background ticks but are snapshots,
not scheduler assignments. Record NIC counters separately: the client reports
unanswered requests and leaves packet loss unknown rather than equating the two.
The Python generator is a correctness-oriented starting point; pacing misses
invalidate a server-saturation claim at that offered rate.

For short trace controls, start a fresh cohort with `--trace`, use a bounded
loss-free window without request-ID reuse, quiesce traffic, and export each
process's buffered traces with SIGUSR2. Capture FPGA DDR through the documented
F_MAC1 workflow. Do not run the full high-rate matrix into a finite trace buffer.
`tools/memcached/latency.py` takes all server/event CSVs, the matching trace dump
and map, plus an explicit `--cpu-map 44:1,45:2,46:3,47:4`. It applies the existing
causal clock fit, checks per-datapath packet counts and unique MAC matches, and
writes a latency plot and uncertainty/provenance summary. Segmented sends have
individual TX brackets. Execution includes TX, and commit-to-egress can overlap
admission; these durations must not be added as independent components. Compare
with a tracing-off cohort at the same workload. The analyzer is locally tested
with synthetic causal fixtures; board trace acceptance remains pending.

`tools/memcached/report.py SUMMARY... --output NEW_REPORT_DIR` writes per-instance
CSV, aggregate throughput curves and worst-instance p99 curves from actual input
summaries. Error bars show observed repetitions, without filling absent cases.
Use the Nix development environment with NumPy, SciPy and Matplotlib for analysis.
No board throughput or latency plots have yet been recorded for this change.

Local verification on October 8: the native UDP full-engine simulation passed
zero-length/padded and 1–1472-byte datagrams, IPv4/UDP checksum and fragment/length
rejection, unknown/cross-domain endpoint rejection, oversized TX rejection and
two responses per RX. Thirteen ONC-RPC/bypass regression cases passed. The runtime
ownership/retry test and pinned-upstream/application compatibility comparison
passed; the application check injects a socket transport and therefore does not
establish ECI scheduling. Deliberate hash expansion exited with the required
scope-failure marker. Native memcached board compatibility, four simultaneous
workers and the complete oversubscription matrix remain acceptance gates.

Enabled background-path audit at the pinned upstream revision: `assoc.c` is the
worker-pause dependency and is guarded by the native scope failure. `items.c`
LRU maintenance/bump processing, `slabs.c` maintenance and crawler expiration use
ordinary locks/conditions and shared storage, without requiring a parked worker
to consume a notification. Requests reach blocking RX only after finishing the
state machine's runnable work and responses, with item locks released. Crawler
client dump/watch paths call `redispatch_conn` and remain excluded, as do shutdown
and administrative pause paths. This audit bounds acceptance; it does not certify
all upstream maintenance modes.

Focused reproduction commands (from the platform root):

```sh
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.NativeUdpSim
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcNestedSim \
  lauberhorn.host.eci.RxBypassSim lauberhorn.host.eci.TxBypassSim \
  -- -l org.scalatest.tags.Slow
nix build .#memcachedDeployFs .#runtimeTests \
  .#checks.x86_64-linux.memcached-adapter .#checks.x86_64-linux.workflow-tools
nix develop -c python3 tools/memcached/test_measurements.py
```

The local matched image/runtime/module build and all four Nix targets above
passed. Local logs are retained under ignored `out/native-udp/`; CI is the
revision-pinned, shareable evidence. No local dirty-tree image should be substituted
for the CI image when programming the board. Slow bypass sweeps were not completed
in this focused run; CI retains its source-defined test selection.

CI pipeline 517943 (`03b1fe04`) passed all fourteen test gates, including
memcached-adapter job 2879263, after application job-token access was enabled.
Preparation job 2879255 then caught a maintained-wrapper omission: generated
native UDP tracing added port 52, while the VHDL wrapper ended at 51. The wrapper
now connects that port at both NicEngine and trace DMA and widens the connecting
arrays. The complete local `nix build .#eciVivadoInputs` preparation check passes,
including generated ports and debug widths. This correction changes no timing
constraints, address layout or PREEMPT behavior.

### Matched CI and board follow-up, October 9

Pipeline [517951](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/517951)
built platform `df6968b09972b456c006af16dab2d8f8e9cd9dba`, with memcached
`b6aaba9a05b5c7f59d86090b271d1881235c71d5`. All fourteen test gates passed,
including memcached-adapter 2879374 and fast-tests-eci 2879370; preparation
2879460, hardware 2879346 and report 2879347 succeeded. The matched RPC and
memcached deployment images, bitstream, LTX and trace map are retained under
`out/native-udp/`, with hashes in `board-provenance.json`,
`ci-517951/artifacts.json` and `hw-2879346/SHA256SUMS`.

Vivado 2025.1 reported WNS **−0.691 ns**, TNS **−1630.849 ns** and **9,877**
failing setup endpoints. Hold and pulse-width checks had no failing endpoints.
This is not timing closure; no timing optimization was performed.

Before the first reset, reservations for zuestoll14 and zuestoll07 were checked.
The previous frozen aligned-Dandelion trace from pipeline 516232 was preserved:
1,904,101,312 bytes, SHA-256
`79b1853dff375e397cd09c5bc161a2f49f05093249c2dd5b9052286400cb4d5d`.
A shared-storage quota interruption required an overlapping resumed read; the
4,096-byte overlap matched exactly. Both pieces and the assembled capture are
retained locally, with the original trace map and preservation record. The
historical sticky loss flag limits event completeness despite a complete buffer
read. No reset preceded this preservation.

The matched RPC harness passed **two independent cold boots and 200 checked
RPCs**, 100 per trial. Both boots passed on their first attempt, with verified
power-off rails, QLM8–13 CDR lock and all 24 CCPI/ECI lanes. The matched module
loaded and verified with tracing armed; both clients exited zero and the harness
reported no faults. Trial durations were 275.2 and 274.6 seconds, including
reset/program/boot and testing, not request latency. Compact results and client
CSVs are in `out/native-udp/rpc-board-517951/`; the exact manifest is retained
there. This validates ONC-RPC regression only. Native memcached board compatibility,
oversubscription, throughput curves and correlated latency breakdowns remain
unrun. The second successful RPC server is left running on the DUT with tracing
enabled. Recheck live reservations and trace state before further work.

The maintainer subsequently authorized merging the platform integration. Merge
`6b489de8` contains the tested implementation without changing its hardware or
software; the continuing tests still use pipeline 517951 artifacts and its exact
`df6968b0` manifest. The completed platform branch was removed after all pipeline
jobs finished. Application acceptance continues on `feat/memcached-board-validation`;
the upstream-based adapter remains on `feat/lauberhorn-native-udp` in the original
memcached repository, preserving the legacy branch.

### First native board attempt after merge

The first native compatibility trial used the same `df6968b0` artifacts, one
request worker, and hashpower 18. Cold boot, module verification and launch
passed, but compatibility timed out before a result could be established.
The harness stopped before its second reset. Memcached remained alive, with
`lh-mc-0` parked in `worker_yield`; no kernel fault was reported.

The preserved initial trace contains 139,942,016 bytes (2,186,594 slots), is
unwrapped, and had no pre-freeze loss or DMA error. Its SHA-256 is
`9a71c19456908d0a3d576adc53354f0f76d1a53970202c7526688c22c8712dad`.
Decoding with the matching map found no RX events. Hardware ingress, Ethernet
and UDP decoder counters were all zero. The peer's FPGA ARP entry was failed;
a known-MAC static-neighbor retry and a separate gateway retry also timed out.
Ten main F_MAC0 VIO samples consistently reported aligned/status=1, local
fault=0, remote fault=1, with all reset/loopback controls deasserted. The separate
F_MAC1 trace link transferred the full buffer successfully. These observations
localize this attempt to the main receive/link path before native UDP admission;
they do not identify the underlying physical fault or establish adapter failure.

The retry trace was also preserved before cold recovery: SHA-256
`77e785de75f7bab4b39e3367609756454146d3bbc88c25fb11678ef3484385c6`.
Its sticky loss flag was set after the initial freeze/resume, so it is not a
loss-free sequence record. Evidence is retained in `out/native-udp/` under
`memcached-board-517951`, `memcached-failure-trace`, `memcached-retry-trace` and
the corresponding preservation JSON files.

Cold recovery cleared the main-link fault (ten healthy VIO samples), but native
compatibility still timed out. The second boot received 109 Ethernet packets and
two native UDP requests; the request worker ran on CPU 44. Its full preserved
trace has SHA-256
`76450049ee7cbe01f0a0f8880d37afd980a53ed77aab6262f53831f2277748ba`.
The prefix ending at pre-freeze slot `0xa29b10` decoded without loss markers.
The full buffer also retains the loss flag first observed after freezing.

The trace shows two stats segments (1,400 and 828 UDP payload bytes) routed
through neighbor resolution and Linux bypass, followed by a 16-byte native
response reaching `TxCmacExit`. A peer raw-socket probe received valid VERSION
and segmented STATS responses, but no short SET acknowledgement. A subsequent
GET returned the value, establishing that the SET executed. Wire evidence is in
`memcached-wire-probe.json` and `memcached-wire-short.json`. This isolates a
missing Ethernet minimum-frame padding defect: memcached's framed `STORED`
response is 58 bytes before FCS, below the required 60-byte minimum. The trace
responder already pads its short ARP frames, but the main MAC path did not.

The correction pads first-and-last aligned TX beats to 60 bytes with zeros before
the CMAC adds FCS. Multi-beat tails and IPv4/UDP lengths are unchanged. The native
UDP regression now checks exact frame length and zero padding, including payload
sizes 0, 1, 7, 16–19 and 21–23 bytes, alongside the existing larger sizes. Before
the correction, the new regression failed with a 42-byte empty UDP response
where 60 bytes were required. New hardware is required before claiming native
memcached compatibility; no throughput measurement has passed this gate yet.

The second failed DUT remains running with its trace frozen. Both boot failures
and the retry are preserved locally under `out/native-udp/`; temporary trace
controls and the peer's static-neighbor override were restored. Bounded wire
probes ran after freeze and are recorded separately, not in the frozen FPGA
window. No timing constraints, address layout or PREEMPT behavior were changed.

Local validation of the padding correction: `nix develop -c mill
gen.test.testOnly lauberhorn.host.eci.NativeUdpSim
lauberhorn.host.eci.OncRpcNestedSim lauberhorn.host.eci.TxBypassSim --
-l org.scalatest.tags.Slow` passed all **nine** tests. The failing pre-correction
reproducer and passing run are retained as `padding-reproducer-2.log` and
`padding-validation.log`. This is simulation evidence, not board acceptance.

The local `nix build .#eciVivadoInputs` check also passed, regenerating the RTL
and validating the Vivado wrapper handoff. Its dirty-tree artifact is validation
only; deployment must use the replacement pipeline’s matched artifacts.

### Compile-time transport selection, October 9

Memcached revision `9c56666e64bb0ca48f3506e64fcfccd841785a3f` removes
`lh_udp_enabled()` and the `LAUBERHORN_UDP` runtime switch. A Lauberhorn build
unconditionally initializes the native endpoint and workers, uses native UDP
receive/send, bypasses worker libevent dispatch and rejects unsupported pauses.
Socket UDP receive/send and listener setup are selected only in non-Lauberhorn
builds. The platform launcher no longer sets the removed switch.

The pinned native adapter passed all 16 compatibility checks against upstream
with the injected test transport, including three-datagram responses, and the
expected expansion-scope rejection. Its AArch64 Nix package built successfully.
The same patched source built without Lauberhorn also passed all 16 socket
compatibility checks. Logs and summaries are under
`out/native-udp/compile-time-*`. This does not add board acceptance. Pipeline
518140 built the padding correction at `9a1f0438` with the previous
adapter pin; its artifacts must not be represented as containing this cleanup.


### Pipeline 518140 results and board deferral, October 9

Pipeline **518140**, revision `9a1f0438c8af7fe771557bd365278a9a4360df8b`,
passed all 14 gating jobs, preparation **2880999**, hardware **2881000** and
report **2881001**. Matched RPC and memcached images, bitstream, LTX and trace
map are collected under `out/native-udp/{ci-518140,hw-2881000}`; hashes and
job provenance are in `out/native-udp/board-provenance-518140.json`.
The hardware report records **WNS −0.462 ns, TNS −858.949 ns and 5,235 failing
setup endpoints**; this is not timing closure. These images contain the older
`b6aaba9a` adapter, not the subsequent compile-time transport cleanup.

At the results check, both zuestoll14 and zuestoll07 were reserved to pengxu,
but another campaign was actively running pipeline **518139** on zuestoll14
(`out/cmac-ci-518139/board-rpc-retry`). The console was occupied and JTAG
reported no device during that campaign's boot. No reset, programming or RPC
test for 518140 was started. The earlier statement that the failed memcached
DUT remains frozen is historical, not current board state. Its 682,201,600-byte
local capture was rehashed successfully against
`76450049ee7cbe01f0a0f8880d37afd980a53ed77aab6262f53831f2277748ba`.
Any subsequent test must recheck reservations, active campaigns and preservation
of the then-current board state. Shared scratch had only 494 MiB free; deployment
and new failure capture need sufficient capacity without deleting evidence.

Two matched cold-boot RPC trials remain pending, followed by native memcached
compatibility and oversubscription acceptance. The adapter's no-work condition
also remains under review: blocking RX inside the connection state machine does
not establish that the entire worker has drained runnable work or reproduce
libevent's notification wake behavior. Protocol mock tests do not validate that
sleep/wake contract. HOST_WAKE remains deferred.


### Pipeline 518140 matched RPC validation, October 9

After the 518139 campaign completed, reservations and live state were rechecked.
The previous memcached failure remained preserved; the live successful RPC
server had active tracing, no sample loss and no DMA error. The matched 518140
RPC image's SHA-256 was verified after staging:
`89e2d04ea6c5702d8735f72ecefe6f2133892cf403a0fc9531b22044528eb657`.

The repository harness passed **two fresh cold boots and 200/200 checked RPCs**
using revision `9a1f0438c8af7fe771557bd365278a9a4360df8b`. Both boots passed
on their first attempt, with verified power-off voltages, QLM8–13 CDR lock and
all CCPI lanes 0–23. Module load and version verification passed without reported
faults. Tracing and the static edge ILA were armed before Linux/module load.
End-to-end harness durations were **282.7 s and 279.5 s**, including reset,
programming and boot; these are not request-latency measurements.

Compact results are in `out/native-udp/rpc-board-518140/summary.json`; both
100-row client CSVs were checked for successful status and correct responses,
and copied under `out/native-udp/rpc-results-518140/`. The updated
`board-provenance-518140.json` records the trials alongside CI and artifact
provenance. The final matched RPC server is left running. This completes the
scheduled RPC regression check, not native memcached compatibility or the
short-response board acceptance. Worker sleep/wake correction, memcached
acceptance, oversubscription measurements and timing closure remain open.
