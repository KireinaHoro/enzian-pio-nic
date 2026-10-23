# RPC instrumentation and hot/cold breakdown accounting

Current software exports **accounting version 4**. The hot CPU interval ends
immediately before the ECI read or mlx5 MMIO notification that hands the reply
to hardware. TX-return clocks, broad marshal/unmarshal spans and duplicate entry
aliases have been removed from acquisition. Archived data retains its declared
version; its import support does not add historical probes to current binaries.

This contract covers successful synchronous inline echo replies. CPU durations
use `CLOCK_MONOTONIC_RAW`; kernel activation fields use `ktime_get_raw_ns()`.
Tofino measures a separate same-switch-clock MAC interval. Cold activation probes
are opt-in and have a separate record stream. Their implementation does not
establish a hardware-validated cold experiment or authorize starting a campaign.

## Shared RPC boundaries

Shared CSV fields have `server_` prefix and `_ns` suffix. DPDK's primary CSV
copies the shared fields it needs alongside its driver/network fields. The
schema is [server_trace.h](../../sw/rt/server_trace.h); `accounting_version=4`
is explicit in every current server CSV. Field justification is below.

| Timestamp | Exact capture point | Source |
| --- | --- | --- |
| `backend_enter` | LH: entry to `rpc_handle_packet`, before event identity extraction and packet-type switch. DPDK: after network parsing, before RPC header validation. Caladan: first timestamp in the UDP `receive` callback, before request-context initialization and RPC validation. | [LH rpc.c](../../sw/rt/rpc.c); sibling `microbenchmarks/server/{dpdk,caladan}/echo_server.c` |
| `lookup_enter`, `lookup_exit` | Immediately around the software program/version/procedure `lookup`, including argument extraction inside the call expression. DPDK and Caladan only; LH exports zero because the hardware descriptor already supplies the registration pointer. | Sibling backend `echo_server.c` files |
| `rpc_enter` | Entry to `rpc_handle_call`, before watchdog state, descriptor-to-registration/codec pointer access and request/trace initialization. | [rpc_call.h](../../sw/rt/rpc_call.h) |
| `alloc_enter`, `alloc_exit` | Immediately before and after `codec->ops->alloc`. | [rpc_request.h](../../sw/rt/rpc_request.h), `decode` |
| `decode_enter`, `decode_exit` | Immediately before and after the conditional call to `codec->ops->unmarshal_call` / `unmarshal_resp`; hot echo uses `unmarshal_call`. | `rpc_request.h`, `decode` |
| `handler_enter`, `handler_exit` | Before the registered handler call; after its return and the optional entry-return event hook. Includes the existing entry-timestamp bookkeeping before the call. | `rpc_call.h` |
| `outcome_enter` | After handler-result trace bookkeeping and restoration of the enclosing trace pointer, immediately before `consume_outcome`. | `rpc_call.h` |
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

| Timestamp | Capture point and purpose |
| --- | --- |
| `rx_enter` | Before receive acquisition in [worker.c](../../sw/rt/worker.c); cold activation-to-RX/idle diagnostic, outside the hot CPU interval |
| `rx_unblock` | Immediately after valid RX control-line read and ordering barrier in [core.h](../../sw/core/eci/core.h); start of request CPU accounting |
| `rx_exit` | After acquisition returns; end of CPU descriptor/ownership processing |
| `tx_enter` | Before commit helper; starts TX descriptor preparation |
| `tx_publish` | Immediately before the opposite control-line read; ends CPU accounting after descriptor stores, barriers and parity preparation |

The doorbell read can block while hardware sends the reply. No clock is taken
at its return or after TX cleanup for this breakdown. Payload cache-line loads
inside the codec remain inside codec time; these probes do not separately
measure those memory stalls.

### DPDK mlx5

Primary CSV transport fields have `_ns` suffix and no `server_` prefix.
Source: sibling `microbenchmarks/server/dpdk/echo_server.c` and the maintained
[mlx5 patch](../../nix/software/mlx5/queue-probes.patch).

| Timestamp | Capture point and purpose |
| --- | --- |
| `rx_cqe` | Successful scalar CQE observation; CPU interval start, excludes empty polling |
| `rx_return` | After CQ/RQ bookkeeping, refill and statistics, before PMD return; closes RX queue preparation |
| `network_rx_begin/end` | Around endpoint extraction and Ethernet/IP/UDP parsing; isolate software RX stack |
| `network_tx_begin/end` | Around Ethernet/IP/UDP header construction; isolate software TX stack |
| `tx_submit_begin` | After mbuf trim/offload preparation, before burst/retry; starts TX submission |
| `tx_pmd_enter` | TX burst entry; separates adapter/retry work from PMD work |
| `tx_publish` | Inside `mlx5_doorbell_ring_traced`, after `rte_io_wmb`, doorbell-record store and `rte_wmb`, immediately before UAR MMIO store; CPU interval end |

The optional inline helper hook is NULL in uninstrumented builds. The 32-bit
helper path takes the hook after acquiring its UAR lock and before the first
MMIO half-write; the AArch64 experiment uses the 64-bit store. The conditional
post-MMIO flush and PMD return are outside the CPU interval. One RX and one TX
packet are required in an unloaded profiled burst.

### CS-A64 (Caladan Simple)

Fields have `server_..._ns`. Sibling Caladan `inc/runtime/packet_trace.h`
defines markers; `microbenchmarks/server/caladan/echo_server.c` exports them.

| Timestamp | Capture point and purpose |
| --- | --- |
| `rx_observed` (`PT_OBSERVED`) | CQ readiness observation before waking RX service in `mlx5_init_common.c`; fallback is now the first valid CQE observation inside gather, never gather entry before checking CQ readiness |
| `rx_queue_enter/exit` (`PT_RX_ENTER/EXIT`) | RX gather entry through refill/delivery in `mlx5_rxtx.c` / `mlx5_rx_stride.c`; bounds queue processing |
| `network_rx_enter` (`PT_NET_RX`) | Before network ingress in `runtime/net/core.c` |
| `network_rx_exit` (`PT_NET_RX_EXIT`) | After UDP header extraction in `runtime/net/udp.c`; before spawner creation or resident socket locking |
| `dispatch_ready` (`PT_READY`) | Spawner argument ready before `thread_ready`, or resident socket publication before reader wakeup; starts dispatch delay |
| `dispatch_enter` (`PT_DISPATCH`) | First handler timestamp after resident socket read/copy; also `backend_enter`, one clock read |
| `network_tx_enter` (`PT_NET_TX`) | Before UDP admission/allocation/copy and header construction |
| `network_tx_exit` (`PT_SUBMIT`) | Entry to `net_tx_raw`, before kthread acquisition / deferred TX; closes network preparation |
| `driver_enter` (`PT_DRIVER`) | Entry to mlx5 transmit; separates deferred handoff from driver work |
| `tx_publish` (`PT_PUBLISH`) | After `udma_to_device_barrier`, doorbell-record store and `mmio_wc_start`, immediately before `mmio_write64_be`; CPU interval end |

`mmio_flush_writes`, bookkeeping, `putk` and trace export follow publication and
carry no further packet-stage clock reads. The completion callback here exports
software evidence; it is not a NIC TX completion acknowledgement.

For residents, `dispatch_ready→dispatch_enter` includes queue wait, reader
wakeup/scheduling, dequeue and copy. For spawners it includes runnable-thread
dispatch; thread creation is `network_rx_exit→dispatch_ready`. RX service wait
before gather is runtime delay. On the genuine-CQE fallback, gather entry can
precede observation: start charged RX work at `max(rx_observed,rx_queue_enter)`.
Cold event records distinguish readiness observation (`peer=1`) from gather
fallback (`peer=2`). Batch timestamps describe elapsed batch context; unloaded
single-outstanding acquisition supplies the per-request interpretation.

## Breakdown formulas

Let `D(a,b)=b−a`, `R` be RX observation, `P` be `tx_publish`, and `W` the
paired switch MAC duration. The CPU outer interval is `C=P−R`.

| System | `R` | RX preparation | TX preparation |
| --- | --- | --- | --- |
| LH | `rx_unblock` | `rx_unblock→rx_exit` | `tx_enter→tx_publish` |
| DPDK | `rx_cqe` | `rx_cqe→rx_return` | `tx_submit_begin→tx_publish` |
| CS-A64 | `rx_observed` | `max(rx_observed,rx_queue_enter)→rx_queue_exit` | `network_tx_exit→tx_publish` |

| Plotted segment | Formula |
| --- | --- |
| Handler | `D(handler_enter,handler_exit)` |
| RPC (un)marshal | `D(decode_enter,decode_exit)+D(encode_enter,encode_exit)` |
| Network stack | DPDK: `D(network_rx_begin,network_rx_end)+D(network_tx_begin,network_tx_end)`; CS-A64: `D(network_rx_enter,network_rx_exit)+D(network_tx_enter,network_tx_exit)`; LH: zero |
| NIC handling | RX preparation + TX preparation + `D(reserve_enter,reserve_exit)` |
| Runtime/dispatch | `C` minus the four preceding CPU categories |
| NIC–wire | `W−C`; combined pre-observation RX and post-publication TX/link paths |

All CPU components are disjoint and sum to `C`; the complete stack sums to `W`.
Clock offsets cancel, but relative clock-rate error and boundary precision
still matter. A negative version-4 NIC–wire remainder is an accounting failure
requiring preserved evidence, never clipping. Version-3 archived plots retain
their independent wire marker and signed difference because they included CPU
return work overlapping transmission. No version mixing or inferred missing
publication endpoint is permitted in a current comparison.

The runtime complement is refined using these existing endpoints (not extra
clock reads):

| Detail | Interval |
| --- | --- |
| Backend dispatch, LH | `backend_enter→rpc_enter` |
| RPC envelope / lookup / setup, DPDK and CS-A64 | `backend_enter→lookup_enter→lookup_exit→rpc_enter` |
| Request setup / allocation / decode setup | `rpc_enter→alloc_enter→alloc_exit→decode_enter` |
| Handler setup / return | `decode_exit→handler_enter`; `handler_exit→outcome_enter` |
| Outcome dispatch / encode setup | `outcome_enter→reserve_enter`; `reserve_exit→encode_enter` |
| Reply dispatch / preparation | `encode_exit→reply_enter→reply_ready` |
| RX dispatch, LH | `rx_exit→backend_enter` |
| RX handoff, DPDK / CS-A64 | `rx_return→network_rx_begin` / `rx_queue_exit→network_rx_enter` |
| RPC handoff, DPDK | `network_rx_end→backend_enter` |
| Mbuf preparation, DPDK | `network_tx_end→tx_submit_begin` |
| RX service wait, CS-A64 | `rx_observed→max(rx_observed,rx_queue_enter)` |
| Thread/socket setup and dispatch, CS-A64 | `network_rx_exit→dispatch_ready→dispatch_enter` |
| TX handoff | `reply_ready→tx_enter` (LH), `reply_ready→network_tx_begin` (DPDK), `reply_ready→network_tx_enter` (CS-A64) |
| Other runtime | Remaining complement, explicitly retained and required nonnegative |

Driver entry refines TX preparation and must not also be added to Runtime/dispatch.
These are elapsed times, including stalls and scheduling delays. They are not
CPU-utilization measurements or isolated instruction costs.

## Purpose of the shared software probes

| Probe(s) | Why needed |
| --- | --- |
| `backend_enter` | Start software RPC validation/dispatch after transport handoff |
| `lookup_enter/exit` | Identify software procedure-table lookup separately from envelope parsing |
| `rpc_enter` | Separate backend dispatch from shared request initialization |
| `alloc_enter/exit` | Identify allocation overhead independently of decoding |
| `decode_enter/exit`, `encode_enter/exit` | Bound actual codec calls, including their buffer accesses |
| `handler_enter/exit` | Separate application execution from dispatch and codecs |
| `outcome_enter` | Separate handler-return bookkeeping from outcome selection |
| `reserve_enter/exit` | Charge transport reservation separately from response encoding |
| `reply_enter`, `reply_ready` | Identify reply-envelope/descriptor preparation and transport handoff |

All probes compile out in off builds. Clock-call costs remain in measured spans;
matched off/on acquisitions assess perturbation. `timestamp_overhead_ns` records
adjacent-clock calibration without subtraction. `timestamp_call_count` counts
shared helpers plus explicitly counted LH probes, not every PMD/packet clock.
PMU snapshots are optional counters, not additional timestamps or substitutes
for elapsed intervals. Their stage names follow the current shared probe enum.

Optional deferred/forwarded application tracing is separate from echo breakdown:
`defer_enqueue` marks fiber submission, `forward_submit/complete` bracket nested
forwarding, and internal `run_start`/run-end reads accumulate handler active/wait
durations. Optional event CSV `timestamp_ns` records fiber, native UDP and
application events only when the event stream is enabled. These are supported
non-inline execution diagnostics, excluded from the hot importer; see
[runtime tracing](runtime-tracing.md). No legacy broad codec or duplicate
entry timestamp fields are emitted.

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
| `yield_enter` | Outgoing `worker_yield` function entry; stored in target activation record together with `outgoing_tid`; separates syscall entry from kernel validation/locking |
| Event 20 (`LH_TRACE_DESCHED`) | Outgoing worker after DESCHED acquisition/type check; marks cooperative entry observation, with physical worker and outgoing activation index |
| Event 21 (`LH_TRACE_YIELD_ENTER`) | Outgoing `lh_yield`, immediately before ioctl; separates runtime entry from syscall admission |
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

Enable `LAUBERHORN_EVENT_TRACE_CSV` on the outgoing domain as well as target
server tracing. Event records include TID and raw-clock timestamps. Pass
`--events OUTGOING_EVENTS.csv` to `activation_breakdown.py`: match outgoing TID,
worker and outgoing activation index, then add the disjoint intervals
`DESCHED→userspace yield→kernel yield_enter→read_begin` to the activation
refinement. No event is matched using a request XID from the outgoing domain.
The existing FPGA-audit path remains for its archived schemas; current request
accounting uses version 4. Hardware packet/activation correlation is still needed
to prove which request caused activation and to construct a full cold RTT stack.

### Caladan: voluntarily parked core

With spinning below the active count, `runtime/sched.c` can exhaust its work
search and call `kthread_park`; `runtime/kthread.c` checks the spinning floor,
yields through KSCHED, and later resumes after IOKernel allocation. The cold
path is pending request → IOKernel demand detection/allocation → activate parked
kthread → runtime RX service → socket publication → resident handler dispatch.
Packet probes start at runtime CQ observation after activation may already
have occurred. The separate activation stream below captures the preceding path.

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

Both modes have opt-in `CALADAN_TRACE` activation records in
`base/activation_trace.c`, independent of the packet-stage buffer:

| Event | Placement and justification |
| --- | --- |
| `demand` (1) | Simple policy first observes congestion/parked-thread work, or explicit core-needed callback; bounds policy response, with target PID |
| `allocate` (2) | Selected target/core in `__sched_run` after pending-command checks, before cede/publication; records target PID/TID and runtime grant generation |
| `cede_request` (3) | Before `cede_gen` publication and interrupt enqueue; records old TID/grant and target TID |
| `run_request` (4) | Before KSCHED target TID/generation publication; records KSCHED core generation |
| `preempt_received` (5) | First C signal-handler entry (ARM and x86 cede path); bounds delivery before deferred-safe-point handling, not architectural exception entry |
| `cede_enter` (6) | `kthread_park_now` after preemption assertion; after any application context-save, also covers cede from scheduler context |
| `park_enter` (7) | Before actual yield helper, after spinning-floor checks; `peer=1` denotes voluntary park, zero forced park |
| `park_resume` (8) | After successful park ioctl/retry loop, before CPU-map restoration; captures runtime reactivation |
| `rx_observed` (9) | Same exact raw timestamp as the packet's observation marker; queue address and producer TID connect RX to the activation stream |
| `park_attempt/result` (10/11) | Around each park ioctl attempt; distinguish interrupted/retried admission from successful reactivation |

`ksched/activation_trace.h` supplies disabled-by-default kernel tracepoints,
with embedded raw nanoseconds, core, TID, target and KSCHED generation:

| Kernel phase | Capture point |
| --- | --- |
| 1 | Park admission after the signal-pending rejection check |
| 2 | Task marked parked immediately before `schedule`, including bootstrap |
| 3 | Valid target resume in `finish_wake`, or same-task regrant return |
| 4 | Before target `wake_up_process` after ownership setup |

These phases distinguish publication, kernel admission, sleep, wakeup and resume;
phase 1 alone does not imply the outgoing task slept. Runtime grant generations
and KSCHED core generations are different namespaces. The join checks both.
The shared control/kernel ABI is unchanged; kernel trace events add no clock
reads when disabled, and off userspace builds contain no activation hooks.

### Caladan cold evidence collection and analysis

Use a traced IOKernel **and** traced runtime. Campaign and trace images expose
`/nix/store/caladan-activation/bin/iokerneld`; the ordinary hot IOKernel stays
unchanged. Create a fresh capture directory, set `CALADAN_ACTIVATION_TRACE_DIR`
for the IOKernel, target and competing runtime, and enable kernel collection
before the controlled cold workload:

```sh
python3 tools/enzian/activation_trace.py --system caladan --output KERNEL_CAPTURE -- WORKLOAD_COMMAND
```

Each traced process creates an exclusive `activation-PID.bin`: a prefaulted,
65,536-record, shared-mapped bounded buffer, with lock-free slot reservation and
per-record publication. Signal nesting cannot overwrite an in-flight record.
There is no allocation or file I/O in the event writer. The writer takes a clock
sample (unless using the packet's existing timestamp), TID and CPU observations;
this optional instrumentation perturbs cold activation and must be compared
against matched off runs. Quiesce all producers before copying/exporting the
files; keep kernel `capture.json`, per-CPU loss statistics and `trace.txt`.

```sh
python3 tools/enzian/caladan_activation.py \
  --capture IOKERNEL.bin --capture TARGET.bin --capture OWNER.bin \
  --kernel KERNEL_CAPTURE --server MEASURED_COLD_ROWS.csv \
  --mode preempt --output ACTIVATION.json
```

Use `--mode parked` for voluntary parking with idle-core activation; omit OWNER
when unused. The server input is the exact measured single-outstanding cold
subset, with warmups excluded explicitly. The tool rejects overflow, incomplete
slot publication, kernel loss, ambiguous joins, generation mismatches and reused
activations. A requested preemption that reaches a cooperative safe point without
a captured signal does not pass the forced-preemption cohort check.

The voluntary partition refines `demand→allocate→run_request→target_running→
park_resume→rx_observed`. The preemption join additionally retains
`cede_request→preempt_received→cede_enter→kernel admission→outgoing parked`.
Publication and cede can interleave: sort the actual timestamps and partition
adjacent endpoints once. Do not add overlapping branches. Prior idle residence
is evidence of cold state, not RPC latency. Wake-publication phase 4 and ioctl
attempt/result records remain available to refine the joined intervals.

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
   Reject obsolete or missing publication endpoints in current captures.
5. Select the central observation(s) after ordering the instrumented cohort by
   wire latency; use those same observations for every segment and total (average
   the two middle observations for an even count). Component-wise medians need
   not describe any request and do not conserve the median total. CDF percentiles
   come from their separate instrumentation-off cohort.
6. Enable and export activation tracing explicitly for cold acquisition. Require
   one uniquely matched fresh activation per cold sample, drain eviction traffic,
   and retain placement and voluntary/forced transition evidence. Hot manifests do not implement this cold protocol; activation evidence and
   packet-trigger causality must be validated before a cold campaign.

The maintained collectors/importers are linked from
[hot measurement](../research/hot-measurement.md),
[runtime tracing](runtime-tracing.md) and
[benchmark campaigns](benchmark-campaign.md).
