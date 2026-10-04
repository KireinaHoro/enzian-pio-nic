# RPC Latency Breakdown

This document describes how the microbenchmarks add latency plot is built from client
timestamps, server runtime timestamps, and Lauberhorn hardware trace events.  It
also documents what the breakdown can and cannot prove. Source-reviewed on
2026-09-08; the numeric example below is historical and was not rerun for this
survey. See [paper goals](paper-goals.md) for the broader evaluation plan.

## DPDK benchmark infrastructure and accounting audit (2026-10-04)

The reusable [traffic generator](../../sw/apps/microbenchmarks/dpdk/README.md)
replaces the client UDP/RPC stack with direct polling through a DPDK PMD.
The common engine owns timing, warmup, request IDs, timeouts and CSV output;
benchmark codecs supply payload encoding/validation. Add and multiply use the
existing `bench.x` wire contract. Echo is a transport diagnostic. See the [Enzian validation results](../development/dpdk-benchmark-results.md) for the physical client evidence and remaining comparison.

For each **matched request**, compute the external residual as:

```text
external_residual = client_E2E - (TxCmacExit - RxCmacEntry)
```

Both terms are durations in their own clock domains; no epoch subtraction or
CPU/FPGA offset fit is needed. Report the raw and one-probe-corrected client
E2E definitions separately. This residual includes client processing/NIC,
link/switch, server transceiver/CMAC portions outside the trace boundary, and
boundary-instrumentation uncertainty. It is not a tunable Lauberhorn component.
Do not subtract percentile values to form it: pair each request first, then
summarize the residual's distribution, tails and first/last-quarter drift.
A negative residual fails validation. Stable external cost is a measurement
hypothesis, not something achieved by forcing a constant into the breakdown.

`MacInterface.scala` emits all four CMAC/CDC markers at **lastFire**, i.e. the
end of the frame. `RxCmacEntry` and `TxCmacExit` traverse `PulseCCByToggle` into
the application clock. The trace map corrects source-to-recorder pipeline
stages, not the phase-dependent pulse synchronizer delay or PHY serialization.
Keep packet sizes and clocks fixed for comparisons; do not claim these markers
measure first-bit wire latency or exact transceiver delay.

The October audit found and corrected:

- Historical completion labels were normalized in the decoder but excluded by
  the earlier event-ID filter. Both `EciTxCtrlUnlocked` and
  `EciTxDataLciaUlDone` now reach the canonical decoder.
- Scheduler attribution could select a later request's IPI or a process-run
  marker predating the current queue entry. The queued-to-dispatched interval
  is now partitioned once; wakeup detail is used only for an ordered chain.
- TX completion searches could see a later reuse of the six-bit host ID.
  Completion must be between this commit and submit. Current RTL emits
  `EciTxCtrlInvalidated` for inline replies **or** `EciTxDataLciaDone` for
  overflow replies. The legacy aliases remain supported.
- TX submission correlation now requires a unique candidate bounded by the
  request's delivery and next dispatch, plus the CPU window. It does not select
  the nearest among ambiguous candidates. Every later TX stage is bounded by
  the next dispatch too, so a missing stage cannot borrow the next response.
- TX host-accept, no-DMA/DMA completion and encoder milestones are retained
  relative to submission in each audit row. DMA completion can overlap encoding
  (`sendDesc` precedes `waitDma`); these milestones are not summed as independent
  additive latency. The submit-to-UDP interval remains one partition.
- The no-trace fallback incorrectly counted pre-arrival blocking RX as service.
  It now attributes only the CPU interval from RX return through TX return,
  plus client XDR, leaving the remainder explicitly unclassified.
- Clamping negative residuals hid over-attribution. Every measured row now
  checks E2E conservation; reversed intervals and excessive CPU/FPGA attribution
  fail. Missing client/server joins fail instead of selecting only survivors.
- Gaps **inside** a matched CMAC interval become `lh_unattributed`, not external
  latency. The detailed stacked components plus that gap equal the complete
  CMAC interval. Raw trace loss markers reject sequence-based correlation.

Performance breakdowns require complete CMAC and TX commit paths for every row.
`--allow-partial-trace` is diagnostic only. Request matching remains a
sequence/offset heuristic without an application XID at all hardware boundaries:
use one outstanding inline request and an isolated service, retain warmup in
both CSVs during matching, and preserve a loss-free complete capture with the
exact build's map. This is not a general loaded/multi-service trace correlator.

For TX comparisons report the same-clock CPU `server_tx_ns`, FPGA
`EciTxCommitRead → EciTxSubmit`, and FPGA `EciTxCommitRead → TxCmacExit`
distributions. These avoid the inferred epoch offset used by the detailed
software/hardware split. Half of FLDX_EH→HAKD median RTT remains a modeling
assumption, not a direct measurement of one-way RX delivery. In particular,
do not use that estimate to establish a small R12→RA3 improvement by itself.
An old/new image comparison also includes all other intervening RTL/runtime
changes; isolating the permission change requires a matched controlled pair.

```sh
python3 data/eci/apps/plot_latency.py CLIENT.csv SERVER.csv \
  --worker-cpu-base 44 --trace-dump TRACE.bin --trace-map MATCHING_MAP.json \
  --audit-json NEW-audit.json --no-plots
python3 tools/enzian/benchmark_summary.py CLIENT.csv \
  --audit NEW-audit.json --baseline-audit OLD-audit.json --output NEW-summary.json
```

The audit retains all measured rows, trace components and input CSV/map hashes.
The summary checks trace coverage and reports residual drift. Preserve the
bitstream/software hashes, kernel, DPDK version, source revision, EAL core,
PCI VF, topology, packet sizes, worker count, tracing mode and FPGA period with
the run. Timestamp and buffering instrumentation still changes the workload;
compare like-for-like traced runs and collect uninstrumented E2E separately.
Historical streaming-CSV runs include additional inter-request file I/O.

A fresh software-only analysis of the checked-in **June 20** CSVs excludes
request 0 explicitly: that ID appears twice at the server, and its client call
includes a roughly 15-second startup/retry interval. IDs 1–9999 pass correctness
and conservation checks. Corrected E2E P50 is **73.320 µs**, P90 **76.390 µs**,
and P99 **93.971 µs** (70 ns client probe correction). This does not reproduce
the separate 87.290 µs prose example below. A matching historical trace map has
not been established, so these CSVs alone cannot establish historical TX
hardware latency or prove an R12→RA3 improvement. The historical same-clock
CPU TX-call median is 2.580 µs (P99 2.630 µs); this is also not a new FPGA TX
measurement.

## E2E Definition

For the microbenchmarks add benchmark, end-to-end latency means the elapsed time in the client process
from entering the ONC-RPC client stub call to returning from that call:

```text
client_call_enter_ns -> client_call_exit_ns
```

This includes everything on the critical path of one synchronous RPC from the
client's point of view:

- client-side ONC-RPC/XDR work;
- client kernel, NIC driver, interrupts, and network stack work;
- Ethernet/network transit between client and Lauberhorn;
- Lauberhorn RX parsing, scheduling, ECI delivery, and TX encode path;
- server runtime work on the ThunderX core after the request is delivered;
- user handler execution;
- response network transit and client receive processing.

It is not just FPGA time and not just server time.  It is the wall-clock latency
the caller observes for `clnt_call()`.

## Timestamp Overhead

The client and server both use `clock_gettime(CLOCK_MONOTONIC_RAW)` probes.  At
startup each side estimates timestamp overhead by taking the minimum nonzero
delta between consecutive timestamp calls.

For the top-level E2E interval, only the timestamp calls that bound
`client_call_enter_ns` and `client_call_exit_ns` are charged against E2E.  Nested
probes, such as client XDR timestamps and server runtime timestamps, are already
inside the wall-clock interval and must not be subtracted from E2E again.

For sub-intervals, timestamp overhead is subtracted from the specific timestamp
pairs that bound that sub-interval.  This keeps local runtime slices from
including their own measurement cost without shrinking the top-level client
wall-clock interval by unrelated nested probes.

Hardware trace timestamps are corrected separately.  `LauberhornTraceDma`
records a `pipeline_stages` count for each trace source in
`lauberhorn_trace_dma_map.json`; the Python trace decoder subtracts that
per-source latency before sorting and correlating events.

## Client CSV

The client timestamp CSV records one row per RPC request.  The relevant fields
are:

- `request_id`: software request sequence number used to join client and server
  rows.
- `client_call_enter_ns`: timestamp immediately before `clnt_call()`.
- `client_call_exit_ns`: timestamp immediately after `clnt_call()` returns.
- `client_xdr_call_enter_ns`, `client_xdr_call_exit_ns`: client-side request
  XDR encode interval.
- `client_xdr_resp_enter_ns`, `client_xdr_resp_exit_ns`: client-side response
  XDR decode interval.
- `xdr_call_count`, `xdr_resp_count`: sanity checks for wrapper invocation
  count.
- `timestamp_overhead_ns`: local timestamp overhead estimate.
- correctness fields: input operands, expected result, actual result,
  response request ID, RPC status, and `ok`.

The plotter refuses to plot rows that fail the local correctness checks.

## Server CSV

Restored in the nested runtime on 2026-09-18. Set
`LAUBERHORN_SERVER_TRACE_CSV=/path/server.csv` before starting the service
(`ADDER_SERVER_TRACE_CSV` is a compatibility fallback). With neither variable
set, receive uses the uninstrumented path and no trace clocks or CSV writes run.
Applications annotate their decoded request with
`lauberhorn_trace_request_id(id)` and optionally `lauberhorn_trace_result(ok)`
from `<lauberhorn/trace.h>`; the runtime is independent of application encoding.
The microbenchmarks do this automatically. Wire XID is unavailable under the
opaque request-context ABI, so its legacy CSV column is empty.

A trace row follows request ownership through defer and forward, and is buffered
once at terminal reply/failure submission. `execution_kind` is a bitmask
(deferred=1, forwarded=2); defer enqueue and forward submit/completion have
separate timestamps. Deferred handler active time sums scheduled fiber execution
intervals; wait time is the remaining handler wall time. This is instrumentation
of runtime scheduling, not exact CPU accounting across OS preemption. Errors
record reply and transport status. A process killed before replying has no
terminal row. CSV formatting and output happen only at the explicit end-of-run
export; the record copy after TX still contributes to the following IPG.
Historical streaming captures formatted and flushed each row in that gap.

The existing latency plotter accepts inline rows only, requires application IDs,
and rejects duplicate IDs. It does not interpret nested waiting as inline handler
work. New rows include RX/TX runtime worker IDs and Linux CPU IDs; hardware
correlation requires `--worker-cpu-base` (44 for the current Enzian setup) and
rejects requests whose RX and TX CPUs differ. Do not equate a runtime worker ID
with a hardware datapath ID.

The server runtime CSV records one completed server-side request row.  The
important fields are:

- `request_id`: extracted from the decoded application request.
- `worker_id`: worker thread/core index.
- `server_rx_enter_ns`, `server_rx_exit_ns`: time around `core_eci_rx()`.
  `server_rx_enter_ns` can include time spent polling/blocking before the
  request is returned to software.  `server_rx_exit_ns` is the point where
  `core_eci_rx()` has returned the request descriptor.
- `server_rx_unblock_ns`: timestamp taken inside `core_eci_rx()` immediately
  after the RX control-cacheline read has completed and the read result has
  been ordered.  New traces use this to split RX-side 2F2F hardware/unblock
  time from RX-side software copy-out work.
- `server_unmarshal_enter_ns`, `server_unmarshal_exit_ns`: ONC-RPC/XDR request
  unmarshal.
- `server_handler_enter_ns`, `server_handler_exit_ns`: application handler.
- `server_marshal_enter_ns`, `server_marshal_exit_ns`: ONC-RPC/XDR response
  marshal.
- `server_tx_enter_ns`, `server_tx_exit_ns`: time around `core_eci_tx()`.
  `server_tx_exit_ns` is the software point after the response doorbell path has
  completed.
- `timestamp_overhead_ns`, `timestamp_call_count`: local timestamp overhead
  estimate and number of runtime probes in the row.  New server rows with
  `server_rx_unblock_ns` use 11 timestamp probes; older 18-column captures
  without this field used 10 probes and are still accepted by the plotter as a
  legacy schema.
- correctness fields: receive buffer extent, encoded response byte count and `ok`.
  `request_bytes` includes the full 48-byte inline area even for shorter wire
  payloads; it is not an exact UDP application-payload length.

## Hardware Trace Events

The plotter optionally consumes a full system trace dump and correlates it with
the client/server CSV rows.  The trace map is the source of truth for event IDs,
source IDs, payload layouts, and pipeline-stage correction.

RX-side Lauberhorn events currently used include:

- `RxCmacEntry`
- `RxAfterCdcQueue`
- `EthernetDecoder`
- `IpDecoder`
- `UdpDecoder`
- `OncRpcCallDecoder`
- `RxRpcEnqueueToHost`
- `SchedulerRequestQueued`
- `SchedulerProcessRun`
- `SchedulerRequestDispatched`
- `EciRxDescSent`

These provide the RX CMAC-to-decoder path, RPC enqueue, scheduler queueing, and
delivery into the ECI/software boundary.  `EciRxDescSent` is the delivery marker
used for the current request.  RX cleanup/invalidation events such as
`EciRxDataLciaUlDone` and `EciRxCtrlUnlocked` are triggered by a later opposite
control-CL read and must not be used as the delivery point for the request that
just returned from `core_eci_rx()`.

TX-side events currently used include:

- `EciTxCommitRead`
- `EciTxCtrlInvalidate`
- `EciTxCtrlUnlocked`
- `EciTxSubmit`
- `TxHostReqAccepted`
- `TxNoDmaRead`, `TxAfterDmaRead`
- `OncRpcReplyEncode`
- `UdpEncoder`
- `IpEncoder`
- `EthernetEncoder`
- `TxBeforeCdcQueue`
- `TxCmacExit`

The plotter also samples sys-clock ECI frames from the trace to estimate the
ECI link roundtrip used by RX-side 2F2F attribution:

- `ECI_CMD_MFWD_FLDX_EH`
- `ECI_CMD_MRSP_HAKD`

Those ECI frames are decoded with the shared `data/eci/sys_trace`
`eci_state_output.py` helpers, so opcode naming and address unaliasing use the
same implementation as the standalone ECI trace tools.

Important correlation details:

- RX `HostMsgID` and TX `HostMsgID` are independent 6-bit counters.
- The response TX `HostMsgID` is found from `EciTxSubmit` near the mapped
  `core_eci_tx()` return point, not by reusing the request `HostMsgID`.
- `EciTxAcquire` is not treated as "start preparing this response"; it is part
  of the two-cacheline TX protocol and can refer to a different cacheline phase.
- `UdpEncoder` and `IpEncoder` should be close in time.  The plotter prefers the
  immediate `IpEncoder` event after `UdpEncoder` and warns if their PacketIDs do
  not match.

## Breakdown Buckets

The plotted bars are a partition of the client-observed E2E interval.  When a
trace is available, request-service segments are split into named Lauberhorn
events.  Blocking receive time before the request reaches `RxCmacEntry` is not
stacked as a request-service component; if it overlaps the client-observed
interval, it remains part of the outside/residual bucket unless more precise
client/kernel instrumentation can attribute it.  Without a trace, the plotter
falls back to coarser software-derived buckets.

Common traced buckets:

- `lh_rx_cmac_to_cdc`, `lh_rx_eth_decode`, `lh_rx_ip_decode`,
  `lh_rx_udp_decode`, `lh_rx_rpc_decode`: packet ingress and protocol decode.
- `lh_rx_host_enqueue`, `lh_scheduler_enqueue`, `lh_scheduler_preempt`,
  `kernel_wakeup`, `lh_scheduler_queue`, `lh_rx_eci_delivery`: host enqueue,
  scheduler/preemption, kernel wakeup, scheduler run queue, and ECI delivery.
  These buckets may be absent or zero for a run that does not exercise the
  deschedule/reschedule path, but they are intentionally kept in the breakdown
  for benchmarks that hammer that path.
- `lh_rx_2f2f_hw`: estimated RX-side 2F2F hardware/unblock time.  The plotter
  measures same-address `ECI_CMD_MFWD_FLDX_EH -> ECI_CMD_MRSP_HAKD` latencies in
  the sys-clock ECI trace and uses half of the median roundtrip as the RX HW
  component for the control-read response path.
- `server_2f2f_rx_sw`: RX-side 2F2F software work after the RX control read has
  unblocked.  With new server CSVs this is measured directly as
  `server_rx_unblock_ns -> server_rx_exit_ns`, minus one local timestamp probe
  cost.  It should include post-unblock descriptor extraction, payload copy-out
  from RX control/overflow cachelines into `ctx->rx_buf`, parity/cleanup work,
  and return to the runtime.  Legacy CSVs without `server_rx_unblock_ns` fall
  back to the older correlated hardware-delivery-to-`core_eci_rx()`-return
  estimate.
- `server_rt_overhead`: runtime code between `core_eci_rx()` return and
  `core_eci_tx()` entry that is not accounted to XDR or the handler.
- `server_xdr_unmarshal`, `handler`, `server_xdr_marshal`: server-side request
  unmarshal, application handler, and response marshal.
- `server_2f2f_tx_sw`: TX-side 2F2F software work from `server_tx_enter_ns`
  through the FPGA trace point `EciTxCommitRead`, minus the local timestamp
  probe cost.  This includes the CPU work in `core_eci_tx()`: filling the TX
  control cacheline, copying inline/overflow payload bytes into the TX 2F2F
  cachelines, flipping the TX parity, and doing the doorbell read, up to the
  point where hardware has observed the new TX control phase.
- `client_xdr_runtime`: client-side XDR encode/decode measured in the client
  process.
- `lh_tx_2f2f_hw`: TX-side 2F2F hardware work after the TX doorbell is observed.
  It is measured from `EciTxCommitRead` to the completion of the TX 2F2F
  invalidation path: `EciTxCtrlUnlocked` for inline/no-overflow packets, or
  `EciTxDataLciaUlDone` when overflow cachelines must also be invalidated.
- `lh_tx_host_submit`, `lh_tx_submit_to_udp_encode`, `lh_tx_ip_encode`,
  `lh_tx_eth_encode`, `lh_tx_output_queue`, `lh_tx_cdc_to_cmac`:
  response-side post-2F2F Lauberhorn/encoder path, when correlated.
- `outside_lh_client_network`: everything in the client E2E interval not
  assigned to a more specific bucket.

`outside_lh_client_network` is a residual bucket, not a proof that the time is
pure wire latency.  It can include client-side CPU time, client kernel/network
stack time, NIC interrupt handling, response processing, uninstrumented
Lauberhorn spans, or trace-correlation gaps.

## Timestamp Correlation

The server CSV uses the CPU `CLOCK_MONOTONIC_RAW` clock.  The hardware trace
uses the FPGA trace timestamp domain.  These clocks do not share an epoch, so
the plotter estimates a CPU/FPGA offset after it has matched request rows to
trace records.

The matching step is per worker core:

- Hardware request records are built around `SchedulerRequestDispatched`, with
  the matching `HostMsgID`, `CoreID`, preceding RPC enqueue/queue events, and
  following ECI RX delivery/completion events.
- New server CSV rows are grouped by `rx_cpu_id - worker_cpu_base + 1`
  (legacy rows use `worker_id + 1`) and sorted by
  `server_rx_exit_ns`, because that timestamp is after `core_eci_rx()` returned
  the request to software.
- If the trace has extra records, the plotter chooses the per-core sequence
  offset that makes the `server_rx_exit_ns - trace_delivery_ns` gaps most
  stable over the first rows.

After matching, new captures synchronize at the RX control-read unblock point:

```text
cpu_fpga_offset_ns =
    min(server_rx_unblock_ns - (trace_core_delivery_ns + eci_half_rtt_ns))
    over matched rows
```

`trace_core_delivery_ns` is the `EciRxDescSent` trace point for that request,
falling back to `SchedulerRequestDispatched` only if no descriptor-delivery
marker is present.  `eci_half_rtt_ns` is half of the median same-address
`ECI_CMD_MFWD_FLDX_EH -> ECI_CMD_MRSP_HAKD` latency observed in the sys-clock
ECI trace.  This moves the estimated ECI/PEMD response latency into the
directional `lh_rx_2f2f_hw` bucket instead of letting it inflate later
software-derived buckets.

The lower envelope is used because the timestamp is still taken after the
hardware event that unblocks the read and can include a small amount of
CPU-side timestamp/instruction delay.  The minimum observed gap is treated as
the best available estimate of the cross-clock epoch offset; larger gaps are
interpreted as real per-request software/wakeup delay rather than clock offset.

Legacy server CSVs without `server_rx_unblock_ns` fall back to the previous
lower-envelope synchronization point:

```text
cpu_fpga_offset_ns =
    min(server_rx_exit_ns - trace_core_delivery_ns) over matched rows
```

This is an inferred synchronization, not a hardware clock synchronization
protocol. Its uncertainty must be established for each workload; the historical
example does not establish nanosecond-level accuracy or generalize to loaded runs.

## Historical Adder-Demo Example

The prior document recorded the following microbenchmarks add results. The exact
run, bitstream/configuration, trace map, and plotting revision were not identified
there; checked-in captures exist under `data/eci/apps/adder-demo/2026-06-{19,20}`.
These numbers are retained as an attribution example, not current performance
claims or a certified reproduction of either capture:


```text
P50 = 87.290 us
P90 = 91.111 us
P99 = 105.091 us
```

The selected P50/P99 rows show that the application handler is not a meaningful
cost: the handler is about 70 ns.

The traced time spent inside Lauberhorn, measured from `RxCmacEntry` to
`TxCmacExit`, is much smaller than the client-observed E2E latency:

- P50 `RxCmacEntry -> TxCmacExit` is about 4.4 us.
- P99 `RxCmacEntry -> TxCmacExit` is about 4.5 us.

This is the historical "inside Lauberhorn" interval for the selected requests:
request arrival at the Lauberhorn RX CMAC trace point through response departure
at the Lauberhorn TX CMAC trace point.  It must be interpreted separately from
the software receive timestamps.  The `core_eci_rx()` timestamp pair measures
the duration of a blocking receive call in the server worker.  In this run, the
worker entered `core_eci_rx()` before the request arrived at `RxCmacEntry`, so
most of that timestamp pair is pre-arrival wait time.  It is not time spent
processing this request inside Lauberhorn.

The selected rows show that distinction clearly:

- P50 `core_eci_rx()` wall time is about 30.3 us, but this is mostly blocking
  wait before the request is returned to software.
- P99 `core_eci_rx()` wall time is about 37.0 us, with the same caveat.

Therefore `RxCmacEntry -> TxCmacExit` can be about 4.4-4.5 us even though the
`server_rx_enter_ns -> server_rx_exit_ns` timestamp pair is about 30-37 us.  The
latter mostly measures the worker already waiting for the next request, not
request service time after the packet entered Lauberhorn.

The illustrated traced breakdown therefore does not include the blocking
`core_eci_rx()` interval as a stacked request-service component.  It keeps the
request-service path non-overlapping: RX CMAC/decode/delivery, server
runtime/XDR/handler work, directional 2F2F software and hardware buckets, TX
encoder/CMAC egress, plus client XDR and the remaining outside bucket.

For the historical selected rows, the server/2F2F split is:

```text
bucket             P50       P99
2F2F RX SW         0.035 us  0.045 us
server RT          0.520 us  0.560 us
server unmarshal   0.580 us  0.600 us
handler            0.070 us  0.070 us
server marshal     0.270 us  0.260 us
2F2F TX SW         1.570 us  1.585 us
2F2F TX HW         0.980 us  0.980 us
```

This table was generated from the existing microbenchmarks add server CSV.  If that CSV
predates `server_rx_unblock_ns`, the RX side is still shown using the legacy
correlated delivery-to-return estimate.  New captures split this into
`2F2F RX HW` from the ECI half-RTT estimate and `2F2F RX SW` from the internal
post-unblock timestamp to `core_eci_rx()` return.

`2F2F TX SW` is the CPU-side `core_eci_tx()` work through the FPGA observing the
TX doorbell phase.  In this small adder response it is dominated by fixed
control/cacheline work rather than payload size.  `2F2F TX HW` is the hardware
invalidation/completion path after that doorbell is observed.  On new captures,
`2F2F RX HW` accounts for the estimated ECI/PEMD response latency that unblocks
the RX control read, while `2F2F RX SW` accounts for the CPU copy-out/tail after
that read returns.

The client-observed residual is even larger in the historical run:

- P50 residual/outside bucket is about 82 us.
- P99 residual/outside bucket is about 100 us.

That residual includes client-side CPU/network-stack work, NIC DMA and interrupt
handling, wire time, switch forwarding latency, QSFP/PHY latency outside the
traced CMAC boundary, and may include un-attributed trace spans.  It should not
be blamed on Lauberhorn without more client-side and network-side
instrumentation.

The traced FPGA datapath micro-stages that are currently attributable are small
relative to the total E2E latency: protocol decode and encoder stages are
generally tens of nanoseconds each.  The historical request-service work visible on
the Lauberhorn/server side is only a few microseconds after receive returns,
while the largest overall bucket is still the client/network/residual bucket.

## Known Ambiguities

Several parts of the breakdown are still estimates or residuals:

- The client process only timestamps before and after `clnt_call()` and around
  client XDR.  It does not split client userspace RPC library time, system calls,
  socket wait time, NIC driver work, interrupt handling, softirq/NAPI, or kernel
  UDP/IP processing.
- Server `core_eci_rx()` wall time is a blocking receive duration.  It is useful
  for worker occupancy and wakeup/reschedule benchmarks, but it is not a causal
  request-service component unless the receive call is known to begin after the
  request has arrived.
- The network path is not independently timestamped at the client NIC, switch,
  or Lauberhorn external CMAC boundary with a shared clock.  It is therefore
  mixed into the residual bucket.
- The CPU/FPGA clock offset is inferred from matched trace/CSV rows.  This is
  an attribution assumption whose error has not been independently bounded here,
  rather than a hardware time synchronization mechanism.
- Trace IDs such as `PacketID`, `RpcID`, and `HostMsgID` are narrow wrapping
  counters.  Correlation must use order, timing, core, and cross-event
  constraints, not ID equality alone.
- Old captures before the `IpEncoder` PacketID latch fix can show immediate
  `UdpEncoder -> IpEncoder` PacketID mismatches.  The plotter warns about this
  and records the observed IDs in the breakdown CSV.
- `outside_lh_client_network` is a residual.  Its size is a prompt for further
  instrumentation, not a diagnosis by itself.

## What To Instrument Next

To split the residual further, useful next probes would be:

- client-side timestamps around the RPC library's socket send/receive path;
- kernel tracepoints on the client for syscall entry/exit, UDP send/receive,
  driver TX/RX, interrupt/softirq, and wakeup scheduling;
- NIC hardware timestamps at the client, if available;
- CMAC ingress/egress packet timestamps tied to request/response IDs;
- server-side kernel or ECI driver wakeup instrumentation, if the runtime is
  blocked in a kernel path before `core_eci_rx()` returns;
- additional Lauberhorn trace points at the exact host-message-to-RPC handoff on
  TX, so response `HostMsgID`, `RpcID`, and `PacketID` are joined by one
  unambiguous event.

## Optimization Targets

Likely Lauberhorn-side targets:

- reduce `server_rt_overhead` between receive return, XDR/handler execution,
  and transmit entry;
- reduce `server_2f2f_tx_sw` if `core_eci_tx()` cacheline copying, parity
  update, or doorbell-read overhead dominates;
- reduce `lh_tx_2f2f_hw` if TX-side 2F2F invalidation/completion latency
  dominates;
- reduce ONC-RPC/XDR marshal and unmarshal overhead if it becomes significant
  for larger messages;
- reduce scheduler/ECI wakeup latency if future deschedule/reschedule traces
  show it growing;
- keep the encoder/decoder trace IDs precise enough that hardware micro-stages
  can be attributed without falling into the residual bucket.

Likely not Lauberhorn-owned targets:

- client ONC-RPC library overhead;
- client kernel networking and socket wakeup overhead;
- client NIC interrupt/driver behavior;
- Ethernet/switch/wire latency outside the FPGA;
- ARP or neighbor-table misses in the client/network environment.

Those costs still matter to E2E latency because E2E is client-observed latency,
but they should be optimized or blamed in the client/network stack, not in the
Lauberhorn datapath.

## Source and reproduction entry points

- [Plotter](../../data/eci/apps/plot_latency.py): joins CSVs, estimates ECI RTT and clock offset, emits percentile breakdown CSV/PDF and histogram PDF. Requires Python and matplotlib for plotting.
- [Client instrumentation](../../sw/apps/microbenchmarks/client/bench_client.c), [server probes](../../sw/rt/server_trace.c), [2F2F receive/transmit](../../sw/core/eci/core.h). Server CSV is enabled with `LAUBERHORN_SERVER_TRACE_CSV` (`ADDER_SERVER_TRACE_CSV` remains a compatibility fallback).
- [Tracing reference](../development/tracing.md) and [trace tools](../../data/eci/sys_trace/README.md).

Example (replace inputs with one matched capture; run from repository root):

```sh
python3 data/eci/apps/plot_latency.py client.csv server.csv \
  --worker-cpu-base 44 --trace-dump trace-dram.bin \
  --trace-map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  -o latency
```

Use the map and trace clock period for that capture, not whichever build happens
to be in `out/`. The plotter defaults to `--trace-cycle-ns 5`. Preserve request
counts, correctness filtering, seeds/workload parameters, CPU/core mapping,
software/FPGA revisions, and correlation warnings with exported figures. Half of
median ECI round-trip time assumes a directional split; it is an estimate, not an
independently measured one-way latency. CSV tracing itself perturbs execution.

## Optional cold-activation timestamps (October 4)

The `lauberhorn:activation` kernel event contains **one buffered record per
successful traced activation**, rather than one event at every boundary. It is
disabled by default. Enabled cold activation takes nine `ktime_get_raw_ns()`
samples; the runtime adds two `CLOCK_MONOTONIC_RAW` samples when server CSV
tracing is enabled. Kernel samples are stored in private per-thread state and
emitted just before the yield handler returns. No hardware or public activation
ABI fields change. The legacy `worker_switch` event still only emits phase 2;
its old phase 0/1 comment was not evidence of those probes existing.

| Partition | Exact boundaries / included work |
| --- | --- |
| `irq_to_dispatch` | Idle-kick handler timestamp to dispatcher returning from its wait; excludes exception entry before the handler |
| `dispatch_to_mmio` | Dispatcher wait return to just before `PreemptInfo`; includes reader mutex and notification read |
| `preempt_info_mmio` | Blocking `PreemptInfo` read start to completion |
| `claim_wait` | Snapshot completion to thread claim; includes validation, claim lock and any wait for a thread |
| `affinity_setup` | Around `set_cpus_allowed_ptr` |
| `activation_publish` | Affinity completion to activation-ready publication timestamp |
| `wake_to_wait_return` | Publication to target thread returning from its wait; includes wakeup, scheduling/context switch and wait-exit work |
| `yield_copyout` | Target wait return through activation copy to userspace |
| `kernel_to_userspace` | Pre-return kernel stamp to first userspace stamp; includes the single trace-record emission, ioctl exit path and clock-read overhead |
| `runtime_activation_setup` | First userspace stamp to alias/parity setup complete |
| `runtime_before_rx` | Setup complete to the traced RX entry; includes intervening local runtime work and any earlier empty polls |

Ordinary cooperative handoffs omit the first two components (no idle kick or
dispatcher). The low-overhead default deliberately does **not** split runqueue
wait from context-switch/restore instructions with broad scheduler/function
tracing. Component differences use a common raw monotonic epoch; the tracefs
record's own clock is not used. No assumption that tracing is free is made.

The runtime CSV retains TID, physical worker index, per-thread activation index,
and both activation timestamps with every request, including across NACKs.
This metadata is copied with each request's trace ownership. TIDs, hardware
worker indices and domain codes are distinct. The analyzer matches a cold row
to exactly one kernel record, rejects ambiguity, and never charges one
activation to multiple cold requests. Hot rows do not recharge old activation.

After loading the instrumented module, wrap the finite traffic phase **on the
DUT**, starting capture before activating the test service:

```sh
sudo python3 tools/enzian/activation_trace.py --output NEW_CAPTURE_DIR -- \
  bash RUN_ONE_PHASE.sh
python3 tools/enzian/activation_breakdown.py \
  --capture NEW_CAPTURE_DIR --server SERVER.csv --audit TRACE_AUDIT.json \
  --condition cold --expected-per-worker 1000 --output COLD_KERNEL.json
```

`RUN_ONE_PHASE.sh` is the campaign's service/client orchestration, not a supplied
script. The collector uses its own tracefs instance, enables only this event,
preserves per-CPU loss counters, and disables/removes its instance on exit.
Allocate/resize and enable the kernel buffers before starting FIFO workers.
Resizing a tracefs instance with an active FIFO worker can wait indefinitely
for per-CPU ring-buffer work: the baseline pilot observed
`ring_buffer_resize` blocked until the measured process was paused. For an
already running service, pause it only during capture setup, resume before
traffic and exclude that preparation from measurements. Preserve the failed
setup and do not relabel it as an RPC timeout.
The normal FPGA trace must independently stay armed through module load. Run
`plot_latency.py` first with matching full hardware traces and CSVs (including
warmup during matching); pass its audit to the activation analyzer. The latter
requires the configured sample count (`--expected-total 1000` for the single
measured thread, or the default per-worker mode for four-worker coverage) and
reports P50/P90/P95/P99/P100 for roundtrip, existing E2E components, and the
separate kernel activation partition. P100 is the observed maximum.

**Do not add the activation partition to the E2E stack.** It refines time already
included in the scheduler/delivery/runtime intervals. Its uncorrected durations
also retain instrumentation cost. The existing E2E stack uses its documented
probe corrections; raw roundtrip is reported separately. Hot/cold classification
compares userspace readiness with FPGA arrival using the existing inferred clock
offset; `--margin-ns` rejects nearby boundaries (default 1000 ns). This margin is
an ambiguity guard, not a proven bound on clock-fit error. Preserve the hardware
activation trace to verify the intended parked/active condition independently.

Before/after each phase the collector reads
`/sys/module/lauberhorn/parameters/activation_clock_cost_ns`: 256 adjacent kernel
clock-read pairs summarized as minimum/mean/maximum. This is an **off-path**
clock-cost calibration, not the total instrumentation cost. Do not run it during
RPC measurement. Compare matched DPDK roundtrip runs with kernel tracing off/on
while keeping runtime tracing constant, and separately compare runtime CSV off/on.
Retain raw differences and tails; do not subtract a guessed constant from every
kernel component. Only the board experiment can establish actual Enzian overhead.

Local validation: native runtime activation and RPC/CSV ownership tests,
six analyzer/CLI/loss-accounting tests, eight existing latency-analysis tests,
and AArch64 module/runtime builds passed. The module vermagic is
`6.8.0-64-generic`. All 21 generated ABI input files were fetched from pipeline
516232 prepare job 2866838 and match the local generation byte-for-byte.
Artifacts and provenance are under `out/cold-activation/`; no FPGA rebuild is
needed for this software-only overlay. Tracing on/off overhead and the complete hot/cold dataset remain board checks.
A real activation capture smoke test is recorded below.

### Instrumentation smoke on baseline 516112

The software overlay at `209a6cb4` passed 100/100 add RPCs on zuestoll14,
with one complete activation record matching all runtime rows, no kernel trace
loss, and clean kernel/TAD checks. All 21 generated ABI inputs match both
516112 and 516232. The boot harness recovered one incomplete CCPI bring-up
with a full cold-reset retry before loading the module. See the
[provenance and results](../../data/eci/board-evidence/cold-activation-instrumentation-20261004.json).

Adjacent kernel timestamp reads measured minimum 30–40 ns and mean 38–40 ns
before/after traffic (256 pairs each). This measures clock reads, not the cost
of emitting the event. The 100-request, one-worker socket-client smoke is
functional validation, not the requested DPDK latency dataset or an overhead
comparison. Matched tracing-off/on measurements remain required.

For the one-thread measured domain plus four-thread flush domain campaign,
pass `--trace-process-id` to `plot_latency.py` with the measured hardware
ProcessID (not a Linux PID). This excludes interleaved flush dispatches from
request matching while retaining their next-dispatch boundaries for TX event
attribution. Require the queued event's ProcessID; missing identity is an error.
Drain and validate every flush reply before starting a measured RPC, and check
activation indices to establish eviction. A successful flush alone does not
prove the measured thread became inactive.

For the single measured thread plus four flushing threads campaign, use
`activation_breakdown.py --expected-total 1000`: the measured thread can migrate
between physical workers. Keep the actual worker attribution in every row and
report its distribution. The default remains 1,000 samples on each of four
workers for campaigns that explicitly require that coverage. Successful flush
replies alone do not prove eviction: require a fresh activation for every cold
request and validate activation readiness against request arrival.

The scheduler trace `ProcessID` filter uses Linux TGID with the current driver
(`sw/kmod/chrdev.c:update_proc_hw`), not the separately allocated ECI domain
code. Record both identities; a filter with no matching dispatches is an error.

### Metadata reordering in the October 4 DPDK campaign

`OncRpcCallDecoder` is a metadata-consumer handshake, not a guaranteed earlier
event than the sibling `RxRpcEnqueueToHost` producer. In both 1,000-request
datasets one matching decoder event arrived 10 ns after host enqueue. A
last-before lookup incorrectly selected an older reuse of the small RPC ID,
creating a false multi-millisecond CMAC interval. The analyzer now requires a
unique matching decoder event within ±1,000 ns of enqueue. Untagged CMAC/CDC
notifications likewise require a unique nearby marker; ambiguity fails closed.
This is appropriate only for isolated foreground requests, including foreground
requests after fully drained flush traffic. It is not a general concurrent
packet-identity solution.

For these captures, use `--combine-rpc-ingress` to report UDP-decode completion
through host enqueue as `lh_rx_rpc_decode_and_host_enqueue`. The individual
RPC metadata/host-enqueue difference remains a signed diagnostic; it is not
clamped or counted as another additive duration. All other ordered RX stages
remain separate. Producer overlap prevents an exact independent split of those
two stages with the existing events. The false CDC reversal seen during the
failed correlation disappeared after fixing RPC identity matching.

DPDK CSVs contribute packet preparation and reply validation as separate
additive client intervals. TX-burst submission and receive polling are retained
as nested diagnostics: hardware can run before submission returns, and polling
includes hardware/network time, so neither is added again. The residual still
combines client NIC, remaining polling/submission, switch/link and boundary
uncertainty; the present instrumentation cannot separate those physical pieces.
The five recorded DPDK timestamps exclude unrecorded timeout-poll clock reads.

### DPDK baseline, October 4 (pipeline 516112)

The [recorded results and provenance](../../data/eci/board-evidence/dpdk-hot-cold-baseline-20261004.json)
and [complete percentile table](../../data/eci/board-evidence/dpdk-hot-cold-baseline-20261004.csv)
cover 1,000 hot and 1,000 cold requests to one measured thread. A separate
four-thread domain evicts it before each cold request using four drained rounds
of 16 requests, with 100 µs work per flush handler. All replies, full trace
correlations and condition checks passed; each cold request had a distinct
activation. Cold samples used cooperative activation, not idle-core IRQ wakeup.
The additive FPGA interval has zero unattributed time; the separate kernel
partition is nested and must not be added to it.

Raw RTT in µs:

| Condition | P50 | P90 | P95 | P99 | P100 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Hot | 34.550 | 35.350 | 47.610 | 48.100 | 57.980 |
| Cold, kernel tracing on | 52.145 | 54.821 | 55.341 | 58.202 | 137.100 |
| Cold, kernel tracing off | 48.260 | 50.832 | 51.451 | 53.271 | 118.930 |

The off/on comparison is one sequential pair with runtime CSV logging unchanged,
not a per-request correction constant. Its median difference is 3.885 µs. The
30–40 ns adjacent-clock calibration therefore does not establish total tracing
cost. Runtime CSV off/on remains unmeasured; row logging after each reply can
affect readiness for the next request. Hot median scheduler queue time was
20.470 µs and cold median was 36.650 µs. The maximum cold sample included
84.730 µs between activation-ready and the RX-enter probe; its cause has not
been established.

The runtime and client images were cross-built with the pinned Nix toolchain.
The runtime-only client image is 88,453,120 bytes and passed ten replay checks
on both the build host and the AArch64 client. Baseline timing remains open.
The GSYNC early-NACK comparison on pipeline 516232 and the later simultaneous
four-worker load experiment remain separate work.


## Buffered export and kernel IPG attribution

The runtime CSV is exported only at campaign end; see the
[runtime export contract](../software/runtime.md#optional-per-request-latency-tracing).
Do not tail a CSV during traffic. The DPDK client also retains its records until
measurement ends. Freeze kernel/FPGA recording before requesting runtime export,
so export syscalls are outside the measurement window.

For kernel coverage, `tools/enzian/activation_trace.py --ipg-cpus 44,45,46,47`
enables syscall entry/exit, IRQ, softirq and context-switch tracepoints in a
private instance; paired IPI events are enabled when available. The collector
requires `mono_raw`, saves event formats and coverage, and exports trace text
and per-CPU loss counters **after** the command finishes and recording stops.
Allocate buffers before starting FIFO workers (or while they are paused), resume
before measurement, and stop traffic before the command returns. There is no
streaming trace reader. Any overrun/drop invalidates the capture. The existing
activation event retains its embedded raw-nanosecond timestamps; ordinary trace
text timestamps have microsecond quantization.

`python3 tools/enzian/ipg_breakdown.py --capture CAPTURE --server SERVER.csv
--output IPG.json` partitions previous TX-exit to next RX-entry for serial inline
RPCs. Off-CPU time takes precedence over IRQ, IPI, softirq, then syscall time,
so a syscall interrupted or descheduled is not charged twice. Other tasks' IRQs
are not charged to the measured TID. Unpaired boundary events receive no causal
attribution; missing coverage and untraced faults/exceptions remain limitations.
The tool rejects runtime loss, missing required events, CPUs outside coverage,
and worker migration between adjacent requests. The first request has no prior
TX boundary. The resulting intervals are diagnostics, **not additional E2E
components**: only their overlap after a request's arrival contributes to RTT.
Without kernel evidence, label the gap **unattributed IPG**, not Linux scheduler
time. FPGA enqueue-to-dispatch time is a distinct hardware observation.

Validation for this change: concurrent runtime export/overflow/duplicate-write
checks, existing native runtime tests and an AArch64 cross-build with the matched
516112/516232 ABI headers. Kernel event coverage was checked on zuestoll14,
Linux 6.8.0-64-generic: all requested events, including both IPIs, and mono_raw
are available. A CPU-0 collector smoke test passed with no lost events; its trace
parsed into 617 syscall, 100 IRQ, 28 softirq and five off-CPU intervals, with
five incomplete boundary intervals left unattributed. This validates capture,
not worker latency. Selected-CPU buffer allocation avoids resizing unrelated
FIFO-worker buffers; pause workers when resizing their own buffers.
An on/off comparison is still required to quantify the broader
kernel tracing cost; cheap timestamp reads do not make event recording free.
