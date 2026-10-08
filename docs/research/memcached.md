# Native UDP memcached

Implementation and acceptance work is on platform branch
`feat/native-udp-memcached` and memcached branch `feat/lauberhorn-native-udp`.
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
item storage and the connection state machine. Set `LAUBERHORN_UDP=1`, disable TCP
with `-p 0`, set `-U PORT`, and use `-t N`. Proxy/extstore are excluded. Upstream
socket UDP remains available without the environment setting for controls.

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
scope-failure marker. Hardware synthesis, board compatibility, four simultaneous
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
including generated ports and debug widths. Synthesis and board acceptance still
require a successful replacement pipeline; this correction changes no timing
constraints, address layout or PREEMPT behavior.
