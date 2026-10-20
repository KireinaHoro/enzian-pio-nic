# Runtime tracing and export

## Build and enable tracing

`nix/software/runtime.nix` accepts `tracing = false` (`LH_ENABLE_TRACING=0`).
This build excludes server tracing and PMU objects, request/fiber trace storage,
probe calls and export threads. Its pkg-config Cflags propagate the same define
into applications, whose public annotation macros do not evaluate arguments.
Runtime and applications must use the same compiled variant. Environment variables
cannot enable tracing in this build. The default build retains optional tracing
for existing applications and acceptance tests. `runtimeUninstrumented` and
`microbenchmarksUninstrumented` expose the disabled variant through Nix.

`LAUBERHORN_SERVER_TRACE_CSV` enables runtime-owned receive/decode/handler/encode/TX
probes. Application code supplies its correlation ID through
`<lauberhorn/trace.h>`; no wire XID or client-address ABI is added. Rows follow
deferred/forwarded ownership and record actual Linux RX/TX CPUs. See
[latency measurement](../research/latency-measurement.md#server-csv) for the
schema, nested timing semantics and plotting restrictions. Runtime collection is disabled
unless enabled by environment; compiled probe support is a separate choice.

## Probe boundaries

`core_eci_tx_commit_traced` optionally records `server_tx_doorbell_ns` after
control descriptor writes, placement, ordering barriers and parity update,
immediately before loading the opposite control CL. The timestamp is held
locally and stored to the trace row after the load; there is no PMU sampling at
this probe. TX-enter → doorbell-probe measures preparation on the CPU clock.
Doorbell-probe → FPGA `EciTxCommitRead` measures clock-correlated request arrival,
including the timestamp callback's return tail. It is not the full blocking
load duration: control invalidation/forwarding and acknowledgement follow the
FPGA event. Clock-offset uncertainty, probe perturbation and coherence queueing
must accompany any comparison with a one-way ECI estimate. Historical rows
without this probe retain the combined preparation/arrival attribution.

## Buffer sizing and export

Tracing uses preallocated, prefaulted per-thread append-only buffers;
`LAUBERHORN_TRACE_CAPACITY` sets rows per thread (default 16384). The request
path copies a fixed-size record and publishes its count; it does no CSV
formatting, locking, allocation, or file I/O. Timestamp probes still have cost.
With borrowed RX, receive exit marks descriptor/view acquisition; payload cache
misses occur during decoding or application access. Compare these stages with
that boundary in mind when using older copied-RX traces.
Initialize tracing before creating application threads: the opt-in tracing mode
reserves and blocks SIGUSR2, inherited by workers. A control thread waits for it.
After **all measured traffic has drained**, send `kill -USR2 SERVER_PID` once.
Wait for `CSV.status.json`, require `loss_free: true`, and verify the CSV count
and expected request IDs. Both CSV and completion manifest appear only at export;
existing output files are never overwritten. Export is a single snapshot for the
process lifetime, not a rearm operation. Size the buffer for all warmups and
measurement phases; duplicate request IDs across phases need external phase
boundaries. Overflow invalidates the capture. SIGTERM/SIGINT still use the
unsupported-exit/fencing path and must not be used to flush traces.

## Runtime events and correlation

`LAUBERHORN_EVENT_TRACE_CSV`, together with the request CSV setting, enables
an additional buffered stream for entry returns, fiber resume/suspend, nested
submissions and every delivered call/completion. Applications can annotate
execution/collection phases with `lauberhorn_trace_event`. Events retain the
request-context token, application correlation ID, TID, actual CPU and raw
monotonic timestamp. Capacity is 32 events per configured request slot per thread;
overflow is reported separately in `EVENT_CSV.status.json`. Wait for both export
manifests and require both to be loss-free. Entry-handler timestamps are retained
separately from deferred-handler timestamps. Fiber-active and child spans overlap
and must not be summed as independent end-to-end components.

Dandelion optionally carries a correlation ID in its BSON arguments to connect
each submitted child with its received request. The ordinary untagged envelope
is unchanged; tagged payload size and event-probe cost must be included in
measurement overhead controls. FPGA and CPU clocks still require an explicit
alignment with stated uncertainty; client RTT is a duration on another host.

## Native UDP correlation

Native UDP trace metadata version 1 exposes the wrapping RX `HostMsgID` in
`packet->trace_ref` (valid bit 15, ID bits 5:0). Pass that scalar as `related_rx`
to `lauberhorn_udp_send_buffer` for each associated response, or zero for an
unrelated send. The FPGA records it at TX submission; it grants no access and
retains no packet. Use matching generated headers and hardware. Narrow IDs need
lifetime/order validation in analysis, not global-ID joins. See the
[memcached breakdown follow-up](../research/memcached.md#rxtx-trace-correlation-and-rtt-breakdown--active-october-10-follow-up).
