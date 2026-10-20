# Hot-path measurement methodology

The paper compares Lauberhorn, DPDK on zuestoll07's ConnectX-5, and
the Caladan-derived AArch64 baseline on that ConnectX-5 after measurement
acceptance. Its [port](caladan-enzian.md) has partial implementation and native
mechanism checks; those checks are not benchmark acceptance. All use schema echo
payloads of 8, 128 and 1432 bytes. NICVF data is not a ConnectX-5 baseline.

## Measurement boundaries

Unloaded CDFs use compiled-out software instrumentation, one resident worker
and one outstanding request. Tofino measures request departure at the
server-facing egress MAC and reply arrival at the server-facing ingress MAC.
Both timestamps use the same switch clock. The resulting wire-to-wire interval
includes both switch–server link delays; retain these rather than silently
subtracting a cable estimate. The accepted convention uses the switch-provided
MAC interval at nominal 1 ns per tick, without external offset correction.
The Tofino project's global-clock rate checks establish these units; retain
`absolute_wire_calibrated: false` as metadata, not a latency veto. This does not
claim analyzer-calibrated physical wire boundaries.
Do not substitute an egress-pipeline timestamp or collector observation time.

Unloaded breakdowns use separate instrumented acquisitions and their own
wire-to-wire totals. CPU clocks are not synchronized to the switch. Subtract
CPU **durations**, never timestamps from different clocks. Report one combined
CPU–wire residual, not separate RX/TX transport intervals. No mlx5 hardware
RX/TX timestamping or completion timestamp extension is required.

The contiguous CPU interval starts when a valid RX CQE is observed and ends
after the reply SQ publication and required doorbell. Partition it into:

| Component | CPU-NIC implementations | Lauberhorn |
| --- | --- | --- |
| RX queue processing | Valid CQE observation through packet delivery, including CQ advancement and receive-queue bookkeeping on this path | No software CQ processing |
| RX network processing | Ethernet/IP/UDP parsing and validation | Hardware work remains in the residual |
| RPC receive | Unmarshaling and dispatch | Unmarshaling and dispatch |
| Handler | Echo handler execution | Echo handler execution |
| RPC transmit | Reply marshaling | Reply marshaling |
| TX network processing | Ethernet/IP/UDP construction and checksum preparation | Hardware work remains in the residual |
| TX submission | Packet ready through SQ construction, publication, ordering, doorbell and PMD return bookkeeping | TX entry through doorbell return, including descriptor stores and barriers |

RX instrumentation belongs inside the selected mlx5/Caladan receive path;
wrapping rx_burst includes empty polling and misses the required CQE boundary.
TX instrumentation must end after submission, not before tx_burst or at later
TX completion. Record the exact selected PMD fast path, compiler configuration,
clock conversion and probe overhead. Cover CQ pointer updates, replenishment,
mbuf construction and TX reclamation wherever actually executed. Attribute
batched/deferred work explicitly; do not omit it or claim a per-packet duration
by dividing a batch without identifying that convention. The unloaded queue
path must be exercised by acceptance tests before collection. See the
[DPDK baseline instrumentation](dpdk-baseline.md#software-probes) and
[board readiness](../development/validation.md#dpdk-baseline-readiness).

For Lauberhorn use CPU receive return through transport return as the outer
interval; retain RPC/handler boundaries and the exact doorbell read/return interval.
Post-doorbell transport cleanup is other CPU work.
Any unclassified CPU gap must be explicit, not silently assigned to the wire.
For each instrumented request, residual = wire duration − CPU duration.
Reject unexplained negative residuals; do not clamp. Report clock-rate and
probe uncertainty. These elapsed components are not CPU utilization estimates.

Loaded cases use compiled-out probes and client RTT, without switch capture,
FPGA readout or NIC PTP. Keep the existing 32-core paced batches, deadline,
loss/rejection accounting, aggregate histograms and complete overload grid.

For historical FPGA-recorder comparisons, use the
[trace timestamp contract](../hardware/tracing.md#timestamp-capture-point-and-legacy-queue-bias)
and [source/acceptance boundaries](../hardware/tracing.md#eci-message-trace-points).
The active Tofino wire measurements have their own boundaries above.

## Capture and collection acceptance

Both rehearsal and full collection require the same validated Tofino backend.
It must identify pipeline/SDE/build hashes, physical ports, MAC timestamp
semantics, clock domain and tick size. Capture exact RPC flow/XID/direction,
exclude priming/setup traffic explicitly and reject duplicate or missing pairs,
FIFO/export loss and ambiguous wraparound. Preserve raw exports and hashes.
Single-outstanding traffic alone does not guarantee a control-plane TX timestamp
FIFO cannot overflow: bound export latency or handshake capture readiness
between calls, outside the measured request interval, and record that pacing.

Use a locked session with bounded snapshot, arm, drain, export and restore
operations. Snapshot before mutation and restore after failure as well as
success. Fail closed if noninteractive access, MAC departure export or restoration
is unavailable. Never install a replacement forwarding pipeline blindly on a
shared leaf. The adapter uses the sibling project's maintained `tools/capture-session.py`
automation and accepted nominal-ns pair output. Bounded unloaded integration
trials use the ordinary hardware adapter. `tools/paper/campaign/wire.py` keeps
long rehearsal and full collection gated until real-workload integration is
validated; synthetic captures and local fixtures cannot satisfy that gate.

A fresh rehearsal must verify all three payloads and both instrumentation modes,
exact pairing, boundary semantics, CPU queue coverage, wrap/loss failure paths,
cleanup and plots. Old MAC/RTT acquisitions cannot satisfy wire-to-wire gates.
Full collection remains gated on that rehearsal. Caladan is not marked complete
until its port and instrumentation pass the same checks.

The plotted CPU–NIC category combines RX queue processing and TX submission.
DPDK TX submission ends at `tx_return`, including post-doorbell mbuf tracking
and counters. NIC–wire is the separate wire-minus-CPU residual. Other CPU
contains uncovered dispatch/transport work and instrumentation; it is not a
measurement of probe overhead. Probe costs also occur inside named intervals.

LH CPU–NIC starts at `server_tx_enter_ns` and ends at
`server_tx_doorbell_return_ns`. The dispatch/outcome/reply-setup gaps and
post-return cleanup stay in RPC runtime, as does DPDK remaining Other CPU.
Payload-dependent pre-doorbell time includes ordering barriers; the recorded
interval cannot isolate barrier cost from descriptor accesses and probing.

## Accounting and instrumentation controls

Join requests before subtracting durations. A residual distribution is formed
from per-request differences, never by subtracting marginal percentiles.
Reject missing/duplicate joins, reversed intervals, unexplained negative
residuals and stored trace-loss markers. Preserve warmups during sequence/clock
alignment, then exclude them explicitly from performance statistics. Diagnostic
partial captures are not performance evidence.

Report raw and probe-corrected intervals separately. An adjacent-clock-read
minimum estimates clock-call cost, not total instrumentation overhead. Probes,
row initialization/copies, disabled instrumentation checks and buffer export can
change execution and readiness. Do not subtract nested probes again from an
outer wall-clock interval. Compare matched instrumentation-off/on cohorts;
never splice instrumented components into an uninstrumented total.

Blocking receive time before request arrival is not request service time.
Client TX submission and receive polling can overlap server execution, so retain
them as nested diagnostics rather than adding them again. An unattributed gap
is not evidence of kernel scheduling, network delay or handler work by itself.
Component medians do not sum to a median total. Waterfalls use an actual request
selected by a stated rule, such as nearest median RTT. Preserve tails and full
observed ranges; a sample maximum is not a bound on future latency.

## Runtime and workload boundaries

[Runtime tracing](../development/runtime-tracing.md) defines probe builds,
buffered export, request ownership and CPU identities. Borrowed RX returns a
view: payload reads occur during decode/application access, unlike older copied
RX captures. Record which boundary the deployed runtime implements.

Deferred handler active time sums scheduled fiber intervals; handler wall time
also contains waits. Neither establishes CPU utilization across OS preemption.
Forwarded and deferred spans overlap. The legacy inline latency analyzer accepts
only inline rows with unique application IDs and equal RX/TX CPUs; it is not a
general nested or multi-service correlator.

For cold activation, distinguish a requested worker pool from observed active
workers. Fully drain eviction traffic, require a fresh activation for every cold
request, and retain actual worker/CPU distribution. Readiness-versus-arrival
classification using an inferred clock fit needs an explicit ambiguity margin;
that margin is not a proven clock-error bound. Kernel activation and inter-packet
gap partitions refine already measured intervals and must not be added to RTT.
See [activation and IPG capture](../development/runtime-tracing.md#activation-and-inter-packet-gaps).

## Historical measurements and comparability

Older socket-client `clnt_call()` measurements include client RPC/XDR, kernel,
NIC and network work. DPDK observations use their recorded preparation, transmit,
receive-observation and validation boundaries; use the campaign's exact endpoint.
Historical FPGA `RxCmacEntry` → `TxCmacExit` intervals differ from current Tofino
MAC intervals. Their external residual is client RTT minus the matched FPGA
interval, combining client/NIC/network and unobserved boundary costs. It is not a
constant to subtract from new measurements.

[Hardware tracing](../hardware/tracing.md) defines FPGA markers, bounded joins,
legacy recorder bias and conditional CPU/FPGA clock alignment. Half of a measured
ECI forward/ack RTT is a symmetry assumption, not a calibrated one-way delay.
Use same-clock comparisons where possible. A whole-revision comparison does not
isolate a single source change; the [hot-path analysis](hot-path-performance.md)
keeps the source and measured regression evidence together.

Superseded campaigns remain reproducible from their tracked data and Git
revisions. Their old boundaries must not be relabelled as acceptance of the
current echo contract. [Paper chart production](../development/paper-charts.md)
and the [chart inventory](../../tools/paper/charts.json) distinguish included
figures from planned replacements.
