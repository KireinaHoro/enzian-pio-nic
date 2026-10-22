# Hot unloaded RPC timestamp accounting

The hot unloaded breakdown uses **CPU accounting version 3** for newly built
LH, DPDK and CS-A64 responders. It separates the shared codec calls from
allocation, transport reservation and runtime dispatch. This contract covers
successful synchronous inline echo replies. All CPU timestamps are
`CLOCK_MONOTONIC_RAW` nanoseconds; Tofino supplies a separate same-switch-clock
wire duration. The [hot measurement methodology](../research/hot-measurement.md)
defines request pairing, warmups and the instrumented/off comparison.

## Shared RPC boundaries

CSV names below have the prefix `server_` and suffix `_ns`, for example
`server_decode_enter_ns`. The shared collector exports them for LH and Caladan.
DPDK copies the accounting and handler timestamps into its primary sample CSV,
which also contains its PMD/network timestamps; its runtime sidecar retains
legacy shared trace fields. The new fields are declared by
`SERVER_TRACE_ACCOUNTING_TIMES` in [server_trace.h](../../sw/rt/server_trace.h).
They do not change the existing PMU stage enum or mask.

| Timestamp | Exact capture point | Source |
| --- | --- | --- |
| `backend_enter` | LH: entry to `rpc_handle_packet`, before event identity extraction and packet-type switch. DPDK: after network parsing, before RPC header validation. Caladan: first timestamp in the UDP `receive` callback, before request-context initialization and RPC validation. | [LH rpc.c](../../sw/rt/rpc.c); sibling `microbenchmarks/server/{dpdk,caladan}/echo_server.c` |
| `lookup_enter`, `lookup_exit` | Immediately around the software program/version/procedure `lookup`, including argument extraction inside the call expression. DPDK and Caladan only; LH exports zero because the hardware descriptor already supplies the registration pointer. | Sibling backend `echo_server.c` files |
| `rpc_enter` | Entry to `rpc_handle_call`, before watchdog state, descriptor-to-registration/codec pointer access and request/trace initialization. | [rpc_call.h](../../sw/rt/rpc_call.h) |
| `unmarshal_enter`, `unmarshal_exit` | Legacy broad boundaries around `decode`, including allocation and new nested probes. | `rpc_call.h` |
| `alloc_enter`, `alloc_exit` | Immediately before and after `codec->ops->alloc`. | [rpc_request.h](../../sw/rt/rpc_request.h), `decode` |
| `decode_enter`, `decode_exit` | Immediately before and after the conditional call to `codec->ops->unmarshal_call` / `unmarshal_resp`; hot echo uses `unmarshal_call`. | `rpc_request.h`, `decode` |
| `handler_enter`, `handler_exit` | Before the registered handler call; after its return and the optional entry-return event hook. Includes the existing entry-timestamp bookkeeping before the call. | `rpc_call.h` |
| `outcome_enter` | After handler-result trace bookkeeping and restoration of the enclosing trace pointer, immediately before `consume_outcome`. | `rpc_call.h` |
| `marshal_enter`, `marshal_exit` | Legacy broad reply span: before capacity selection through completion of response encoding. Includes TX reservation and nested probes. | [rpc_reply.h](../../sw/rt/rpc_reply.h) |
| `reserve_enter`, `reserve_exit` | Immediately around `rpc_tx_reserve`, after capacity selection. LH reserves an ECI TX buffer and updates its placement/control state; DPDK and Caladan return their prepared body-buffer pointer. | `rpc_reply.h`; LH `rpc.c` and sibling `server/rpc_backend.h` |
| `encode_enter`, `encode_exit` | Immediately around `codec->ops->marshal_resp`, after the reservation-success check. | `rpc_reply.h` |
| `reply_enter` | Entry to backend `send_reply`, before worker lookup and reply metadata/header preparation. | Backend `send_reply` implementations |
| `reply_ready` | LH: after reply descriptor preparation, reserve fallback check and CPU-ID read, before TX entry. DPDK: after the ONC-RPC reply envelope, before Ethernet/IP/UDP construction. Caladan: after the reply envelope and reply-length assignment, before copying the final trace into its export slot. | Backend `send_reply` implementations |

The codec intervals include indirect-call/XDR adapter overhead and memory
accesses made inside the codec. The allocation interval includes allocator work.
The reply reservation interval includes LH's buffer/control-line accesses and
barrier. Each is measured directly; the shared source alone does not imply equal
elapsed cost across transports, buffers or compiler contexts.

## Transport boundaries

### Lauberhorn

All names in this table also use `server_..._ns`.

| Timestamp | Capture point |
| --- | --- |
| `rx_enter` | `server_trace_rx_begin` in [worker.c](../../sw/rt/worker.c), before receive acquisition; includes waiting if used as an interval start. Retained as a diagnostic. |
| `rx_unblock` | [core_eci_rx_acquire_traced](../../sw/core/eci/core.h): immediately after the RX control-line valid-field read and ordering barrier, before parity toggle and descriptor decoding. This is the version-3 CPU interval start. |
| `rx_exit` | Worker immediately after traced acquisition returns, before CPU-ID read and packet dispatch. |
| `tx_enter` | `send_reply`, just before `core_eci_tx_commit_traced_full`. |
| `tx_doorbell` | Immediately before the ECI commit control-line read. |
| `tx_doorbell_return` | Immediately after that read. |
| `tx_exit` | `send_reply`, immediately after the commit helper returns, before export bookkeeping. This is the CPU interval end. |

`rx_unblock → rx_exit` captures host RX metadata/ownership processing. The
control-line access that blocks for arrival precedes `rx_unblock` and remains
in the combined wire-minus-CPU residual. Payload reads happen later inside the
codec/handler. TX descriptor stores, barriers and commit belong to TX submission;
TX reservation is a separate measured interval in the same plotted category.

### DPDK

These primary CSV names have only the suffix `_ns`. Capture sites are sibling
`microbenchmarks/server/dpdk/echo_server.c` and the maintained
[mlx5 probe patch](../../nix/software/mlx5).

| Timestamp | Capture point |
| --- | --- |
| `rx_poll_enter` | Entry to the successful scalar mlx5 RX burst. Diagnostic; excludes earlier empty polls. |
| `rx_cqe` | Successful valid-CQE observation inside the selected mlx5 RX path. CPU interval start. |
| `rx_return` | After CQ/RQ bookkeeping and RX statistics, immediately before PMD return. |
| `network_rx_begin`, `network_rx_end` | Around endpoint extraction and Ethernet/IP/UDP `parse_udp`, before RPC envelope validation. |
| `rpc_rx_end`, `handler_end`, `rpc_tx_end` | Legacy aliases of shared `handler_enter`, `handler_exit`, `marshal_exit`. |
| `network_tx_begin`, `network_tx_end` | Around Ethernet/IP/UDP `make_udp_header`. |
| `tx_submit_begin` | After mbuf trim and offload-flag preparation, before shared TX probe and the burst/retry path. |
| `tx_pmd_enter` | Entry to mlx5 TX burst. |
| `tx_doorbell` | Immediately after the mlx5 doorbell helper. |
| `tx_return` | After post-doorbell mbuf tracking/counters, immediately before PMD return. CPU interval end. |

The gap from `network_tx_end` to `tx_submit_begin` is explicit runtime mbuf
preparation in version 3. The PMD inner timestamps refine submission; adding
them again would double count. Profiling requires exactly one RX and one TX
packet in the selected burst sample.

### CS-A64 (Caladan Simple)

All names use `server_..._ns`. Existing packet tracing in the Caladan tree is
joined to the shared RPC row by the adapter's `trace_complete`; no new Caladan
scheduler or watchdog behavior is required.

| Timestamp | Packet marker and source capture point |
| --- | --- |
| `rx_observed` | `PT_OBSERVED`: timestamp saved on CQ readiness observation in `runtime/net/directpath/mlx5/mlx5_init_common.c`; RX gather entry is the fallback when no readiness timestamp exists. CPU interval start. |
| `rx_queue_enter`, `rx_queue_exit` | `PT_RX_ENTER/EXIT`: RX gather entry through packet delivery after CQ/RQ bookkeeping in `mlx5_rxtx.c` or `mlx5_rx_stride.c`. |
| `network_rx_enter` | `PT_NET_RX`: `runtime/net/core.c`, before network ingress processing. |
| `network_rx_exit` | `PT_NET_RX_EXIT`: after UDP header extraction in `runtime/net/udp.c`: `udp_par_recv` before thread creation (spawner), `udp_conn_recv` before socket queue locking (resident). |
| `dispatch_ready` | `PT_READY`: after filling the spawned thread argument, before `thread_ready` (spawner); after socket queue publication, before waking a reader (resident). |
| `dispatch_enter` | `PT_DISPATCH`: first timestamp in the shared echo datagram handler, after socket read/copy for residents; also used as `backend_enter`. |
| `network_tx_enter` | `PT_NET_TX`: entry to `udp_send` via `udp_respond` (spawner), or `udp_write_to2` (resident), before buffer admission/allocation/copy and UDP/IP construction. |
| `network_tx_exit` | `PT_SUBMIT`: entry to `net_tx_raw`, before kthread acquisition and directpath/deferred submission. |
| `driver_enter` | `PT_DRIVER`: entry to mlx5 single-packet transmit. |
| `tx_doorbell` | `PT_DOORBELL`: after BlueFlame MMIO write and flush. |
| `submit_return` | `PT_TX_RETURN`: after doorbell bookkeeping and `putk`, before completion/export callback. Also exported as `tx_exit`; CPU interval end. |

For the spawner, `dispatch_ready → dispatch_enter` includes runnable-queue
publication, scheduling and delivery to the request user thread; it is runtime dispatch delay.
`network_rx_exit → dispatch_ready` includes thread creation/argument setup.
For resident workers, `network_rx_exit → dispatch_ready` instead includes socket
queue locking/publication; `dispatch_ready → dispatch_enter` includes queue wait,
reader wakeup/scheduling, dequeue and payload copying. `udp_read_from_traced`
copies the RX markers before the mbuf is freed; socket TX attaches the request's
persistent trace slot to the outgoing mbuf. The common NIC/PMD markers are
unchanged. `rx_observed → rx_queue_enter` includes deferred RX servicing. These
all remain in Runtime/dispatch. `network_tx_exit → submit_return` includes any deferred TX
submission before driver entry. These are elapsed request intervals, including
waiting where present. A batched queue span is shared elapsed context, not an
amortized per-packet CPU-service estimate; paper unloaded requests are single
outstanding.

## Segment formulas

Write `D(a,b) = b − a`. All intervals below are disjoint. The implementation is
[accounting.py](../../tools/paper/campaign/accounting.py); importers retain
`cpu_partition_version: 3` plus each row's `accounting` object with CPU bounds,
used timestamps and `runtime_details_ns`. Raw CSV files remain hashed evidence.

| Plotted segment | Formula |
| --- | --- |
| Handler | `D(handler_enter, handler_exit)` |
| RPC (un)marshal | `D(decode_enter, decode_exit) + D(encode_enter, encode_exit)` |
| Network stack | DPDK: `D(network_rx_begin, network_rx_end) + D(network_tx_begin, network_tx_end)`; Caladan: `D(network_rx_enter, network_rx_exit) + D(network_tx_enter, network_tx_exit)`; LH: zero CPU network interval |
| CPU–NIC | RX processing + `D(reserve_enter, reserve_exit)` + TX submission, using the per-system intervals below |
| Runtime/dispatch | CPU outer duration minus Handler, RPC (un)marshal, Network stack and CPU–NIC |
| NIC–wire | Tofino wire duration minus CPU outer duration |

| System | CPU outer interval | RX processing | TX submission |
| --- | --- | --- | --- |
| LH | `rx_unblock → tx_exit` | `rx_unblock → rx_exit` | `tx_enter → tx_doorbell_return` |
| DPDK | `rx_cqe → tx_return` | `rx_cqe → rx_return` | `tx_submit_begin → tx_return` |
| CS-A64 | `rx_observed → submit_return` | `rx_queue_enter → rx_queue_exit` | `network_tx_exit → submit_return` |

Internally `rpc_receive` and `rpc_transmit` hold the two codec durations;
`tx_submission` sums reservation and submission; `cpu_other` holds Runtime/dispatch.
The stored runtime details form a second, disjoint partition of `cpu_other`:

| Runtime detail | Interval |
| --- | --- |
| `backend_dispatch` (LH) | `backend_enter → rpc_enter` |
| `rpc_header` (DPDK/CS-A64) | `backend_enter → lookup_enter` |
| `lookup` (DPDK/CS-A64) | `lookup_enter → lookup_exit` |
| `backend_setup` (DPDK/CS-A64) | `lookup_exit → rpc_enter` |
| `request_setup` | `rpc_enter → alloc_enter` |
| `allocation` | `alloc_enter → alloc_exit` |
| `decode_setup` | `alloc_exit → decode_enter` |
| `handler_setup` | `decode_exit → handler_enter` |
| `handler_return` | `handler_exit → outcome_enter` |
| `outcome_dispatch` | `outcome_enter → reserve_enter` |
| `encode_setup` | `reserve_exit → encode_enter` |
| `reply_dispatch` | `encode_exit → reply_enter` |
| `reply_preparation` | `reply_enter → reply_ready` |
| `dispatch_wait` (CS-A64) | `dispatch_ready → dispatch_enter` |
| `rx_dispatch` (LH) | `rx_exit → backend_enter` |
| `transport_return` (LH) | `tx_doorbell_return → tx_exit` |
| `rx_handoff` (DPDK/CS-A64) | DPDK: `rx_return → network_rx_begin`; CS-A64: `rx_queue_exit → network_rx_enter` |
| `rpc_handoff` (DPDK) | `network_rx_end → backend_enter` |
| `tx_mbuf_preparation` (DPDK) | `network_tx_end → tx_submit_begin` |
| `rx_service_wait` (CS-A64) | `rx_observed → rx_queue_enter` |
| `request_thread_setup` (CS-A64) | `network_rx_exit → dispatch_ready` |
| `tx_handoff` | LH: `reply_ready → tx_enter`; DPDK: `reply_ready → network_tx_begin`; CS-A64: `reply_ready → network_tx_enter` |
| `other_runtime` | Remaining `cpu_other` after subtracting all above applicable details |

The named details expose queue/network/backend handoffs, thread creation and
transport-return bookkeeping. Caladan's `tx_handoff` includes the post-reply
shared cleanup before `udp_respond`. `other_runtime` retains any remaining gap
explicitly; it is zero when these named details cover the complete complement.
The raw endpoints above let these gaps be inspected without assigning them to
wire latency. They do not individually time every pointer read or switch
instruction; the [specialized trampoline](../proposals/specialized-rpc-trampoline.md)
remains a proposal, whose savings require a separate experiment.

## Validation and scope

Import rejects missing, zero, reversed, overlapping or out-of-CPU boundaries;
runtime details must fit entirely within the runtime complement. It rejects
negative wire residuals and conserves the complete per-request wire duration.
Chunks must agree on accounting version. A chart comparison containing version
3 must use version 3 throughout its breakdown cohorts; the legacy version-1/2
import/correction path remains available for historical figures. Version 2 used
broader RPC spans and LH's `rx_exit` CPU start, so its values retain that meaning.
No historical dataset is silently upgraded from aggregate durations.

All new clock calls compile out with `LH_ENABLE_TRACING=0` (and Caladan's matching
packet-trace build). Instrumented segments include clock-read costs, trace copies
and other probe effects; no estimate is subtracted. `timestamp_calls` counts the
shared stamp helpers, not every adapter/PMD/packet probe. Use matched off/on
acquisitions to assess perturbation. All new builds require fresh unloaded
acquisition before making a numerical comparison with this accounting.

The outer interval ends at reply submission. LH/DPDK outcome destruction,
argument freeing and RX retirement performed afterwards lie outside it.
Caladan performs shared outcome/argument cleanup before UDP reply submission, so that
work is inside its outer interval. Caladan's later UDP release/export and other
post-submission work lie outside. These breakdowns measure the request-to-reply
critical elapsed path, not total CPU work per request or steady-state capacity.
