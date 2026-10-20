# Lookup table scaling and spill design

This is a proposal for software-backed hot tables and typed slow paths. General
RPC spill is not implemented. The [packet-processing contract](../hardware/packet-processing.md#lookup-and-capacity-behavior)
describes current admission, rejection and drop behavior. Proposed block names,
descriptors and policies below are design sketches, not an available ABI.

Incoming reply contexts and outgoing nested-request reservations are separate
resources. Both reject capacity failures without replacing live operations.
Any spill design must preserve their identity, ownership and completion rules;
it cannot start from the former `(funcPtr, xid)` server-session table.

## Motivation

The current [`LookupTable`](../../deps/blocks/blocks/src/jsteward/blocks/misc/LookupTable.scala) implementation is a fully-connected associative
table: each entry is a register, and each lookup port builds a comparator for
every entry plus a priority/value mux over the full table. This gives a three-stage
pipelined lookup; backpressure can extend elapsed latency. Table size and
lookup-port count are expensive in LUTs, routing and timing.

This leaks into system-level capacity. Today the hardware table sizes are also
the admission limits:

- `NUM_SERVICES = 8`: max hardware-visible ONC-RPC services.
- `NUM_PROCS = 16`: max scheduler-visible applications/processes.
- `NUM_SESSIONS = 16`: bounds each of the distinct incoming reply-context and
  outgoing nested-request tables.
- `NUM_NEIGHBOR_ENTRIES = 8`: max cached IPv4 neighbors.

The [current behavior table](../hardware/packet-processing.md#lookup-and-capacity-behavior)
distinguishes registration capacity, per-request admission and neighbor caching.

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

## Completion capacity and delivery

A hardware slot can retire while its completion is still queued for software.
The runtime bounds live outgoing calls, including ready but unconsumed futures,
to `NUM_SESSIONS`; Dandelion retries local-loopback `SYSTEM_ERR` as busy. See the
[programming-model contract](../software/programming-model.md#loopback-composition-receive-side-backpressure)
and [finite evaluation](../research/dandelion-measurement.md#wide-fan-out-and-temporary-udp-backpressure--october-8).

Spill must account for ownership through software consumption, not just hardware
retirement. Larger tables or retries after an indeterminate outcome do not by
themselves guarantee delivery. This proposal must define admission and completion
queue capacity together before lifting the existing bound.

## Existing slow paths and TCP reference

The implemented [kernel bypass path](../hardware/packet-processing.md#kernel-bypass-and-neighbor-misses)
handles raw packets and packet-bearing neighbor misses. It supplies a useful
allocation/acknowledgment mechanism for kernel-owned exceptions. Application-owned
spills need separate typed requests and a real software delivery route.

The unmerged [TCP project](../research/tcp.md) demonstrates typed exceptions,
packet lifetime and software ownership in test infrastructure. Its exception
route is the bypass datapath; the production kernel/runtime does not implement
that TCP ABI. It is not evidence of an existing per-application spill path.
Use its ownership lessons, but design and implement the RPC consumer explicitly.

## Two Slow-Path Planes

The proposal separates two ownership planes. Only raw packet/neighbor bypass
exists today; hot-table management and runtime spill below require new plumbing:

1. Kernel/control bypass.

   This would extend the existing `BypassCmdSink` path for work owned by the
   kernel, Linux network stack or hardware resource manager:

   - raw packet bypass and netdev injection;
   - ARP and neighbor-cache resolution;
   - global hot-table admission, eviction, and generation management;
   - security/isolation validation before an app runtime can own a request.

2. Runtime/application bypass.

   This would be a typed host-request path to the scheduler/runtime, not
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

Use this proposed class when the eventual owner is userspace/runtime state.
Preserve enough metadata and bytes for software to continue correctly. TCP's
recoverable exception payload is a useful ownership example, but its current
route is the bypass datapath; direct runtime delivery must be implemented
separately for this RPC spill design.

## Proposed request types and routes

The existing ONC-RPC names already distinguish RX/TX directions. Preserve those
contracts, native UDP and execution-control descriptors when extending the ABI.
The following names are placeholders; exact enum values and descriptor packing
must be designed with generated headers, kernel decode and runtime dispatch.
Do not add unused aliases or handler stubs before selecting a complete path.

| Proposed request | Owner and purpose |
| --- | --- |
| `serviceMissRuntime`, `processMiss`, `oncRpcCallRxSpill` | Runtime-owned request delivery, with packet bytes and a resolvable application identity |
| `serviceMissValidate`, `processSlotPressure` | Kernel validation and global slot admission/drain |
| `sessionInsertMissRuntime`, `sessionLookupMissRuntime` | Runtime reply-routing fallback; “session” here denotes a proposed logical request context, not the removed server-session lookup |
| `sessionSlotPressure`, `tablePromote`, `tableEvict` | Global resource management with generation/owner checks |

Kernel-owned variants need matching `TY_*` decode and NAPI handlers; runtime-owned
variants need runtime decode and dispatch. The route must be explicit in hardware.
A descriptor name does not supply a delivery channel, especially when the target
process has no hardware slot. Promotion/eviction also needs an acknowledged
host-to-hardware control path.

## Proposed descriptor payloads

These sketches describe candidate `HostReqData` union members. They require a
selected delivery and lifetime contract before ABI implementation.

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

### Reply-context spill descriptors

Incoming request routing currently uses an owner-checked opaque slot/generation
token, not a `(funcPtr, xid)` lookup. Proposed `sessionInsertMissRuntime`,
`sessionLookupMissRuntime` and `sessionSlotPressure` must refer to a logical
request identity whose lifetime survives movement between hardware and software.
They must not reinterpret a stale hardware token as a valid software key.

An admission spill needs the wire XID, client IPv4/UDP tuple, local port,
validated application identity, parsed call metadata and retained request bytes.
Software either accepts ownership and creates its own reply-routing context or
rejects the call. A handler must not become visible before that ownership transfer
is committed. Preserve repeated-XID independence: wire XID alone is not unique.

A reply lookup spill needs the logical request/context identity, owner/generation,
reply length, and explicit ownership of any consumed descriptor or payload.
Two policies remain possible:

- Notify software and require resend, with a result that unambiguously says the
  reply was not accepted or transmitted.
- Retain the reply in a bounded pending queue until software resolves routing or
  cancels it. This requires backpressure and a release contract.

Neither policy permits falling back on stale tokens or transmitting twice after
an ambiguous result. Define when routing metadata is captured, when the context
retires, and who owns bytes during output backpressure.

Slot-pressure control messages need table/context identity, owner, generation,
operation (promote, evict, drain or invalidate) and reason. Kernel/resource
management must validate operations and account for outstanding packets and
callbacks before reuse. Outgoing nested-call reservations additionally require
cookie/tuple identity, timeout/cancellation and late-reply handling; they are not
interchangeable with incoming reply contexts.

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

### Scheduler process table

`Scheduler.procDb` maps PID to a process queue. Unmatched, disabled and full-queue
requests enter the explicit discard path; packet storage and incoming reply
contexts are released through DMA cleanup. General software spill is absent.

The proposed process cache separates the full registry from active hardware
slots. Replace selected rejection cases with typed `processMiss` delivery while
preserving cleanup on rejection. The runtime may execute or queue a request in
software, while kernel control manages promotion, isolation and shared capacity.
A runtime without a hardware process slot needs a defined host delivery route;
sending it back through the same missing `procDb` entry would not make progress.

Eviction requires no assigned worker and either an empty queue or transfer of all
queued requests to software. Track outstanding contexts, accepted responses and
generations as well as queue occupancy. Promotion must commit before reinjection.

For larger tables, use an indexed/hashed PID lookup with BRAM queue metadata and
compact bitmaps for enabled/hot, nonempty, assigned and draining/promoting state.
Candidate selection should use those bitmaps rather than scanning a large RAM
on every scheduling decision.

### Incoming ONC-RPC reply contexts

`OncRpcReplyEncoder.contexts` reserves one context per admitted invocation.
Full capacity rejects the call with a bounded, best-effort `SYSTEM_ERR` path;
no live context is overwritten. Reply lookup validates slot, generation and
owner, then captures routing and retires the context. See
[reply contexts](../software/reply-contexts.md) and the
[current behavior](../hardware/packet-processing.md#lookup-and-capacity-behavior).

A RAM-backed indexed context store is a candidate for tens/hundreds of entries,
with URAM considered only at larger scales. Preserve atomic allocation,
owner/generation checks, output capture and retirement when changing storage.
A free bitmap/list can separate allocation from lookup. Software-backed contexts
require the logical-identity and byte-ownership contract above; adding generation
bits alone does not define migration or a miss policy.

### Outgoing nested-request reservations

`OncRpcReplyDecoder.requestDb` matches `(xid, remoteAddr, remotePort, localPort)`
to PID/cookie. Admission precedes TX DMA/encoding. An exact live retransmission
retains its reservation; conflicting identity or full capacity rejects admission
without overwriting an active entry. The free-slot lookup remains always active.

A proposed spill/cache extension must preserve completion routing, cancellation,
late-reply rejection and the lifetime through software consumption. Define an
explicit rejection/retry or software-owned path before increasing concurrency.
This table needs its own capacity and backpressure evaluation, independently of
incoming reply-context pressure.

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

### ECI ownership table

The physical-worker decoder and indexed ownership table have one entry per
worker; they are not an associative thread router or an initial spill target.
Keep address authorization independent of service/process cache replacement.
See the [ECI ownership contract](../hardware/eci-implementation.md#address-ownership-and-integration).

## Lookup Table Implementation Plan

Implement RAM-backed lookup as new blocks in `deps/blocks`, not as an immediate
replacement for every `LookupTable` user.

### New Block Variants

1. `LookupTableRegCam`

   - current behavior;
   - explicit name for small fully-connected tables;
   - three-stage pipelined lookup;
   - useful for `listenDb`.

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
   - suitable for `serviceDb`, outgoing request lookup and process PID lookup.
     Incoming reply contexts are already addressed by token slot.

4. `LookupTableFreeList`

   - helper for tables with allocation semantics;
   - free bitmap or FIFO;
   - removes always-on `lookupFree` ports.

### Interface Requirements

The existing `makePort` API exposes streams and nominal lookup latency; it does
not define spill or replacement outcomes.
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

Potential benefits to measure:

- fewer wide priority muxes;
- lower fanout from storage registers;
- fewer duplicated comparators per lookup port;
- table capacity no longer increases placement pressure linearly per port.

## Integration dependencies and evaluation

A first spill path needs a complete owner, delivery route, packet-lifetime rule
and acknowledged promotion/rejection result. Define those before freezing the
ABI or implementing RAM tables. Regenerate matched Mackerel/C headers and wire
both kernel-owned and runtime-owned consumers for the selected path. Service
misses can be evaluated separately from process-slot eviction; neither implies
reply-context spill is solved.

Evaluate storage changes independently of spill semantics, using the same
workload and capacity. Compare the register implementation, RAM service/context
storage, and scheduler PID/bitmap split with the utilization/timing criteria
above. Any routed experiment remains subject to the existing
[physical-implementation pause](../hardware/physical-implementation.md#scope-and-interpretation).

Correctness and performance cases must include:

- More registered services/processes than hot slots, including a miss for a
  runtime that has no hardware slot.
- Incoming contexts and outgoing requests at capacity without replacing live
  identities; repeated XIDs, stale tokens, cancellation and late replies.
- Promotion/eviction while packets, replies or software completions are pending.
- Neighbor pressure and simultaneous kernel-control/runtime-payload backpressure.
- Line-rate hot traffic, mixed hit/spill traffic and bounded spill overload;
  measure loss, rejection, throughput and latency separately.

Use [validation](../development/validation.md) for existing build/test flows.
These are proposed evaluation cases, not reported test outcomes.

## Remaining Decisions

- How does a service miss identify and authorize its runtime owner, and how is
  the request delivered if that runtime has no hardware process slot?

- Exact control ABI between runtime and kernel/resource manager for hot-slot
  promotion, eviction, drain, and generation fencing.
- Should a valid software-owned reply-context miss retain bytes in hardware,
  or return an unambiguous result requiring software resend?
- What replacement policy should hot service/context/process tables use:
  runtime-requested promotion with kernel-managed slots, or a limited hardware
  policy for selected tables?
- When a runtime executes the first spilled request in software, should
  promotion be synchronous for subsequent packets or lazy/asynchronous?
- How much capacity is needed for the kernel control bypass FIFO versus the
  scheduler/runtime typed-spill queues?
