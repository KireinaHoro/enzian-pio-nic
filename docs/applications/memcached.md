# Native memcached

The same application tree supports native UDP and optional userspace TCP.
TCP uses the shared FreeBSD component and DEADLINE wakeups through the VM or
ECI transport. AArch64 TCP and memcached packages are composed by Nix; board
acceptance belongs to
[phase four](../proposals/userspace-tcp.md#phase-4-lauberhorn-integration-and-tcpmemcached-validation-on-enzian).
Each instance selects one transport. The UDP ownership contract below is unchanged.

Comparing native Lauberhorn memcached against [Caladan memcached (CS-A64)](../research/caladan-enzian.md)
is a [main paper goal](../research/paper-goals.md#planned-applications-and-baseline).
Match application versions, operation mixes, concurrency, skew and core budgets.
The finite integration and scheduling results below establish bounded native
operation; they do not establish the controlled Caladan comparison. The
Linux-over-FPGA-bypass experiment is a separate control.

The application is pinned at `13f9e9a` and
based on upstream `0e6ba4da1fcaa3bf93f588d6d1144c9ecb231f9e`, before the
legacy PIONIC changes. Platform Nix calls its source-owned `package.nix` with
the matched runtime and builds `memcachedImage`. The optional AArch64 TCP
composition is `dev.x86_64-linux.software.memcachedTcp`, backed by
`dev.x86_64-linux.software.tcp`; the default `memcached` package selects UDP. The deployment manifest
identifies application, runtime, module, RTL and generated headers.

## Adapter organization

The application keeps native support in three modules: `lauberhorn.c` owns
transport selection, domain initialization and the worker startup barrier;
`lauberhorn_udp.c` owns borrowed datagrams and direct TX; `lauberhorn_tcp.c`
owns stream admission, parser serialization, readiness and migratable response
allocations. The shared lifecycle selects a transport once; UDP entry points do
not dispatch TCP. Autoconf compiles only the selected components, with dependency
selection supplied by the source-owned Nix recipe.

The upstream core retains narrow hooks for startup/worker dispatch, socket-event
bypass, UDP receive/framing/release, stream close and allocations that survive
worker migration. TCP drives the existing state machine under a connection
parser lock. Command parsing, item storage and response construction stay in
the upstream implementation. Ordinary socket builds omit the adapter modules.

## Integration and ownership

Each instance binds a domain-owned native IPv4 UDP endpoint before registering
request workers. The [runtime contract](../software/programming-model.md)
defines endpoint ownership and borrowed receive/direct transmit APIs; the
[HW/SW interface](../architecture/hw-sw-interface.md) defines cache-line layout.
Memcached retains upstream parsing, request IDs, UDP framing/segmentation, item
storage and connection state machines. It does not introduce UDP sessions or
response-retirement handles.

Native workers dispatch ready libevent callbacks and submit IO before checking
for idle. Only that idle boundary acquires a packet. One pending runtime-owned
view activates the connection event; the receive hook consumes that readiness
once and otherwise returns EAGAIN without another transport call. The parser
uses mutable borrowed bytes and advances past the eight-byte UDP framing header.
It restores the connection's owned allocation before cleanup and releases the
borrow after parser references, continuations and response/item work drain.
Neither the view nor its payload may survive release or be freed/reallocated.
Malformed and no-reply paths also release the borrow exactly once.

Each response segment gathers its framing/iovecs directly into a TX reservation.
The adapter chooses inline placement when the available iovec total is at most
64 bytes; larger segments use overflow. This removes transport staging, while
stored item bytes still need copying into the outgoing packet. Upstream retains
item/iovec ownership until local consumption. A definite submission rejection
consumes the reservation: reserve again and rebuild the same segment, advancing
its sequence only after successful submission. An indeterminate error must not
trigger automatic retry. TX success means local admission, not remote delivery.
Backpressure must settle before another blocking RX.

The native RX buffer validates IPv4 version, fixed header length, header checksum,
fragment flags/offset, destination, UDP/IP lengths and nonzero UDP checksums before
host admission. IPv4 UDP checksum zero is permitted. Payloads are 0–1472 bytes;
Ethernet padding is removed. Malformed/oversized input is drained and rejected.
No IP reassembly or options are supported. Unmatched UDP ports retain bypass;
ONC-RPC listeners retain their existing path. UDP TX checksum is zero, as allowed
for IPv4. Zero-length datagrams still transmit a UDP header. Short Ethernet TX
frames are zero-padded to 60 bytes before FCS without changing IP/UDP lengths;
this includes memcached's short `STORED` response.

## Worker and maintenance boundary

Each instance declares 1–W initialized request workers. Multiple instances may
declare more than W in aggregate. Main/clock and background maintenance threads
run on ordinary CPUs outside the isolated physical-worker pool. Workers complete
connection, logger and LRU-bump initialization before registration and bootstrap
parking. DESCHED immediately follows the existing yield path. RX NACK only
returns control to an assigned worker; it cannot activate a parked worker.

A `HAVE_LAUBERHORN` build uses native transports: select UDP with `-p 0 -U PORT`
and use `-t N`. Adding `lauberhornTcp` in the application's Nix recipe also
builds TCP; select it with `-l IPv4 -p PORT -U 0`. There is no environment switch
or socket UDP fallback. Proxy/extstore are excluded.
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
Use the existing [fenced process lifecycle](../software/scheduler.md#failure-and-reset-boundary) and
[Enzian harness](../development/validation.md#board-validation); killing instances does not make
resources safely reusable without the documented coherence reset.

In the pinned upstream source, `assoc.c` is the
worker-pause dependency and is guarded by the native scope failure. `items.c`
LRU maintenance/bump processing, `slabs.c` maintenance and crawler expiration use
ordinary locks/conditions and shared storage, without requiring a parked worker
to consume a notification. Requests reach blocking RX only after finishing the
state machine's runnable work and responses, with item locks released. Crawler
client dump/watch paths call `redispatch_conn` and remain excluded, as do shutdown
and administrative pause paths. These dependencies define the supported maintenance scope.

## TCP connection execution

The optional TCP package owns one shared stack per process. Any assigned worker
may accept or service a connection. A per-connection parser lock protects the
upstream command state machine; packet input and timer callbacks use stack
locks and never take the parser lock. All parser locks are released before RX
can park the worker. Read/write readiness is level-triggered and remains in the
stack while another worker is parsing. Parser continuations and writable
responses drain before the next receive boundary. Input/timer dispatch publishes
a readiness generation; scans recheck it after releasing parser locks so a
concurrent dispatch cannot leave readable data stranded behind a busy parser.

TCP parser buffers and response objects can survive a worker change. They use
connection-owned allocations instead of worker-local caches; response accounting
retains the allocating worker's stats owner. TCP rejects the worker-cache
`read_buf_mem_limit` option instead of silently ignoring it. Stable, unbound identity descriptors
index upstream connection objects and remain allocated per slot until process
exit; they carry no network traffic.

The parser reads stream bytes from the stack and writes existing response iovecs
without an intermediate application staging buffer. TCP retains retransmission
bytes independently of memcached item references. The stack fills actual
per-worker TX reservations, and DEADLINE drives its timers without a helper
thread. The VM checks run normal Linux clients against both this port and the
upstream reference; UDP checks use the same native binary.

## Proposed maintenance support

[HOST_WORK](../proposals/host-work.md) remains an unimplemented shared proposal.
Its assigned RX completion is only one part of supporting maintenance. Hash
expansion calls `pause_threads` for every registered worker; a parked worker
cannot consume its libevent notification. Finite RX polling cannot activate an
unassigned domain. Targeted worker notification, host-driven allocation and
host-only pause/resume must also be designed and implemented. Keep the expansion
and pause guards until actual resize succeeds across more registered workers
than physical workers, including all workers parked and resume without new
network traffic. The application uses the shared proposal rather than defining
another notification ABI here.

## Evaluation methodology

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
[clock-alignment limits](../hardware/tracing.md#clock-alignment) and tracing-off controls.
Uncorrelated timestamps must not be subtracted across hosts or FPGA clocks.

## Build and validation

Build with `nix build .#memcachedImage`. CI publishes this as
`out/memcached-deploy.img` beside the ordinary RPC image from the same revision.
`memcached-adapter` is a required preparation gate. The image contains
`apps/memcached` and `apps/memcached-tools` under `/nix/store/lauberhorn`, including
Python and command wrappers; it does not depend on a copied host executable.
`nix build .#dev.x86_64-linux.software.memcachedReferenceNative` builds the exact pinned upstream socket server
for local comparisons.

Follow [development](../development/workflow.md) for image builds and
[validation](../development/validation.md#board-validation) for matched-image
board provisioning, boot and acceptance. After that setup, use the packaged
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
first run `memcached-ready --host FPGA_IP --ports PORT... --output NEW_JSON`
from the client. This has a bounded startup deadline, records each probe and
retries only connection refusal/timeouts on fresh stats sockets. These probes
are outside compatibility and measured traffic; malformed responses fail instead
of being retried. Then run
`memcached-compatibility --host FPGA_IP --port PORT --output NEW_JSON` from
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

For finite switching acceptance, `tools/memcached/switching.py` combines that
startup gate with per-instance compatibility, distinct 1-KiB values, alternating
requests, concurrent GET/SET traffic and idle-to-active traffic. Compatibility
and workload requests never retry. Preserve the launch manifest and full frozen
trace; `tools/memcached/analyze_switching.py` requires every recorded process and
physical worker to receive dispatches, and process changes on a physical worker
to follow ordinary yields. It rejects trace loss, unmatched assignment/dispatch
identities and observed activation-parity mismatches. This finite correctness
test is separate from the saturation/performance matrix above.

`tools/memcached/report.py SUMMARY... --output NEW_REPORT_DIR` writes per-instance
CSV, aggregate throughput curves and worst-instance p99 curves from actual input
summaries. Error bars show observed repetitions, without filling absent cases.
Use the Nix development environment with NumPy, SciPy and Matplotlib for analysis.
The finite results below are not a completed saturation matrix.

Focused reproduction commands (from the platform root):

```sh
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.NativeUdpSim
nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcNestedSim \
  lauberhorn.host.eci.RxBypassSim lauberhorn.host.eci.TxBypassSim \
  -- -l org.scalatest.tags.Slow
nix build .#memcachedImage .#checks.x86_64-linux.runtime-tests \
  .#checks.x86_64-linux.memcached-adapter .#checks.x86_64-linux.workflow-tools
nix develop -c python3 tools/memcached/test_measurements.py
```

## Request tracing and latency analysis

Use [runtime tracing](../development/runtime-tracing.md#native-udp-correlation)
for capture/export and [hardware tracing](../hardware/tracing.md#native-udp-correlation-fields)
for descriptor references and timestamp boundaries. Start traced cohorts through
`memcached-launch --trace`, which blocks SIGUSR2 before exec so all threads
inherit the mask; direct signal export without that setup can terminate the
process. Quiesce requests at their wait boundaries before export. Capture FPGA
DDR through the [F_MAC1 workflow](../development/tracing.md). Keep traced windows
bounded; the full high-rate matrix does not fit in finite trace storage.

`breakdown.py` is the joined client/application/hardware analysis described
below. The older `latency.py` rejects global request-ID reuse and lacks client
RTT joins; do not use it for multi-tenant or wrapped-ID claims.

The pinned memcached adapter `b376490d303da13ba15ed0295190893c64d1d324`
echoes the reference from its still-borrowed runtime view. Event 203 records the
response preparation boundary before TX reservation/copy and the native
request-ID/sequence/segment-count header. Runtime event 200 records admission
entry plus the reference; event 202 retains the timestamp taken immediately
before the commit CL read and the immediate return timestamp (before trace
bookkeeping); event 201 records completion. RX `request_context`
contains the trace reference. The request's client IPv4/port/memcached ID remains
in `request_id`. Segment preparation and the blocking commit read are therefore
separately observable. Tracing-off behavior performs no timestamp work.

`tools/memcached/breakdown.py` uses narrow-ID lifetime constraints rather
than pretending IDs are globally unique. It checks complete MAC/Ethernet order,
RX enqueue/queue/dispatch identities, unique live per-process IDs, per-core RX
sequences, related TX references, software thread intervals and native response
segment headers. Different workers may dispatch out of process FIFO order.
Repeated client wire IDs are allowed only with nonoverlapping client lifetimes;
source IP/port are recorded by `patterns.py`. Missing, duplicated, mismatched,
failed, incomplete or ambiguous evidence is an error, not an inferred match.
The current bounded analyzer requires responses to finish before that worker's
next RX; it does not claim arbitrary delayed cross-worker UDP reply support.

`clock_alignment.py` extracts actual DCS RX responses, TX requests and TX responses
using the selected address map and trace-map source-delay corrections. It fits
CPU/FPGA clocks only after packet identity joins, reports conditional uncertainty,
and rejects fits needing more than 25 ns shared slack. It does not use internal
RX descriptor emission as the ECI response time. Additive timelines partition
MAC ingress to final segment egress; separate per-segment commit-read spans can
overlap other work and must not be added again. Small negative stages within
clock uncertainty remain visible. Figures show RTT and MAC latency ECDFs plus an
actual median-RTT request's timeline, with per-request CSV and JSON evidence.

For a bounded joined measurement:

1. Recheck reservation/live state and preserve frozen evidence. Collect the
   pipeline's matching bitstream, module, runtime, memcached image and trace map.
   Run the repository's matched RPC harness twice with tracing through module
   load before replacing the RPC service with memcached.
2. Use one registered instance declaring four workers and one outstanding
   request first. Then use the same client/workload with window 16, followed by
   five registered instances and single/pair/all-active bursts. Include both
   ordinary sends and the original batched sends. Keep GET and SET distributions
   separate. Main/maintenance remain outside the four-worker pool; retain hash
   expansion scope checks and existing harness lifecycle restrictions.
3. Start a bounded trace window while workers are quiescent before the first
   memcached request (including readiness/setup); finish all RX/TX before freeze.
   Enable loss-free buffered runtime and event CSVs with adequate capacity. Export
   only after requests return to their wait boundaries, which publish trace rows.
   Preserve the full setup/measurement/stats window; do not trim packets to make
   counts match. Export client request CSVs with source endpoint identity.
4. Run `nix develop -c python3 tools/memcached/breakdown.py --server SERVER.csv ...
   --events EVENTS.csv ... --client requests.csv --trace trace.bin --map MAP.json
   --capture-summary CAPTURE/summary.json --output NEW_DIR`, pairing server/event
   paths by instance in the same order. Default CPU mapping is 44:1 through 47:4;
   verify actual assignments. Retain raw captures and sidecar loss reports.
5. Repeat with application/FPGA tracing off to quantify observation overhead.
   Report RTT, MAC-to-MAC, their difference, queueing, CPU preparation, commit read,
   and aligned additive stages with uncertainty. The outside-MAC difference
   includes client processing, kernel/NIC queues and network in both directions;
   it is not solely network latency. Entry/sustained traffic labels alone are not
   evidence of hot/cold assignment.

Aligned mode rejects clock fits needing more than 25 ns shared slack.
`--clock-mode independent` reports FPGA-recorder intervals and CPU durations
separately; it does not relax aligned-mode acceptance. Use `report_breakdown.py`
for client distributions and tracing-off controls. A fit's conditional uncertainty
does not bound recorder queue bias or untimestamped system-event CDC delay.

## Five instances on four workers

The October 10 cohorts used the explicit readiness gate described above.
Compatibility and measured requests did not retry. The single-instance
compatibility control passed sixteen checks, including three-datagram responses
and unchanged hash power.

Two fresh cold-boot cohorts used five instances, one declared request worker
each, on the four physical workers mapped to Linux CPUs 44–47. Main and
maintenance threads inherited CPUs 0–43. Both cohorts passed all 80 per-instance
compatibility checks and 1,400 additional checked requests: 40 alternating,
200 concurrent and 40 idle-to-active requests per instance. Each instance had
a distinct 1-KiB value under the same key to expose cross-domain state or payload
confusion. The concurrent and idle-to-active phases use 95/5 GET/SET. Hash
expansion stayed inactive with hash power 18. This is finite functional
acceptance, not a saturation or latency benchmark.

The first trial's complete trace proves 404 process changes across the four
physical workers, each preceded by an ordinary yield. All five PIDs have exactly
302 dispatches, matching all readiness, compatibility, preload, workload and
final stats requests (1,510 total). All 408 scheduler assignments have matching
committed/delivered activations; 408 first-RX and 408 first-TX parity checks passed.
The audit also observes 166 domain migrations and 275 A→B→A reuse sequences.
No stored loss marker or parity mismatch was found.

The second complete trace independently confirms 389 process changes and 393
assignments with matching committed/delivered activations. Again, every instance
has 302 dispatches and all four physical workers execute requests. All 393
first-RX and 393 first-TX parity checks pass, with no stored loss marker or parity
mismatch; it records 129 domain migrations and 291 A→B→A reuse sequences.
Together the trials establish 793 yield-preceded process changes, 801 assignments
and 3,020 dispatches, alongside 160 compatibility checks and 2,800 additional
checked workload requests. Migration/reuse counts alone do not prove cache
freshness; distinct per-instance payload checks provide finite functional coverage.

The measured components were pipeline SHA `45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4`,
hardware job 2884502, runtime `aa1255b7c271769e67a84767a67ee57882140aa3`,
application `2eaa2b84e7a6ed0a7d0489b6caad49c211649864`, and the corrected target-tools
image (SHA-256 `cbe08b46e2327de8d1e2da1303c853137881c3586d66097000bd7df6231d1887`).
Client scripts are recorded separately by content hash; the readiness, workload
and trace-analysis tools are committed at `abe5e073`. Evidence is under
`out/memcached-switching/`: `provenance.json`, `cases-0{1,2}.json`,
`board-0{1,2}/summary.json`, `run-0{1,2}/client/summary.json`, full traces/maps,
switching audits, and `assignments-0{1,2}.{png,pdf}`. The plot shows recorded
assignments using the nominal 200-MHz FPGA clock; it is not CPU utilization or
correlated end-to-end latency.

Both 40-second CPU-sampling windows found main/background threads outside the
worker pool. Recorded main/background CPU time was 0.02/0.05 s in trial one
and 0.00/0.05 s in trial two, at 100 ticks/s resolution. Worker totals were
159.39/159.61 CPU-seconds; these snapshots include assigned workers awaiting RX
and are not proof of useful-work utilization. Worker snapshots before activation
can still show an ordinary CPU; hardware dispatch traces establish actual service
placement. All four worker CPUs appear in both sampling windows.

The two full captures are 1,607,767,296 bytes (SHA-256
`171eac634604abb4454d2dd2b930c788250492ffb4fac441a04d56a599621c13`)
and 1,371,885,504 bytes (`f29756198e9bb40b123539da67cc871092edaba40489cfa89d6d45c096d6a382`).
Both reported loss=0 before freeze and loss=1 afterward, without wrap or DMA
error. Stored-marker checks establish the analyzed window's completeness.
These October 10 results use 518689 hardware, WNS −0.950 ns, not timing closure.

## Four workers per tenant and variable bursts

The October 10 synthetic cohort declares four request workers in each of five instances
(20 declared workers sharing CPUs 44–47). It uses the same 518689 hardware,
`aa1255b7` runtime, `2eaa2b8` application and target-tools image recorded above.
The client/tool implementation starts at `c048ad9d`. Post-test `/proc` snapshots
confirm four `lh-mc-*` threads per PID and main/background execution outside the
worker CPUs; snapshots alone do not establish simultaneous assignments.

`patterns.py` rotates through five isolated tenant bursts, five overlapping
adjacent pairs (including last/first), and one all-five burst. It repeats that
sequence three times, with 100 ms idle gaps. Each active tenant sends 256 requests
per phase, with a bounded window of 16 outstanding requests on its own socket:
244 GETs and 12 SETs, a distinct 1-KiB value per tenant under the same key. This
window matches the 16-entry per-domain hardware queue and exceeds its eight-entry
additional-core watermark without deliberately overflowing an unserved queue.
All responses are checked, no workload request retries, and hash expansion is
rejected. This is a finite diagnostic workload, not open-loop offered-load replay.

Both the traced workload and subsequent tracing-frozen control passed all 15,360
requests (33 phases each), with no client errors/timeouts or observed hash
expansion. The post-test kernel diagnostic check passed. Every request's send and
receive timestamps, tenant, phase, operation, request ID and RTT are retained in
CSV. `plot_patterns.py` plots every sample and separate empirical CDFs, with
per-phase/tenant percentiles in JSON. “Entry” means the first 16 requests of a
burst; “sustained” means the remaining requests. These traffic labels are **not**
proof of hardware hot/cold state, and residency is not proof of cache warmth.

| Pattern | Traced sustained median / p99 (µs) | Tracing-frozen median / p99 (µs) |
| --- | ---: | ---: |
| Single tenant | 319.4 / 500.6 | 363.4 / 551.6 |
| Pair | 738.8 / 1068.3 | 748.0 / 1262.7 |
| All five | 1546.4 / 2673.6 | 1691.9 / 2977.8 |

These are gateway Python/socket RTTs, including generator event-loop, socket and
network overhead. The generator's receive ordering and bounded window affect the
results. The control does not establish a causal tracing-overhead estimate; this
is one boot with three pattern repetitions per run, not independent repetitions
or a publication-quality NIC latency benchmark. Do not equate the entry/sustained
RTT difference with activation cost. Hardware enqueue-to-dispatch delay uses its
own nominal 200-MHz clock, without claiming client/FPGA clock correlation.

The complete workload capture is 701,479,040 bytes, SHA-256
`dedb6be8b8a0992762322dbadac1a519d4bf4055d0161253f5aefde6aef75339`.
It has no wrap or DMA error, but contains stored trace loss: a setup/resume gap and
a short loss burst during the first all-five phase. Thus the full capture is not
a loss-free audit of the entire workload. Keep the full capture and explicit
analysis-window limits; never remove loss markers from inside a claimed window.

The explicitly bounded loss-free prefix ends before the first all-five phase
(cycles `[91735818816, 96641756442)`). It audits 3,850 dispatches (ten setup
requests plus the first ten workload phases), 17 assignments and 13 process
changes, with 17 first-RX and 17 first-TX parity checks and no mismatch. One
instance peaks at three simultaneous assignments; the other four peak at one.
Declaring four workers did not make this socket-paced run exercise four-way
scale-up for every tenant.

Within that prefix, 15 requests arrived with no assigned worker: enqueue-to-
dispatch median 31.235 µs, p99 84.459 µs. The 3,835 requests arriving with an
assigned worker have median 0.015 µs but p99 177.108 µs. Residency therefore does
not eliminate queueing, and a cold activation is not necessarily the largest
delay. These are scheduler intervals, not application service time or client RTT.
`switching-prefix.json` records the exact bounds; `queue-latencies-prefix` and
`assignments-prefix` provide the corresponding PNG/PDF plots. Reproduce using
`analyze_switching.py --start-cycle 91735818816 --end-cycle 96641756442` with the
captured trace/map and `run-01/launch.json`; the default full-capture audit rejects
this trace's loss. These prefix results do not validate the excluded all-five
phase or subsequent repetitions.

Evidence: `out/memcached-patterns/provenance.json`, `cases-01.json`,
`board-01/summary.json`, `run-01/{client,control}/{summary.json,requests.csv}`,
`trace-{setup,workload}/`, `capture-audit.json`, and
`latencies-{01,control}{,-cdf}.{png,pdf}`. The setup capture SHA-256 is
`62af27038596fdca2508695212bcca5cdeeda3c08a8a309cdc04a0117649b867`.
Future multi-tenant dataset replay needs timestamped tenant
arrivals, preserved burst/idle structure, an open-loop generator with pacing
error accounting, and repeated tracing-off performance runs; this test does not
select a dataset or establish paper benchmark performance.

### Kernel-batched bursts: complete scale-up and switching evidence

The tightened variant (`patterns.py --batch`, tools at `a32f3cf9`) uses Linux
`sendmmsg` to enqueue each bounded tenant burst in one syscall. It retains the
16-request window and all 33 phases/15,360 requests. Each request in a batch is
timestamped at the same client call-preparation start, so RTT includes ctypes
preparation and kernel batch transmission; these are not individual wire-send
timestamps. The batched variant and its matching tracing-frozen control both
passed all 15,360 requests. Across the two variants and their controls, 61,440
workload requests passed without errors/timeouts or hash expansion.

The full 808,888,576-byte trace is checksum-verified, SHA-256
`a64148b7cf20f5bb6222f5df6f8249d214e7feefbb03914f6ffc5717e4e06e00`.
It has **no stored loss markers**, wrap or DMA error. The VIO loss flag was zero
before freeze and one afterward; the stored-marker audit establishes the captured
history's completeness.

All five tenants individually reached **four simultaneous assigned workers**,
and every tenant dispatched requests on every physical worker. The full audit
proves 2,477 yield-preceded process changes and 2,481 assignments with matching
committed/delivered activations. All 2,481 first-RX and 2,481 first-TX parity checks
passed. Each PID has 3,075 dispatches (one readiness request, one preload, 3,072
workload requests and one final stats request), totaling 15,375. The audit also
observes 617 domain migrations and 1,475 A→B→A reuse sequences. These are finite
correctness and scheduling observations, not fairness or latency-SLO guarantees.

The hardware latency plot contains every one of the 15,360 workload requests.
The two serialized initial requests and one final stats request per PID are
explicitly excluded from these latency statistics, while remaining in the full
trace audit. Requests are classified using assignment state at enqueue and the
last assignment to their eventual dispatch core before dispatch:

| Assignment state at enqueue | Requests | Queue median (µs) | Queue p99 (µs) |
| --- | ---: | ---: | ---: |
| No worker assigned to tenant | 1,064 | 30.518 | 56.630 |
| Tenant resident, eventual dispatch worker assigned afterward | 4,603 | 72.875 | 97.174 |
| Eventual dispatch worker already assigned | 9,693 | 20.230 | 100.905 |

The middle class captures worker activation/scale-up while the tenant is already
resident. The last class is the closest scheduling analogue of “hot,” but does
not prove cache warmth; all three include queueing. Thus activation is visible,
but simply labeling the first packet cold and the rest hot misses both scale-up
and load-dependent queueing tails. FPGA intervals use nominal 200 MHz (5 ns
quantization), without an independent oscillator or client-clock calibration.

| Batched pattern | Traced sustained median / p99 (µs) | Tracing-frozen median / p99 (µs) |
| --- | ---: | ---: |
| Single tenant | 585.8 / 987.2 | 586.3 / 1004.7 |
| Pair | 972.1 / 2053.0 | 966.3 / 2137.4 |
| All five | 1744.4 / 5496.1 | 1914.2 / 2551.5 |

These gateway RTTs have the client limitations described above. In particular,
the different all-five tail in the two finite runs is not a causal estimate of
tracing overhead. A paper benchmark needs the controlled generator/repetition
work, and correlated clocks for a joined end-to-end breakdown.

The post-test kernel/TAD records and CPU snapshots are preserved. The 59.935 s
sampling window recorded main/background CPU time of 0.00/0.05 CPU-seconds at
100 ticks/s resolution, with all observed main/background CPUs outside 44–47.
Worker CPU time was 239.34 CPU-seconds, including long assigned RX waits; it is
not useful-work utilization.

Additional evidence under `out/memcached-patterns/`: `cases-batch.json`,
`board-batch/summary.json`, `run-batch/{client,control}/{summary.json,requests.csv}`,
`run-batch/cpu-summary.json`, `trace-batch/`, `switching-batch.json`, and the
workload-only derivative `switching-batch-workload.json`. Plots (PNG and PDF):
`latencies-batch`, `latencies-batch-cdf`, `latencies-batch-control`,
`latencies-batch-control-cdf`, `queue-latencies-batch`, `assignments-batch`.

### Separating hot/cold from backlog

The earlier resident-worker distribution mixed immediate dispatch and backlog.
`tools/memcached/latency_cases.py` reconstructs older requests still awaiting
**dispatch** for the same PID at each enqueue, using the complete audited trace.
This includes staged requests; it is not a literal reading of the FIFO fill
register. It counts neither other tenants' requests nor requests already in
execution. The five disjoint workload groups are:

| Case | Requests | Median (µs) | p90 (µs) | p99 (µs) |
| --- | ---: | ---: | ---: | ---: |
| Assigned worker; no older request waiting | 3,200 | 0.015 | 6.461 | 17.506 |
| Assigned worker; older requests waiting | 6,493 | 68.595 | 90.539 | 103.920 |
| No worker assigned to tenant; no older request waiting | 618 | 28.190 | 30.727 | 31.895 |
| No worker assigned to tenant; older requests waiting | 446 | 53.140 | 55.615 | 57.023 |
| Tenant resident; eventual dispatch worker newly assigned | 4,603 | 72.875 | 85.239 | 97.174 |

All values are **scheduler enqueue-to-dispatch**, not memcached response latency.
“No older request waiting” is deliberately not called “no queueing”: a previously
dispatched request may still occupy the worker. Of the 3,200 hot/no-backlog
requests, 1,759 dispatch in two or three cycles (10–15 ns at nominal 200 MHz).
Their remaining tail must not be presented as an idle-worker baseline. Exact
idle/busy-at-arrival and end-to-end RTT per hardware case require additional
state/correlation evidence; the existing client RTTs are not joined to these
hardware cases. Assignment-hot does not mean cache-hot.

The clearer case CDFs and exact percentiles, including p99.9 and maximum, are in
`out/memcached-patterns/latency-cases.{png,pdf,json}`; the per-request classification
is in `latency-cases-requests.json`. The tool accepts the full audit and the
workload-only derivative and verifies their trace hashes match. Its regression
checks cover tenant independence and dispatch-time removal from the backlog.

For clarity, the existing batched RTT medians/p99s over **all** workload requests
(including burst entry) are 591.2/1001.8 us for one active instance,
980.2/2052.6 us for two, and 1771.5/5590.7 us for five. Tracing-frozen controls
are 590.5/1016.7, 971.6/2136.5 and 1931.6/2540.5 us respectively. These include
Python client/batch overhead. The single-active case still has five registered
instances and up to 16 outstanding requests against four workers; it is not
an idle-worker or queue-free baseline. Source: the recorded
`run-batch/{client,control}/requests.csv` files (15,360 requests per run).

## Joined RTT measurements and recorder limitation

The October 11 matched 518836 image (`8d291b7f`, memcached `b376490d`) completed two
1,536-request traced single-instance runs and one 1,536-request tracing-off
control. Each case uses four declared workers, three 128-request bursts,
1-KiB values and SET every twentieth request. All completed cases passed reply
checks and retained hashpower 18 without expansion. Main/background threads
ran on CPUs 0–43. The joined cohort used all worker CPUs 44–47; one-outstanding
and ordinary-send cases predominantly use CPU 44. This is a bounded latency
experiment, not a saturation or hot/cold residency classification.

[Compact results, hashes and provenance](../../data/eci/board-evidence/memcached-breakdown-518836-20261011.json)
record the matching hardware/software artifacts. Raw evidence is under
`out/memcached-breakdown/`. The repeated trace joins all 1,548 in-window requests
(1,536 measured plus readiness/setup/statistics), with 1,550 software rows including
two explicitly identified prewindow neighbor warmups. Software/event sidecars
report zero drops. The 339,883,456-byte hardware window passed hash/length checks,
contains no loss markers, and had no pre-freeze loss, wrap or DMA error. The
post-freeze sticky loss flag is recorded separately.

Median durations, microseconds:

| Client case | Traced RTT | Tracing-off RTT | Recorder-observed MAC interval* | Observed scheduler wait* |
| --- | ---: | ---: | ---: | ---: |
| Window 1, individual sends | 125.916 | 126.091 | 18.980 | 0.030 |
| Window 1, batched sends | 213.897 | 212.597 | 19.205 | 0.030 |
| Window 16, individual sends | 1528.033 | 1526.663 | 18.790 | 0.030 |
| Window 16, batched sends | 1718.264 | 1724.134 | 36.537 | 14.878 |

*The recorder timestamp defect below qualifies hardware intervals, particularly
under concurrency; these are not precise wire-time claims.* RTT p99s in the same
row order are 160.35, 272.91, 1791.59 and 4052.77 µs. These client RTTs do not
reproduce the historical 591 µs result, and are not a matched before/after
performance regression. Both new traced cohorts agree closely, and the control
shows that application/FPGA tracing is not responsible for the large RTTs.

CPU-clock medians are about 12.0–12.3 µs from RX return to the first response
preparation, 2.66–2.74 µs from preparation to the TX commit read, and 3.64–3.65 µs
across the instrumented commit call. The first interval includes runtime,
libevent dispatch, parsing and storage; it does not isolate the hash lookup.
The TX preparation includes response assembly and runtime descriptor/barrier
work. The commit-call interval overlaps NIC work and must not be added to a
hardware TX interval as a separate sequential stage.

Client timestamps show a median 1.16 ms span to issue 16 individual requests and
about 1.66 ms to collect 16 responses, including Python bookkeeping/CSV processing
between receives. Batched timestamps start before ctypes preparation, not at
individual wire sends. The observed large RTT is dominated by the generator's
processing and socket/kernel/network path; `RTT - MAC` is not solely network
latency. A native generator and kernel/wire client timestamps are needed for
paper latency claims. The qualified plots are
`out/memcached-breakdown/report-qualified/{distributions,breakdown}.{png,svg}`.
The full join retains separate GET/SET, entry/sustained, port and workload groups.
Mean stacked stages conserve durations; independent medians are not summed.

**Recorder timing blocker:** matched dependency `5845335` samples `savedTs` on
`bufferedPorts.fire`, after the trace input FIFO, rather than on input arrival.
Consequently, variable recorder queue residence contaminates event timestamps
without requiring any dropped sample. System ECI traces also cross an
untimestamped asynchronous FIFO before reaching this recorder. The combined
DCS/CPU fit needs 575.6 ns slack and is rejected by the 25 ns acceptance limit.
Three isolated workload fits require only the existing 10 ns quantization slack
and show conditional uncertainties around 166–171 ns, but that model does not
bound recorder queue bias. The batched concurrent case is rejected. Do not
promote the conditional fine splits to accurate wire measurements.

The analysis has explicit `--clock-mode independent` reporting: additive
FPGA-recorder intervals plus separate CPU durations, with the timestamp contract
and limitations in the output. It does not relax the aligned-mode acceptance
threshold. `report_breakdown.py` compares all client samples with the tracing-off
control. The recorder correction carries the timestamp into the input FIFO and
adds an ingress-versus-dequeue regression. Its new map labels the timestamp point;
system-CDC uncertainty remains explicit and must be rechecked on the new build.

The subsequent five-instance/four-workers-each extension reached 2,688 completed
requests before the peer client output filesystem returned ENOSPC. The 477,408,384-byte frozen
hardware window and all five loss-free software traces are preserved; it is not a
passing oversubscription latency campaign. Use `/dev/shm` for future client output,
retain existing evidence, and repeat on the corrected recorder hardware.

The corrected recorder subsequently passed two matched cold-boot RPC trials
(200 replies) on 518922, revision `cc19c4285148db65c5b5517f6a02abd04b7f9142`,
with dependency `e24f2ef`. The matching map declares
`trace_dma_input_before_batch_queue`; system events still cross an untimestamped
asynchronous FIFO. The 412,129,920-byte RPC trace had zero recorded loss markers.
This is functional RPC acceptance, not corrected memcached measurement evidence.
[Artifact hashes and acceptance](../../data/eci/board-evidence/trace-recorder-518922-board-20261011.json)
record WNS −0.691 ns and 48,874 failing setup endpoints, not timing closure.
Corrected memcached burst measurements and the CDC uncertainty bound remain open.

## Linux bypass control

On October 11, pinned upstream `0e6ba4da1fcaa3bf93f588d6d1144c9ecb231f9e`, built for
AArch64 with the existing Nix package (`enableLauberhorn = false`), ran on
zuestoll14 Linux 6.8.0-64. Its four ordinary libevent request threads were pinned
individually to CPUs 40–43; main/background threads were restricted to 0–39.
Native instances used isolated CPUs 44–47. TCP and
extstore were disabled; UDP port 11220, 128 MiB and hashpower 18 matched the
bounded native setup. Application source was unmodified; the package's generated
version label was unchanged. The standalone Nix closure was mounted separately
and invoked through its own glibc loader/library path.

The CPU `nicvf` interfaces advertise software timestamping only. Therefore this
comparison uses **Linux UDP/libevent over Lauberhorn's FPGA bypass/netdev**, with
the same F_MAC0 arrival/egress boundaries as the native result. It is not a
conventional CPU-NIC baseline. FPGA pipeline 518836 and matching trace map stayed
unchanged; no reboot, programming, timing or DESCHED change occurred.

Three 128-request bursts with one outstanding request and 1-KiB values passed:
384 requests, no errors/timeouts, no hash expansion. Each burst has 122 GETs and
six SETs (SET every twentieth request). Excluding the first GET of each burst,
the **363 sustained GETs** compare as follows (recorder-observed microseconds):

| Path | p50 | p90 | p99 |
| --- | ---: | ---: | ---: |
| Native, earlier `w1-send` capture | 18.970 | 21.248 | 26.106 |
| Upstream Linux through FPGA bypass | 74.650 | 77.746 | 92.595 |

All-command Linux MAC medians were 74.818, 74.773 and 74.623 µs across repeats.
Client RTT median was 327.972 µs with tracing and 328.568 µs with tracing frozen;
the identical 384-request control also passed. Neither client RTT is a MAC time.
The peer AF_PACKET capture ran during both new cohorts; the older native cohort
had no such capture. Different CPU sets and OS scheduling/interrupt behavior
remain comparison limits. Sustained traffic and one outstanding request do not
prove CPU-cache warmth or absence of Linux scheduling delays.

All 387 IPv4 request MAC events and 389 response MAC events match the peer's
complete IPv4 wire order; UDP port/request IDs select the 384 measured exchanges.
The additional traffic is startup/stats, including two segmented stats replies.
Two ARP frames captured outside the measured hardware window are excluded; every
hardware RX Ethernet event in that window has an IPv4 decoder event. MAC/Ethernet
counts, causal ordering, single-segment measured responses and full application
reply validation are checked. The complete 58,684,672-byte bounded dump has no
lost-sample markers (the live sticky loss bit was already set before this window).
The legacy recorder queue-residence timestamp bias still applies: these are
provisional hardware intervals, not corrected-recorder precision claims.

[Compact evidence](../../data/eci/board-evidence/memcached-linux-bypass-518836-20261011.json)
records artifact hashes, upstream/binary/closure provenance, thread affinities,
per-repeat distributions and limitations. Local `out/memcached-vanilla/` contains
`summary.json`, `joined.json`, wire/client records, `comparison.png`/`.svg`, the
frozen dump and a hashed `reproduction.tar.gz` of exact scripts. Decode, joins and
plot generation ran through `nix develop`.

This control does not implement or measure Caladan. The main paper comparison
requires the [Caladan Enzian baseline](../research/caladan-enzian.md) with matched
workload semantics and resource budgets.
