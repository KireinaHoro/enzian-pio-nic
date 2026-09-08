# Software runtime and datapath

Scope: source inspection, 2026-09-08. Current usable implementation is the ECI/AArch64 stack; backend-neutral API naming is an intent, not evidence of PCIe support.

## Source map

| Layer | Entry points | Responsibility |
|---|---|---|
| Public API | [`lauberhorn.h`](../../sw/include/lauberhorn.h), [`oncrpc.h`](../../sw/include/lauberhorn/oncrpc.h) | Context, service registration, pthread workers, XDR schemas |
| Runtime | [`eci.c`](../../sw/rt/eci.c), [`oncrpc.c`](../../sw/rt/oncrpc.c) | Control syscalls, worker lifecycle, synchronous RPC dispatch, serialization, CSV tracing |
| Shared transport | [`core.h`](../../sw/core/eci/core.h), [`core-common.h`](../../sw/core/core-common.h) | ECI RX/TX descriptor and payload transfers, parity, barriers |
| Kernel control | [`chrdev.c`](../../sw/kmod/chrdev.c), [`ioctl.h`](../../sw/kmod/ioctl.h) | Process/service registration and memory mappings |
| Kernel fallback | [`bypass.c`](../../sw/kmod/bypass.c), [`cmac.c`](../../sw/kmod/cmac.c) | Linux netdev/NAPI bypass, neighbor handling, MAC control |

`sw/rt/Makefile` builds `liblauberhorn.so` with ThunderX/AArch64 flags and generated hardware/register headers. The older `sw/README.md` contains planned API/library naming; use source and current flake outputs to establish exact artifacts. `sw/core/pcie/core.h` contains legacy `pionic` names and a literal unfinished TX doorbell expression.

## Runtime control and hot path

`lauberhorn_init` opens `/dev/lauberhorn`, maps a shared parity page and installs SIGINT handling. Registering a service installs an opaque userspace handler-record pointer in hardware and attempts UDP portmapper registration. Each pthread maps its own datapath region, allocates one request object per registered schema, and invokes its initialization callback after hardware first schedules it.

The worker loop is synchronous: `core_eci_rx_traced` → XDR decode → handler → XDR encode → `core_eci_tx`. Hardware returns the opaque pointer and XID; the runtime dereferences the pointer to select the handler and schema. The reply descriptor echoes pointer/XID so hardware can recover the client session/address. Malformed serialization is logged/skipped; there is no automatic application error reply.

RX/TX each use two alternating 128-byte control cachelines plus overflow lines. Payload bytes are copied into runtime buffers; this is not a general application zero-copy API. The control line also has payload space at offset `0x40`. Keep generated descriptor layout and software inline-byte constants synchronized.

Userspace enters a READY/BUSY critical section around each transport operation, uses ARM `dmb sy` barriers, and shares the next RX/TX parity bytes with Linux. TX writes the current line then reads the opposite line as a doorbell. `core_eci_tx` returns `void` and discards the read value: generic accepted/retry/error status is not implemented. Kernel bypass uses the same transport code with critical-section operations disabled.

## Tracing and shutdown

- `LAUBERHORN_SERVER_TRACE_CSV` enables per-request monotonic timestamps around RX, unmarshal, handler, marshal and TX. `ADDER_SERVER_TRACE_CSV` is a legacy fallback. Schemas can supply request-ID and correctness callbacks. `LAUBERHORN_RT_VERBOSE` enables runtime diagnostics.
- CSV output is line-buffered and serialized by a global mutex, so enabled tracing changes execution cost. See [`development/tracing.md`](../development/tracing.md) and [`research/latency-measurement.md`](../research/latency-measurement.md) before interpreting timestamps.
- SIGINT clears a global run flag and requests wakeup of parked workers. Main joins workers, unmaps their datapaths, deregisters services and closes the context. This is the demonstrated lifecycle, not a generalized cancellation API.

## Pending work and review hazards

- Nested/client descriptor packing and reply unpacking already exist in `core.h`; the worker loop still asserts every received request is `TY_ONCRPC_CALL_RX`. Fiber scheduling, public async/await APIs, acceptance status, and completion dispatch remain planned; see [`software/nested-rpc.md`](../software/nested-rpc.md).
- Runtime globals (`fd`, run flag, monotonically increasing schema/worker indices) mean independent contexts, reinitialization, dynamic registration and concurrent control operations are not established supported behavior.
- Source-review concern: runtime deregistration passes `int *`, while the kernel ioctl expects `lauberhorn_dereg_srv_t` and writes back `func_ptr`. Repair/validate this ABI before relying on teardown robustness.
- Allocation failure paths, service capacity checks, and XDR-owned variable-length allocation cleanup need hardening. Request free currently performs only `free(msg)`; the loop does not perform an XDR free pass.
