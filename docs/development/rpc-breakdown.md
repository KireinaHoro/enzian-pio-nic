# RPC instrumentation and hot/cold breakdown accounting

The hot unloaded breakdown uses **CPU accounting version 3** for newly built
LH, DPDK and CS-A64 responders. It separates the shared codec calls from
allocation, transport reservation and runtime dispatch. This contract covers
successful synchronous inline echo replies. All CPU timestamps are
`CLOCK_MONOTONIC_RAW` nanoseconds; Tofino supplies a separate same-switch-clock
wire duration. The [hot measurement methodology](../research/hot-measurement.md)
defines request pairing, warmups and the instrumented/off comparison.

This is the timestamp inventory and accounting contract for unloaded RPC
measurements, including cold domain/core activation. The tables distinguish
captured fields from required additions. **The implemented importer remains
version 3**; the pre-publication split below is the agreed next accounting
contract, not an implemented change or a relabelling of existing plots.

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

## Implemented version-3 segment formulas

Write `D(a,b) = b − a`. The CPU component intervals below are disjoint; the
wire-total marker measures a separate interval. The implementation is
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
| Wire-total marker | Independently measured Tofino MAC interval for the same selected observation(s) |

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

## RX/TX ordering and overlapping intervals

The switch captures request TX with `capture_tstamp_on_tx` and reply RX from
`ingress_mac_tstamp`; the pairer subtracts those MAC timestamps on the same
server-facing port. CPU probes use `CLOCK_MONOTONIC_RAW`. These clocks do not
provide a shared epoch, so measured durations alone cannot locate CPU events
inside the switch interval.

For LH, the receive path is CMAC → complete-frame RX CDC FIFO → Ethernet/IP/UDP/
ONC-RPC decode → payload DMA into the packet buffer (when nonempty) → scheduler process/worker
selection → ECI RX descriptor/control-line response. The RX DMA must complete
before the scheduler receives the descriptor. `rx_unblock` is sampled after the
CPU's valid-field load and ordering barrier; descriptor decode and return follow,
ending at `rx_exit`. The CPU interval excludes that preceding receive path and
blocked read. Overflow payload cache-line loads occur later in the codec/handler
and are inside the CPU interval. `rx_enter` precedes the blocking read and can
include idle time before the request was sent; it is not the CPU partition start.

LH TX stores the reply descriptor and orders stores before `tx_doorbell`, then
reads the opposite control line. The FPGA collects the dirty control and overflow
cache lines, issues the TX descriptor and starts packet-buffer read DMA. Payload
streams through ONC-RPC, UDP, IP and Ethernet encoders, alignment/padding and a
complete-frame TX CDC FIFO to CMAC and the switch. In `DmaControlPlugin`, success
is returned after read-DMA completion (or descriptor acceptance for a zero-DMA
packet). Specifically, `axi_dma_rd.v` emits descriptor status when it produces
the final beat into its internal output FIFO; it does not wait for that FIFO to
drain into the encoders. `DcsTxAxiRouter.waitResult` then releases the pending ECI read response.
This response and the remaining encoder/FIFO/CMAC/link path are separate branches;
there is no wire-completion dependency on the CPU response. `tx_doorbell_return`
is after the CPU read and barrier; `tx_exit` follows probe stores, helper return
and transport cleanup. Neither timestamps wire completion.

For DPDK, CX-5 receives the frame and makes its RX buffer/CQE available before
`rx_cqe`, sampled in the successful scalar PMD receive path. CQ/RQ updates,
replenishment and mbuf work run through `rx_return`; network parsing, RPC lookup,
shared codecs and handler follow. TX constructs the reply frame, enters the PMD,
prepares WQEs and publishes the doorbell. `tx_doorbell` is after the PMD doorbell
helper; `tx_return` follows mbuf tracking/counters, before PMD return. NIC DMA and
transmission proceed independently after publication. The CPU end is submission
return, not a CQE indicating physical TX completion.

For resident Caladan, the CQ-readiness poll records `rx_observed` before waking
the RX polling thread. `rx_queue_enter/exit` bracket gather/refill work. If no
readiness timestamp exists, `rx_observed` falls back to gather entry, before its
CQE checks; that fallback is not an exact CQE-observation timestamp. Network
processing reaches `network_rx_exit` after UDP-header extraction. Socket queue
locking/publication reaches `dispatch_ready`; reader wakeup, queue wait/dequeue
and payload copy precede `dispatch_enter`. Shared RPC processing then produces
the reply. `network_tx_enter` precedes socket admission, allocation/copy and
UDP/IP/Ethernet construction; `network_tx_exit` is entry to `net_tx_raw`.
`driver_enter` precedes mlx5 WQE preparation. `tx_doorbell` follows the BlueFlame
write/flush; `submit_return` follows doorbell bookkeeping and `putk`, before
trace export. NIC transmission is independent after the doorbell. Outer
`net_tx_raw`/UDP return work after this probe is outside the recorded CPU interval.

Consequently the CPU partition is a partition of its recorded elapsed interval,
not of wire RTT or the complete request lifetime. Keep all its nonnegative
components and the independent wire duration. The signed duration difference
combines unmeasured receive/transmit prefixes with overlap; its sign alone does
not identify the overlapping stage or its duration. Do not infer directional
transport costs or actual overlap from unsynchronized endpoint timestamps.

## Validation and scope

Import rejects missing, zero, reversed, overlapping or out-of-CPU boundaries;
runtime details must fit entirely within the runtime complement. CPU components
conserve the CPU outer duration. The legacy `components_ns.cpu_wire` key stores
the signed difference `wire_ns − cpu_duration_ns`, including negative values; it
is not plotted as a sequential NIC/wire segment. New joined rows also retain
`cpu_duration_ns` explicitly. Historical rows derive it by summing CPU components.
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


## Pre-publication breakdown contract (implementation pending)

End the request's CPU interval immediately **before the operation that publishes
TX to the NIC**. Call this endpoint `P`; call first RX observation `R`. LH already
records `P = server_tx_doorbell_ns` before its blocking ECI control-line read.
DPDK and Caladan require a new pre-publication probe: their existing
`tx_doorbell` fields are after the doorbell helper / BlueFlame write and flush.
Driver entry is earlier than WQE preparation and cannot substitute for `P`.
Keep the old post-publication probes as diagnostics with their existing meaning.
The new probe must precede the first operation that permits transmission, with
compiler/architecture ordering checked against the actual selected driver path.

For each paired request, let `W` be the switch MAC duration and `C = P − R`.
Use the shared Handler, codec and Network stack formulas above unchanged.

| Segment | New formula / boundary |
| --- | --- |
| CPU–NIC RX, LH | `rx_exit − rx_unblock`: observed valid descriptor through acquisition return |
| CPU–NIC RX, DPDK | `rx_return − rx_cqe`: observed CQE through packet delivery by PMD |
| CPU–NIC RX, Caladan | `rx_queue_exit − rx_queue_enter`: gather/refill; preceding RX service wait remains Runtime/dispatch |
| CPU–NIC TX, LH | `P − tx_enter` |
| CPU–NIC TX, DPDK | `P − tx_submit_begin` |
| CPU–NIC TX, Caladan | `P − network_tx_exit`; retain `driver_enter` to expose deferred submission within this span |
| CPU–NIC total | RX + TX + `reserve_exit − reserve_enter` |
| Runtime/dispatch | `C − Handler − RPC (un)marshal − Network stack − CPU–NIC` |
| NIC–wire | `W − C` |

These RX boundaries end when the packet/descriptor is available to the next
software layer. Software handoff gaps and Caladan's receive-service scheduling
remain explicit runtime details; include them when discussing total elapsed
RX preparation, rather than hiding them inside driver service. Reservation is
charged once despite preceding encoding. LH payload cache-line reads inside the
codec remain in the codec interval; the probes cannot isolate their stall cost.

The CPU components sum to `C`; adding NIC–wire sums to `W`. The latter combines
switch-to-RX-observation and pre-doorbell-to-switch-return paths, including
transmission triggering and both links. Post-publication CPU return work has no
sequential position in this stack. Record its duration separately; remove LH's
`transport_return` from the new runtime complement. This decomposition does not
split RX from TX hardware latency. Clock offsets cancel in durations; relative
clock-rate error and probe placement still affect the remainder. Investigate a
negative new remainder rather than clipping it or extending the CPU endpoint.

Use a new accounting version and record the endpoint semantics in imported
rows. LH raw captures can support retrospective recomputation when all fields
are present. Existing DPDK/Caladan captures cannot supply the missing endpoint.
Never mix those approximations with newly instrumented cohorts in one breakdown.

Caladan must preserve whether `rx_observed` came from CQ readiness or the
gather-entry fallback. Establish valid request observation for the new CPU start;
a fallback sampled before checking the CQE is not an exact availability boundary.

## Why each software probe is retained

The capture-point tables above define placement; this table states the purpose
of every listed group. Paired probes bracket only the named operation. Additional
markers below cover activation and optional execution paths.

| Probe(s) | Measurement purpose |
| --- | --- |
| `backend_enter` | Begin software RPC validation/dispatch after transport handoff |
| `lookup_enter/exit` | Isolate program/version/procedure lookup from envelope parsing and codec work |
| `rpc_enter` | Separate backend dispatch from shared request initialization |
| `alloc_enter/exit` | Separate allocation from decoding |
| `decode_enter/exit`, `encode_enter/exit` | Measure actual codec calls with their memory accesses |
| `handler_enter/exit` | Bound application execution independently of dispatch and serialization |
| `outcome_enter` | Expose handler-return bookkeeping before outcome selection |
| `reserve_enter/exit` | Separate transport buffer reservation from encoding |
| `reply_enter`, `reply_ready` | Expose backend metadata/envelope preparation and the following transport handoff |
| `unmarshal_enter/exit`, `marshal_enter/exit` | Retain legacy broad spans for old analyses; never add them to their nested codec/allocation/reservation spans |
| LH `rx_enter` | Diagnose blocked receive/idle time; unsuitable as the hot CPU start |
| LH `rx_unblock`, `rx_exit` | Separate pre-observation hardware delivery from CPU descriptor processing |
| LH `tx_enter`, `tx_doorbell` | Bracket descriptor preparation/barriers before publication |
| LH `tx_doorbell_return`, `tx_exit` | Diagnose blocking commit response and subsequent cleanup separately from transmission |
| DPDK `rx_poll_enter`, `rx_cqe`, `rx_return` | Separate successful-poll prefix, packet availability and PMD delivery; empty polls are outside the request span |
| DPDK `network_rx_begin/end`, `network_tx_begin/end` | Separate Ethernet/IP/UDP processing from RPC processing and queue submission |
| DPDK `tx_submit_begin`, `tx_pmd_enter` | Expose adapter/retry overhead before PMD WQE preparation |
| DPDK `tx_doorbell`, `tx_return` | Diagnose publication helper and post-publication bookkeeping; neither supplies the new pre-publication endpoint |
| DPDK `rpc_rx_end`, `handler_end`, `rpc_tx_end` | Legacy aliases; no independent clock read or extra additive component |
| Caladan `rx_observed`, `rx_queue_enter/exit` | Separate RX servicing delay from gather/refill; retain the observation-versus-fallback distinction |
| Caladan `network_rx_enter/exit`, `network_tx_enter/exit` | Separate network stack/socket TX preparation from RPC and driver work |
| Caladan `dispatch_ready`, `dispatch_enter` | Measure request publication and resident/spawned handler dispatch delay |
| Caladan `driver_enter` | Identify deferred TX or kthread acquisition before the driver |
| Caladan `tx_doorbell`, `submit_return` | Diagnose publication/return; keep their post-publication semantics |
| New DPDK/Caladan pre-publication probe | Close the CPU critical interval before transmission can proceed independently |

### Additional runtime fields and optional paths

[server_trace.h](../../sw/rt/server_trace.h),
[server_trace.c](../../sw/rt/server_trace.c) and [rpc.c](../../sw/rt/rpc.c)
retain the following fields outside the hot inline partition:

| Field | Capture and purpose |
| --- | --- |
| `entry_enter/exit` | Inline handler entry/return aliases; retain entry-interface compatibility without charging a second interval |
| `defer_enqueue` | Before deferred request submission; identifies transition from inline handling to queued work |
| `forward_submit` | Before nested forward submission; starts the forward-wait diagnostic |
| `forward_complete` | Completion/error callback for the nested forward; ends that diagnostic |
| `run_start` | Internal timestamp at resumed handler execution; paired with run-end clock reads to accumulate active spans |
| `handler_active_ns`, `handler_wait_ns` | Derived active-span sum and handler elapsed minus active time; not independent timestamps and not a CPU-utilization claim |
| `timestamp_overhead_ns` | Adjacent-clock calibration statistic; retained without subtracting an estimated cost from samples |
| Optional event CSV `timestamp_ns` | `server_trace_event` samples the raw clock when appending a request/context-tagged event; exposes fiber/forward/entry transitions, with event definitions and collection in [runtime tracing](runtime-tracing.md); disabled for the Caladan stage-only adapter |
| PMU snapshots | Optional counters at the legacy stage enum boundaries; counters are not elapsed timestamps and do not replace raw-clock probes |

Deferred/forwarded spans overlap other spans. Version 3 accepts only synchronous
inline replies and must not apply its partition to those execution kinds.

## Cold activation paths and timestamps

Cold here means a request requires activation, not merely that caches are cold.
Record the outgoing and incoming domains, Linux CPUs/TIDs, physical NIC worker,
core ownership and configuration. Require a fresh activation for each cold
request. Initial bootstrap, idle activation, cooperative cross-domain handoff,
Caladan voluntary parking and Caladan forced preemption are distinct cohorts.

### LH cooperative entry and wakeup in another domain

The requested path is: outgoing worker observes DESCHED → calls `lh_yield()` →
kernel validates/fences the outgoing task → reads `DeschedInfo` → NIC completes
ownership/transport cleanup → kernel claims an initialized waiting thread in
the target domain → installs affinity and publishes activation → wakes target →
target returns from its prior yield → restores alias/parities → receives RPC.
See [scheduler](../software/scheduler.md) and
[activation collection](runtime-tracing.md#activation-and-inter-packet-gaps).
This ordinary path uses no scheduling IRQ. Idle-to-active uses SGI 8 and a pinned
kernel dispatcher before the shared activation helper.

Existing `lauberhorn:activation` fields are embedded `ktime_get_raw_ns()` values
from [sched.c](../../sw/kmod/sched.c), not the surrounding ftrace record time.
The event is emitted once, just before the target's successful yield return.

| Existing stamp | Exact point and reason |
| --- | --- |
| `irq` | First unconsumed notification in [worker.c](../../sw/kmod/worker.c); bounds idle-path interrupt-to-dispatch delay; zero for ordinary cooperative handoff |
| `dispatch` | Dispatcher before activation reconciliation; separates dispatcher scheduling from MMIO; zero for cooperative handoff |
| `read_begin`, `read_end` | Immediately around `DeschedInfo` read; measures accepted handoff/cleanup transaction |
| `claim` | After finding and claiming a waiting target thread; separates target availability/claim from MMIO |
| `affinity` | After `set_cpus_allowed_ptr`; isolates affinity installation |
| `wake` | Before copying timing and publishing activation-ready under lock; separates publication/wakeup/scheduling from earlier work; it is before the actual `wake_up` call |
| `resume` | Target's successful `wait_event_killable` return; closes publication-to-resumption delay |
| `return` | After activation copyout, before emitting trace event and returning from syscall; isolates kernel copyout |
| `server_activation_ioctl_exit_ns` | First userspace stamp after successful yield ioctl in [activation.c](../../sw/rt/activation.c); measures trace/syscall-return tail |
| `server_activation_ready_ns` | After alias/parity installation and activation metadata update; bounds runtime activation setup |

Kernel `cold = !outgoing` identifies the idle-dispatcher route. A cooperative
cross-domain request can be cold for the experiment while its kernel record has
`cold=false`. Do not select experimental cold samples by that flag alone.
Kernel worker sequence and runtime per-thread activation index are different
counters: join using worker, domain/TID, ordering and activation bounds, with a
unique match, rather than assuming numerical equality. Hot requests that reuse
an activation must not recharge its cost.

Available CPU-clock activation partition, for the selected activation:
`read_begin→read_end` + `read_end→claim` + `claim→affinity` +
`affinity→wake` + `wake→resume` + `resume→return` +
`return→ioctl_exit` + `ioctl_exit→ready`.
Idle-path diagnostics additionally split `irq→dispatch→read_begin`.
`ready→rx_unblock` includes waiting for/acquiring the request and is only an
activation-to-RX bridge, not pure scheduler work.

**Missing for full cooperative entry accounting:** request-correlated outgoing
DESCHED observation, userspace yield entry and kernel yield entry timestamps.
`read_begin` is after validation/locking, so it cannot measure all cooperative
entry overhead. Kernel syscall/scheduler tracing can refine these gaps when
captured, but is not an existing per-request raw-clock field. Add explicit
markers to separate DESCHED delivery, runtime entry and kernel admission; carry
the outgoing/incoming activation identities across the join. Hardware event
correlation below is needed to separate packet queuing from activation demand.

### Caladan: voluntarily parked core

With spinning below the active count, `runtime/sched.c` can exhaust its work
search and call `kthread_park`; `runtime/kthread.c` checks the spinning floor,
yields through KSCHED, and later resumes after IOKernel allocation. The cold
path is pending request → IOKernel demand detection/allocation → activate parked
kthread → runtime RX service → socket publication → resident handler dispatch.
Existing packet probes start at runtime CQ observation (or gather fallback),
after activation may already have occurred. They do not time parked-core wakeup.

### Caladan: preempt owner, then activate target

`iokernel/simple.c` permits preemption when the owner is above its guarantee and
the requester is below its guarantee. In `iokernel/sched.c::__sched_run`, an
occupied core receives a `cede_gen` update and queued `KSCHED_INTR_CEDE`, then a
`ksched_run` request for the target. Signal/IPI delivery reaches the outgoing
runtime (`runtime/preempt.c`, ARM signal path), which cedes via
`thread_finish_cede`/`kthread_park_now`; the target can then run. Requesting the
new thread and completing the old owner's cede are separate events. The signal
may be deferred by runtime preemption-disabled regions. Priority labels alone
do not establish this simple-policy condition.

Both Caladan modes need these **new**, request/activation-correlated probes:

| Required stamp | Placement and justification |
| --- | --- |
| `park_enter`, `park_resume` | Around actual `kthread_yield_to_iokernel`; prove voluntary park occurred and bound reactivation return (the intervening idle duration is not RPC latency) |
| `demand_observed` | IOKernel first observes pending work for target; begins CPU-clock scheduling delay, distinct from runtime `rx_observed` |
| `allocation_decided` | Core/target selected; isolates policy and allocation wait |
| `run_requested` | At target KSCHED publication; separates allocation bookkeeping from activation |
| `cede_requested` | Before outgoing cede publication; required only for occupied-core case |
| `preempt_received` | Outgoing signal/IPI handler entry; distinguishes delivery delay from deferred cede |
| `cede_enter`, `cede_complete` | Outgoing runtime transfer and kernel acceptance of relinquishment; separates safe-point/context-save delay from target activation |
| `target_running` | Target kernel resume boundary; separates KSCHED handoff from runtime restoration |
| `rx_observed` provenance | Preserve existing packet observation and mark fallback explicitly; connect activation to the matching RX work |

Use the same host raw-clock domain or record explicit conversions for kernel
and runtime probes. IOKernel scheduler cycles and optional NIC CQ hardware
clock readings are not existing joined request timestamps. A packet-trace pointer
alone cannot identify the activation that happened before RX; add core, source
and target process/TID, wake generation and request/queue correlation. Record
actual allocation and cede outcomes, not just requested configuration.

For voluntary park, refine `demand_observed→allocation_decided→run_requested→
target_running→park_resume→rx_observed`. For preemption, report the cede branch
`cede_requested→preempt_received→cede_enter→cede_complete` and target activation
branch with their actual order. Do not add branches that overlap. Partition the
union of ordered events from demand observation to RX once, then apply the
ordinary request processing partition. Full packet-to-demand latency still
requires hardware correlation or remains in the combined NIC–wire remainder.

DPDK remains a resident polling baseline in this contract. No DPDK parked-core
or cross-domain cold activation protocol is defined; any such experiment needs
its own activation and correlation contract before collection.

### Placement in a cold RTT breakdown

The hot CPU start at RX observation omits activation before observation. With
that start, `W − C` contains activation as well as hardware/link time: it must
not be labelled pure NIC–wire in a cold chart. Initially report activation as a
separate refinement with explicit coverage. To produce a disjoint cold stack,
choose a request-caused activation start `A` in the same CPU clock, prove its
causal placement after switch departure and before RX, and replace `C` by
`P − A`. Split `A→R` into activation and explicit gaps; then reuse the hot
`R→P` components and use `W − (P − A)` as the remaining hardware/link interval.
An outgoing yield before request arrival is not an admissible `A`. Without a
correlated demand timestamp, that expanded cold partition is unavailable.

## Hardware and client collection inventory

| Timestamp / event | Collection and role |
| --- | --- |
| Tofino request TX | Server-facing egress MAC TX timestamp captured with `capture_tstamp_on_tx`, drained by `bf_port_1588_timestamp_tx_get`; start of `W` |
| Tofino reply RX | Server-facing ingress `ingress_mac_tstamp`; end of `W`; preserve raw values and pair by flow/XID/direction |
| FPGA trace sample timestamp | Input-batch capture before recorder queue in current maps; source pipeline correction from the exact generated map; not a CPU or Tofino timestamp |
| `RxCmacEntry`, `RxAfterCdcQueue` | Last-beat CMAC RX event transferred into app clock, and complete-frame RX CDC dequeue; exposes receive buffering |
| `TxBeforeCdcQueue`, `TxCmacExit` | Last-beat TX CDC enqueue and CMAC stream exit event transferred into app clock; exposes transmit buffering, not switch MAC boundaries |
| `RxRpcEnqueueToHost`, `RxBypassEnqueueToHost` | First host-enqueue observation after RX DMA, with packet/host IDs and drop flag; distinguish RPC scheduling from bypass delivery |
| `SchedulerRequestDropped`, `SchedulerWatchdogExpired` | Failure/expiry diagnostics; exclude failed requests from successful-path decomposition without hiding their counts |
| `SchedulerRequestQueued`, `SchedulerRequestDispatched`, `SchedulerProcessRun`, `SchedulerDeschedCore` | Scheduler queue/dispatch and ownership transition observations; identify activation demand and packet delivery when correlation is unique |
| `YieldAccepted`, `ActivationCommitted`, `ActivationDelivered` | Accepted DeschedInfo read, completed cleanup/ownership install, and response consumption; bound hardware handoff |
| `TxHostReqAccepted`, `TxNoDmaRead`, `TxAfterDmaRead` | Host TX request and DMA-path completion diagnostics; DMA completion means final beat produced into DMA output FIFO, not wire completion |
| DCS/ECI channel events | Accepted message observations at the documented source points; diagnose coherent RX/TX and activation transactions using exact generated source IDs |

FPGA traces are optional diagnostic acquisitions, not timestamps automatically
collected by the Tofino hot campaign. The complete build-specific event/field
inventory is `lauberhorn_trace_dma_map.json`; use the
[hardware trace contract](../hardware/tracing.md) for source channels, RX DMA
events, drops/watchdog events and pipeline correction. Preserve that map with the
bitstream hash. CMAC pulse CDC and ECI asynchronous FIFO residence limit physical
event localization; fixed pipeline subtraction does not remove variable CDC
delay. Never interpret a trace event's descriptive name as an exact physical
wire timestamp. The active mlx5 packet probes do not export per-packet hardware
RX/TX timestamps into the breakdown.

The unloaded DPDK client (`microbenchmarks/client/bench_dpdk.c` in the sibling
repository) records these raw-clock diagnostics independently of server clocks:

| CSV field | Placement and purpose in the single-outstanding path |
| --- | --- |
| `client_call_enter_ns` | Before codec/frame preparation; client API-side start |
| `client_tx_ready_ns` | After packet construction; separates preparation from submission |
| `client_tx_submit_ns` | After TX burst/retry; diagnostic submission return, not MAC TX |
| `client_rx_burst_ns` | Immediately after successful receive burst, before parsing; records software reply observation |
| `client_call_exit_ns` | After validation/completion; includes client processing |
| `timestamp_overhead_ns` | Clock-pair calibration; no subtraction |
| `scheduled_ns`, `start_lag_ns`, `client_tx_admit_enter_ns` | Loaded-generator schedule/admission diagnostics; not unloaded wire or server breakdown boundaries |

Loaded prepared-slot and parallel paths use their own timing contract; do not
apply the unloaded construction endpoints to those rows. Flush traffic has
separate enter/exit records and is excluded from measured requests.

## Collection, clock conversion and aggregation

1. Preserve source/build hashes, instrumentation mode, runtime/core configuration,
   selected driver path, clock metadata, raw client/server CSV and switch pairs.
   Collect instrumented breakdown cohorts separately from instrumentation-off
   CDF cohorts. Loaded runs use their own generator timing and no breakdown probes.
2. Use one outstanding unloaded RPC, explicit setup/warmup exclusion, unique
   flow/XID pairing and bounded switch export without FIFO loss. Keep warmups in
   correlation until joins are resolved; exclude them from statistics afterwards.
   Reject ambiguous/missing/duplicate pairs and missing required probe fields.
3. Convert each clock using its own contract. Server userspace RAW and kernel
   `ktime_get_raw_ns` already return nanoseconds. Enzian's advertised architectural
   counter is 100 MHz (10 ns ticks); Linux performs its conversion. CPU GHz is
   irrelevant. Tofino exports nominal nanoseconds despite its 1.22 GHz device
   clock: increment 220029063 / 2^28 ns per device cycle. Compute same-clock
   modular differences (48-bit wire pairing) at nominal 1 ns/unit. Preserve the
   actual clock snapshot/qualification for each acquisition; never apply the
   device frequency to already converted timestamps. CPU-to-switch relative
   rate is not established by a switch-clock check against the switch host.
4. Derive per-request intervals before aggregation. Check endpoint order and
   nonoverlap, and require segment conservation for the declared accounting
   version. Never add legacy broad spans to nested probes or activation twice.
   Preserve diagnostic return spans outside the pre-publication stack.
5. Select the central observation(s) after ordering the instrumented cohort by
   wire latency; use those same observations for every segment and total (average
   the two middle observations for an even count). Component-wise medians need
   not describe any request and do not conserve the median total. CDF percentiles
   come from their separate instrumentation-off cohort.
6. Enable and export activation tracing explicitly for cold acquisition. Require
   one uniquely matched fresh activation per cold sample, drain eviction traffic,
   and retain placement and voluntary/forced transition evidence. Existing hot
   manifests and the hot version-3 importer do not implement this cold protocol.

The maintained collectors/importers are linked from
[hot measurement](../research/hot-measurement.md),
[runtime tracing](runtime-tracing.md) and
[benchmark campaigns](benchmark-campaign.md).
