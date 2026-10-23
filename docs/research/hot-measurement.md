# Hot-path measurement methodology

The paper compares Lauberhorn, DPDK on zuestoll07's ConnectX-5, and
CS-A64 (Caladan Simple on AArch64) on that ConnectX-5 after measurement
acceptance. Its [port](caladan-enzian.md) has finite native mechanism, shared-echo and CPU-trace checks;
those checks are not paper benchmark acceptance. All use schema echo
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
wire-to-wire totals. Version 4 ends CPU accounting immediately before the TX
notification: LH's ECI control-line read, or DPDK/Caladan's mlx5 MMIO store.
Descriptor preparation, doorbell-record stores and ordering barriers are inside
the CPU interval. Post-notification flush and return work are outside it.
CPU clocks are not synchronized to the switch: subtract durations, never
absolute timestamps across those clocks. The new NIC–wire segment is switch
MAC duration minus the CPU interval, combining both RX and TX/link portions.

The [instrumentation contract](../development/rpc-breakdown.md) defines every
probe, purpose, segment formula and cold activation path. The CPU start is valid
CQ observation for DPDK/Caladan and the ordered valid RX control-line read for LH.
Components are Handler, codec-only RPC (un)marshal, Runtime/dispatch, Network stack,
NIC handling and NIC–wire. Allocation and Caladan dispatch waits belong to
Runtime/dispatch; TX-buffer reservation belongs to NIC handling. Components describe
the same central instrumented wire-latency observations, not separate medians.

RX instrumentation belongs inside the selected receive path. Cover CQ pointer
updates, refill, packet construction and TX reclamation wherever actually
executed. Record exact driver path, compiler settings, clock conversion and
probe effects. Batches are elapsed contexts, not per-packet CPU costs divided
by an arbitrary batch size. The unloaded single-outstanding path must pass
acceptance before collection. Negative version-4 NIC–wire values stop import
with preserved evidence. Archived versions retain their original interpretation;
version 3 included overlapping TX-return work and retains its signed difference
and independent wire marker. No new acquisition emits those historical probes.

Loaded cases use compiled-out probes and client RTT, without switch capture,
FPGA readout or NIC PTP. Use configurable aggregate slotted-Poisson arrivals over 32 generator cores; retain the deadline,
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

The plotted NIC handling category combines RX metadata/queue processing, TX-buffer
reservation and TX submission. Runtime/dispatch contains all remaining CPU gaps,
including dispatch waits and instrumentation. Probe costs also occur inside
named intervals. Payload-dependent pre-doorbell time includes ordering barriers;
the recorded interval cannot isolate barrier cost from descriptor accesses and
probing. See the timestamp contract for exact boundaries and post-submission
cleanup scope.

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
