# Application programming model

Scope: source inspection, 2026-09-08. Follow the synchronous server model below for the current runtime. Async nested RPC is a planned extension.

## Current contract

An application is a Linux process registering ONC-RPC services with `(UDP port, program, version, procedure)`. A service supplies an opaque data pointer, a handler, and an XDR schema. The NIC performs network/protocol/service demultiplexing; userspace decodes argument objects and runs ordinary C code. Linux/FPGA schedule the process's registered pthread workers.

Canonical example: [`sw/apps/microbenchmarks/main.c`](../../sw/apps/microbenchmarks/main.c); wire types/XDR are in [`client/bench.h`](../../sw/apps/microbenchmarks/client/bench.h) and [`client/bench_xdr.c`](../../sw/apps/microbenchmarks/client/bench_xdr.c). The executable accepts `add|mul [workers=4] [server_trace.csv]`; services use UDP 12345 and 12346 respectively. Match worker count to the generated hardware capacity.

## Lifecycle

1. Initialize `lauberhorn_t` on the main/process-leader thread with `lauberhorn_init`.
2. Construct `lauberhorn_schema_t` with `call_size`, `call_func`, and `resp_func`; keep it alive through worker termination. Optional trace callbacks extract request IDs and check responses.
3. Register services with `lauberhorn_reg_srv`. Finish registration before starting workers: request buffers are allocated once from the registered schema list.
4. Create workers with `lauberhorn_create_worker(ctx, init, fini)`; supply callbacks (`noop_cb` is available), not NULL. Callbacks receive a worker ID, not a CPU ID. Initialization happens after the datapath `mmap` is scheduled by hardware, potentially only when traffic arrives.
5. Join workers. The demonstrated termination mechanism is SIGINT. Deregister services after joins, then call `lauberhorn_fini`.

Public declarations: [`lauberhorn.h`](../../sw/include/lauberhorn.h), [`lauberhorn/oncrpc.h`](../../sw/include/lauberhorn/oncrpc.h). Implementation limitations: [runtime.md](runtime.md).

## Handler semantics

```c
lauberhorn_msg_t handler(void *app_data, lauberhorn_msg_t request, int xid);
```

The request points to a per-worker decoded object reused on subsequent calls. The returned response pointer must remain valid until the runtime immediately serializes it. A stack-local response is invalid after return. The example uses `thread_local` response storage. Do not retain request pointers without copying/owning their data.

Multiple workers can call the same handler concurrently and share `app_data`; synchronize shared mutable state. Worker IDs are stable runtime indices, while CPU placement can change through hardware/Linux dispatch. There is no public request yield, continuation, async call, explicit response-send, or worker-stop API today. Blocking or long computation occupies that worker; do not assume fairness or forced recovery is complete.

The runtime handles RPC argument/result serialization, not arbitrary transport selection. Existing clients use UDP ONC-RPC; adding a new wire protocol requires hardware decoder/encoder and host/runtime changes. Variable-length XDR objects need explicit ownership review because current runtime cleanup only frees the outer request allocation.

## Intended extension: nested RPC and fibers

[`software/nested-rpc.md`](../software/nested-rpc.md) describes the not-yet-integrated student runtime in `../sven-dandelion/platform` and the intended `call_async` / `await_any` model. Handler fibers yield while waiting, allowing worker threads to continue dispatching other calls/completions. Hardware should route replies by owning PID plus cookie; userspace locates the continuation, potentially on another worker. No hardware fiber ID or original-core affinity is required by that plan.

The plan also specifies finite in-flight capacity with retryable submission failure, hardware TX acceptance status, a configured nested source port, and local RPC short-circuiting at metadata level. Descriptor support alone does not implement this API. Do not copy paths to the standalone sibling repository into portable build instructions.

Questions to settle before expanding the API: supported blocking/syscall behavior in handlers; whether yielding fibers may migrate and use thread-local state; timeout/cancellation/retry semantics; and the desired ownership model for variable-length requests and responses.
