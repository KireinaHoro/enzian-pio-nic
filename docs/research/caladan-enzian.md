# CS-A64: Caladan Simple on AArch64

**CS-A64** is the paper's Caladan baseline: ThunderX-1 ARMv8, ConnectX-5,
mlx5 directpath and the **simple** scheduling policy. Applications access NIC
queues directly; the IOKernel retains core allocation and preemption. IAS and
its interference-control claims are outside this baseline's scope. Native x86
implementations are preserved; no x86 calibration experiments are planned.

See [paper goals](paper-goals.md#planned-applications-and-baseline) for the
comparison with Lauberhorn and DPDK, and [build, deployment and tracing](../development/caladan.md)
for commands. Caladan owns its Nix recipes; platform composes the cross toolchain,
matched kernel modules and SquashFS image through `mkCaladan`. No RTL build is
required. Progress and detailed test provenance live in [agent context](../../agents/caladan.md).

## Selected setup and paper comparison

The reference is [Caladan, OSDI'20](https://www.usenix.org/system/files/osdi20-fried.pdf),
particularly §§5–7. Our upstream base is
`bdb4ddebc2198cd95991079ed20e44a5f210d6d3` (May 2026), not the original paper
artifact. The table distinguishes published settings from implementation details;
an unchanged upstream default does not by itself establish a paper setting.

| Setting | Paper / upstream reference | CS-A64 configuration |
| --- | --- | --- |
| CPU, NIC and kernel | Paper: Broadwell Xeon, ConnectX-5, modified Linux 5.2; one socket used. | ThunderX-1, ConnectX-5, Linux `6.8.0-146-generic` with matched ARM `ksched`/`fake_idle`. |
| Network datapath | Paper: runtime kernel-bypass networking through libibverbs. | Existing mlx5 directpath, `enable_directpath 1`; no IOKernel packet forwarding. Record negotiated steering mode. |
| Scheduling policy | Paper: interference-aware allocation with bandwidth and hyperthread controllers. | `simple noht nobw`; `CONFIG_IAS=n`. ThunderX has no SMT. |
| Preemption | Paper: KSCHED IPIs and runtime signals. UINTR entered upstream in December 2023. | `CONFIG_NO_UINTR=y`; signal/IPI preemption. Disabling UINTR is not a departure from the original paper. |
| IOKernel delay-measurement interval | Paper: 10 µs control interval. Upstream: `IOKERNEL_POLL_INTERVAL=10`. | Unchanged, 10 µs slow pass; fast scheduling checks run every loop. This is not a per-packet polling interval. |
| Runtime work-search polling | Upstream: `RUNTIME_SCHED_MIN_POLL_US=2`, `RUNTIME_SCHED_POLL_ITERS=0`; not specified in the paper. | Unchanged: minimum 2 µs search before voluntary parking, with no iteration-count minimum. Pending I/O can extend polling. |
| Queue-delay threshold | Paper evaluation: `THRESH_QD=10 µs`. | Default `runtime_qdelay_us=10`. |
| Receive/softirq watchdog | Not described in the paper. October 2020 source (`47a22d5`) already sets `RUNTIME_WATCHDOG_US=50`. | Unchanged, enabled, 50 µs; checked on scheduler entry, not a periodic interrupt. See the limitation below. |
| User-thread quantum | Current upstream default `runtime_quantum_us=100`; consumed by IAS time sharing. | Inactive under simple; not a 100 µs preemption guarantee. |
| NIC queue-delay measurement | Paper: packet arrival timestamps in NIC RX descriptors. | Enabled by default: NIC CQ timestamps and the mapped CX-5 clock. `no_hw_qdel` selects queue scanning/software estimation for a diagnostic fallback. |
| Core entitlement and residency | Paper evaluation: 22 guaranteed logical cores for LC tasks. | Hot campaign: `runtime_kthreads`, `runtime_guaranteed_kthreads` and `runtime_spinning_kthreads` all equal the configured worker count; priority BE. |
| Idle handoff | Paper: MONITOR/MWAIT. | Repeated callbacks of up to 64 acquire polls; increased idle CPU/power, potentially lower activation latency. |
| Compiler optimization | Paper does not specify these flags; upstream optimized build enables native targeting, LTO and fast-math. | `CONFIG_OPTIMIZE=y` with overridden flags: release `-O3`, explicit `-mcpu=thunderx+crc`, `-mno-outline-atomics`, LTO and fat LTO objects. No build-host native targeting or fast-math. The shared echo link inherits target/LTO flags. |
| Storage | Paper includes SPDK for its storage workload. | `CONFIG_SPDK=n`; outside the selected network baseline. |

Implementation references in the sibling tree: `package.nix`, `build/shared.mk`,
`runtime/defs.h`, `runtime/ioqueues.c`, `runtime/kthread.c`, `iokernel/sched.c`
and `iokernel/simple.c`. Platform's campaign configuration is generated in
`tools/paper/campaign/caladan_peer.py`.

The IOKernel command selects `simple noht nobw <CPU-pool> nicpci
<CX-5-PCI-address>`. The zuestoll07 setup uses CX-5 `0005:90:00.0`, control CPU
44, scheduler/dataplane CPU 45 and runtime CPUs 46/47. Worker count is a campaign
parameter, not fixed by those example placements. Host mlx5 drivers and kernel
modules must match the boot kernel.

Simple preempts an owner above its guarantee for a requester below its guarantee.
It does not implement IAS's LC/BE interference policy or general time slicing
for continuously busy zero-guarantee tenants. Changing the priority label alone
does not supply those policies.

### Residency and receive scheduling

A guaranteed core is an entitlement, not a permanently running worker. With
`runtime_spinning_kthreads=0`, a worker may park after its work-search timeout.
The hot unloaded and loaded campaigns set maximum, guaranteed and spinning
kthreads to the worker count. Historical acquisitions with spinning omitted
retain their original configuration and do not establish residency between
requests. Future cold microbenchmarks will vary these three settings and compare
idle-to-active behavior against Lauberhorn; that sweep is not yet implemented.
Spinning prevents voluntary
parking below the configured count; IOKernel-requested cede/park still wins.
It keeps workers executing in userspace rather than configuring Linux cpuidle.
Even a parked ARM core polls in the kernel idle handoff, but needs runtime
reactivation before it can process RX.

The watchdog makes network/timer work eligible ahead of runnable application
threads after 50 µs without softirq service. It runs only when control reaches
the scheduler: it neither interrupts a continuously executing handler nor makes
an idle worker wait 50 µs before polling RX.

Under sustained load, mlx5 receive work queued at the head can repeatedly take
precedence over UDP handlers when receive batches exceed the watchdog interval.
Bounded single-worker diagnostics observed handlers resume after traffic stopped;
disabling the watchdog restored timely replies at 125,000 RPC/s. The selected
baseline retains the inherited watchdog; disabling it is a diagnostic control,
not a baseline setting. Valid loaded collapse points count as server loss,
including zero goodput and 100% deadline loss: they end the qualifying
latency/goodput curve and remain in the loss curve. Diagnostic provenance is in
[agent context](../../agents/caladan.md).

## ARM port differences

This comparison is with x86 using the same simple policy and signal preemption.
The runtime structure is preserved; additional machine-level costs mainly come
from per-thread addressing and preemption-counter updates.

| Mechanism | x86 implementation | AArch64 implementation and cost |
| --- | --- | --- |
| Per-thread reads | One GS-relative load. | A fixed-width assembly helper reads TPIDR_EL0, loads the Caladan allocation offset from libc TLS, then loads the value. This adds a call/return and address-generation instructions. |
| Preemption counter | GS-relative memory add/subtract; enable tests the resulting flags. | Inline LL/SC update on enable, testing the decrement result directly; disable uses an assembly helper. More instructions and possible exclusive-store retries, but no syscall or outlined atomic dispatch. |
| Context switching | Assembly saves/restores the x86 callee-saved registers. | Assembly saves/restores the AAPCS64 callee-saved integer registers and low halves of v8–v15 with paired loads/stores. The larger saved state follows the ABI. Preemption enable stays inline; only pending requests call C. |
| Signal preemption | Signals interrupt user threads and enter the scheduler. | Same mechanism, with an assembly counter increment and PC checks to make nested signals and interrupted TLS reads safe. Signals remain unmasked; no extra signal-unmask syscall. |
| Memory ordering | x86 ordering permits ordinary loads/stores and compiler barriers for several operations. | Acquire/release operations and explicit barriers enforce CPU and NIC DMA/MMIO ordering. These instructions preserve the same publication/ownership contracts on ARM. |
| Idle core handoff | MONITOR/MWAIT waits for a scheduling request. | Up to 64 acquire polls per idle callback. This keeps the core executing instead of entering monitored sleep, increasing idle CPU/power cost and potentially reducing activation latency in the baseline’s favor. WFE is not substituted directly because this callback runs with IRQs masked and needs interrupt-safe wakeup. |
| Shared RX-buffer layout | DPDK uses 64-byte alignment. | Header and object sizes follow the target DPDK definitions (128-byte alignment on ThunderX). Address reconstruction remains compile-time arithmetic. |
| Hashing and checksums | Hardware CRC32C; word-wide Internet checksum. | Hardware CRC32C through ACLE intrinsics and `-march=armv8-a+crc`; 64-bit Internet checksum accumulation. Neither uses a bitwise software CRC fallback. |

### Why TLS reads need PC restart

TPIDR_EL0 remains owned by libc. A signal can migrate a Caladan user thread to
another pthread between reading its TLS base and loading the value. The signal
handler recognizes that instruction interval and rewrites the saved PC to the
reader's entry, so the address is recomputed on the destination pthread. Once
the value load completes, no restart is needed—just as migration can follow a
completed x86 GS-relative load.

This adds recovery work on an interruption, rather than an exclusive-monitor
probe on every read. Accesses made with preemption disabled need no restart.
Retaining a per-thread pointer still requires preventing migration while using
it; the value-read protocol does not extend a pointer's lifetime.

### Interface differences

Clock reads use CNTVCT rather than the x86 TSC. Ordinary reads are unordered;
the ordered helper adds ISB. Scheduler time conversion requires integral counter
ticks per microsecond, and the ARM `rdtscp` wrapper returns auxiliary CPU ID zero.
Custom per-user-thread libc TLS (`thread_set_fsbase` with a nonzero argument) is
unsupported. These are interface restrictions, separate from instruction costs.

### Masked-SGI wake limitation

On ThunderX, the IRQ-masked `LDAXR/WFE` probe did not wake for a pending
cross-call SGI; SEV woke it and the SGI callback ran after IRQ restoration.
This explains why the port keeps polling instead of substituting WFE in the
IRQ-masked idle callback. The result applies to the tested ThunderX setup;
[probe conditions and provenance](../../agents/caladan.md#masked-sgi-probe)
record the disabled timer event stream and rescue controls.

## Measurement contract

Match Lauberhorn payload/handler semantics, worker counts and offered loads.
Record compiler flags, runtime options, steering mode, hardware and dependency
versions with each dataset. Keep resident-worker, idle-to-active and busy-owner
preemption experiments distinct. Use the [shared hot-path methodology](hot-measurement.md)
and [campaign workflow](../development/caladan.md#unattended-hot-echo-campaigns).

The shared echo backend uses Caladan's UDP spawner, one user thread per datagram,
and user-thread-specific request context. Schema, handler, XDR and synchronous
RPC code are shared with DPDK. The probe-free build removes timing hooks while
preserving scheduling. Optional packet-stage tracing follows RX through UDP
spawning and TX through deferred submission; `SIGRTMIN+4` exports the shared
collector without consuming preemption signals. The
[RPC timestamp contract](../development/rpc-breakdown.md) maps these packet
markers and shared probes to codec, allocation, reservation and dispatch-delay
intervals; dispatch delay is included in Runtime/dispatch.

Distinguish client RTT, switch NIC-to-NIC timing and CPU intervals; preserve
request correlation, raw evidence and tracing-off controls. `no_hw_qdel` controls
scheduler feedback, independently of external wire-latency capture. Clock-domain
conversion must be established for any timestamps combined in a breakdown.
Datasets and figures belong with the [paper charts](../development/paper-charts.md),
not functional acceptance logs.

For [memcached](../applications/memcached.md), pin the revision, pre-size the
hash table, and match operations, key/value sizes, dataset and maintenance scope.
The published runtime port lists hash expansion as unfinished. A minimal mlx5
packet-I/O control may isolate NIC cost but does not replace the Caladan runtime
baseline. New TCP implementation, general-purpose tracing, NICVF/VNIC porting
and portability beyond the selected ARM/kernel/NIC setup are outside this scope.
