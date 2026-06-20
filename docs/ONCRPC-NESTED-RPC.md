# ONC-RPC Nested Call/Reply Plan

This document plans the mirrored ONC-RPC client path:

- existing server path: `OncRpcCallDecoder` receives a network call and records
  a reply session for `OncRpcReplyEncoder`;
- new nested/client path: `OncRpcCallEncoder` sends a call and records an
  in-flight request for `OncRpcReplyDecoder`;
- new reply receive path: `OncRpcReplyDecoder` receives a reply, maps it back
  to the process/fiber waiting for it, and enqueues a completion into the
  scheduler.

The goal is for software to issue nested RPC calls without remembering where
the eventual reply should be routed.  Software supplies the destination service
identity and UDP/IP addressing for the call; hardware records enough request
state at call ingress to route the reply completion back to the right process.

## Current Baseline

The current server-side path is stateful in hardware:

- `OncRpcCallDecoder` matches incoming calls against a service table keyed by
  `(UDP dport, progNum, progVer, proc)`.
- On a match, it emits `OncRpcCallRxMeta` to `DecoderSink` and therefore to
  `DmaControlPlugin` and `Scheduler`.
- It also emits `newSessionEvent` to `OncRpcReplyEncoder`, recording the
  client source IP/port, server listen port, handler `funcPtr`, and XID.
- `OncRpcReplyEncoder` later receives a host TX reply descriptor containing
  only `(funcPtr, xid, reply payload)`, looks up the recorded session, and
  emits a UDP reply to the original client.

The mirrored files exist but are placeholders:

- `hw/src/lauberhorn/net/oncrpc/OncRpcCallEncoder.scala`
- `hw/src/lauberhorn/net/oncrpc/OncRpcReplyDecoder.scala`

The host-visible data model is also server-only today:

- `HostReqType` has directional server names `oncRpcCallRx` and
  `oncRpcReplyTx`, and planned client names `oncRpcCallTx` and
  `oncRpcReplyRx`.
- `EciHostCtrlInfo` has a shared server bundle for receiving calls and sending
  replies, plus a TODO for client bundles.
- `Scheduler` accepts schedulable server call RX and nested reply RX requests.

## Addressing Model

Nested RPC keeps UDP/IP addressing in the software contract, but the hardware
should not unnecessarily materialize UDP/IP envelopes when the target is local.
In the short-circuit path, UDP/IP fields are metadata used for lookup and
software transparency; they are not packet bytes inserted by `UdpEncoder` and
then removed again by `UdpDecoder`.

That means:

- service-facing software still describes an RPC endpoint as
  `(daddr, dport, progNum, progVer, procNum, codec)`;
- the nested call TX descriptor still carries UDP source port and destination
  IP/port;
- the in-flight reply DB is still keyed with UDP/IP addressing plus XID;
- for real remote traffic, `OncRpcCallEncoder` emits to `UdpEncoder` and the
  normal UDP/IP/Ethernet encoder hierarchy builds the packet exactly once at
  the external network boundary;
- for local traffic, the system should short-circuit at ONC-RPC metadata level
  or UDP metadata level and avoid building a UDP/IP/Ethernet packet only to
  re-decode it.

This keeps software agnostic to service placement while avoiding unnecessary
encoder descent and decoder ascent inside the same FPGA/NIC.

## Sven Runtime Findings

Sven's standalone runtime is under:

`/local/home/pengxu/work-local/lauberhorn/sven-standalone-runtime`

The important reusable ideas are in the userspace runtime:

- incoming calls marked as nested-capable are run in fibers;
- `lauberhorn_call_async(endpoint, payload, payload_len)` allocates an in-flight
  packet-table entry, tags the XID with the entry index, marshals the call, and
  transmits it;
- `lauberhorn_await_any(await_set, completion)` sets the current fiber's wait
  mask and yields if no reply is ready;
- when a reply arrives, `handle_oncrpc_reply` maps XID to packet-table entry,
  unmarshals the reply, stores completion data, signals the waiting fiber, and
  releases the completion to `await_any`.

The mock kernel side is less directly reusable because it builds `sk_buff`s and
uses the Linux network stack.  It does, however, answer two design questions:

- It uses one configured nested RPC source/listen port.  `init_nested_rpc_sock`
  binds a UDP socket to that port; outgoing nested calls use that port as the
  UDP source port.
- It does not establish per-request ephemeral routes.  It tags the XID with a
  process index and uses the fixed nested reply port to identify replies.

For the FPGA implementation, keep the runtime API shape similar but use a
hardware in-flight DB instead of encoding process routing only into XID bits.
The userspace packet-table index remains useful as a completion cookie and can
still be embedded in low XID bits for quick software lookup after delivery.

## Scheduler Interaction

Sven's design depends on two scheduling layers:

1. Hardware/kernel schedules worker threads onto cores and delivers packets to
   a process.
2. Userspace worker runtime multiplexes multiple request handlers as fibers on
   a worker thread.

When a handler waits for a nested reply, the desired behavior is:

- the handler fiber yields;
- the worker thread returns to its local runtime loop;
- the worker can run other ready fibers or call `core_eci_rx` again;
- when the nested reply arrives, hardware schedules a reply completion to the
  same process;
- userspace stores the completion and wakes the waiting fiber.

The current stateless call/reply worker loop in `platform/sw/rt/eci.c` does not
support this naturally, because it handles one request synchronously:

1. `core_eci_rx`
2. unmarshal call
3. call handler
4. marshal reply
5. `core_eci_tx`

It can be adapted, but nested calls require adopting Sven's fiber/event-loop
shape.  The hardware scheduler can remain process-queue based if it treats
incoming server calls and nested reply completions uniformly as `HostReq`s with
a destination PID.  The userspace runtime then decides whether a `HostReq` is a
new server call or a reply completion.

Required scheduler changes:

- allow scheduler enqueue for both `HostReqType.oncRpcCallRx` and
  `HostReqType.oncRpcReplyRx`;
- choose the process lookup key from the active descriptor variant:
  server calls use service-table PID, reply completions use in-flight DB PID;
- keep existing per-process queues and preemption policy;
- consider a future "waiting worker" hint, but do not require it for the first
  implementation because the userspace worker can continue polling RX after a
  fiber yields.

This is sufficient for prompt reply delivery.  After a fiber yields, the worker
returns to the runtime loop, drains any ready fibers, and then calls
`core_eci_rx` again.  At that point the worker is ready from the hardware
scheduler's perspective.  A nested reply completion is just another
schedulable request for the same PID, so the current process-queue scheduler
can deliver it to any worker currently running that process or can preempt an
idle/ready worker for that process using the same mechanism as incoming server
calls.

The reply does not need to return to the original worker/core.  Sven's fiber
runtime already treats the waiting continuation as a runtime object rather than
as a hardware thread identity: the reply handler uses the completion index/XID
to find the packet-table entry, signals the waiting fiber, and the fiber can be
queued by the worker that received the reply.  This matches the intended
continuation model as long as handlers that yield are reentrant with respect to
worker migration, which Sven's fiber design already assumes.

Therefore the first FPGA implementation should keep reply scheduling
PID-directed, not waiter/core-directed:

- hardware routes nested replies to the owning PID and carries a `cookie`;
- userspace maps the `cookie` or XID to the waiting continuation;
- whichever worker receives the reply completion wakes or queues that
  continuation;
- no hardware-visible fiber ID or original-core affinity is required.

The only promptness caveat is the normal one for process-level scheduling: if
all workers for the process are actively running non-yielding computation, the
reply completion queues until a worker becomes ready or the scheduler can
preempt according to existing policy.  A handler blocked in `await` does not
create that problem, because yielding returns the worker to the receive loop.

Validate this design with an RTL simulation or software model with:

- one process and one worker;
- a server handler running as a fiber;
- the handler issuing a nested call TX descriptor and yielding;
- a synthetic nested reply completion delivered to the same PID;
- the worker receiving that completion through the same RX path and waking the
  fiber;
- the fiber resuming and sending the original server reply.

## Hardware Data Structures

Add client-side ONC-RPC metadata:

- `OncRpcCallTxMeta`
  - `rpcId`
  - `pid`
  - `cookie` or packet-table index
  - `xid`
  - `daddr`
  - `sport`
  - `dport`
  - `progNum`
  - `progVer`
  - `proc`
  - inline call data
  - total call argument length

- `OncRpcReplyRxMeta`
  - `packetId`
  - `rpcId`
  - `pid`
  - `cookie`
  - `xid`
  - response source IP
  - response source UDP port
  - response destination UDP port
  - inline reply data
  - UDP payload size

- `OncRpcInFlightReqDef`
  - match key: `(xid, remoteAddr, remotePort, localPort)`
  - value: `(pid, cookie, active)`

Do not key only on XID.  Sven's mockup can route from XID bits because it owns
the kernel datapath and uses one nested port.  The FPGA should tolerate reused
XIDs across different UDP/IP tuples.

### In-Flight Capacity

The in-flight request DB is a hard hardware resource.  If it is full,
`OncRpcCallEncoder` must not silently overwrite an active entry and must not
emit a call whose reply cannot be routed back to software.

The intended contract matches Sven's software-table design:

- async call submission may fail because in-flight tracking is exhausted;
- this is a backpressure condition, not a fatal protocol error;
- the client/runtime must collect existing completions, releasing entries, and
  then retry issuing more calls;
- applications with larger fanout than the in-flight capacity must naturally
  batch their fanout through `await_any`/`await_all` loops.

In Sven's mockup, the limiting resources are the software packet table and the
fixed XID bits reserved for routing.  In the FPGA-assisted design, the limiting
resources are both:

- the userspace packet/completion table, which owns continuation state and
  unmarshalling metadata;
- the FPGA `OncRpcReplyDecoder` in-flight DB, which owns reply routing.

The runtime should acquire both resources before exposing a successful async
handle to the handler:

1. acquire a userspace packet-table slot;
2. submit the nested call descriptor to hardware;
3. hardware reserves an in-flight DB entry before the call is emitted;
4. if hardware reports DB full, validation failure, or another non-accepted
   status, release the userspace slot and return a retryable error such as
   `-EAGAIN` where appropriate.

This implies the TX host path needs an acknowledgement/status for nested call
submission.  The current TX path is mostly fire-and-forget after the host
writes the descriptor; for nested calls, software needs to know whether the
call was actually accepted into the in-flight DB.

Use the TX doorbell read control CL as the status return path.  Today the host
writes the inactive TX control CL and then rings the doorbell by reading the
next control CL.  Extend that read response so it returns a small generic TX
status descriptor for the request being submitted by that doorbell read.  The
read response may stall until the TX datapath has either accepted the request
or determined that it must be rejected.

This should be a general datapath facility, not an ONC-RPC-only special case:

- every TX request can produce a status code;
- simple packet types can report `accepted` once the TX descriptor is captured
  or queued to the downstream TX pipeline;
- nested RPC calls can report `retry/full` if no in-flight DB entry was
  reserved;
- future packet types can report validation errors, unsupported descriptor
  types, queue full, or policy drops;
- software can ignore the status for legacy fire-and-forget operations, but
  runtimes that need correctness can check it.

Suggested status fields:

- `valid`: status belongs to the request submitted by this doorbell read;
- `code`: `accepted`, `retry`, `invalid`, `unsupported`, `dropped`;
- `detail`: protocol-specific small reason, for example in-flight DB full;
- `cookie`: optional echo from the submitted descriptor for correlation.

The status should occupy a fixed field in the returned control CL.  The host
may overwrite that CL with the next TX descriptor after consuming the status,
so the status is a response value, not persistent shared state.

For nested RPC, `core_eci_tx` should read and decode this doorbell response,
then return success or a retryable error to `lauberhorn_call_async`.  Prefer
explicit failure over blocking forever.  Blocking until space appears can
deadlock if the blocked handler is the only runtime context that can collect
the completions needed to free entries.

For remote nested calls, `accepted` should mean that the in-flight DB entry was
reserved and the request was accepted into the hardware TX pipeline.  It does
not need to mean that the Ethernet frame has physically left the MAC, but it
must not be returned for requests known to be unroutable or dropped before
entry into the TX pipeline.  If the IP/neighbor path can reject immediately
because no route or neighbor state is available, that rejection should also be
reported through the same status mechanism and the in-flight entry should be
released.

## Hardware Modules

### `OncRpcCallEncoder`

Implement the client TX path.

- Accept host packets via `EncoderSource`.
- Build the ONC-RPC call header:
  - `xid`
  - `msgType = CALL`
  - `rpcVer = 2`
  - `progNum`
  - `progVer`
  - `proc`
  - null cred/verifier
- Inject the call header plus inline call data.
- Pass overflow argument bytes from the packet buffer.
- Emit `UdpTxMeta` for remote traffic.
- Emit `newRequestEvent` to `OncRpcReplyDecoder` before the call can receive a
  reply.
- If no in-flight DB slot is available, reject the TX request and report a
  retryable failure to software instead of emitting the call.
- Count DB full, lookup/allocation failures, and ARP/neighbor drops where
  observable.

The inline/tail logic should mirror `OncRpcReplyEncoder`.

### `OncRpcReplyDecoder`

Implement the client RX completion path.

- Consume from `UdpDecoder` where `nextProto == oncRpcReply`.
- Extract `OncRpcReplyHeader` and inline reply bytes.
- Validate:
  - `msgType == REPLY`
  - initially only `MSG_ACCEPTED/SUCCESS`
- Lookup in-flight DB by `(xid, ip.saddr, udp.sport, udp.dport)`.
- On hit:
  - emit `OncRpcReplyRxMeta` with PID and cookie;
  - clear or mark inactive the in-flight entry;
  - pass overflow payload bytes to `DecoderSink`.
- On miss:
  - drop payload;
  - increment miss/drop counters.

The DB should have CSR readback/update hooks for debugging and eventual timeout
cleanup.

### Local Short-Circuit

Add a local path before materializing UDP/IP/Ethernet headers.

Preferred first implementation:

- in `OncRpcCallEncoder`, detect `daddr == localIp` and a local ONC-RPC service
  match;
- synthesize the equivalent of `UdpRxMeta` plus call payload into
  `OncRpcCallDecoder`, or factor the service lookup/decode core so local calls
  and network calls share the same service-table logic;
- for local replies, similarly route directly into `OncRpcReplyDecoder` when
  the destination is the local nested reply port;
- preserve UDP/IP tuple fields in metadata so the software-visible behavior and
  in-flight lookup stay identical.

Avoid relying on Ethernet hairpin as the base design.  Current TX uses the IP
neighbor table and Ethernet encoder, while RX only sees frames that return from
the MAC path.  Same-host traffic would require CMAC loopback or external switch
reflection, plus appropriate ARP/neighbor state.  That is useful as a test mode
but too fragile as the primary local RPC mechanism.

## Host and DMA Changes

Extend host-visible request types.  Suggested names:

- `oncRpcCallRx`: incoming server call
- `oncRpcReplyTx`: outgoing server reply
- `oncRpcCallTx`: outgoing nested/client call
- `oncRpcReplyRx`: incoming nested/client reply

The current enum names can be preserved if ABI churn is a concern, but the
direction should be explicit in Scala/C helpers to avoid more overloading.

Changes:

- `hw/src/lauberhorn/host/package.scala`
  - add client TX/RX bundles;
  - add union variants.
- `hw/src/lauberhorn/net/package.scala`
  - add `PacketDescData` variants for call TX and reply RX;
  - update `PacketDescType.selectData`, payload length, `packetId`, `rpcId`.
- `hw/src/lauberhorn/DmaControlPlugin.scala`
  - RX: translate `OncRpcReplyRxMeta` into host reply-completion descriptor;
  - TX: translate host nested-call descriptor into `OncRpcCallTxMeta`;
  - apply inline/tail DMA length rules for call TX, analogous to reply TX.
- `hw/src/lauberhorn/host/eci/EciHostCtrlInfo.scala`
  - add client call and client reply bundles;
  - add a generic TX status bundle for the doorbell-read response;
  - update `packFrom` and `unpackTo`;
  - generate mackerel datatypes.
- `hw/src/lauberhorn/host/eci/DcsTxAxiRouter.scala`
  - adjust invalidation length for `oncRpcCallTx` because host-visible length
    includes inline bytes, while DMA tail length excludes them;
  - return a generic TX status descriptor on the TX doorbell read control CL;
  - allow that read to wait for downstream TX accept/reject status.
- `hw/src/lauberhorn/host/eci/EciDecoupledRxTxProtocol.scala` and
  `DmaControlPlugin.scala`
  - plumb encoder/DMA accept or rejection status back to the per-core TX
    router so it can complete the doorbell read response.

## Software Runtime Changes

Adopt Sven's service-facing shape:

```c
typedef struct {
    const char *daddr;
    uint16_t dport;
    uint32_t prog_num;
    uint32_t prog_ver;
    uint32_t proc_num;
    rpc_codec_t *rpc_codec;
} lauberhorn_rpc_endpoint_t;

int lauberhorn_call_async(lauberhorn_rpc_endpoint_t *ep,
                          void *payload,
                          size_t payload_len);

bool lauberhorn_await_any(await_set_t *set,
                          lauberhorn_completion_t *result);
```

Recommended adaptations:

- add an explicit local/source port configuration at context init or process
  registration time, matching Sven's single nested RPC source port idea;
- keep the returned async handle as a packet-table index;
- store `(codec, xid, endpoint, state, waiting fiber)` in a runtime table;
- use a hardware-visible cookie equal to the table index;
- encode the cookie into XID low bits as an optimization, not as the only
  routing mechanism;
- treat hardware in-flight DB exhaustion like software packet-table exhaustion:
  return a retryable error from `lauberhorn_call_async`, let the handler collect
  already issued calls, then retry;
- provide `await_all` later, but `await_any` is enough for the first matrix
  multiplication demo.

Runtime worker loop:

- initialize a fiber scheduler per worker;
- run ready fibers before blocking on `core_eci_rx`;
- on server call:
  - if service is fiber-enabled, allocate a fiber and enqueue it;
  - otherwise allow synchronous legacy handling;
- on nested reply:
  - use cookie/XID to find the packet-table entry;
  - unmarshal response with the stored codec;
  - mark completion ready;
  - wake the waiting fiber.

Core ECI helpers:

- `core_eci_tx` must support `TY_ONCRPC_CALL_TX`;
- `core_eci_tx` must decode the generic TX status returned by the doorbell
  read control CL and return success, retry, or error to the runtime;
- `core_eci_rx` must support `TY_ONCRPC_REPLY_RX`;
- marshal call payloads with `marshal_call`;
- unmarshal reply payloads with `unmarshal_resp`.

Kernel module:

- program UDP listen table entries for the nested reply source port as
  `listen_onc_rpc_reply`;
- initialize generated devices for `OncRpcCallEncoder` and
  `OncRpcReplyDecoder`;
- optionally expose ioctls for selecting the nested local/source port per
  process;
- do not require per-request binds or ephemeral software routes.

Sven's runtime therefore maps to a fixed local bind model, not an implicit
"outgoing request establishes an ephemeral route" model.  The FPGA design can
keep the fixed source-port convention and use the in-flight hardware DB for the
additional routing precision that the mock kernel encoded into XID bits.

## Simulation Plan

Add `hw/test/src/lauberhorn/host/eci/OncRpcNestedSim.scala`, parallel to
`OncRpcSim`.

Core tests:

- `tx-call-inline-only-skips-dma`
  - host sends nested call with payload <= inline size;
  - verify emitted ONC-RPC call packet for remote path;
  - verify no packet-buffer DMA read.
- `tx-call-tail`
  - payload exceeds inline size;
  - verify DMA tail and packet body.
- `reply-rx-matches-inflight`
  - send call, inject matching reply;
  - verify scheduler delivers reply completion to the PID and cookie.
- `reply-rx-miss-drops`
  - wrong XID, wrong source IP, wrong source port, wrong destination port.
- `parallel-inflight`
  - multiple outstanding calls with same XID on different UDP/IP tuples;
  - multiple outstanding calls to same tuple with different XIDs.
- `inflight-full-backpressure`
  - fill the hardware in-flight DB;
  - verify the next nested call is rejected before network emission;
  - verify the doorbell read control CL returns a retry/full TX status;
  - verify software can collect one completion, retry, and then issue another
    call.
- `tx-status-generic`
  - verify normal TX packet types return `accepted`;
  - verify malformed or unsupported descriptors return a non-accepted status
    without relying on ONC-RPC-specific logic.
- `scheduler-mixed`
  - interleave incoming server calls and nested reply completions for one PID.
- `fiber-wait-model`
  - model a worker that receives a call, sends nested call TX, yields, receives
    reply completion, then sends original reply.
- `local-shortcircuit`
  - local nested call reaches service without external TX;
  - reply reaches waiting fiber without external RX;
  - metadata still contains the expected UDP/IP tuple.
- `db-full-and-timeout`
  - verify in-flight DB full policy and CSR cleanup/readback.

## Demo Application

Build a matrix multiplication demo using nested calls:

- `adder` service: adds two integers or matrix scalar values.
- `multiplier` service: multiplies two integers or matrix scalar values.
- `matmul` service:
  - receives matrices;
  - issues multiply calls for each `(i, k, j)` term;
  - awaits completions with `await_any`;
  - reduces partial products with parallel adder calls;
  - returns the resulting matrix.

The first demo can use remote `adder` and `multiplier` endpoints.  After local
short-circuit lands, run the same binary with local endpoint addresses to prove
software does not need to know whether a service is local or remote.

## Suggested Implementation Order

1. Validate the scheduler/fiber wait model before building the full datapath.
2. Rename or wrap host request types to make RX/TX direction explicit.
3. Add client-side host/ECI descriptors and simulation descriptor helpers.
4. Add generic TX status return through the doorbell read control CL, including
   nested-call in-flight DB full.
5. Implement `OncRpcReplyDecoder` DB and RX completion path.
6. Implement `OncRpcCallEncoder` remote TX path and in-flight allocation.
7. Extend scheduler to accept reply completions.
8. Add `OncRpcNestedSim` remote-path tests.
9. Port Sven's fiber runtime shape into `sw/rt`.
10. Add kernel/runtime configuration for nested source port.
11. Implement local short-circuit without materializing UDP/IP/Ethernet.
12. Add matrix multiplication demo and end-to-end tests.
