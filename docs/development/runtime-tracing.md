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
[latency measurement](runtime-tracing.md#server-csv) for the
schema, nested timing semantics and plotting restrictions. Runtime collection is disabled
unless enabled by environment; compiled probe support is a separate choice.

## Probe boundaries

`core_eci_tx_commit_traced` optionally records `server_tx_publish_ns` after
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
path takes the next slot and writes directly into it; completion publishes the
slot without copying it, including after migration or deferred execution. Unused
fields are zeroed once at startup. Exhaustion aborts the capture. It does no CSV
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
[memcached breakdown follow-up](../applications/memcached.md#request-tracing-and-latency-analysis).

## Server CSV

Application IDs join completed requests; opaque contexts do not expose wire XIDs.
Rows retain worker/TID and actual RX/TX CPU identities. `execution_kind` records
deferred (1) and forwarded (2) execution. Deferred active time sums scheduled
fiber spans, while wall time includes waits. Killed requests without a terminal
reply have no terminal row; export completeness must reflect the intended scope.

Version-5 RX-enter is taken only on the first received record after activation
and can include pre-arrival blocking; RX-unblock follows the ordered control
read, and RX-exit marks descriptor/view acquisition. Decode, handler and encode
pairs describe their runtime stages. Version 4 ends at `tx_publish` before the
blocking control read. Broad codec spans, duplicate entry aliases and TX-return
probes are removed from current acquisition; archived schemas keep their original
interpretation.
`request_bytes` is the deployed runtime's recorded buffer extent, not a universal
wire payload size; historical rows included a 48-byte inline extent.

The legacy inline plotter rejects duplicate IDs, non-inline rows and unequal
RX/TX CPUs. Its `--worker-cpu-base` maps CPU IDs to datapaths; runtime worker IDs
are not hardware datapath IDs. See [hardware correlation](../hardware/tracing.md).

## Activation and inter-packet gaps

The [RPC instrumentation inventory](rpc-breakdown.md#cold-activation-paths-and-timestamps)
defines the exact activation stamps, cooperative cross-domain path, outgoing
entry probes, Caladan parked/preempted paths, and their placement in a cold RTT
partition. This section describes the existing LH collection and analysis tools.

The `lauberhorn:activation` kernel event contains **one buffered record per
successful traced activation**, rather than one event at every boundary. It is
disabled by default. Idle activation takes nine `ktime_get_raw_ns()` samples;
ordinary cooperative activation omits the IRQ and dispatcher samples; the runtime adds two `CLOCK_MONOTONIC_RAW` samples when server CSV
tracing is enabled. Kernel samples are stored in private per-thread state and
emitted just before the yield handler returns. No hardware or public activation
ABI fields change. The redundant legacy `worker_switch` event is removed; the activation record
carries target and outgoing identity.
Cooperative activation also records outgoing TID and kernel `yield_enter`.
The optional runtime event CSV records DESCHED observation (20) and userspace
yield entry (21); `activation_breakdown.py --events` joins them to that outgoing
identity and adds their disjoint entry spans.

| Partition | Exact boundaries / included work |
| --- | --- |
| `irq_to_dispatch` | Idle-kick handler timestamp to dispatcher returning from its wait; excludes exception entry before the handler |
| `dispatch_to_mmio` | Dispatcher wait return to just before `DeschedInfo`; includes reader mutex and notification read |
| `desched_info_mmio` | Blocking `DeschedInfo` read start to completion |
| `claim_wait` | Snapshot completion to thread claim; includes validation, claim lock and any wait for a thread |
| `affinity_setup` | Around `set_cpus_allowed_ptr` |
| `activation_publish` | Affinity completion to activation-ready publication timestamp |
| `wake_to_wait_return` | Publication to target thread returning from its wait; includes wakeup, scheduling/context switch and wait-exit work |
| `yield_copyout` | Target wait return through activation copy to userspace |
| `kernel_to_userspace` | Pre-return kernel stamp to first userspace stamp; includes the single trace-record emission, ioctl exit path and clock-read overhead |
| `runtime_activation_setup` | First userspace stamp to alias/parity setup complete |
| `runtime_before_rx` | Setup complete to the traced RX entry; includes intervening local runtime work and any earlier empty polls |

Ordinary cooperative handoffs omit the first two components (no idle kick or
dispatcher). The low-overhead default deliberately does **not** split runqueue
wait from context-switch/restore instructions with broad scheduler/function
tracing. Component differences use a common raw monotonic epoch; the tracefs
record's own clock is not used. No assumption that tracing is free is made.

The runtime CSV retains TID, physical worker index, per-thread activation index,
and both activation timestamps with every request, including across NACKs.
This metadata is copied with each request's trace ownership. TIDs, hardware
worker indices and domain codes are distinct. The analyzer matches a cold row
to exactly one kernel record, rejects ambiguity, and never charges one
activation to multiple cold requests. Hot rows do not recharge old activation.

After loading the instrumented module, wrap the finite traffic phase **on the
DUT**, starting capture before activating the test service:

```sh
sudo python3 tools/enzian/activation_trace.py --output NEW_CAPTURE_DIR -- \
  bash RUN_ONE_PHASE.sh
python3 tools/enzian/activation_breakdown.py \
  --capture NEW_CAPTURE_DIR --server SERVER.csv --audit TRACE_AUDIT.json \
  --condition cold --expected-per-worker 1000 --output COLD_KERNEL.json
```

`RUN_ONE_PHASE.sh` is the campaign's service/client orchestration, not a supplied
script. The collector uses its own tracefs instance, enables only this event,
preserves per-CPU loss counters, and disables/removes its instance on exit.
Allocate/resize and enable the kernel buffers before starting FIFO workers.
Resizing a tracefs instance with an active FIFO worker can wait indefinitely
for per-CPU ring-buffer work. For an
already running service, pause it only during capture setup, resume before
traffic and exclude that preparation from measurements. Preserve the failed
setup and do not relabel it as an RPC timeout.
The normal FPGA trace must independently stay armed through module load. Run
`plot_latency.py` first with matching full hardware traces and CSVs (including
warmup during matching); pass its audit to the activation analyzer. The latter
requires the configured sample count (`--expected-total 1000` for the single
measured thread, or the default per-worker mode for four-worker coverage) and
reports P50/P90/P95/P99/P100 for roundtrip, existing E2E components, and the
separate kernel activation partition. P100 is the observed maximum.

**Do not add the activation partition to the E2E stack.** It refines time already
included in the scheduler/delivery/runtime intervals. Its uncorrected durations
also retain instrumentation cost. The existing E2E stack uses its documented
probe corrections; raw roundtrip is reported separately. Hot/cold classification
compares userspace readiness with FPGA arrival using the existing inferred clock
offset; `--margin-ns` rejects nearby boundaries (default 1000 ns). This margin is
an ambiguity guard, not a proven bound on clock-fit error. Preserve the hardware
activation trace to verify the intended parked/active condition independently.

Before/after each phase the collector reads
`/sys/module/lauberhorn/parameters/activation_clock_cost_ns`: 256 adjacent kernel
clock-read pairs summarized as minimum/mean/maximum. This is an **off-path**
clock-cost calibration, not the total instrumentation cost. Do not run it during
RPC measurement. Compare matched DPDK roundtrip runs with kernel tracing off/on
while keeping runtime tracing constant, and separately compare runtime CSV off/on.
Retain raw differences and tails; do not subtract a guessed constant from every
kernel component. Only the board experiment can establish actual Enzian overhead.


### Inter-packet gap attribution

The runtime CSV is exported only at campaign end; see the
[runtime export contract](#buffer-sizing-and-export).
Do not tail a CSV during traffic. The DPDK client also retains its records until
measurement ends. Freeze kernel/FPGA recording before requesting runtime export,
so export syscalls are outside the measurement window.

For kernel coverage, `tools/enzian/activation_trace.py --ipg-cpus 44,45,46,47`
enables syscall entry/exit, IRQ, softirq and context-switch tracepoints in a
private instance; paired IPI events are enabled when available. The collector
requires `mono_raw`, saves event formats and coverage, and exports trace text
and per-CPU loss counters **after** the command finishes and recording stops.
Allocate buffers before starting FIFO workers (or while they are paused), resume
before measurement, and stop traffic before the command returns. There is no
streaming trace reader. Any overrun/drop invalidates the capture. The existing
activation event retains its embedded raw-nanosecond timestamps; ordinary trace
text timestamps have microsecond quantization.

`python3 tools/enzian/ipg_breakdown.py --capture CAPTURE --server SERVER.csv
--output IPG.json` partitions previous TX-exit to next RX-entry for serial inline
RPCs. Off-CPU time takes precedence over IRQ, IPI, softirq, then syscall time,
so a syscall interrupted or descheduled is not charged twice. Other tasks' IRQs
are not charged to the measured TID. Unpaired boundary events receive no causal
attribution; missing coverage and untraced faults/exceptions remain limitations.
The tool rejects runtime loss, missing required events, CPUs outside coverage,
and worker migration between adjacent requests. The first request has no prior
TX boundary. The resulting intervals are diagnostics, **not additional E2E
components**: only their overlap after a request's arrival contributes to RTT.
Without kernel evidence, label the gap **unattributed IPG**, not Linux scheduler
time. FPGA enqueue-to-dispatch time is a distinct hardware observation.


For one measured thread migrating among workers, use
`activation_breakdown.py --expected-total 1000` (or the actual expected total);
per-worker mode applies only when that coverage is required. Drain every flush
reply, require a fresh activation for each cold request and retain placement.
Successful flush replies alone do not prove eviction.

[handoff_breakdown.py](../../tools/enzian/handoff_breakdown.py) joins incoming
activation to an outgoing yield syscall and cooperative DESCHED on the same CPU.
Require unique joins, loss-free capture, temporal order and audit hashes.
Generic syscall timestamps have microsecond quantization; the outgoing interval
includes userspace and exception entry and does not isolate the exception
instruction. Keep these nested diagnostics within their instrumented cohort.

## PMU and archived diagnostics

Version-5 PMU coarse mode samples RX exit and reply-ready (`pmu_tx_enter`);
detailed mode adds handler entry/exit, with masks 36/60. It measures service
counts after RX, not pre-arrival blocked time. Version-4 captures retain their
original RX-entry start and masks 37/61. The importer selects the version's
stage map. The archived post-return IPG analysis and Dandelion TX-return clock
fitting require return probes and reject versions 4 and 5. Dandelion CPU
waterfalls use publication and application/entry-return events for both versions.
