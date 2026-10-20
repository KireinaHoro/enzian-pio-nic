# Caladan Enzian baseline design and progress

Use **SH-A64** as the chart label for the AArch64 Caladan baseline, alongside
**DPDK** and **LH**. Add its series only after implementation and acceptance.

**Scope: ARMv8 + mlx5 directpath; focused native CPU, kernel/runtime and CX-5 packet checks pass; broader acceptance pending.**
Updated October 20, 2026. Port the maintained Caladan tree to Enzian AArch64
with ConnectX-5 directpath and the simple policy. This is the baseline for
Lauberhorn hot latency/throughput, core reclamation and bounded multi-instance
memcached. It does not reproduce IAS or full Caladan interference-control claims.

The previous centralized NICVF datapath, DSCP/VNIC directpath investigation and
x86 calibration experiments are superseded and outside the implementation plan.
Preserve upstream x86 code and default behavior; no separate x86 calibration
recipes, deployments or measurements are required.

## Selected setup

| Setting | Target |
| --- | --- |
| Source | `../caladan`, branch `enzian-port`, upstream base `bdb4ddebc2198cd95991079ed20e44a5f210d6d3` |
| CPU and NIC | Enzian ThunderX ARMv8 with Mellanox ConnectX-5 |
| Selected boards | **zuestoll07** DUT with CX-5; **zuestoll14** peer/load generator, authorized October 20. Live reservations checked before testing. |
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

## Packaging and deployment

Caladan owns `package.nix`, `ksched/package.nix`, and the mlx5 DPDK/patched
rdma-core recipes. Platform `mkCaladan { source = ...; }` supplies the AArch64
toolchain, prepared kernel and standalone SquashFS image. Reuse the platform
flake lock and DPDK-baseline deployment routines; no Lauberhorn runtime or RTL
build is required. The image exposes `/nix/store/caladan`, a provenance manifest,
Caladan tools and its scheduler modules. Mellanox host kernel drivers must match
the selected boot kernel; the image does not currently package replacements.

```sh
# From ../caladan; board acceptance remains separate from these builds.
nix build --impure --file default.nix runtime
nix build --impure --file default.nix image
nix develop --impure --file default.nix shell
nix build --impure --file default.nix checks
```

The DPDK recipe selects mlx5 and its PCI/auxiliary dependencies and retains
Caladan's memseg setting. rdma-core is pinned to
`53ee89b4abb1463e7ae713103f552b2bef1307ce` with Caladan's five existing patches.
The ARM dependency cross-builds now pass. MMIO/DMA behavior still requires
hardware acceptance; historical x86 builds do not establish that result. The calibration wrapper and centralized-build feature switch
have been removed. Native x86 architecture code remains intact.

Use the [deployment skill](../../tools/enzian/skills/enzian-deploy/SKILL.md), EMG
and the platform harness. Check live reservations and preserved experiment state
before deployment. The October 20 instruction authorizes zuestoll07/14 for focused
Caladan tests, superseding the earlier instruction to leave zuestoll07 untouched.
Record PCI address, firmware, link,
NUMA placement, kernel and source/image identity before acceptance.

The earlier reservation of **zuestoll04** with volume
`caladan-enzian-z04-20261016`, cloned from golden `2025-07-28`, is historical
provisioning evidence, not the selected CX-5 target. It was acquired powered off
with boot bundle `pengxu/lauberhorn-6.8.0-146-ptp/{vmlinuz,initrd.img}`; no Caladan
boot or deployment was performed. No release or reassignment is implied by this
scope update. Recheck its state before cleanup. For a new board use an explicitly
selected golden image and a checked-unused, distinct root-volume name: EMG can
silently reuse names and couple active exports. Never reuse the z04 volume for
another active board. The deploy skill's golden-image/name safeguards remain.

## Remaining port and acceptance stages

| Stage | Required evidence | Status |
| --- | --- | --- |
| ARM build and primitives | Libraries/tests build; AAPCS64 context/SIMD preservation, stack ownership, TLS/migration, atomics/barriers and timing work | ARM/x86 builds, QEMU checks and native ARM mechanism checks pass; sustained stress pending |
| Kernel handoff | Matched ARM modules; repeated yield, signal/IPI preemption of a busy process, resume and idle handling | Matching modules load; API/shared mapping and one-/two-kthread runtime checks pass; busy-owner signal/IPI preemption still pending |
| mlx5 directpath | Patched rdma-core/DPDK cross-build; DMA/MMIO ordering, queue creation, RX/TX/completions, buffer ownership, ARP and steering | CX-5 initialization, flow steering and three peer ICMP replies pass; sustained traffic/ownership stress pending |
| Scheduling integration | Arrival to zero-core process wakes it; core reassignment and busy-owner reclamation preserve packet and runtime progress | Pending |
| Hot baseline | Correct UDP request/reply at selected workers/loads; unloaded and loaded controls; validated clocks and instrumentation | Pending |
| Memcached | Pinned runtime-native port, bounded single/multi-instance GET workload and actual scheduling progress | Not started; beyond the current stages 1–3 request |

Architecture work includes raw syscalls, signal-frame/restoration semantics,
GS/FS-dependent state, checksum/CRC helpers and ARM acquire/release barriers.
Preserve Linux signal/IPI handoff while gating x86 UINTR/PMC paths. Validate page
sizes, fixed-address assumptions, PCI MMIO barriers, DMA visibility and the
mlx5 directpath backend's architecture assumptions. The existing non-MWAIT
fallback polls in microsecond steps; record and measure any tighter polling
choice rather than claiming power-efficient idle.

The simple policy preempts an owner above its guarantee for a requester below
its guarantee. Use feasible guarantees and burstable allocations. Continuously
busy zero-guarantee tenants are not automatically assured time-sharing. Test
idle wakeup and busy-owner preemption separately and verify actual allocation,
not merely successful process startup.

## Measurement contract

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
maintenance to match [Lauberhorn's memcached scope](memcached.md). New TCP,
general-purpose tracing and portability beyond the selected ARM/kernel/NIC
configuration remain outside this port.

## Current implementation checkpoint — October 20, 2026

Caladan `586a5a1` on `enzian-port` adds focused hardware fixtures and fixes the
shared RX object layout. The separate
[AArch64 port approach](../../../caladan/docs/arm-port-approach.md) compares the
implementation and machine-level costs with x86; progress belongs here.

### Tested setup

- DUT zuestoll07: ThunderX-88XX, 48 CPUs/one NUMA node, 4 KiB pages,
  CRC32 available, architectural counter 100 MHz. Kernel `6.8.0-146-generic`.
- CX-5 `0005:90:00.0`, `enP5p144s0np0`, MAC `b8:ce:f6:87:c2:9e`,
  firmware `16.35.8002`, kernel `mlx5_core`/`mlx5_ib`; link/RDMA ACTIVE in test.
- IOKernel: `simple noht nobw no_hw_qdel 44-47 nicpci 0005:90:00.0`,
  control CPU 44, dataplane 45, runtime workers 46/47. Normal mlx5 flow steering.
- Runtime: `enable_directpath 1`, one then two kthreads, one guaranteed kthread,
  BE priority; IP `192.168.128.207/18`, gateway `192.168.128.1`.
- Peer zuestoll14: existing `cpu40g1`, `192.168.129.193`, used only for three
  ICMP probes. No benchmark or peer configuration change.
- Existing root volumes `lauberhorn-linux-6.8` (07) and
  `echo-generator32-20261015` (14) retained; no provisioning, reboot or FPGA work.
  DUT root hostname misleadingly says zuestoll14; console identity, iSCSI
  initiator, management address and PCI/MAC establish that it is zuestoll07.

### Results

All eight CPU mechanism checks passed natively, plus the platform requirements
probe: 10,000 CRC reference comparisons; checksum alignment/tails/carries;
10,000 signal-frame context switches preserving integer/SIMD state; 20,000
direct switches; 999 live signal-frame migrations; 100,000 ordered LRPC messages;
10,000 nested signal deliveries; and five million TLS-read iterations across
four widths with 821 actual asynchronous poisoned-register PC restarts.
These are finite mechanism checks, not production ksched preemption stress.

Matched `fake_idle`/`ksched` modules loaded. Scheduler/user API versions were
both 2, shared state mapped, and the ARM UINTR ioctl returned EOPNOTSUPP.
The normal runtime/directpath fixture passed with both one and two kthreads:
four user threads completed 512 mutex-protected increments with yields and
short timer sleeps. Holding the runtime alive then yielded **3/3 ICMP replies**
from zuestoll14; neighbor resolution matched the CX-5 MAC, and the DUT Linux
stack had no runtime IP. This establishes a basic peer-to-runtime RX/TX path,
not a throughput, latency or weak-memory stress result. No new kernel faults
were found in the postflight diagnostic comparison.

The first actual IOKernel startup exposed x86-only RX layout constants:
ThunderX DPDK aligns objects to 128 bytes, giving a 9472-byte object instead of
9408. Sizes now derive from target DPDK types/alignment, retaining x86's layout.
Initialization checks reconstruct the mbuf from both ends of every buffer.
ARM hardware startup and the native x86 userspace regression build pass.

Startup needed more than the initially available 768 hugepages for the fixed
2 GiB ingress pool. The successful tests temporarily reserved 4096 × 2 MiB
pages and allowed 45 seconds for readiness (DPDK initialization took about
16 seconds). Scratch quota/root-space constraints were handled by staging
images in gateway `/tmp` and DUT `/dev/shm`, without deleting existing data.

### Reproduction and artifacts

`nix build --impure --file default.nix testImage` packages the runtime/modules
and native checks at `/nix/store/caladan-checks/bin`. The maintained
`test_runtime_smoke CONFIG [HOLD_MS]` fixture exercises threads/mutexes/timers;
its optional bounded hold permits external packet probes through the existing
network stack. It adds no echo benchmark.

Final tested image (including the network hold):
`/nix/store/5m0c4lx4yh8hzyfl4i1d99rzmmmnixln-caladan-focused-tests.img`, SHA256
`3b17d60e6df857b7ca106d05eac3a950ff765e05ac793b7c87c20f3b522e95e0`.
The one-/two-kthread layout checks used
`/nix/store/6pbcd0w7v3h3gv1k1f20j98br4zn7jdr-caladan-focused-tests.img`, SHA256
`58dbcaaa123d4591114ee03ea1f36ca55f04d532db499d787a18bf7f362a9da6`.
Builds include all eight QEMU checks; the final native x86 build also passed.
These artifacts were built before committing their source as `586a5a1`.

Local evidence: `../caladan/out/hardware-20261020-01/`, including
`cpu-tests.json`, `module-smoke.json`, `layout-smoke.json`, `peer-ping.json`,
`peer-neighbor.json`, `postflight.json`, `reclaim.json`, image provenance and
build logs. Failed startup diagnostics are retained alongside successful runs.

Postflight stopped owned test processes and the temporary image server,
unloaded both scheduler modules, restored CX-5 down, restored
`nr_hugepages=0` and `shm_rmid_forced=0`, and reclaimed only test-prefixed DPDK
hugepage files (final Hugetlb zero). cpuidle returned to `none`. The read-only
test image remains mounted at `/nix/store`; diagnostic files remain in `/tmp`.

**Review gate:** focused board checks are now available for review. Busy-owner
signal/IPI preemption, zero-core wakeup and core reclamation under contention
remain separate acceptance work. Finish/review stages 1–3 before stage 4.
Wait for the user's shared platform echo microbenchmark extraction; no echo
integration or measurement campaign has started. No timing/power claims or
memcached results are established by these tests.
