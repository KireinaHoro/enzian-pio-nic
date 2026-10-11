# Native UDP memcached

The transport and upstream adapter integration merged into platform `master`
in `6b489de8`. Current transport and adapter follow-up is on platform branch
`feat/memcached-rx-wait`; the upstream-based application worktree is on
`feat/lauberhorn-rx-wait` in `../memcached-legacy`. The platform pins the borrowed
adapter after local validation. Pipeline 518689 now has matched RPC validation
and finite memcached acceptance with five instances sharing four physical workers.
Both one-worker pools and four-worker pools passed; the latter exercised
single/pair/all-tenant bursts, every tenant reaching four assigned workers, and
trace-verified switching. See [initial switching](#five-instances-on-four-workers-october-10)
and [burst/latency results](#four-workers-per-tenant-and-variable-bursts-october-10).
The full pool-size/performance matrix and timing closure remain open.

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
ports to host order. RX exposes one contiguous mutable transport borrow until
explicit release; retaining data beyond release requires an application copy. TX takes endpoint,
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

TX is synchronous through local admission and packet-buffer consumption; success
does not establish network delivery. Submission consumes the transport reservation
even on definite rejection. The adapter retains its application response and
rebuilds a fresh reservation for retry. Indeterminate errors must not trigger an
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


## Active plan: contiguous borrowed RX and direct TX before worker-wait adaptation

Maintainer direction (October 9): first fix the RX hardware/software contract so
RPC calls/replies, bypass and native UDP all expose one contiguous host buffer,
on both RX and TX.
Keep the small-message fast path: a complete host payload of **0–64 bytes** is
in the selected control CL's upper half; a payload **over 64 bytes** starts at
the direction-relative offset **0x100**, the third CL. Large messages do not place a prefix in the
control CL. RX descriptor length selects the location; TX reservation capacity selects it
and the explicit placement bit preserves that choice. RX length describes the entire
host-visible buffer, including reconstructed bypass headers and RPC argument
bytes previously embedded in metadata. The existing twelve overflow CLs provide
1,536 bytes, sufficient for the configured 1,518-byte frame maximum and
1,472-byte UDP payload. No worker address-layout expansion is needed.

### Transport implementation and compatibility

- Reconstruct each host payload in the RX router from descriptor-resident prefix
  bytes plus packet-buffer bytes. Move bypass headers and the existing 48-byte
  RPC argument/reply prefix into the contiguous data region; retain RPC tokens,
  handler addresses and completion-routing fields as metadata. Preserve existing
  RPC short-argument padding semantics unless separately changing the decoder.
- Return zero in the control payload half for large messages. Map overflow byte
  zero to host-payload byte zero, with byte-exact prefix/body joining and zero
  padding after the logical length. Replay of either control/data line must be
  stable until retirement; no speculative out-of-range read may disclose data.
- Count RX overflow invalidations as zero for lengths <=64 and ceil(length/128)
  otherwise. TX uses its explicit placement bit and actual send length: zero
  overflow lines for inline, otherwise ceil(length/128). Cancel completes the
  transaction without a packet. Full invalidation remains an ownership-handoff
  operation. Preserve cancellation, cooperative
  PREEMPT, write-return service, ownership and quiescence behavior.
- Update generated descriptor documentation and an explicit RX/TX layout marker,
  C runtime/kernel readers and simulation CPU readers together. Never deploy
  the changed software against 518140 or any other old-layout bitstream.
- RX wait/acquire returns the contiguous borrowed view directly: metadata,
  mutable payload pointer and length. There is no separate runtime consume call
  and no staging copy. The old copying RX/TX compatibility APIs have been
  retired; memcached must migrate to the borrowed-view/reservation APIs.
  Payload writes are permitted: current RX
  return writes must survive conflict eviction/reload. The router retains payload
  writebacks in a per-packet shadow until retirement; descriptor stores remain
  ignored and packet-buffer storage is not modified. Discard-only writeback
  handling is insufficient for a mutable parser borrow.
- Enforce one outstanding borrow. Local repeated readiness checks do not call
  RX again. Explicit release means every user of the packet has finished with
  its bytes; it does not itself advance the hardware handshake. Reject another
  acquisition or assignment-releasing yield while borrowed. PREEMPT remains
  control-only and cannot coexist with an outstanding packet borrow.
- Copying a pointer or view into a queue, callback, deferred request, fiber or
  future does not consume the packet or transfer its backing storage. Such a
  reference keeps the borrow live. Work that must survive release must first
  decode/copy into genuinely owned application storage. No queued raw 2F2F
  pointer may survive release, the next RX, or assignment release/reassignment.

### TX placement and default-runtime staging removal

The same rule applies to TX: a complete transport payload <=64 bytes occupies
only the selected control CL's upper half; larger payloads start at TX offset
0x100 and occupy only overflow CLs. Descriptor metadata must not contain hidden
RPC payload prefixes. Translate between the new contiguous host representation
and network-encoder metadata/streams consistently, including nested calls and
replies. Bypass's header-generation commands remain metadata: document explicitly
whether its transport length excludes the generated Ethernet header, and keep
kernel framing consistent with that choice. Do not silently change wire output.

The default runtime must not allocate or fill a worker-local tx_buf staging
allocation. tx_get_buffer(capacity, &reservation) selects inline storage for
capacity <=64 and overflow otherwise. Commit takes actual_length <= capacity.
A common TX control-header inline_payload bit records the reservation's placement:
hardware must never infer TX placement from actual length. An overflow reservation
committed with 1–64 bytes remains in overflow, without relocation or padding the
wire length. Inline placement with actual_length >64 is invalid. Zero-length
commits transmit no payload regardless of placement; execution controls are
always descriptor-only. RX continues to select placement by received length.

An upper bound is sufficient for codecs to reserve; exact-size codecs can still
select the smallest region, but a sizing pass is not mandatory. Encode directly
within capacity and commit the returned size. UDP callers construct each response
directly in the reservation; the old sendv API is retired.
Set placement before writes can evict the control CL. Overflow-mode control-half
writeback must never overwrite payload byte zero; replay of that unused half is
zero. Invalidation uses actual send length, not capacity: inline has no overflow;
overflow uses ceil(length/128). Byte 56 is reserved again. Before the doorbell,
voluntary overflow returns update the packet slot. The router retains control
inline bytes separately, restores them after control invalidation and suppresses
overflow writes until an inline TX completes. This includes a control line that
was evicted before the doorbell. Same-domain stale readback is permitted; full
invalidation and scrubbing remain at ownership handoff. Do not leak or
transmit bytes outside actual_length. Test reserve 65/128/1472 -> commit 0/1/64,
as well as exact-capacity sends, both parities, rejection/retry and buffer reuse.

A reservation is bound to the current assignment and parity. Reject overlapping
reservations and yield/reassignment while reserved. Commit publishes metadata
and payload with the established barriers, then advances the TX handshake.
Accepted TX consumes the reservation; definite rejection may be retried only
with valid payload ownership at the new parity. Do not retain a pointer into an
old control CL or assume an invalidated CL still contains its old contents.
Indeterminate failure must not be retried. Descriptor-only notifications must
not clobber an active data reservation. RPC codec failure and pending-call
allocation failure cancel through a payload-free local no-op; no failed request
is emitted on the network.

Keep the old copied RX wrapper lifetime. A kernel/legacy copied TX wrapper may
remain for compatibility, but it must use the new layout and must not hide a
staging allocation in the default userspace runtime. Test direct encoding, both
parities, 64/65-byte transitions, zero length, maximum sizes, rejection/retry,
cancel, codec-size mismatch, stale reservations and assignment release. Record
removed staging copies separately from unavoidable application/storage copies.

### Memcached follow-up after contiguous RX is validated

Keep separate application event-wait and receive-hook call sites, but do not
split the runtime RX API into wait and consume. Event-wait obtains the borrowed
view pointer and stores only that pointer in the worker's single pending slot.
The runtime owns the view object and its lifetime. The receive hook hands
that same view to the connection exactly once, without another transport call
or copy. Track PENDING -> IN_USE -> RELEASED: handing the pointer to the parser
clears local receive readiness but does not release transport ownership. Point
the native UDP parser at the mutable borrowed contiguous payload; skip its 8-byte UDP framing header by advancing a pointer, not memmove.
Keep the connection's owned allocation separately and restore it before cleanup;
never free/realloc a borrowed RX address. Finish the acquired datagram, all local
continuations and admitted response segments before releasing the view. Retry
TX rejection without another RX. Malformed and no-reply requests must also
release exactly once, including all error/cleanup paths.

Restore worker event dispatch, IO submission and explicit local readiness.
A receive without an acquired packet returns EAGAIN. Blocking RX belongs only
at the worker idle boundary. If a host pause arrives with a packet acquired,
finish that one datagram before acknowledging pause, and acquire no next packet.
Do not add a spill queue. Keep adapter-local host-event registration and
host-only barrier-parking extension points, with injected HOST_WAKE/resume tests;
real HOST_WAKE and yield(on_host_only=True) remain deferred. Real hash expansion
stays guarded; ordinary main/independent maintenance threads remain enabled.

### Migrate current consumers without transport staging

Migrate all current consumers of the default runtime to borrowed RX and
capacity-reserved direct TX. Application handler signatures need not change merely
to remove transport staging. Audit each codec and every pointer retained across
callbacks, nested waits, fibers, queues and futures.

- RPC/XDR: decode directly from RX CLs into the existing owned argument/result
  objects; reserve a validated encoded-size bound and marshal directly into TX CLs.
- Nested-validation: update codec capacity handling and keep owned decoded messages across
  deferred execution. Never substitute a borrowed RX pointer for that ownership.
- Dandelion: update the pinned codec and Rust FFI with the reservation/capacity
  contract; audit BSON/XDR intermediates separately from transport staging.
- Memcached: parse the borrowed mutable contiguous payload, keep stored values
  owned by items, and gather response iovecs directly into reserved TX CLs.
- Kernel bypass: copy directly from RX CLs into the owned skb and directly from
  skb data into TX CLs. Linux may retain the skb after the transport is released.
- Forwarded RPC replies: copy directly from the borrowed RX region into reserved
  TX CLs; independent RX/TX storage still requires this copy.

Remove default-runtime rx_buf/tx_buf staging allocations after migrating their
users. Keep copied compatibility wrappers only where explicitly requested by a
caller; no hidden staging fallback in the default path. Serialization, item
storage, skb ownership and RX-to-TX forwarding copies are not removed by this
transport change and must not be reported as zero-copy application processing.

### Validation and delivery gates

1. Exercise both parities and logical lengths 0, 1, 63, 64, 65, 127, 128, 129
   and maximum sizes across native UDP, RPC calls/replies and bypass. Verify exact
   contents, reconstructed-prefix boundaries, padding, replay and no disclosure.
2. Exercise overflow invalidation counts, RX writeback (including software-dirtied
   borrowed data), NACK/PREEMPT, release/reassignment and concurrent RX/TX progress.
   Keep ONC-RPC/TX/bypass regressions. Run source-defined targets through nix develop.
3. Test view lifetime, duplicate receive-hook delivery/release, acquire/yield while borrowed,
   old wrapper data lifetime and parity-independent contiguous pointers.
4. Test memcached callback ordering, no RX while work/response retry is runnable,
   pause-before/after-processing ordering and injected wake/resume. Compare supported
   commands with pinned upstream, including CAS, expiry, append/prepend, no-reply,
   segmented replies and malformed requests. Build with the existing Nix packaging.
5. Regenerate matched ABI collateral and launch a new hardware pipeline through
   the scheduled CI workflow. Recheck reservations, preserve existing failures,
   then test at least two cold-boot RPC repetitions plus bypass and native UDP
   compatibility using matching artifacts. Do not substitute old hardware.
6. Only then resume memcached compatibility and oversubscription measurements.
   Report timing without optimization; record provenance and finite coverage.

The earlier split-copy implementation was interrupted before validation; it is
not acceptance evidence. This plan supersedes its split-span and separate
wait/consume API designs and the proposal to linearize large parity-0 packets
in memcached. The remaining uncommitted split-copy code must be adapted or
removed before validation; none of those interfaces is the accepted final API.

### October 9 implementation checkpoint: contiguous transport

Historical version-2 checkpoint (superseded by the October 10 version-3
transaction refinement below): the foundation was implemented on
`feat/memcached-rx-wait`. Generated
`ECI_PAYLOAD_LAYOUT_VERSION=2` selects the new contract; the C transport refuses
old headers. TX bit 23 records inline placement and control byte 56 records the
reserved overflow-line count. No worker address-map or PREEMPT change is needed.

The default RPC runtime decodes directly from borrowed RX and encodes directly
into TX reservations, without worker RX/TX staging allocations. Existing codec
signatures are unchanged. Generic RPC codecs reserve their safe maximum wire
capacity, so small encoded RPCs can use overflow; this is correct but does not
claim optimal small-message performance. Exact-capacity callers retain inline
placement. Kernel bypass copies directly between transport and owned skbs.
Native UDP exposes `lauberhorn_udp_receive_view`/`lauberhorn_udp_release` and
`lauberhorn_tx_get_buffer`/`lauberhorn_udp_send_buffer`/`lauberhorn_tx_abort`.
The copied receive and sendv compatibility paths remain available.

RX writeback needs a per-packet byte-masked replay shadow: simply discarding
returned bytes loses parser mutations after eviction. A directed test exposed
and corrected shadow indexing relative to the RX control base. TX commit and
abort drain the whole reservation, including dirty bytes beyond actual length;
malformed placement uses ordinary bounded descriptor rejection. Unused RX
regions and out-of-range TX accesses preserve zero masking and adjacent-slot
isolation. The ownership fixture now probes the inline half and twelve overflow
CLs separately; treating the new overflow region as 1,600 bytes correctly trips
the address firewall and is not a legal test access.

Local validation through `nix develop` and Nix package targets:

- Full fast ECI run: 55 executed, 51 initially passed, four failures diagnosed;
  one explicitly ignored test remained ignored. Corrected cooperative ownership,
  oversized TX rejection, RX padding and TX allocation-boundary cases all passed
  focused reruns. The other passing coverage includes RPC/nested RPC, concurrent
  workers, GSYNC, ownership/firewall checks, bypass and captured-packet replay.
  A fresh full run after the final corrections is delegated to hardware CI.
- Native UDP covers 0/1/63/64/65/127/128/129/1024/1472-byte boundaries, both
  placements, full-capacity dirty reservations committed short, abort-to-inline
  reuse, explicit mutable-RX eviction/reload, endpoint ownership and bad packets.
  The original failing replay seed (`setup=-1275007138`, `sim=-1104709226`)
  passed after correction; the final focused UDP rerun also passed.
- Production C ownership, copied-wrapper lifetime, stale TX handles, rejection,
  codec/future/activation tests and buffer/codec ASan+UBSan checks passed.
  Final `eci.generateVerilog` and regenerated Mackerel-header C checks passed.
- Nix built the AArch64 kernel/runtime, microbenchmark and Dandelion deployment
  images and runtime-interface example; the existing memcached adapter
  compatibility check passed. The pinned Dandelion `1a9eb8f6` codec already
  respects output capacity and decodes into owned strings/BSON objects.

Reproduction: `mill gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci`
inside `nix develop`; focused suites are `CooperativeYieldSim`,
`RxPayloadIsolationSim`, `TxPayloadIsolationSim` and `NativeUdpSim`.
Package targets are `runtimeTests`, `deployFs`, `dandelionDeployFs`,
`checks.x86_64-linux.runtime-interface` and
`checks.x86_64-linux.memcached-adapter`. Local transcripts, preserved failure
captures and Nix output manifests are under `out/contiguous-transport/`.
The final runtime-test output is
`/nix/store/ba4w27kzcr9xja60f7bsryc72inalmps-lauberhorn-runtime-tests`.

No new Enzian run, timing result or throughput claim is established by these
local checks. Deploy only new, matched CI artifacts. Memcached's event-wait/
receive-hook adaptation and borrowed connection-buffer lifecycle remain the
next application change; HOST_WAKE and host-only yield remain deferred.

Hardware handoff: [pipeline 518395](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/518395)
builds transport commit `8bb3afe222c92d57e09f6ca3833634ba67b4b6b2` on the retained
`feat/memcached-rx-wait` branch. Initial jobs: fast ECI `2882561`, runtime tests
`2882572`, memcached compatibility `2882571`, preparation `2882575`, hardware
`2882576`, report `2882577`; the full initial job list is preserved in
`out/contiguous-transport/ci-jobs-initial.json`. At launch, test gates were
running/pending and preparation/hardware were created, not passed.
The required preflight timer is armed for **October 9, 18:36:53 CEST** in thread
`01a11cbc-4d8f-76f1-9945-f69de06ed22f`. Follow the scheduled CI workflow, then test
matched artifacts on Enzian; never substitute an older bitstream or the later
documentation-only branch head for the pipeline SHA.

October 9 preflight: pipeline `518395` at the SHA above passed all 14 test gates
(jobs `2882561`–`2882574`), including the full fast ECI suite. Preparation
`2882575` failed fetching the pinned Ubuntu kernel tag
`Ubuntu-6.8.0-64.67` from Launchpad: four HTTP 504 responses (“Path translation
timed out”). Hardware `2882576` and report `2882577` were skipped; there is no
new bitstream or board result. The job trace and status snapshots are preserved
under `out/contiguous-transport/prepare-2882575.log` and
`preflight-518395-{pipeline,jobs}.json`. A subsequent read-only `git ls-remote`
against the same URL/tag succeeded with `fb720c5872758f60e0fde7f53884bf0357b32912`.
This is a transient dependency-fetch failure; relaunch through the ordinary Git
workflow with this documentation update, without changing sources or pins, and
arm a new 30-minute preflight. Retain the branch and use the replacement
pipeline's exact SHA for any later artifact collection and board tests.

Replacement [pipeline 518400](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/518400)
uses exact SHA `cce9a36220313e75e8afb626e1937d65d7600e0b` on
`feat/memcached-rx-wait`. At the October 9 preflight, all 14 gates
(`2882603`–`2882616`) and preparation `2882617` passed; hardware `2882618`
was running and report `2882619` was created. No retry jobs were present.
Full status/provenance snapshots are in
`out/contiguous-transport/preflight-518400-{pipeline,jobs}.json`.
The five-hour results follow-up is armed for **October 10, 00:10:01 CEST**
in thread `01a11cbc-4d8f-76f1-9945-f69de06ed22f`; do not poll during that
interval. Keep the pipeline branch. Hardware completion, timing results and
matched Enzian validation remain pending; no new board result is claimed.

October 10, 00:10 CEST results check: pipeline `518400` still uses
`cce9a36220313e75e8afb626e1937d65d7600e0b`; hardware job `2882618` remains
running, report `2882619` is created, and all 14 gates plus preparation
`2882617` remain successful, with no retry jobs. Snapshots are preserved in
`out/contiguous-transport/results-518400-{pipeline,jobs}.json`.
The required 30-minute results recheck is armed for **00:40:32 CEST** in the
same thread. No duplicate build or board test was started. Matched hardware
artifacts, timing results and Enzian validation remain pending; retain the branch.

October 10, 00:41 CEST recheck: pipeline `518400`, SHA
`cce9a36220313e75e8afb626e1937d65d7600e0b`, still has hardware `2882618`
running and report `2882619` created. Gates `2882603`–`2882616` and preparation
`2882617` remain successful; no retries are listed. Read-only snapshots:
`out/contiguous-transport/recheck-518400-20261009T224055Z-{pipeline,jobs}.json`.
Re-armed the 30-minute results timer for **October 10, 01:11:01 CEST** in the
same thread. Hardware artifacts and matched board tests remain pending; no
new build, board access or implementation change was made. Branch retained.

October 10, 01:11 CEST recheck: pipeline `518400` at exact SHA
`cce9a36220313e75e8afb626e1937d65d7600e0b` remains running: hardware `2882618`
is running, report `2882619` is created, and gates `2882603`–`2882616` plus
preparation `2882617` remain successful, with no retries. Snapshots are in
`out/contiguous-transport/recheck-518400-20261009T231130Z-{pipeline,jobs}.json`.
The next 30-minute results recheck is armed for **October 10, 01:41:35 CEST**
in the same thread. Branch retained; matched hardware artifacts and Enzian
validation remain pending. No duplicate build or board test was started.

### Pipeline 518400 matched RPC validation, October 10

Pipeline **518400**, exact revision `cce9a36220313e75e8afb626e1937d65d7600e0b`,
passed all 14 gates (`2882603`–`2882616`), preparation `2882617`, hardware
`2882618` and reporting `2882619`, without retries. Collected bitstream, LTX and
trace map are in `out/contiguous-transport/hw-2882618/`; matching RPC and
memcached images plus the physical summary are in `ci-518400/` beneath that
same output root. The RPC image SHA-256 is
`9b0a8d5e337483d34693e86b886462c6a656b2ae56080127478b7b1852e5731d`, verified
after staging. `board-provenance-518400.json` records artifact hashes and job IDs.
Reported timing is **WNS −0.780 ns, TNS −7211.323 ns, 27,040 failing setup
endpoints**; hold violations are zero. This is not timing closure; no timing,
address-layout or PREEMPT change was made during the follow-up.

Fresh reservations confirmed zuestoll14 and zuestoll07 belonged to pengxu.
Before reset, the DUT still ran the successful 518140 RPC server, with tracing
active, a wrapped buffer, no sticky sample loss and no DMA error. Earlier
memcached failure captures remain preserved. A read-only setup command initially
had incorrect shell argument quoting, then queried an absent module `version`
sysfs file; neither diagnostic failure reset or changed the board. The successful
state checks and VIO evidence are retained alongside the campaign artifacts.

The repository harness passed **two fresh cold boots and 200/200 checked RPCs**
against the pipeline SHA. Both boots succeeded on their first attempt, with
verified power-off voltages, QLM8–13 CDR lock and CCPI lanes 0–23. Module loading,
revision verification and both 100-call clients passed with no reported faults.
Both CSVs independently passed response-value, echoed-request-ID and status
checks. The programming script armed tracing and the static edge ILA before
Linux/module load. End-to-end trial times were **282.7 s and 285.7 s**, including
reset/programming/boot, not request-latency measurements.

Compact results: `out/contiguous-transport/rpc-board-518400/summary.json`;
manifest: `rpc-harness.json`; checked CSVs: `rpc-results-518400/{01,02}/client.csv`.
The final matched RPC server remains running. All jobs are complete; retain
`feat/memcached-rx-wait` while application work remains unfinished. This closes
the scheduled RPC regression, not native UDP/memcached board acceptance,
Dandelion validation, concurrent-worker coverage or oversubscription measurement.
The memcached borrowed-buffer/event-wait adaptation, HOST_WAKE and host-only
yield remain as previously scoped; no performance or unrestricted maintenance
claim follows from these tests.

### Retire copying transport APIs, October 10

Removed core `core_eci_rx`, `core_eci_rx_traced`, `core_eci_rx_consume` and
`core_eci_tx`, plus public `lauberhorn_udp_receive` and `lauberhorn_udp_sendv`.
The copied UDP receive allocation and obsolete receive capacity fields are gone.
Platform consumers use borrowed acquire/release and TX reserve/commit;
execution-control messages reserve zero bytes. Kernel bypass retains the
necessary skb-to-NIC copy explicitly at the caller and counts failed TX
admission as a dropped packet rather than successful transmission. RX tests now
check direct mutable views, explicit release and outstanding-borrow exclusion;
UDP tests retain zero-length, retry/error, capacity and ownership coverage.

Memcached and its mock are deliberately unchanged at the maintainer's request.
The pinned adapter still calls the removed public APIs, so memcached deployment
and its adapter check are not acceptance gates that pass at this intermediate
branch state. Migrate its wait/receive ownership and segmented TX next; do not
restore compatibility shims or present the mock check as runtime integration.
Keep this work on `feat/memcached-rx-wait` until that integration is complete.
There is no hardware ABI, CL-layout or PREEMPT change in this API retirement.

Validation through `nix develop`: `.#runtimeTests` (including ASan/UBSan core
buffer and codec checks), `.#checks.x86_64-linux.runtime-interface`, `.#deployFs`
and `.#dandelionDeployFs` all built successfully. This covers the AArch64 runtime,
kernel module, RPC consumers and pinned Dandelion build. Logs are
`out/contiguous-transport/retire-copy-{build,dandelion}.log`; the runtime test
output is `/nix/store/vwqg94zp7skh752767shcn7rv28l7ih8-lauberhorn-runtime-tests`.
No new board run is claimed for this software-only retirement. Push with CI
skipped while the intentionally unchanged memcached adapter is incompatible.

October 10 API cleanup: removed the redundant `rx_buf` alias from core state;
RPC decoding/forwarding and kernel bypass read `rx_view.data` directly. The one
view records a contiguous borrow, not fragments or owned staging storage.
Renamed core `rx_discard` to `rx_release` and updated all platform callers/tests.
Public buffer/UDP headers and the runtime/programming-model notes now distinguish
software release from hardware retirement, document handle lifetimes and local
TX outcomes, and remove obsolete copied-buffer language from the active contract.
No public UDP API rename or memcached change is included. `nix develop` validation
passed `.#runtimeTests`, `.#checks.x86_64-linux.runtime-interface` and `.#deployFs`
(including the AArch64 runtime, module and RPC applications). Evidence:
`out/contiguous-transport/rx-api-cleanup.log`. Hardware layout is unchanged;
memcached remains pending on this branch as documented above.


### TX transaction refinement, October 10

A buffer reservation belongs to exactly one 2F2F transaction, completed by send
or cancel. Version 3 removes the capacity-derived cleanup field from byte 56;
normal TX invalidates only the overflow lines covering actual payload length,
with zero overflow invalidations for inline or descriptor-only transactions.
The placement bit still selects the storage originally reserved, so a short
send from overflow remains there. `core_eci_tx_cancel` and `lauberhorn_tx_cancel`
replace the misleading abort names; cancel completes a control no-op, not a
network packet, and need not drain unused same-domain overflow lines.

Voluntary overflow writebacks before the doorbell update the packet slot.
The router retains the inline half of control writebacks, restores it after
control invalidation and masks subsequent overflow writebacks until completion.
Retaining that half also handles control eviction before the doorbell and
reloading it after an overlapping overflow write. Same-domain handlers may read
stale transport bytes; preemption still fully invalidates and scrubs for the next
owner. No worker-address-map or PREEMPT implementation change is included.
This supersedes earlier capacity-based cleanup and abort wording above.

Short sends no longer pay invalidation cost for unused reservation capacity.
That is a protocol change, not a measured latency improvement: new matched
hardware and board measurements are required. Never combine version-3 software
with the tested version-2 pipeline 518400 bitstream. Memcached migration remains
pending; its source and mock are untouched.

Local version-3 validation through `nix develop` passed seven focused native-UDP,
TX isolation and bypass simulations, `runtimeTests`, `deployFs` and the runtime
interface check. Logs: `out/contiguous-transport/tx-length-focused.log` and
`tx-length-runtime.log`. The opt-in `LAUBERHORN_SKIP_MEMCACHED=1` CI scope omits
only the incompatible adapter gate/image and records that omission in the
preparation manifest; all other gates remain required. CI helper tests passed
15 cases (`out/contiguous-transport/ci-scope-tests.log`).

Pipeline [518689](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/518689)
was launched for `45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4` with memcached omitted.
The 30-minute preflight is armed for October 10, 10:08 CEST in the existing thread.
Hardware results and matched board tests remain pending; retain the branch.
The [TX layout model](../../model/evidence/tx-payload-layout.md) passed its bounded
positive case and three expected fault controls. It confirms the inline restore,
late-write mask and preemption cleanup requirements, under the stated write
completion assumptions; it is not a complete RTL refinement or RX lifetime proof.

#### Pipeline 518689 preflight, October 10

At 10:09 CEST, pipeline 518689 remained running at exact source revision
`45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4` on `feat/memcached-rx-wait`.
All thirteen enabled test gates passed: fast-tests-eci 2884488, dcs-rom-thx
2884489, dcs-lauberhorn 2884490, blocks-tests 2884491, trace-tests 2884492,
trace-fifo-tests 2884493, eci-toolkit-rx 2884494, workflow-tools 2884495,
interactive 2884496, runtime-interface 2884497, runtime-tests 2884498,
eci-address-map 2884499 and scheduling-cooperative 2884500. Preparation
2884501 passed; its downloaded `out/ci-scope.json` confirms that exact revision
and `memcached_omitted: true`. Hardware job 2884502 was running and report job
2884503 remained created. No retries were present in the retrieved job list.

The results timer is armed for October 10 at 15:09:22 CEST, five hours after this
check, in thread `01a11cbc-4d8f-76f1-9945-f69de06ed22f`. No intermediate polling
or duplicate build/test was launched. Preserve the pipeline branch until every
job finishes. Hardware results, timing and matched Enzian trials remain pending;
memcached is omitted, not passing. API status and preparation manifest are saved
under `out/contiguous-transport/518689-preflight/`.

At the scheduled results check on October 10 at 15:09 CEST, the same SHA and
job IDs remained current: all thirteen gates and preparation passed, hardware
2884502 was still running, and report 2884503 remained created, with no retries.
Read-only API snapshots are saved under
`out/contiguous-transport/518689-results/{pipeline,jobs}.json`.
The 30-minute `results-recheck` timer is armed for **15:40 CEST**, in the same
thread. No duplicate build or board test was started and no board state changed.
Hardware artifacts, timing results and matched RPC/memcached board acceptance
remain pending; retain the pipeline branch and do not poll before that recheck.

The 15:40 CEST recheck found the same revision and job statuses, with no retries:
hardware 2884502 still running, report 2884503 created, all thirteen gates and
prepare-eci 2884501 successful. API evidence is in
`out/contiguous-transport/518689-results-recheck-20261010-154012/`.
Rearmed the same results-recheck timer for **16:10:20 CEST** (30 minutes).
No duplicate build/test or board change; artifact collection and board acceptance
remain pending, and the pipeline branch is retained.

#### Pipeline 518689 hardware and RPC results, October 10

The 16:10 CEST recheck found all thirteen enabled gates, preparation 2884501,
hardware 2884502 and report 2884503 successful, without retries, at exact SHA
`45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4`. Memcached was explicitly omitted
from this CI pipeline. Matching bitstream/LTX/trace map are saved in
`out/contiguous-transport/hw-2884502/`; CI software, scope and timing summary
are in `ci-518689/` under the same root. The RPC image SHA-256 is
`3fa8b086bc77e611596d779da3d4706bca78997175aa01203a2eec17a0b5cc64`.
Reported timing: **WNS -0.950 ns, TNS -14578.675 ns, 44,830 failing setup
endpoints**, zero hold violations. This is not timing closure. No timing,
address-layout or PREEMPT implementation changed during this follow-up.

Fresh reservations confirmed zuestoll14 and zuestoll07 belonged to pengxu.
The DUT retained the successful 518400 RPC server; tracing was active, wrapped,
with no sticky sample loss or DMA error. Earlier failure captures remain intact.
The repository harness passed **two cold-boot trials and 200/200 checked RPCs**.
Trial one rejected its first boot for a missing BDK CCPI lane report before
module load, then passed its full cold-reset retry. The failed console showed
QLM8–13 lock but no lane list, stopping after USB initialization; that log is
retained. Trial two passed its first boot. Both successful boots verified all
24 CCPI lanes, module/revision checks and response values/request IDs/statuses,
with no reported kernel faults. Tracing and the static edge ILA were armed
before module load. Trial durations were 419.3 s (including retry) and 286.3 s;
these are end-to-end harness times, not request latency.

Evidence: `rpc-board-518689/summary.json`, `rpc-518689-harness.json`,
`rpc-518689-{first,second}.csv`, `rpc-518689-response-check.json` and
`board-provenance-518689.json`, all under `out/contiguous-transport/`.
The subsequent memcached image staging hit the shared scratch quota. Two old
remote deployment copies (518140/518400) were removed only after verifying
their hashes against retained local originals; no logs or captures were removed.
`518689-quota-cleanup.json` records the retained paths and hashes. The updated
memcached image below is staged and verified; its board trials are separate
from this completed RPC regression.

The previously running local fast ECI suite also completed: 55 tests passed,
20 suites completed, zero failures/aborts and one ignored test, in 11m11s.
Evidence: `out/contiguous-transport/tx-length-eci-fast.log`. No hardware, address
layout, PREEMPT or timing implementation was changed during this follow-up.


### Memcached restart checkpoint, October 10

Transport version 3 and API cleanup are implemented and locally validated;
pipeline 518689 hardware/board acceptance remains pending on its scheduled timer.
The application checkout is clean on `feat/lauberhorn-rx-wait`. Inspection confirms
that its worker still loops over `drive_machine`, blocks inside `lh_udp_recv`,
copies the packet into `c->rbuf`, shifts the framing header with `memmove`, and
uses the retired `lauberhorn_udp_receive`/`lauberhorn_udp_sendv` APIs.

Next implement the worker-idle boundary and single pending borrow described above:
run ready event callbacks, connection continuations and IO submissions before
blocking in `lauberhorn_udp_receive_view`; the receive hook only consumes local
readiness and returns EAGAIN otherwise. Bind the parser to the borrowed bytes,
advance past the UDP header, and restore owned connection storage before cleanup.
Keep the borrow until parser references and response work are drained, with exact
release on malformed/no-reply/error paths. For each existing upstream response
segment, reserve TX storage, gather its framing/iovecs directly into that region,
and commit. This removes transport staging, not the necessary copy from stored
items into the outgoing packet. A definite rejection consumes the reservation:
reserve again and rebuild the same segment from retained upstream ownership.

Validate the idle predicate and borrow lifetime with adapter tests, then rerun
upstream command/framing/segmentation comparisons. Update the pinned application
and Nix deployment only after those checks pass; restore the normal memcached CI
gate/image for that build. Preserve the bounded maintenance scope: no real
HOST_WAKE or host-only parking implementation, no hash expansion acceptance, and
no claim that polling can wake a parked worker.

### Borrowed memcached adapter implementation, October 10

Application commit `9453656b629acc33728a18ad98d7320c8d7eed45` on
`feat/lauberhorn-rx-wait` implements the local worker-wait adaptation. Native
workers dispatch ready libevent callbacks and submit IO before checking the
connection's idle state. RX acquire occurs only there. A single pending borrow
activates the existing connection event; `lh_udp_recv` consumes readiness once
and otherwise returns EAGAIN without a transport call. The parser points directly
into mutable RX storage and advances past the UDP header. At the idle/cleanup
boundary, it restores the owned connection buffer and releases the borrow after
response/item work drains. The application does not queue a transport pointer as
if it were an owned packet. Unsupported asynchronous IO/teardown paths fail
rather than entering RX while suspended.

Each upstream UDP response segment is assembled into one TX reservation. Item
and iovec ownership remains upstream; this avoids staging but still copies stored
values into the outgoing packet. Definite rejection rebuilds the same segment in
a fresh reservation. UDP sequence advancement now follows successful submission,
so rejected attempts do not skip sequence numbers. No normal UDP socket fallback,
HOST_WAKE, host-only barrier parking or hardware changes were added.

The pinned `memcached-adapter` Nix check passed all 16 upstream command comparisons,
including three-datagram response assembly; borrow/continuation tests and hash
expansion scope rejection also passed. The socket mock returns NACK, rejects each
first TX attempt, checks outstanding ownership and uses inaccessible pages after
RX release/TX completion. Additional requests exercise fairness yield at `-R 1`,
zero/truncated framing, multi-datagram rejection, incomplete commands and an
incomplete SET followed by valid requests. A first boundary-test failure assumed
multi-key replies formed one response; the corrected test queries the two stored
keys separately. No application change was needed for that test correction.
Evidence: `out/contiguous-transport/memcached-pinned-tests.log` and
`/nix/store/wzzczksf9g1i727say7pymmhkrqp86hw-memcached-adapter-compatibility/summary.json`.
The real AArch64 `memcachedDeployFs` build also passed. These are local software
results, not ECI/scheduler/board acceptance. The normal CI configuration now has a
compatible pinned adapter/image; pipeline 518689 itself still omits memcached.

For the already running hardware pipeline, use
`tools/memcached/matched-image.nix` with immutable platform revision
`45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4` and the application revision above.
It preserves the platform identity and asserts unchanged RTL, generated headers,
runtime and kernel-module derivations. A plain `--override-input` experiment
produced a dirty platform identity; that image is explicitly retained as
`memcached-518689-override-dirty.img` and must not be deployed.
At the existing results follow-up, first complete the scheduled matched RPC
checks, then run memcached compatibility with the matched application image and
at least two harness repeats. Recheck reservations, preserve any frozen failure,
keep tracing armed through module load and capture any failure before reset.
Do not claim oversubscription or complete maintenance from this bounded test.

The matched image build passed, including all four derivation-identity assertions.
Its manifest verifies platform `45cbfe52` and application `9453656` separately.
Image: `out/contiguous-transport/memcached-518689-matched.img`, resolving to
`/nix/store/5brbwz7hrx7jsf0ykasliwcs8q7cyr1j-lauberhorn-memcached.img`, SHA-256
`d5bb0331de0fce16bbded6a62633a3d4f7b3576173bccb29658bb1045064fbc6`.
Manifest: `out/contiguous-transport/memcached-518689-manifest.json`.
The unrun single-worker harness case, smoke matrix, client scripts and provenance
are in `out/contiguous-transport/memcached-518689/`. Stage that image as
`/scratch/pengxu/memcached-518689/deploy.img` and the case's client/matrix files
under the same directory on the gateway. Its program command expects the results
workflow's matched `/tmp/lauberhorn-518689/program-arm.tcl`, bitstream and LTX on
ba2. Verify these inputs and reservations before running `tools/enzian/test.py`
on `cases.json` with a fresh log directory and `--repeats 2`. The image and case
are prepared locally only; no board state was changed during software work.

Reproduce the matched build through `nix develop`:

```sh
nix build --impure --file tools/memcached/matched-image.nix \
  --argstr platformRef 'git+file:///local/home/pengxu/work-local/lauberhorn/platform?rev=45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4&submodules=1' \
  --argstr applicationRevision 9453656b629acc33728a18ad98d7320c8d7eed45 \
  --out-link out/contiguous-transport/memcached-518689-matched.img
```


### Runtime-owned UDP view, October 10

The pointer-returning API in platform `aa1255b7c271769e67a84767a67ee57882140aa3`
returns `const lauberhorn_udp_datagram_t **`: on success the caller receives a
pointer to a worker-owned view, not a copy of that view. Its metadata is const;
the referenced payload remains mutable. The worker retains one public UDP view
instead of the redundant saved UDP descriptor. Core `rx_view` remains private
transport borrow bookkeeping; it does not grant separate application ownership.
NACK/error leaves the output pointer unchanged, including a rejected second
acquisition. Release clears the runtime view and transport borrow; callers must
clear their pointer and must not use either view or payload afterward.

Memcached `2eaa2b84e7a6ed0a7d0489b6caad49c211649864` stores only the borrowed view
pointer plus its PENDING/IN_USE delivery state and saved owned connection buffer.
It clears the pointer after release. The mock has the same runtime-owned-view
contract. Runtime tests check pointer identity, mutable payload access, NACK,
zero-length packets, second-acquisition rejection and release invalidation.
The RPC codec contract now explicitly forbids retaining borrowed input aliases.

The active consumer audit found no further view copies: bypass copies into skbs;
RPC calls/completions decode into owned objects; deferred requests/futures retain
those objects; forwarding copies into a TX reservation before release. Pinned
Dandelion's Rust codec constructs owned strings/vectors and BSON objects. Its
old student/mock runtime tree is not the active platform consumer and is unchanged.

Validation through `nix develop` passed runtimeTests, runtime-interface,
memcached-adapter, memcachedDeployFs and dandelionDeployFs. Evidence:
`out/contiguous-transport/runtime-owned-view-tests.log`; compatibility summary:
`/nix/store/ar26gz0dzxg5smb941dbfp1f3g7aqv0j-memcached-adapter-compatibility/summary.json`.
This is a software API change: rebuild application and runtime together. No
hardware layout, descriptor encoding or PREEMPT change was made.

The pending 518689 memcached test must use the updated image with the new runtime
and application together, superseding the earlier application-only image above.
`matched-image.nix` now accepts an immutable `runtimeRef`: only `sw/rt` and
`sw/include` come from that revision. The transport core, ioctl/build contract,
kernel module and generated collateral remain from the hardware pipeline SHA.
The image manifest records `platform.runtimeOverride` explicitly; it must not be
reported as running the pipeline's original runtime. The scheduled RPC tests
continue to use the original CI software image unchanged.

**Board-discovered packaging correction:** the first matched-image helper omitted
the AArch64 Python/Bash overrides that normal platform packaging already supplies.
The first memcached board attempt passed module/revision checks after two rejected
CCPI bring-up attempts, then failed before launching memcached with Python
`Exec format error`. The harness stopped without a second trial or reset.
Full frozen trace (130,695,872 bytes, no loss/DMA error), map, hash and provenance
are preserved in `out/contiguous-transport/memcached-518689/launch-failure-trace/`;
the failed harness summary is `out/contiguous-transport/memcached-board-518689/summary.json`.
The helper now supplies target Python/Bash while writing wrappers on the build
host. The rebuilt image's interpreters were verified as AArch64 ELF; its manifest
matches the earlier image's platform, application, runtime, module and collateral.
Use `out/contiguous-transport/memcached-518689-target-tools.img`, resolving to
`/nix/store/pyira29gqx1z30bf9cq6bddapbfca6nf-lauberhorn-memcached.img`, SHA-256
`cbe08b46e2327de8d1e2da1303c853137881c3586d66097000bd7df6231d1887`.
Its manifest is `out/contiguous-transport/memcached-518689-target-tools-manifest.json`.
This supersedes the faulty launcher image below; keep that image only as failure
provenance. The fresh retry uses `cases-target-tools.json` and
`out/contiguous-transport/memcached-target-tools-board-518689/`.

The corrected-image trial passed its first cold boot, module/revision checks and
application launch, but its first compatibility request returned
`ConnectionRefusedError`. The harness stopped; its second repeat was not run.
The complete 138,985,280-byte frozen trace, map, checksum and provenance are in
`out/contiguous-transport/memcached-518689/compatibility-failure-trace/`.
Pre/post-freeze loss and DMA-error flags were zero. The local capture hash and
length match the successful peer receiver; the shared scratch copy is truncated
by quota and must not be used as the full capture. The complete peer original is
also retained under `/tmp/memcached-518689-compatibility-failure` on zuestoll07.
The local transfer used a one-request management-network server after scratch
copying failed. Additional removed remote deployment copies (517951 images and
the faulty-launcher image) remain available locally; captures were not deleted.

Decoded evidence (`memcached-518689/decoded-summary.json` and
`compatibility-failure-events.json`) contains one UDP decode followed by bypass
delivery, with no native UDP decode, scheduler dispatch or application RX event.
Memcached remains alive; its renamed `lh-mc-0` worker is parked in `worker_yield`.
The kernel recorded application registration, and no kernel fault was reported.
**A startup readiness race is suspected, not confirmed:** the launcher waits only
100 ms and explicitly reports readiness unverified. The initial request may have
preceded endpoint registration. Investigate that ordering and establish a bounded
readiness gate before retrying compatibility; do not count this trial as passing
or silently retry measured requests. The board remains on the corrected image
with tracing frozen after this failure. No reset followed capture, and no timing,
address-layout or PREEMPT change was made. RPC regression is complete; memcached
compatibility, oversubscription and performance acceptance remain open.

The updated matched image build passed. Its manifest verifies the kernel module
and all generated collateral paths are identical to the baseline matched image.
Use `out/contiguous-transport/memcached-518689-owned-view.img`, resolving to
`/nix/store/9f97s2bc19pria8qjxb51il3kjz7rnc7-lauberhorn-memcached.img`, SHA-256
`f9b1969f9b8b5d18fc537d8e8b667c7e736df54068d30d35005fd1ba3ef8e215`.
Manifest: `out/contiguous-transport/memcached-518689-owned-view-manifest.json`;
provenance: `out/contiguous-transport/memcached-518689/owned-view-provenance.json`.
Stage this replacement image at the existing case's `deploy.img` path. The case
remains unrun; board acceptance awaits the scheduled hardware results follow-up.

Reproduce through `nix develop`:

```sh
nix build --impure --file tools/memcached/matched-image.nix \
  --argstr platformRef 'git+file:///local/home/pengxu/work-local/lauberhorn/platform?rev=45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4&submodules=1' \
  --argstr applicationRevision 2eaa2b84e7a6ed0a7d0489b6caad49c211649864 \
  --argstr runtimeRef 'git+file:///local/home/pengxu/work-local/lauberhorn/platform?rev=aa1255b7c271769e67a84767a67ee57882140aa3&submodules=1' \
  --out-link out/contiguous-transport/memcached-518689-owned-view.img
```

### Five instances on four workers, October 10

The preserved single-instance process passed all sixteen compatibility checks
when tested after initialization, including three-datagram responses and unchanged
hash power. The earlier first-request refusal is consistent with startup racing
endpoint registration. A new, explicit startup gate now precedes compatibility:
bounded stats probes may retry refusal/timeouts, on fresh sockets, with every
attempt recorded. Compatibility and workload requests never retry. No RX wait,
HOST_WAKE, hardware layout or PREEMPT semantics changed.

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
No stored loss marker or parity mismatch was found. The initial boot lacked a
CCPI lane report and was rejected before module load; its full cold-reset retry
passed. The second cohort booted on its first attempt. Harness durations were
449.1 s and 310.7 s, including programming/boot/workload, not request RTT.

The second complete trace independently confirms 389 process changes and 393
assignments with matching committed/delivered activations. Again, every instance
has 302 dispatches and all four physical workers execute requests. All 393
first-RX and 393 first-TX parity checks pass, with no stored loss marker or parity
mismatch; it records 129 domain migrations and 291 A→B→A reuse sequences.
Together the trials establish 793 yield-preceded process changes, 801 assignments
and 3,020 dispatches, alongside 160 compatibility checks and 2,800 additional
checked workload requests. Migration/reuse counts alone do not prove cache
freshness; distinct per-instance payload checks provide finite functional coverage.

The matched components remain pipeline SHA `45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4`,
hardware job 2884502, runtime `aa1255b7c271769e67a84767a67ee57882140aa3`,
application `2eaa2b84e7a6ed0a7d0489b6caad49c211649864`, and the corrected target-tools
image above (SHA-256 `cbe08b46e2327de8d1e2da1303c853137881c3586d66097000bd7df6231d1887`).
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

The first trace receiver exhausted the peer's root-filesystem `/tmp`; partial
output was retained and the unchanged frozen buffer was reread into `/dev/shm`.
Full captures and checksums were transferred directly over the management network
to avoid shared scratch quota. The first complete capture is 1,607,767,296 bytes,
SHA-256 `171eac634604abb4454d2dd2b930c788250492ffb4fac441a04d56a599621c13`.
The second complete capture is 1,371,885,504 bytes, SHA-256
`f29756198e9bb40b123539da67cc871092edaba40489cfa89d6d45c096d6a382`.
Both freezes reported loss=0 before freeze and loss=1 afterward, with DMA error=0
and no wrap; stored-marker checks, not the post-freeze flag alone, determine the
analyzed window's validity. Historical failure captures remain preserved.

Nix validation passed the adapter/upstream comparisons, delayed-start/deadline
readiness tests and five-instance socket cohort, plus synthetic trace rejection
checks for missing yields, loss, incomplete activation and parity mismatches.
The full 1–W/mixed-pool matrix, offered-load sweeps, tracing-off performance
controls, HOST_WAKE and unrestricted maintenance remain follow-on work. Timing
is unchanged from 518689 (WNS -0.950 ns); no timing closure is claimed.

### Four workers per tenant and variable bursts, October 10

The next synthetic cohort declares four request workers in each of five instances
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

The cold boot passed all QLM/CCPI gates, and module verification and server launch
completed. Informational registration printks split the console runner's zero-exit
marker, so the harness reported a setup timeout before running its client. The
134,471,936-byte setup trace was frozen, captured and checksum-verified before
resuming the same processes. `dmesg -n 4` suppressed informational console output;
no reset or server restart followed. The harness failure remains recorded as a
failure, separate from successful resumed workload results.

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
All prior captures remain preserved. The cohort remains alive with trace
collection frozen. Future multi-tenant dataset replay needs timestamped tenant
arrivals, preserved burst/idle structure, an open-loop generator with pacing
error accounting, and repeated tracing-off performance runs; this test does not
select a dataset or establish paper benchmark performance.

#### Kernel-batched bursts: complete scale-up and switching evidence

The tightened variant (`patterns.py --batch`, tools at `a32f3cf9`) uses Linux
`sendmmsg` to enqueue each bounded tenant burst in one syscall. It retains the
16-request window and all 33 phases/15,360 requests. Each request in a batch is
timestamped at the same client call-preparation start, so RTT includes ctypes
preparation and kernel batch transmission; these are not individual wire-send
timestamps. The batched variant and its matching tracing-frozen control both
passed all 15,360 requests. Across the two variants and their controls, 61,440
workload requests passed without errors/timeouts or hash expansion.

This fresh cold boot passed the full harness on its first attempt in 302.2 s,
including module verification, launch and client execution. Informational console
messages were suppressed before server launch to avoid the earlier marker
interleaving. The full 808,888,576-byte trace is checksum-verified, SHA-256
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
not useful-work utilization. Twenty request threads remain alive, four per
instance. The latest cohort and its frozen trace remain on the DUT.

Additional evidence under `out/memcached-patterns/`: `cases-batch.json`,
`board-batch/summary.json`, `run-batch/{client,control}/{summary.json,requests.csv}`,
`run-batch/cpu-summary.json`, `trace-batch/`, `switching-batch.json`, and the
workload-only derivative `switching-batch-workload.json`. Plots (PNG and PDF):
`latencies-batch`, `latencies-batch-cdf`, `latencies-batch-control`,
`latencies-batch-control-cdf`, `queue-latencies-batch`, `assignments-batch`.
Nix upstream/adapter/cohort checks and the strengthened cold/scale-up/assigned
trace-classification tests passed. No hardware timing, address layout, PREEMPT,
server implementation or HOST_WAKE behavior changed in this campaign.

#### Separating hot/cold from backlog

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
No hardware rerun was needed for this reanalysis.

#### Campaign closeout and per-request tracing follow-up

The October 10 campaign completes bounded native memcached request processing
and synthetic oversubscribed switching validation: five instances declare four
workers each on four physical workers, all tenants reach four assignments, and
61,440 workload requests across the recorded patterns and controls pass. This
is finite correctness/scheduling evidence, not the full paper benchmark matrix.

The remaining measurement task is to bridge client, application and hardware
tracing and produce detailed per-request breakdowns comparable to the nullrpc
microbenchmark. Client RTT samples exist for every workload request, but they
are not joined to the hardware hot/cold/backlog classifications. MAC ingress
and egress markers exist in the capture, but validated request-to-response
pairing and MAC-to-MAC distributions for one, two and five active instances
have not been established. Application tracing was disabled in these runs;
`tools/memcached/latency.py` requires application records for its current joins.
Do not present scheduler enqueue-to-dispatch intervals as response latency.

Follow-up acceptance:

- Establish an unambiguous request identity across tenant/socket request IDs,
  RX/TX hardware IDs and worker ownership, including ID wrap, migration,
  segmented responses and multiple outstanding requests. Reject ambiguous
  joins and incomplete/lost trace windows rather than pairing by global order.
- Correlate NIC arrival, scheduler enqueue/assignment, host receive, memcached
  execution, TX admission and final response egress; retain per-request client
  RTT and MAC-to-MAC intervals. Use the established clock-correlation procedure,
  report uncertainty and distinguish overlapping from additive stages.
- Produce per-request timelines and distributions by traffic pattern and actual
  assignment/backlog state, with bounded traced windows and tracing-off controls.
  Add a single registered instance with one outstanding request as the
  low-concurrency baseline before increasing concurrency and offered load.

For clarity, the existing batched RTT medians/p99s over **all** workload requests
(including burst entry) are 591.2/1001.8 us for one active instance,
980.2/2052.6 us for two, and 1771.5/5590.7 us for five. Tracing-frozen controls
are 590.5/1016.7, 971.6/2136.5 and 1931.6/2540.5 us respectively. These include
Python client/batch overhead. The single-active case still has five registered
instances and up to 16 outstanding requests against four workers; it is not
an idle-worker or queue-free baseline. Source: the recorded
`run-batch/{client,control}/requests.csv` files (15,360 requests per run).

Full saturation sweeps, multi-tenant dataset replay, unrestricted maintenance
and HOST_WAKE remain follow-on work. Preserve the captured evidence and pinned
hardware/software provenance when merging this completed campaign.

#### Explicit TX placement selection — October 10 follow-up

TX reservation now takes `inline_payload`, not a byte count. True exposes
64 writable bytes; false exposes the full transport capacity. The actual send
length is checked separately against both that capacity and the protocol MTU.
Short overflow sends stay in overflow. Unknown sizes use false, without a sizing
pass. The bounded serializer still receives its output capacity.

The memcached adapter at `05c6b4831348f103058fefd37c27de64516016b1`
selects inline when the already-available iovec total is at most 64 bytes. The
platform pin and mock transport use the same API. Dandelion is pinned to
`d74fb48a06c719296126d59ac5bb7cf4b423be63`, with the corresponding Rust codec
field initialized to false and C/Rust ABI checks. RPC codecs default to overflow;
nullrpc explicitly enables inline replies, removing the per-reply `xdr_sizeof`
traversal. This does not change the version-3 hardware layout or ownership rules.

The per-request tracing acceptance above remains open. In particular, the
existing application ID contains client IPv4/port and the 16-bit memcached ID,
but no tenant destination or reuse generation. The analyzer currently rejects
reused IDs globally and lacks client RTT joins; it must not be used to claim
validated multi-tenant or wrapped-ID per-request breakdowns.

Validation for this API change passed through `nix develop`: `runtimeTests`
(including ASan/UBSan codec and core-buffer checks), `runtime`, `kmod`,
`recovery-test`, `nested-validation`, `checks.x86_64-linux.runtime-interface`,
`checks.x86_64-linux.memcached-adapter`, `memcached` and `dandelion`. The
source-defined `applications.microbenchmarks` target also cross-built. Coverage
includes both placement choices/parities, zero/short/full-length sends, 65-byte
inline rejection, MTU overflow rejection, stale handles and bounded XDR writes.
The adapter comparison passed against pinned upstream with injected transport.
These are software/build checks; no new Enzian latency result is claimed.

#### RX/TX trace correlation and RTT breakdown — active October 10 follow-up

Active platform branch: `feat/memcached-trace-correlation`. The user authorized
adding trace-only UDP descriptor metadata and measuring the roughly 500–600 µs
client RTT. The earlier 591 µs figure was a Python/batched, 16-outstanding RTT;
nullrpc's roughly 4 µs was a NIC MAC-to-MAC duration. Measure both boundaries on
the same memcached requests before assigning the difference to the server.

The RX UDP control descriptor exposes `trace_ref`: bit 15 is valid, bits 5:0
are the existing RX `HostMsgID`, and other bits are zero. It occupies the aligned
16-bit field at byte 20, padded to a 32-byte Mackerel datatype. RX enqueue
tracing already links that ID to ingress `PacketID`. TX reuses the aligned
16-bit reserved field at byte 14 for `related_rx`. Software echoes the RX
reference on every response segment; zero means unrelated. `EciTxSubmit` emits
`RelatedRxTrace` together with the existing TX `HostMsgID` and core. This is
untrusted diagnostic metadata only: no effect on admission, destination,
ownership, PREEMPT, packet retention or wire bytes. All 64 ID values are usable;
validity is independent of the all-ones ID. Copying the scalar after RX release
is permitted; copying or retaining the borrowed packet pointer is not.

Payload layout remains version 3. Generated headers additionally declare
`ECI_UDP_TRACE_VERSION=1`, required by the updated core runtime. Regenerate
Mackerel/config/trace-map collateral and deploy matching hardware/software;
existing 518689 hardware does not supply this metadata. No address layout or
PREEMPT change is included.

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

The new `tools/memcached/breakdown.py` uses narrow-ID lifetime constraints rather
than pretending IDs are globally unique. It checks complete MAC/Ethernet order,
RX enqueue/queue/dispatch identities, unique live per-process IDs, per-core RX
sequences, related TX references, software thread intervals and native response
segment headers. Different workers may dispatch out of process FIFO order.
Repeated client wire IDs are allowed only with nonoverlapping client lifetimes;
source IP/port are now recorded by `patterns.py`. Missing, duplicated, mismatched,
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

Validation uncovered an existing shared `decoder_sink` trace-port collision:
ONC-RPC and Ethernet metadata can fire simultaneously for different packets.
The exact failing `OncRpcNestedSim` local-roundtrip seed was setup `-1697878308`,
sim `-503111269`. Each decoder now has its own trace source. The exact seed
passes after this correction, and the five native UDP/ONC-RPC/RX isolation cases
pass together. Native UDP testing covers ID wrap, echoed trace fields on two
responses in both placements and unchanged cross-domain rejection. Its extended
wrap sequence required raising the simulation time limit from 500 million to
1.5 billion units; the original progressing timeout is preserved under
`out/udp-trace/initial-wrap-timeout`, and collision evidence under
`out/udp-trace/decoder-port-collision`. Neither was a board run.

Measurement sequence once the new CI hardware succeeds:

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
   evidence of hot/cold assignment. Do not optimize timing as part of this work.

No new board breakdown or explanation of the 500 µs RTT has been measured yet.
Hardware CI and matched captures remain the acceptance boundary.

Local validation for this follow-up passed through `nix develop`: the five
focused NativeUdp/OncRpcNested/RxPayloadIsolation cases, the exact decoder
collision reproducer, and NativeUdp again with final generated-descriptor
padding. Nix regenerated matching RTL/config/Mackerel headers and built
`runtimeTests` (including ASan/UBSan), `memcached`, `kmod`,
`checks.x86_64-linux.runtime-interface` and
`checks.x86_64-linux.memcached-adapter`. The latter includes the upstream
protocol comparison and five new analyzer tests for narrow-ID wrap, reordered
dispatch, segmented replies, repeated client IDs, DCS clock bounds and plot
export. These are local simulation/software results, not Enzian measurements.

Hardware pipeline [518832](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/518832)
was launched for `6551d0461dc8e4fd0ab28d185273a6054054378f` on
`feat/memcached-trace-correlation`. Initial jobs: test gates 2885462–2885475,
prepare 2885476, hardware 2885477, report 2885478. The launch snapshot is
`out/udp-trace/ci-launch/latest.json`; local validation logs are under
`out/udp-trace/validation/`. At launch, gates were running/pending and hardware
was not started. A 30-minute preflight is armed for October 10, 23:10:36 CEST
in thread `01a11cbc-4d8f-76f1-9945-f69de06ed22f`. Retain the branch until all
jobs finish. After successful matched RPC bring-up, continue the bounded
memcached breakdown sequence above; no new hardware result is implied here.

The scheduled preflight for 518832 found all 14 test gates (2885462–2885475)
successful, but prepare 2885476 failed; hardware 2885477 and report 2885478
were skipped. The generated-port check caught five omitted wrapper connections:
separate decoder sources increased the NIC trace count from 53 to 58, while
`lauberhorn_eci.vhd` still declared/bound 53. The correction extends the trace
arrays and connects sources 53–57 at both NicEngine and trace-DMA instances.
`nix develop -c nix build .#eciVivadoInputs --no-link` passed, including generated
port/debug checks and bundle preparation. No timing, address-layout or PREEMPT
change was made. Failure log and snapshot are in `out/udp-trace/ci-preflight/`.
There is no bitstream or Enzian result from 518832; relaunch is required.

Replacement pipeline [518836](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/518836)
tests `8d291b7f00b701e7cd23ab02a75a78080d6e9e42` on the retained branch.
Initial gates are 2885491–2885504, prepare 2885505, hardware 2885506 and report
2885507. The read-only launch snapshot is `out/udp-trace/ci-relaunch/latest.json`.
Its preflight is armed for October 10, 23:43:06 CEST in the same thread. Gates
were running/pending at launch; no hardware result or new latency measurement
is available. Use this pipeline SHA for subsequent matched artifacts.

At the 518836 preflight, all 14 gates (2885491–2885504) and prepare 2885505
passed; hardware 2885506 was running and report 2885507 had not started.
The snapshot is `out/udp-trace/ci-518836-preflight/latest.json`, for pipeline SHA
`8d291b7f00b701e7cd23ab02a75a78080d6e9e42`. Results collection is scheduled
five hours later, October 11 at 04:43:42 CEST, in the same thread; no polling
is scheduled during that interval. Keep the pipeline branch. No bitstream,
Enzian validation or new RTT breakdown is available yet.

At the October 11 five-hour results check, hardware job 2885506 in 518836
was still running; all 14 gates and prepare 2885505 remained successful,
with report 2885507 not started. Snapshot:
`out/udp-trace/ci-518836-results/latest.json`. A 30-minute results-recheck is
armed for 05:14:18 CEST, without restarting the five-hour delay. Provenance
remains `8d291b7f00b701e7cd23ab02a75a78080d6e9e42`; the branch is retained.
No completed hardware artifacts or new board tests/breakdown are available.

The 05:14 CEST results-recheck still found 518836 hardware 2885506 running,
report 2885507 not started, and all 14 gates plus prepare 2885505 successful.
Snapshot history is under `out/udp-trace/ci-518836-rechecks/`; matched SHA remains
`8d291b7f00b701e7cd23ab02a75a78080d6e9e42`. The next 30-minute recheck is
armed for October 11, 05:44:48 CEST. No board action or new measurement was
performed; retain the branch while jobs remain unfinished.

At the 05:44, 06:15, 06:45, 07:16, 07:46 and 08:17 CEST rechecks,
pipeline 518836 remained running at
`8d291b7f00b701e7cd23ab02a75a78080d6e9e42`: hardware 2885506 running,
report 2885507 not started, prepare 2885505 and gates 2885491–2885504
successful. The timestamped snapshot is retained under
`out/udp-trace/ci-518836-rechecks/`. Re-armed results-recheck for October 11,
08:47:56 CEST. No new build or board test was launched; completed hardware
artifacts and the memcached breakdown remain pending. The branch is retained.

#### Matched trace hardware bring-up — October 11

Pipeline 518836 completed successfully: all gates 2885491–2885504, prepare
2885505, hardware 2885506 and report 2885507. Tested SHA is
`8d291b7f00b701e7cd23ab02a75a78080d6e9e42`, not the later documentation-only
branch head. Artifacts, hashes and the compact board results are recorded in
[the provenance manifest](../../data/eci/board-evidence/udp-trace-518836-rpc-20261011.json).
The hardware archive/digest is under `out/udp-trace/ci-518836-artifacts/2885506/`;
bitstream, LTX, trace map and both matching deployment images are under
`out/udp-trace/hw-2885506/`. Routed WNS is −0.724 ns with 42,712 failing setup
endpoints: not timing closure. No timing, address-layout or PREEMPT change was
made during this follow-up.

Reservations for zuestoll14 and zuestoll07 were rechecked as pengxu. Before
reset, the frozen pointer matched the prior successful nullrpc capture and its
2,445,207,936-byte dump passed SHA-256 verification. Current TAD and kernel
fault scan were clean, but continuity with the prior kernel log was not
established; preserve that limitation rather than claiming a continuous clean
interval. Evidence is in `out/udp-trace/preservation.json` and `pre-health.json`.

The repository harness passed two matched cold-boot RPC trials, 100 checked
responses each (arithmetic and echoed request IDs independently rechecked).
Round 1 passed its first boot; round 2 required one full cold-reset retry after
missing CCPI lane initialization, then passed with QLM8–13 locked and all 24
lanes initialized. No module/RPC faults were reported. Tracing was armed during
programming before module load; the post-test stop readback was zero before
freezing. Harness: `out/udp-trace/rpc-harness.json`; compact results:
`out/udp-trace/rpc-board-518836/summary.json`. DUT remains on the successful RPC
cohort with tracing frozen. Post-freeze VIO: writeSlot `0x00722193`, no wrap or
DMA error, sticky sample-loss flag set. This success trace was not dumped or
claimed loss-free; it is not a memcached breakdown window.

The RPC image was staged and its local/remote hash matched; the memcached image
is collected locally but not deployed. Shared `/scratch` was nearly full, so
only the RPC image was staged and no old evidence was deleted. The next work is
the bounded native memcached correlation/RTT campaign above, using this matched
hardware and the collected image after arranging staging space. There is still
no new memcached latency result or measured explanation of the roughly 500 µs
client RTT. All CI jobs have finished; the unmerged feature branch is retained
for that remaining application validation. No further CI timer is needed.
