# Lookup Table Scaling and Spill Design

Status (2026-09-08): proposed scaling design, not implemented general spill support. Existing baseline paths were checked against the main source tree. The separate TCP tree described below is historical branch context; its implementation was not re-audited here and the main checkout still has TCP/HTTP/gRPC placeholders. New block/API names and staged implementation steps below are proposals. See [hardware status](implementation-status.md).

## Motivation

The current `LookupTable` implementation is a fully-connected associative
table: each entry is a register, and each lookup port builds a comparator for
every entry plus a priority/value mux over the full table. This gives a simple
3-cycle lookup, but it makes table size and lookup-port count expensive in LUTs,
routing, and timing.

This leaks into system-level capacity. Today the hardware table sizes are also
the admission limits:

- `NUM_SERVICES = 8`: max hardware-visible ONC-RPC services.
- `NUM_PROCS = 16`: max scheduler-visible applications/processes.
- `NUM_SESSIONS = 16`: max in-flight reply-routing sessions.
- `NUM_NEIGHBOR_ENTRIES = 8`: max cached IPv4 neighbors.

These should not be hard registration limits. Hardware should cache the active
working set. Registration, process count, and service count should scale through
software-backed spill paths: per-application runtime paths for request data and
kernel/control paths for global resource management.

The design goal is:

- fast path stays entirely in hardware on table hit;
- table miss or table full transfers ownership to an explicit software slow
  path;
- request-bearing RPC spills go to the per-application runtime by default;
- kernel/control bypass handles raw packets, ARP, resource management, and
  validation;
- software can install/promote entries into hot hardware tables;
- hardware table capacity affects hit rate and latency, not correctness;
- lookup-table storage and lookup logic become easier to place and route.

## Existing Slow Paths

The existing kernel bypass slow path is `BypassCmdSink`.

`BypassCmdSink` accepts multiple `Stream[HostReqWithTrace]` producers, arbitrates
them, stores requests in a FIFO, and delivers them to the bypass datapath
service. It is already used by:

- `DmaControlPlugin` for normal bypass packets and packet-bearing `neighborMiss`
  requests originating at `IpEncoder` and passing through `DecoderSink`.

On the kernel side, `sw/core/eci/core.h` decodes bypass-core RX descriptors into
`lauberhorn_pkt_desc_t`. `sw/kmod/bypass.c` polls the bypass datapath from NAPI
and currently handles:

- `TY_BYPASS`: inject packet into Linux netdev RX;
- `TY_NEIGHBOR_MISS`: reconstruct IPv4 and submit the retained payload to Linux
  routing/`ip_local_out`; neighbor handling also populates `IpEncoder.neighborDb`.

This packet-bearing path replaced the older `arpReq` notification-only path.
Source comments still mentioning drop-on-neighbor-miss or direct `IpEncoder`
commands are stale; follow the `neighborMiss` descriptor/payload wiring.

This is the right mechanism for kernel-owned exceptions, but not for every
spill. Runtime-owned exceptions should use typed host requests routed through
the scheduler/runtime RX path. Do not overload `HostReqType.bypass` for every
miss. Add explicit typed slow-path requests, and route each request to the owner
that can actually resolve it.

## TCP Branch Reference

Flavian's unmerged TCP tree is a useful precedent for how exception delivery
should be split.

The branch adds TCP-specific `HostReqType` values:

- `tcpRxPayload`
- `tcpException`
- `tcpRecoverableException`
- `tcpAckObserved`
- `tcpTxPayload`
- `tcpResync`
- `tcpTcbInstall`

It does not send all TCP exceptions to the kernel bypass NAPI path. In
`DmaControlPlugin`, only `HostReqType.bypass` is enqueued into `BypassCmdSink`;
all other host requests, including TCP payloads and TCP exceptions, are enqueued
to `sched.logic.rxMeta`. On the software side, the kernel `core_eci_rx` guard
still accepts only `bypass` and `arpReq`, and `sw/kmod/bypass.c::poll_once`
handles only `TY_BYPASS` and `TY_ARP_REQ`.

The TCP decoder still uses generic bypass, but only for packet-shaped TCP
traffic that should be reconstructed as a normal network packet. The bypass TCP
payload fork is explicitly dropped, and only header/control descriptors are
emitted through `DecoderSink` as `HostReqType.bypass`. TCP offload exceptions
that matter to the runtime are represented as typed host requests:

- accepted in-order payload: `tcpRxPayload`;
- recoverable payload exception: `tcpRecoverableException`;
- non-recoverable or metadata exception: `tcpException`;
- ACK/control observation: `tcpAckObserved`.

This is the right shape for RPC spilling. "Bypass" should mean a slow-path
escape hatch, not necessarily the kernel netdev bypass queue. Kernel bypass is
appropriate for network-stack-owned work such as raw packets, ARP, and perhaps
registry-control misses. Runtime/application-owned spills should use typed
scheduler-visible host requests so they can be consumed by userspace or runtime
workers without first pretending to be kernel network traffic.

TCP segment aggregation also stays out of the hardware hot path. The decoder
buffers at most a frame-aligned MTU payload while checksum and state decisions
complete. It emits one host descriptor per TCP segment/event. The software owner
model then buffers out-of-order ranges, merges overlapping ranges, reinjects
contiguous recovered segments, and falls back to host ownership if software-side
buffer limits are exceeded. RPC spilling should follow the same principle:
hardware spills packet/request units, while software owns batching, aggregation,
retry, and overflow policy.

## Two Slow-Path Planes

There are two classes of "bypass" in the broader exception-path sense:

1. Kernel/control bypass.

   This is the existing `BypassCmdSink` path. It is for work owned by the
   kernel, the Linux network stack, or the hardware resource manager:

   - raw packet bypass and netdev injection;
   - ARP and neighbor-cache resolution;
   - global hot-table admission, eviction, and generation management;
   - security/isolation validation before an app runtime can own a request.

2. Runtime/application bypass.

   This is a typed host-request path to the scheduler/runtime, not
   `HostReqType.bypass`. It is for work whose semantics are owned by the
   application runtime:

   - service-table miss for a registered service that is not hot in hardware;
   - process hot-cache miss for an otherwise registered application;
   - request queue overflow when software can queue or execute the request;
   - software-owned session/reply handling when the hardware cache misses;
   - protocol exceptions where software can continue from metadata plus bytes.

The default owner for RPC spilling should be the per-application runtime. It
knows whether to execute, queue, batch, retry, reject, or promote a service or
process. The kernel should own global resource allocation and protection, not
the normal data-bearing spill path. If a spill needs both, split it into a
kernel/control event and a runtime packet-bearing event instead of forcing all
exceptions through kernel bypass.

## Ownership Model

Every spill must fit one of three ownership classes.

### Control-Only Spill

No packet payload is transferred. The host request contains only metadata and
possibly a table index/operation. It may be routed to the kernel bypass queue,
the scheduler/runtime RX path, or a host-to-hardware control path depending on
which side owns the state.

Examples:

- ARP request;
- table eviction notification;
- software promotion request;
- session-cache full notification when software can own future reply routing.

The request must set `len = 0` and clear `buffer`.

### Kernel Packet-Bearing Spill

The request transfers an RX packet or packet fragment to software. Hardware must
provide a valid `PacketBufDesc`; the bypass core owns that buffer until it
acknowledges/freeing through the normal `hostRxAck` path.

Examples:

- service-table miss for an incoming ONC-RPC call, if the kernel owns the full
  registry;
- raw packet miss after partial decode;
- kernel-owned policy spill that must preserve packet bytes.

For packet-bearing RX spills, prefer routing through `DecoderSink` and
`DmaControlPlugin`, because that path already allocates packet-buffer memory,
writes payload bytes, and emits `HostReqWithTrace`.

Use this class when the consumer is the kernel bypass path: raw packet delivery,
ARP/neighbor resolution, or a miss that must be resolved by kernel-owned control
state before any userspace/runtime worker sees it. Process misses should not use
this route unless the process registry is explicitly kernel-owned.

### Runtime Packet-Bearing Spill

The request transfers an RX packet, parsed request, or inline payload directly
to the scheduler/runtime host path as an explicit `HostReqType`, not as
`HostReqType.bypass`.

Examples:

- service-table hit but process table miss, where the runtime can execute or
  queue the request in software;
- service-table miss if the full service registry lives in the runtime;
- ONC-RPC request overflow when the first request can be executed in software
  and promotion can happen later;
- TCP-style recoverable exception with payload, if TCP is merged.

Use this class when the eventual owner is userspace/runtime state. TCP's
`tcpRecoverableException` path is the model: preserve enough metadata and bytes
for software to continue correctly, but keep the kernel bypass queue out of the
steady slow path.

## New Host Request Types

Rename direction-sensitive RPC request names as part of this work if convenient.
The current ONC-RPC descriptor names use explicit RX/TX direction.

Suggested `HostReqType` set (retain the existing nested-RPC directions as well):

- `error`
- `bypass`
- `neighborMiss`
- `oncRpcCallRx`
- `oncRpcReplyTx`
- `oncRpcCallTx`
- `oncRpcReplyRx`
- `serviceMissRuntime`
- `serviceMissValidate`
- `processMiss`
- `processSlotPressure`
- `sessionInsertMissRuntime`
- `sessionLookupMissRuntime`
- `sessionSlotPressure`
- `tablePromote`
- `tableEvict`

The first implementation can keep old aliases internally, but generated
Mackerel names and C-side enums should move to direction-explicit names.

Be liberal about adding typed requests. The TCP branch demonstrates that adding
domain-specific host request types is cleaner than overloading generic bypass.
For RPC spill, use this split:

- kernel/control path: `serviceMissValidate`, `processSlotPressure`,
  `sessionSlotPressure`, `tablePromote`, `tableEvict`;
- scheduler/runtime path: `serviceMissRuntime`, `processMiss`,
  `oncRpcCallRxSpill`, `sessionInsertMissRuntime`,
  `sessionLookupMissRuntime`.

The exact names can be shortened in implementation, but the owner and route
must be explicit in hardware. Do not infer the owner from
`HostReqType.bypass`.

Suggested C-side descriptor enum additions in `sw/core/eci/core.h`:

- `TY_SERVICE_MISS_RUNTIME`
- `TY_SERVICE_MISS_VALIDATE`
- `TY_PROCESS_MISS`
- `TY_PROCESS_SLOT_PRESSURE`
- `TY_SESSION_INSERT_MISS_RUNTIME`
- `TY_SESSION_LOOKUP_MISS_RUNTIME`
- `TY_SESSION_SLOT_PRESSURE`
- `TY_TABLE_PROMOTE`
- `TY_TABLE_EVICT`

Add corresponding cases in `sw/kmod/bypass.c::poll_once` only for
kernel-owned request types. Scheduler/runtime-owned requests need runtime decode
and dispatch instead.

## New Descriptor Payloads

Add new `HostReqData` union members.

### `HostReqServiceMissRuntime`

Packet-bearing. Used by ONC-RPC call decode when no hot service entry matches,
but the request belongs to a registered runtime service or can be resolved by
the runtime's full service registry.

Fields:

- packet buffer descriptor and length in existing `HostReq`;
- source IP address;
- source UDP port;
- destination UDP port;
- ONC-RPC `xid`;
- ONC-RPC `progNum`;
- ONC-RPC `progVer`;
- ONC-RPC `proc`;
- inline argument bytes;
- packet ID / RPC ID through trace sideband only, not host ABI unless needed.

Runtime action:

- look up service in the full software registry;
- if found, optionally promote service to hardware and deliver to runtime;
- if not found, generate ONC-RPC reject or drop according to policy;
- free the packet buffer after handling.

Routing:

- default: send `serviceMissRuntime` through the scheduler-facing RX path;
- if a kernel-owned validation table must approve the service before delivery,
  send a control-only `serviceMissValidate`/promotion request to the kernel
  control path;
- if a deployment makes the full service registry kernel-owned, send the
  packet-bearing miss to `BypassCmdSink`, but this should not be the default
  for application RPC traffic.

### `HostReqServiceMissValidate`

Control-only. Used when kernel-owned policy must validate or authorize a
service miss before runtime delivery or hot-slot promotion.

Fields:

- service key: `(listenPort, progNum, progVer, proc)`;
- source address/port summary if needed for policy;
- requested runtime/process identity if already decoded;
- reason: validation miss, registration generation mismatch, promotion denied.

Kernel action:

- validate registration and isolation policy;
- authorize or deny runtime delivery/promotion;
- optionally install or refresh a hot service entry.

### `HostReqProcessMiss`

Packet-bearing. Used by `Scheduler` when the decoded PID is not hot in
`procDb`, or when a hardware process slot cannot be admitted without eviction.

Fields:

- original `HostReqOncRpcCallRx`;
- requested PID;
- optional service/function pointer;
- reason: no hot process, queue full, admission blocked, evict required.

Runtime action:

- decide whether to run on software slow path;
- promote PID/process into hardware if it should become hot;
- execute or enqueue the request in software;
- retry/enqueue into hardware only after promotion is committed, if the runtime
  chooses synchronous promotion.

This is usually a runtime packet-bearing spill, not kernel bypass. The decoded
service already maps to an application/process identity; if the process is not
hot in hardware, software should be allowed to execute or queue the request and
promote a hardware slot later. This keeps "number of registered applications"
from being bounded by `NUM_PROCS`.

Do split process spills into data and control:

- `processMiss`: request-bearing event to the per-app runtime;
- `processSlotPressure` or `processEvict`: control-only event to the
  kernel/resource manager when a hardware process slot must be allocated,
  evicted, or drained.

The kernel should not be on the request-bearing path merely because `procDb`
missed. It should be involved when changing global scheduler state, enforcing
isolation, or reclaiming shared hardware resources.

### `HostReqProcessSlotPressure`

Control-only. Used when a runtime or scheduler path needs a shared hardware
process slot allocated, evicted, drained, or invalidated.

Fields:

- PID/process identity;
- requested operation: promote, evict, drain, invalidate;
- queue state summary: empty, non-empty, assigned core, draining;
- generation/version;
- reason: no hot process slot, queue pressure, stale generation, policy.

Kernel/resource-manager action:

- enforce process isolation and global process-slot ownership;
- approve or reject hot-slot promotion;
- initiate safe drain/eviction only when queued packets and core assignment are
  accounted for.

### `HostReqSessionInsertMissRuntime`

Control-only or packet-associated, depending on policy. Used by
`OncRpcReplyEncoder.sessionDb` when ingress call/session recording cannot fit in
the hot session table.

Fields:

- `funcPtr`;
- `xid`;
- client IP;
- client UDP port;
- server UDP port;
- optional PID/process identity if nested-RPC reply routing needs it.

Runtime action:

- store session in the runtime-owned software session table;
- ask the kernel/control plane to evict/promote hot session entries if a shared
  hardware slot change is needed;
- mark the corresponding call as requiring software reply routing if the
  hardware reply path cannot be made lossless.

### `HostReqSessionLookupMissRuntime`

Control-only for software-resend semantics, or packet-bearing for lossless
retry. Used on TX reply lookup miss.

Fields:

- `funcPtr`;
- `xid`;
- reply length;
- optional inline reply bytes if hardware consumed the descriptor;
- reason: no session, software-owned session, table evicted, table full.

Policy choices:

- simple first step: notify runtime and require software resend;
- lossless step: park TX reply in a pending FIFO and ask runtime to resolve or
  release it.

Do not overwrite session entry 0 on table full. That is not a spill policy; it
turns capacity pressure into data loss.

### `HostReqSessionSlotPressure`

Control-only. Used when the runtime needs a shared hardware session slot
allocated, evicted, drained, or invalidated.

Fields:

- session key: `(funcPtr, xid)`;
- client/server address and port tuple when available;
- requested operation: promote, evict, drain, invalidate;
- generation/version;
- reason: table full, stale generation, software-owned session became hot.

Kernel/resource-manager action:

- enforce global slot ownership and isolation;
- update hot session table state;
- reject stale or unauthorized slot operations.

### `HostReqTablePromote` / `HostReqTableEvict`

Control-only. Used to make hardware/software table ownership explicit.

Fields:

- table ID: service, process, session, neighbor;
- table index;
- generation/version;
- key fields;
- reason/counter.

These are not required for the first fast path, but they are useful once hot
tables become caches with replacement.

## Spill Points

### UDP Listen Table

Current use:

- small 16-entry table;
- one lookup;
- miss means normal bypass packet, not an error.

Plan:

- keep fully-connected or move to LUTRAM only if convenient;
- no new spill semantics required;
- if listen table grows, `listenMiss` is simply ordinary packet bypass.

### ONC-RPC Service Table

Current use:

- `OncRpcCallDecoder.serviceDb`;
- match key is `(listenPort, progNum, progVer, proc)`;
- value is `(funcPtr, pid)`;
- miss currently drops the packet.

Plan:

- change miss from drop to `serviceMissRuntime`;
- keep payload ownership through `DecoderSink` and `DmaControlPlugin`;
- add a packet descriptor variant for service miss, or add a generic
  `PacketDescType.oncRpcServiceMiss`;
- update `DmaControlPlugin` to translate this descriptor into
  `HostReqType.serviceMissRuntime`;
- route to the scheduler/runtime RX metadata path by default;
- let runtime consult the full service registry, execute/queue the request, and
  request hot-slot promotion if useful;
- emit `serviceMissValidate` only for kernel-owned validation/policy checks.

RAM implementation:

- for moderate table sizes, use BRAM-backed associative scan or hash buckets;
- for large registered-service counts, use URAM/BRAM hot cache plus software
  full registry;
- avoid serial full-table scan on every packet if service count is large enough
  to affect line rate. Prefer a hashed/set-associative hot table keyed by
  `(port, progNum, progVer, proc)`.

### Scheduler Process Table

Current use:

- `Scheduler.procDb`;
- `rxPush` maps PID to process queue index;
- `drain` continuously scans for non-empty queues not assigned to any core.

Plan:

- explicitly check `rxPush` match status and produce typed `processMiss`;
  current code consumes `pushResult.idx` without a `matched` guard, so a miss
  must not be assumed to have a safe drop behavior;
- route `processMiss` to scheduler/runtime RX metadata by default;
- split process registry from active process slots;
- hardware `procDb` becomes the active process cache;
- process eviction must not discard queued packets. Evict only if:
  - no core is currently assigned to the process;
  - hardware queue is empty; or
  - queue contents have been drained/spilled to software.

RAM implementation:

- do not implement `drain` as a RAM scan if possible;
- maintain bitmaps:
  - process enabled/hot;
  - queue non-empty;
  - process has assigned core;
  - process eviction/promotion in progress;
- derive drain candidate from bitmaps with a priority encoder;
- implement PID-to-process lookup as BRAM/LUTRAM indexed/hash structure;
- use BRAM for queue metadata if `NUM_PROCS` grows, but keep hot scheduler
  combinational decisions over compact bitmaps.

### ONC-RPC Session Table

Current use:

- `OncRpcReplyEncoder.sessionDb`;
- ingress call records `(funcPtr, xid) -> client/server address/port`;
- TX reply lookup uses `(funcPtr, xid)`;
- free-slot lookup is always active;
- table full currently overwrites entry 0.

Plan:

- replace entry-0 overwrite with explicit spill;
- maintain a free list or free bitmap instead of continuously scanning for free;
- on session insert miss/full, emit `sessionInsertMissRuntime`;
- on TX lookup miss, emit `sessionLookupMissRuntime`;
- define whether TX miss is runtime notify plus software resend, or a lossless
  retry path. Lossless retry requires a pending reply queue and runtime release
  contract.

RAM implementation:

- strongest URAM/BRAM candidate;
- use BRAM for tens/hundreds of in-flight sessions;
- use URAM for larger session hot caches;
- prefer keyed/indexed design over serial scan if reply throughput matters;
- add generation bits so software can distinguish stale promoted sessions from
  current sessions.

### Nested ONC-RPC Request Table

The newer `OncRpcReplyDecoder.requestDb` is separate from server reply sessions.
`OncRpcCallEncoder` records outbound nested requests; replies match
`(xid, remoteAddr, remotePort, localPort)` and recover PID/cookie. It also uses
`NUM_SESSIONS`, an always-active free-slot lookup, and increments `reqTblFull`
while still writing the returned free index on full. Scaling/spill design must
cover this table too, with explicit payload ownership, timeout/cancellation and
late-reply behavior. The typed spill interfaces above do not yet implement it.

### IP Neighbor Table

Current use:

- `IpEncoder.neighborDb`;
- absent entry creates an incomplete slot; absent/incomplete neighbors emit
  packet-bearing `neighborMiss` through the RX DMA/bypass path;
- kernel reconstructs IPv4 and uses Linux output/neighbor resolution, and
  programs reachable state later;
- full-table insertion still uses allocation index 0 and increments a counter;
  explicit safe replacement remains work.

Plan:

- keep existing spill model;
- replace free-slot scan with free list/bitmap;
- add neighbor-table full spill if all hot slots are in use;
- software can evict stale neighbor entries and reprogram hardware.

RAM implementation:

- LUTRAM/BRAM if table grows;
- URAM is overkill unless thousands of neighbors are cached.

### ECI Thread Router Table

Current use:

- `EciThreadClRouter.threadDb`;
- many lookup ports, but only `NUM_CORES` entries in generated hardware.

Plan:

- do not make this a spill target initially;
- keep fully-connected while core count is small;
- if scaling core/thread mappings becomes necessary, redesign as direct
  prefix/index maps rather than a generic multi-cycle associative RAM.

## Lookup Table Implementation Plan

Implement RAM-backed lookup as new blocks in `deps/blocks`, not as an immediate
replacement for every `LookupTable` user.

### New Block Variants

1. `LookupTableRegCam`

   - current behavior;
   - explicit name for small fully-connected tables;
   - 3-cycle pipelined lookup;
   - useful for `listenDb` and `threadDb`.

2. `LookupTableRamScan`

   - BRAM/URAM-backed storage;
   - one or more lookup engines scan entries over multiple cycles;
   - simple first step for low-rate control paths;
   - suitable for free/debug/readback scans, not line-rate service lookup.

3. `LookupTableSetAssoc`

   - hash/index key to a small set;
   - compare ways in parallel;
   - BRAM/LUTRAM storage;
   - fixed lookup latency independent of total table size;
   - suitable for `serviceDb`, `sessionDb`, and process PID lookup.

4. `LookupTableFreeList`

   - helper for tables with allocation semantics;
   - free bitmap or FIFO;
   - removes always-on `lookupFree` ports.

### Interface Requirements

The existing `makePort` API hides whether lookups are parallel or multi-cycle.
For spill-aware tables, expose explicit lookup result reasons:

- hit;
- miss;
- table full;
- busy/backpressured;
- collision/way full;
- stale generation.

For replacement-aware tables, expose management operations:

- update by index;
- invalidate by index;
- readback by index;
- optional promote/evict command with generation.

### PNR Goals

Measure before/after with:

- LUT/FF/BRAM/URAM utilization per table;
- worst negative slack and critical path module names;
- route congestion around decoder/scheduler/encoder regions;
- generated RTL size and number of high-fanout table signals.

Expected wins:

- fewer wide priority muxes;
- lower fanout from storage registers;
- fewer duplicated comparators per lookup port;
- table capacity no longer increases placement pressure linearly per port.

## Implementation Phases

### Phase 1: ABI and Slow-Path Plumbing

- Add direction-explicit `HostReqType` names and spill request types.
- Implement the owner/route split:
  - kernel bypass queue for raw packets, ARP, validation, and global
    resource-management events;
  - scheduler/runtime RX metadata queue for request-bearing service, process,
    and session misses;
  - host-to-hardware control commands for promotion, eviction, drain, and
    generation updates.
- Extend `HostReqData` with service/process/session spill payloads.
- Regenerate Mackerel headers.
- Extend `sw/core/eci/core.h` descriptor enum and decode for any
  kernel-owned spill types.
- Extend `sw/kmod/bypass.c::poll_once` and add handler stubs for
  kernel-owned spill types:
  - `rx_handle_service_validate`;
  - `rx_handle_process_slot_pressure`;
  - `rx_handle_session_slot_pressure`;
  - `rx_handle_table_promote`;
  - `rx_handle_table_evict`.
- Add runtime/core decode for runtime-owned spill types:
  - `serviceMissRuntime`;
  - `processMiss`;
  - `oncRpcCallRxSpill`;
  - `sessionInsertMissRuntime`;
  - `sessionLookupMissRuntime`.
- Add counters for each spill type in hardware and in the owning software path.
- Add a small control ABI for runtime-to-kernel hot-slot requests:
  - promote service/process/session;
  - evict or drain service/process/session slot;
  - reject stale generation.

### Phase 2: Service Miss Spill

- Add `PacketDescType.oncRpcServiceMiss` or equivalent.
- Change `OncRpcCallDecoder` miss path from drop to spill descriptor.
- Route service misses through `DmaControlPlugin` to scheduler/runtime RX
  metadata by default.
- Runtime handler executes, queues, rejects, or promotes according to the full
  service registry.
- Add optional kernel validation/control hook only for deployments that need
  kernel-owned registration policy.

### Phase 3: Process Miss Spill

- Add an explicit `Scheduler` PID-miss branch that emits `processMiss`.
- Route request-bearing `processMiss` to scheduler/runtime RX metadata.
- Add kernel/control events for `processSlotPressure`, eviction, and drain.
- Define safe process slot eviction rules.
- Add tests for more registered applications than hardware process slots.

### Phase 4: Session Spill

- Replace session free-slot scan with free bitmap/list.
- Replace entry-0 overwrite with `sessionInsertMissRuntime`.
- Add `sessionLookupMissRuntime` on TX reply miss.
- Decide and implement first policy:
  - runtime notify plus software resend; or
  - lossless pending/retry path with runtime release.
- Add timeout/generation handling.

### Phase 5: RAM-Backed Hot Tables

- Implement `LookupTableRegCam` alias for current behavior.
- Implement BRAM-backed set-associative service table.
- Implement BRAM/URAM-backed session table.
- Implement scheduler PID lookup and drain bitmap split.
- Keep listen/thread tables on `LookupTableRegCam` unless reports show they are
  material.

### Phase 6: Scaling Validation

Run simulation and hardware tests for:

- many services registered, fewer hot service slots;
- many processes registered, fewer hot process slots;
- session table pressure with no entry-0 overwrite;
- neighbor cache pressure;
- bypass slow-path FIFO backpressure;
- scheduler/runtime typed-spill backpressure;
- line-rate hot-path traffic with no spills;
- mixed traffic where spill rate is non-zero but bounded.

Collect Vivado reports for:

- current fully-connected tables;
- service/session RAM tables;
- scheduler bitmap/process lookup split.

## Remaining Decisions

- Exact control ABI between runtime and kernel/resource manager for hot-slot
  promotion, eviction, drain, and generation fencing.
- Should TX session lookup miss be lossless in hardware, or is software resend
  acceptable?
- What replacement policy should hot service/session/process tables use:
  runtime-requested promotion with kernel-managed slots, or a limited hardware
  policy for selected tables?
- When a runtime executes the first spilled request in software, should
  promotion be synchronous for subsequent packets or lazy/asynchronous?
- How much capacity is needed for the kernel control bypass FIFO versus the
  scheduler/runtime typed-spill queues?
