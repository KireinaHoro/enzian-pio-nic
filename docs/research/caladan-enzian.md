# CS-A64: Caladan Simple on AArch64

**CS-A64 (Caladan Simple)** is the paper's Caladan baseline, alongside **DPDK**
and **LH**. It runs Caladan's simple scheduling policy and mlx5 directpath on
Enzian ThunderX with ConnectX-5. It is a Caladan port, not a Shenango simulation.
IAS and full Caladan interference-control claims are outside the selected scope.

The [paper goals](paper-goals.md#planned-applications-and-baseline) distinguish
DPDK's transport/CPU-processing control from CS-A64's runtime-managed core
allocation and networking. Native Lauberhorn versus Caladan
[memcached](../applications/memcached.md) is a main application comparison.
Add measured series only after the relevant benchmark acceptance; functional
checks do not establish latency, throughput, power or scheduling guarantees.

## Selected setup

| Setting | Target |
| --- | --- |
| Source | Maintained AArch64 Caladan port in `../caladan`, upstream base `bdb4ddebc2198cd95991079ed20e44a5f210d6d3` |
| CPU and NIC | Enzian ThunderX ARMv8 with Mellanox ConnectX-5 |
| Datapath | Existing mlx5 directpath backend, adapted for ARM; runtime RX/TX bypass IOKernel packet forwarding |
| Scheduling | Actual Caladan runtime and IOKernel simple-policy core allocation/preemption |
| IOKernel arguments | `simple noht nobw no_hw_qdel nicpci <CX-5 PCI-address>` |
| Runtime configuration | `enable_directpath 1`; record negotiated flow/queue/external steering mode |
| Build selection | Directpath enabled as upstream; `CONFIG_IAS=n`, `CONFIG_SPDK=n`, `CONFIG_NO_UINTR=y`, `CONFIG_OPTIMIZE=n` |
| Kernel | Initially platform-prepared `6.8.0-146-generic`, matching ARM `ksched`/`fake_idle`; confirm mlx5_core/mlx5_ib and userspace compatibility |
| CPU placement | Initial isolated pool 44–47; actual control/worker placement depends on selected board and PCI/NUMA topology |

Directpath retains the IOKernel's scheduling role. Preserve the original mlx5
queue/flow-steering mechanisms where possible; do not replace them with fixed
cooperative workers. Exclude UINTR, IAS/Intel PCM/PMCs and SPDK from this selected
build, without deleting their upstream implementations. No NICVF/PF, DSCP
classification, custom VNIC ownership or ThunderX PTP work is required.


## Port and build

The [ARM port differences](caladan-arm-port.md) describe TLS access, context
switching, signal preemption, memory ordering, idle polling, DPDK alignment and
clock restrictions relative to x86. ARM idle handoff uses up to 64 acquire polls
per callback. It keeps the CPU executing and may favor baseline activation
latency at the cost of idle CPU/power; no power saving is established.

The simple policy preempts an owner above its guarantee for a requester below
its guarantee. Use feasible guarantees and burstable allocations. Continuously
busy zero-guarantee tenants are not automatically assured time-sharing. Idle
wakeup and busy-owner preemption require separate allocation/progress checks.

Caladan owns its build recipes; platform `mkCaladan { source = ...; }` composes
the AArch64 toolchain, matched kernel modules and SquashFS image. No Lauberhorn
RTL build is required. Host mlx5 drivers must match the boot kernel. See
[build, validation and tracing](../development/caladan.md) for commands and
[board validation](../development/validation.md#board-validation) for deployment.
Our human documentation and agent context are maintained in this platform
repository; the port tree contains navigation links only.

## Paper measurement contract

Use the port directly on Enzian; **no x86 calibration experiments** are planned.
Match Lauberhorn payload/handler semantics, worker counts and offered loads.
Record actual directpath steering mode and all dependency/hardware differences.
Include primitive tests, resident-worker echo, idle-to-active echo, busy-owner
preemption and bounded single/multi-instance memcached. A minimal mlx5 packet
I/O control may help isolate NIC cost, but is not a replacement for Caladan.

Distinguish client RTT, NIC-to-NIC timestamps and CPU intervals. Validate CX-5
clock conversion and timestamp availability for the selected directpath mode;
ThunderX NICVF timestamp assumptions do not transfer. Keep tracing-off controls,
request correlation and raw evidence. Do not publish a breakdown from unvalidated
clock domains. Use the existing generator when it can express the workload.

Pin the memcached revision and pre-size its table: the published runtime port
lists hash expansion as unfinished. Bound operations, keys/values, dataset and
maintenance to match [Lauberhorn's memcached scope](../applications/memcached.md). New TCP,
general-purpose tracing and portability beyond the selected ARM/kernel/NIC
configuration remain outside this port.

## Bounded functional evidence

The October 20 checks used zuestoll07's ThunderX-88XX (48 CPUs, one NUMA node,
4 KiB pages, CRC32, 100 MHz architectural counter), kernel `6.8.0-146-generic`,
and CX-5 `0005:90:00.0`, firmware `16.35.8002`. IOKernel used simple policy and
normal mlx5 flow steering, control CPU 44 and dataplane CPU 45; runtime workers
used 46/47 with one guaranteed kthread. Zuestoll14 was the peer (NICVF for the
functional checks, not a substitute for the DUT's CX-5). These are recorded test
placements, not required or live reservations.

### CPU, kernel and packet path

At Caladan `586a5a1`, all eight CPU checks passed natively: 10,000 CRC reference
comparisons; checksum alignment/tail/carry checks; 10,000 signal-frame switches
preserving integer/SIMD state; 20,000 direct switches; 999 signal-frame
migrations; 100,000 ordered LRPC messages; 10,000 nested signals; and five million
TLS-read iterations with 821 asynchronous poisoned-register PC restarts.
QEMU and native x86 build checks also passed. These are finite mechanism checks,
not busy-owner scheduler preemption or ARM weak-memory stress.

Matched `fake_idle`/`ksched` modules loaded with API version 2 and shared mapping;
ARM UINTR returned EOPNOTSUPP. One- and two-kthread fixtures completed 512
mutex-protected increments with yields/timer sleeps. Three peer ICMP requests
received replies through runtime directpath, with the Linux stack holding no
runtime IP. These establish a basic RX/TX path, not sustained traffic capacity.
Target-derived RX object sizes corrected the 128-byte ThunderX alignment case
(9472 bytes versus x86's 9408), retaining x86 layout and reconstruction checks.

### Shared echo and stage tracing

The shared backend uses Caladan's UDP spawner, one user thread per datagram,
and user-thread-specific request context. Schema, handler, XDR codecs and the
synchronous RPC path are shared with DPDK. The probe-free build removes software
trace hooks; it does not replace Caladan scheduling with a cooperative loop.

Caladan `ba303c6` records functional echo0/8/128/1432 acceptance: each case passed
16 checked requests plus four warmups using the shared DPDK generator. Backend
source was microbenchmarks `8d87fb0`. This is correctness smoke coverage.

Caladan `d6a6be2` adds optional packet-stage tracing. RX timestamps follow the
mbuf through regular/strided CQ consumption and UDP spawning; TX uses a persistent
request slot through overflow and finishes at mlx5 submission. Dedicated
`SIGRTMIN+4` export leaves runtime preemption signals unchanged. The tracing
workflow and collector-source requirement are in the [development guide](../development/caladan.md).

Recorded traced checks produced 144 echo8 rows (128 plus 16 warmups) and 20 rows
each for echo0/128/1432 (16 plus four warmups): all 204 paired exactly with client
XIDs, had monotonic boundaries and conserved CPU partitions, with zero collector
drops. Their cohorts explicitly have `paper_inputs=false`; no Tofino capture was
collected. These results do not establish a wire-latency distribution or a
performance comparison. Exact images and evidence paths are in
[port continuation](../../agents/caladan.md).

## Remaining evaluation scope

Sustained CPU/queue/ownership stress, busy-owner signal/IPI preemption, zero-core
wakeup and core reclamation under contention remain separate acceptance work.
Shared echo correctness is established at the finite scope above; unloaded and
loaded paper measurements still require campaign integration and validated wire
collection. Caladan memcached integration/evaluation has no result in this record.
Use the same bounded maintenance scope as Lauberhorn before comparing it.
