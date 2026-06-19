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

This includes everything needed for one synchronous RPC from the client's point
of view:

- client-side ONC-RPC/XDR work;
- client kernel, NIC driver, interrupts, and network stack work;
- Ethernet/network transit between client and Lauberhorn;
- Lauberhorn RX parsing, scheduling, ECI delivery, and TX encode path;
- server runtime work on the ThunderX core;
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
- `server_unmarshal_enter_ns`, `server_unmarshal_exit_ns`: ONC-RPC/XDR request
  unmarshal.
- `server_handler_enter_ns`, `server_handler_exit_ns`: application handler.
- `server_marshal_enter_ns`, `server_marshal_exit_ns`: ONC-RPC/XDR response
  marshal.
- `server_tx_enter_ns`, `server_tx_exit_ns`: time around `core_eci_tx()`.
  `server_tx_exit_ns` is the software point after the response doorbell path has
  completed.
- `timestamp_overhead_ns`, `timestamp_call_count`: local timestamp overhead
  estimate and number of runtime probes in the row.
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
- `EciRxDescSent`, `EciRxCtrlUnlocked`, `EciRxDataLciaUlDone`

These provide the RX CMAC-to-decoder path, RPC enqueue, scheduler queueing, and
delivery into the ECI/software boundary.

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
trace is available, hardware segments are split into named Lauberhorn events;
otherwise the plotter falls back to coarser software-derived buckets.

Common traced buckets:

- `lh_rx_cmac_to_cdc`, `lh_rx_eth_decode`, `lh_rx_ip_decode`,
  `lh_rx_udp_decode`, `lh_rx_rpc_decode`: packet ingress and protocol decode.
- `lh_rx_host_enqueue`, `lh_scheduler_enqueue`, `lh_scheduler_queue`,
  `lh_rx_eci_delivery`: host enqueue, scheduler, and ECI delivery.
- `kernel_wakeup`: estimated time from hardware wakeup-related trace points to
  server runtime observation, when enough matching rows exist to estimate the
  CPU/FPGA clock offset.
- `sw_rpc_runtime`: server runtime work around `core_eci_rx()`, unmarshal,
  marshal, and `core_eci_tx()`.
- `client_xdr_runtime`: client-side XDR encode/decode measured in the client
  process.
- `handler`: application handler execution.
- `lh_tx_2f2f_ctrl`, `lh_tx_host_submit`, `lh_tx_dma_read`,
  `lh_tx_reply_encode`, `lh_tx_udp_encode`, `lh_tx_ip_encode`,
  `lh_tx_eth_encode`, `lh_tx_output_queue`, `lh_tx_cdc_to_cmac`: response-side
  Lauberhorn/encoder path, when correlated.
- `outside_lh_client_network`: everything in the client E2E interval not
  assigned to a more specific bucket.

`outside_lh_client_network` is a residual bucket, not a proof that the time is
pure wire latency.  It can include client-side CPU time, client kernel/network
stack time, NIC interrupt handling, response processing, uninstrumented
Lauberhorn spans, or trace-correlation gaps.

## Current Adder-Demo Example

For the current adder-demo run, the plotter reported approximately:

```text
P50 = 87.290 us
P90 = 91.111 us
P99 = 105.091 us
```

The selected P50/P99 rows show that the application handler is not a meaningful
cost: the handler is about 70 ns.  The large measured server-side cost is the
runtime path around ECI receive/transmit and ONC-RPC marshal/unmarshal:

- P50 server runtime is about 33.7 us, dominated by `core_eci_rx()` at about
  30.3 us.
- P99 server runtime is about 40.5 us, dominated by `core_eci_rx()` at about
  37.0 us.

The client-observed residual is even larger in the current run:

- P50 residual/outside bucket is about 51 us.
- P99 residual/outside bucket is about 63 us.

That residual includes client-side CPU/network-stack work and network transit,
and may include un-attributed trace spans.  It should not be blamed on
Lauberhorn without more client-side and network-side instrumentation.

The traced FPGA datapath micro-stages that are currently attributable are small
relative to the total E2E latency: protocol decode and encoder stages are
generally tens of nanoseconds each, while the ECI control path and server runtime
are much larger.

## Known Ambiguities

Several parts of the breakdown are still estimates or residuals:

- The client process only timestamps before and after `clnt_call()` and around
  client XDR.  It does not split client userspace RPC library time, system calls,
  socket wait time, NIC driver work, interrupt handling, softirq/NAPI, or kernel
  UDP/IP processing.
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

- reduce server runtime CPU overhead in the `core_eci_rx()`/`core_eci_tx()` path;
- reduce ONC-RPC/XDR marshal and unmarshal overhead if it becomes significant
  for larger messages;
- reduce scheduler/ECI wakeup latency if future traces show it growing;
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
