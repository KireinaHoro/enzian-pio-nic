# RPC Latency Breakdown

This document describes how the adder-demo latency plot is built from client
timestamps, server runtime timestamps, and Lauberhorn hardware trace events.  It
also documents what the current breakdown can and cannot prove.

## E2E Definition

For adder-demo, end-to-end latency means the elapsed time in the client process
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
- correctness fields: request/response byte counts and `ok`.

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
- Server CSV rows are grouped by `worker_id + 1` and sorted by
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

This is accurate enough for microsecond-scale bucket attribution in the current
adder-demo run, but it is still an inferred synchronization, not a hardware
clock synchronization protocol.

## Current Adder-Demo Example

For the current adder-demo run, the plotter reported approximately:

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

This is the best current "inside Lauberhorn" number for the selected requests:
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

The current traced breakdown therefore does not include the blocking
`core_eci_rx()` interval as a stacked request-service component.  It keeps the
request-service path non-overlapping: RX CMAC/decode/delivery, server
runtime/XDR/handler work, directional 2F2F software and hardware buckets, TX
encoder/CMAC egress, plus client XDR and the remaining outside bucket.

For the current selected rows, the server/2F2F split is:

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

This table was generated from the existing adder-demo server CSV.  If that CSV
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

The client-observed residual is even larger in the current run:

- P50 residual/outside bucket is about 82 us.
- P99 residual/outside bucket is about 100 us.

That residual includes client-side CPU/network-stack work, NIC DMA and interrupt
handling, wire time, switch forwarding latency, QSFP/PHY latency outside the
traced CMAC boundary, and may include un-attributed trace spans.  It should not
be blamed on Lauberhorn without more client-side and network-side
instrumentation.

The traced FPGA datapath micro-stages that are currently attributable are small
relative to the total E2E latency: protocol decode and encoder stages are
generally tens of nanoseconds each.  The current request-service work visible on
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
  good enough for relative segmentation but is not a hardware time
  synchronization mechanism.
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
