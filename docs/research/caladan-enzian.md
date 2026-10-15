# Caladan Enzian baseline design and progress

**Status: design agreed, October 15, 2026.** Port the maintained Caladan tree
minimally to Enzian AArch64, validate its mechanisms and measurements, and use
that port as a baseline for Lauberhorn's hot latency/throughput and breakdown,
core reclamation, and multi-instance memcached evaluation. Optionally run a
comparable x86/DPDK configuration to strengthen confidence in the port.
Calibration relates published results, our x86 measurements and our Enzian
measurements; it does not require reproducing identical absolute numbers.

Caladan replaces Demikernel as the primary baseline in the
[paper evidence plan](paper-goals.md): its runtime-managed core allocation and
networking are a closer match for the mechanisms under evaluation. Implementation
and measurement stages below are pending; this note establishes their scope and
acceptance criteria.

## Implementation scope

Use `../caladan`, with centralized NICVF DPDK networking and the `simple`
scheduler. The intended configuration is `simple noht nobw no_hw_qdel`, with
build-time exclusion of unsupported subsystems as well as runtime settings.
Do not enable IAS or reproduce Caladan's interference-control or fairness claims.
The result should be identified as a Caladan-derived AArch64 baseline with the
simple policy and centralized DPDK datapath.

Reuse `../thunderx-dpdk-ptp` for NICVF and packet timestamp support and
[shenango/memcached](https://github.com/shenango/memcached) for the application
port. Keep `../shenango` as a reference for simpler TLS and signal implementations.
Its smaller runtime does not remove the shared ARM context-switch/barrier work,
and its older DPDK integration and SMT assumptions add work of their own.

| Required case | Minimum supported behavior |
| --- | --- |
| Hot latency and throughput | UDP request/reply through the real IOKernel/runtime path, resident workers, selected worker counts and offered loads. Match payload and handler semantics to Lauberhorn, including encoding work where applicable. |
| Hot breakdown | Packet timestamps and a small set of buffered CPU events with explicit boundaries and request correlation; separate tracing-off controls. |
| Core reclamation | Two processes sharing a worker core, actual preemption of a busy owner, release and resumption, plus an idle-to-active control. |
| Memcached | Existing runtime-native UDP port, bounded preloaded dataset, one-instance control and multiple independent instances sharing a selected worker pool. |

Exclude UINTR, Intel PCM/PMCs, IAS bandwidth/SMT controllers, Mellanox directpath,
SPDK and new TCP acceptance work. Avoid porting the Rust load generator when the
existing platform generator can express the workload. Defer general-purpose
tracing, power-efficient idle, portability across ARM boards/kernel versions,
and unrestricted memcached maintenance. Leave unrelated portable code intact
where removing it would require additional surgery.

For memcached, pin the application revision and bound operations, value/key
sizes, dataset, maintenance and thread configuration. The published port lists
hash expansion as unfinished; pre-size the table and verify that expansion does
not occur. Match Lauberhorn's [bounded memcached scope](memcached.md) where
possible and disclose differences in application revisions and maintenance.

## Architecture work that remains

| Work package | Required implementation and validation |
| --- | --- |
| Context switching | AArch64 thread frame, initial stack and arguments, register save/restore, AAPCS64 callee-saved SIMD state and stack ownership publication. |
| TLS and preemption | Replace GS-relative state; maintain correct kernel-thread identity after migration. Replace single-instruction x86 counter updates with signal-safe atomic operations, not plain C load/modify/store. Custom user-thread FS-base support can be excluded if unused by the selected applications. |
| Signals and syscalls | Port raw syscall helpers and Caladan's custom signal-restoration semantics, or justify a simpler implementation. Its x86 signal-frame offsets remain a dependency even with UINTR disabled. |
| Memory ordering and helpers | Implement ARM acquire/release/barriers, counter-based timekeeping, checksum/CRC and relax helpers. Existing compiler atomic RMW primitives can be reused. Check cache-line layout, page sizes and fixed-address assumptions. |
| Kernel handoff | Gate x86 UINTR/PMC code and initialization; preserve Linux signal/IPI and task-management mechanisms. Adapt idle waiting and validate the cpuidle hook on the deployed kernel. |
| DPDK integration | Negotiate offloads/RSS, validate shared-buffer DMA mappings and completions, tenant addressing, backpressure and packet ownership. |

Caladan already has a non-MWAIT polling fallback, but it polls in 1 microsecond
steps. Start with correctly ordered tight polling if needed for the mechanism
benchmark, record that choice, and measure its effect. This does not establish
power-efficient idle. No new ARM interrupt controller or PMU implementation is
required: Linux supplies the signal/IPI mechanism and PMCs are outside scope.

Do not replace the measured runtime with pinned cooperative workers or bypass
its scheduling/queue path to simplify the port. Fixed resident allocations are
appropriate for hot measurements; reclamation requires the actual handoff path.
The simple policy preempts an owner above its guarantee for a requester below
its guarantee. Configure feasible guarantees and burstable allocations; arbitrary
continuously busy zero-guarantee tenants are not assured time-sharing. Define
multi-instance arrival patterns and verify actual allocation/progress rather
than inferring it from process startup.

## Calibration and measurement acceptance

ARM correctness and measurement validation are mandatory. The x86 deployment is
optional and follows initial Enzian bring-up; it is a diagnostic control, not a
prerequisite for starting the port. Use the same selected Caladan revision,
centralized DPDK path, simple policy, applications and experiment definitions on
both platforms, retaining the native x86 architecture implementation. Record
hardware, kernel, DPDK and configuration differences explicitly.

| Experiment | What it establishes |
| --- | --- |
| Mutex, yield, condvar and spawn/join | Runtime primitive costs and regressions in switching, TLS and synchronization. |
| Cross-core queue exchange | Publication correctness and cost of ARM synchronization/cache transfer. |
| Minimal DPDK echo | Platform/NIC latency floor and datapath throughput reference. |
| Echo with resident runtime worker | Added runtime and IOKernel overhead. |
| Echo requiring worker wakeup | Idle-to-active allocation cost. |
| Echo requiring busy-owner preemption | Core reclamation and resumption cost. |
| Bounded single/multi-instance memcached | Application correctness, useful throughput and allocation under the chosen traffic patterns. |

[Shenango section 7.3, Table 2 and Figure 6](https://www.usenix.org/system/files/nsdi19-ousterhout.pdf)
provide the threading and echo/wakeup/preemption experimental structure.
[Caladan sections 6 and 7](https://www.usenix.org/system/files/osdi20-fried.pdf)
provide implementation and evaluation context, but its paper datapath gives
runtimes direct NIC access. Centralized DPDK forwarding is therefore a material
configuration difference even before comparing x86 with ARM.

Maintain a compact comparison table per experiment with columns for the paper
reference/value, our x86 result (or not run), our Enzian result, configurations,
and the explanation or unresolved question. Use published numbers only where
the measurement boundaries and workload are comparable; otherwise mark the
comparison as qualitative or not applicable. Align meanings and explain gaps;
do not rescale Enzian results to match x86/paper values or require an arbitrary
percentage agreement. A platform difference alone is not evidence of a port bug.
Investigate disproportionate runtime/handoff overhead relative to the local DPDK
and primitive controls before accepting performance conclusions.

Record scheduler timing rather than assuming `simple` reproduces historical
Shenango: the surveyed Caladan tree has a 10 microsecond scheduler polling
interval, 2 microsecond minimum runtime polling period and 50 microsecond
watchdog threshold. Preserve the initial settings for paired runs and document
any subsequent tuning; do not silently tune to a published target.

Use these measurement rules with the existing [latency methodology](latency-measurement.md):

- Validate ARM counter frequency, conversion, read overhead and cross-core
  comparability. Counter ticks are not automatically CPU cycles. Keep CPU and
  NIC clock domains separate unless explicitly correlated.
- Define client RTT, MAC-to-MAC service time and CPU-stage intervals separately.
  For reclamation, record demand/preemption request, old-owner release and
  new-owner execution; separate policy detection from mechanism latency.
- NICVF permits one outstanding or unread TX timestamp per port. Use bounded
  timestamped latency trials or sampling; do not serialize saturation traffic
  for timestamps and report that as uninstrumented throughput.
- Compare traced and untraced controls. Use per-request stage records for a
  breakdown; differences between percentile values are not component timings.
- Record payloads, workload distribution, warmup, offered/achieved load, duration,
  repeats, correctness, loss/timeouts and generator capacity. Report worker CPUs
  and IOKernel/control/maintenance CPU budgets separately.

## Execution and progress

The workflow is agent-based. Separate architecture/build helpers, runtime
switching/TLS/signals, kernel handoff, and memcached packaging into coherent work
packages with explicit ownership of shared ABI files. Integrate through a common
ARM build and staged board tests; avoid concurrent incompatible edits to thread
frames, shared queues or syscall contracts.

Reuse platform [application packaging](../development/interactive-testing.md),
[validation selection](../development/validation.md) and
[Enzian provisioning/testing](../development/hardware-test.md). Provision through
EMG, use matching kernel/module artifacts and existing deployment runners, and
preserve source revisions and compact results. No FPGA redesign or new deployment
framework is part of this port. Keep temporary probes under ignored `out/`;
retain maintained experiment recipes and paper data through the existing tracked
measurement workflow.

| Stage | Exit evidence | Current status |
| --- | --- | --- |
| Scope and source audit | Minimal datapath, policy, architecture work and calibration approach identified | Complete as design only |
| ARM build and runtime | Reproducible libraries/module; repeated switches, register preservation, TLS/migration and queue tests | Pending |
| Kernel handoff | Repeated cross-process park/wake and busy-owner preemption without lost wakeups or state corruption | Pending |
| NICVF and hot measurements | Correct request/reply, DMA/completion handling, validated clocks, DPDK/runtime controls and traced/untraced results | Pending |
| Multi-instance memcached | Bounded dataset checks, correct replies and trace/telemetry evidence of intended worker sharing | Pending |
| Optional x86 calibration | Comparable runs and paper/x86/Enzian comparison with explained limitations | Not started; optional |
| Main evaluation baseline | Accepted ARM correctness and measurement results, pinned configuration and reproducible benchmark artifacts | Pending |

Source audit starting points: Caladan `bdb4ddebc2198cd95991079ed20e44a5f210d6d3`,
Shenango `09cdab31ef0f755799c1461c2adb4aa9d20d6a00`, and ThunderX DPDK/PTP
`395471583305995801f96989e2f8e108b0721369`. These identify inspected source,
not validated deployment artifacts. Pin the actual port, memcached, DPDK and
kernel revisions when implementation begins. No Caladan ARM build, board run
or calibration result has been established by this design exercise.

Once the ARM acceptance stages pass, use the measured Enzian port directly in
the main evaluation. If x86 calibration is omitted, record that explicitly;
it does not block baseline use when local correctness and measurement evidence
are sufficient. Keep any unresolved performance anomaly attached to the affected
result rather than presenting the port as a reproduction of full Caladan.
