# RPC instrumentation and hot/cold breakdown accounting

Current source exports **accounting version 5** for successful synchronous inline
echo. Acquisition versions 2–4 retain their original import formulas; version-4
paper data still combines Runtime/dispatch. New data is required for the separate
**Misc runtime** and **Dispatch** segments. No historical observations are
relabeled or mixed across accounting versions.

CPU clocks use `CLOCK_MONOTONIC_RAW`. The CPU interval starts at NIC observation
and ends immediately before ECI/MMIO publication. Tofino measures a separate
same-switch-clock MAC interval. There is no TX-return timestamp. Cold activation
probes are separate, opt-in streams; the hot changes do not establish a validated
cold campaign.

## Work intervals and shared boundaries

Names below omit the CSV `server_` prefix and `_ns` suffix. A boundary is sampled
once and shared by adjacent intervals. LH `select_enter` reuses `rx_exit` without
another clock read. DPDK and CS-A64 export the same version-5 timestamp namespace.

| Work between timestamps | LH | DPDK | CS-A64 | Segment |
| --- | --- | --- | --- | --- |
| `rx_unblock → max(rx_unblock,rx_gather_enter)` | — | — | RX service scheduling after CQ readiness | Dispatch |
| RX processing start → `rx_exit` | ECI descriptor/ownership acquisition | CQ/RQ processing/refill | Gather/refill after the preceding scheduling interval | NIC message passing |
| `rx_exit → network_rx_end` | Hardware path | Ethernet/IP/UDP processing and handoff from PMD | Network processing and handoff from gather | Network stack |
| `network_rx_end → backend_enter` | — | — | Socket publication, wakeup, scheduling, dequeue/copy; spawner delivery in that variant | Dispatch |
| RPC availability → `select_enter` | — | RPC envelope validation | RPC envelope validation after `backend_enter` | Misc runtime |
| `select_enter → select_exit` | Packet-type dispatch and descriptor-to-registration/codec/handler selection | Service lookup and shared handler/codec selection | Service lookup and shared handler/codec selection | Dispatch |
| `select_exit → decode_enter` | Request initialization and argument allocation | Same | Same | Misc runtime |
| `decode_enter → decode_exit` | Request codec | Same | Same | RPC (un)marshal |
| `decode_exit → handler_enter` | Decode-result check and invocation setup | Same | Same | Dispatch |
| `handler_enter → handler_exit` | Handler call | Same | Same | Handler |
| `handler_exit → reserve_enter` | Return bookkeeping, outcome switch and reply selection | Same | Same | Dispatch |
| `reserve_enter → encode_enter` | TX reservation, ownership and success check | Response-buffer reservation and success check | Same | NIC message passing |
| `encode_enter → encode_exit` | Response codec | Same | Same | RPC (un)marshal |
| `encode_exit → reply_ready` | Encode-result check and reply-descriptor preparation | Encode-result check and RPC reply-envelope preparation | Reply-envelope preparation and handoff to UDP transmit | Misc runtime |
| `reply_ready → packet_ready` | Hardware path | Ethernet/IP/UDP header construction | UDP admission/allocation/copy and header construction | Network stack |
| Packet ready → `tx_publish` | From `reply_ready`: ECI descriptor stores and ordering | From `packet_ready`: mbuf preparation, PMD/WQE/doorbell-record work and ordering | From `packet_ready`: kthread/deferred handoff, driver/WQE/doorbell-record work and ordering | NIC message passing |

There is no separate NIC-submission-start or driver-entry probe. In particular,
CS-A64 deferred TX handoff remains inside NIC message passing under this boundary
choice. Dispatch is not a count of instructions removable by specialization:
its elapsed spans include checks/bookkeeping at the specified boundaries, and
codec/handler intervals include their indirect calls. Compare matched generic
and specialized implementations to quantify the trampoline's savings.

Let `R=rx_unblock`, `P=tx_publish`, `C=P−R`, and `W` be the paired switch duration.
Dispatch, NIC message passing, Network stack, RPC (un)marshal and Handler
are accumulated from their disjoint intervals.
Misc runtime is the remaining CPU interval, including allocation, header validation
and reply preparation. NIC–wire is `W−C`, the combined pre-observation RX and
post-publication TX/link remainder. CPU categories sum to `C`, and all seven
segments sum to `W`. Negative remainders, missing/reversed timestamps and mixed
accounting versions are rejected, never clipped. No synchronized CPU/switch
clock is assumed; relative clock-rate error still matters.

## Physical sampling points

The shared schema and collector are [server_trace.h](../../sw/rt/server_trace.h)
and [server_trace.c](../../sw/rt/server_trace.c). Shared RPC sites are
[rpc_call.h](../../sw/rt/rpc_call.h), [rpc_request.h](../../sw/rt/rpc_request.h),
[rpc_reply.h](../../sw/rt/rpc_reply.h) and [rpc.c](../../sw/rt/rpc.c).

- LH `rx_unblock` follows the valid control-line read and barrier; `rx_exit`
  follows acquisition and trace-slot selection. `select_exit` follows loading
  the codec and handler selection, before request initialization. `reply_ready`
  follows reply-descriptor preparation. `tx_publish` precedes the blocking
  opposite-control-line read, after stores, barriers and parity preparation.
- DPDK `rx_unblock` copies the scalar PMD's valid-CQE timestamp; `rx_exit` copies
  its end-of-RX timestamp. Empty polls take neither timestamp. `network_rx_end`
  follows parsing. `select_enter` precedes service lookup. `reply_ready` follows
  RPC envelope construction; `packet_ready` follows network-header construction,
  before mbuf trim/offload preparation. The maintained
  [mlx5 patch](../../nix/software/mlx5/queue-probes.patch) samples `tx_publish`
  after WQE/doorbell-record stores and ordering, immediately before UAR MMIO.
- CS-A64 `rx_unblock`, `rx_gather_enter`, `rx_exit`, `network_rx_end`,
  `reply_ready`, `packet_ready`, `tx_publish` map respectively to Caladan
  `PT_OBSERVED`, `PT_RX_ENTER`, `PT_RX_EXIT`, `PT_NET_RX_EXIT`, `PT_NET_TX`,
  `PT_SUBMIT`, `PT_PUBLISH`. `backend_enter` is sampled after socket receive/copy,
  before RPC validation. `select_enter` precedes service lookup. CQ readiness
  precedes RX-service activation; the fallback samples the first valid CQE
  inside gather. Thus gather entry can precede observation, requiring the `max`
  above. Publication is after `udma_to_device_barrier`, doorbell-record store and
  `mmio_wc_start`, before `mmio_write64_be`; flush/return are excluded.

The shared codec, handler and reservation boundaries directly surround the work
listed in the table. `select_exit` is after shared descriptor-based selection.
Removed acquisition probes include allocation begin/end, shared RPC entry,
lookup exit, outcome entry, reply entry, reservation exit, separate network begin,
LH TX entry, DPDK submission/PMD entry, and Caladan ready/driver-entry markers.
Source does not retain them to support historical imports.

## Fixed trace storage and collection

Each worker's collector array is allocated with `calloc` and every page is
prefaulted before activation. Reserving a request means advancing an index into
that fixed array. Only metadata is set on acquisition; unused fields were already
zeroed at startup. The worker, RPC request, deferred fiber and forwarded parent
retain the same slot pointer. Completion marks that slot with a release store;
it does not copy a whole trace record. Export uses acquire loads and supports
out-of-order completion on another thread. Pending records fail completeness;
discarded non-request records are skipped. Exhaustion aborts rather than growing,
overwriting or silently losing the buffer.

The campaign configures capacity from actual request counts: LH includes its 17
priming/setup requests, all measured requests and every capture chunk's warmups.
DPDK and Caladan use their configured total including repeated chunk warmups.
For 10,000 measurements and 128 warmups per 896-measurement capture chunk, this
is 11,553 LH slots and 11,536 DPDK/Caladan slots. Arrays are per worker; the hot
unloaded experiment uses one worker.

DPDK's lightweight sample ledger points to the same collector rows; it no longer
copies RPC timestamps into another sample structure. Its transport PMD scratch
values are copied to the request row. CSV serialization occurs after collection.
Caladan retains fixed packet-context slots for deferred TX completion, holding a
pointer to the collector row rather than a second copy of it. RX packet metadata
still crosses the network/socket boundary; it is not a copy of the full RPC row.

Successful hot requests take 12 LH, 15 DPDK and 17 CS-A64 breakdown clock reads.
LH takes an additional pre-acquisition `rx_enter` only for the first received
record after activation, for the cold path; ordinary hot requests do not take
that clock. Caladan readiness/gather clocks belong to batches, so empty/repeated
service attempts can add observations outside a successful request. Optional
application events and cold activation streams add their own clocks when enabled.
Per-read timestamp counters have been removed. Startup adjacent-clock calibration
is retained as `timestamp_overhead_ns`, without subtracting it from observations.

PMU snapshots remain opt-in and use their own stable stage enum. Version-5 hot
PMU attribution starts at RX exit and ends at reply-ready (`pmu_tx_enter`); it no
longer claims a pre-arrival RX counter interval. Coarse/detailed masks are 36/60.
Deferred/forwarded application tracing retains enqueue, nested completion and
fiber-active clocks when those paths execute; hot accounting rejects such rows.
All software instrumentation compiles out in the off variants.

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
accounting uses version 5. Hardware packet/activation correlation is still needed
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
