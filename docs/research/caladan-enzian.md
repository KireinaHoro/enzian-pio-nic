# Caladan Enzian baseline design and progress

**Scope: ARMv8 + mlx5 directpath; port not yet build- or board-accepted.**
Updated October 16, 2026. Port the maintained Caladan tree to Enzian AArch64
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
| Board candidates | User reports CX-5 on **zuestoll03, zuestoll05, zuestoll06, zuestoll07**; select after live reservation/NIC checks |
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
# From ../caladan; these remain unfinished ARM port targets.
nix build --impure --file default.nix runtime
nix build --impure --file default.nix image
nix develop --impure --file default.nix shell
```

The DPDK recipe selects mlx5 and its PCI/auxiliary dependencies and retains
Caladan's memseg setting. rdma-core is pinned to
`53ee89b4abb1463e7ae713103f552b2bef1307ce` with Caladan's five existing patches.
Validate ARM cross-build and MMIO behavior; historical x86 builds do not establish
ARM compatibility. The calibration wrapper and centralized-build feature switch
have been removed. Native x86 architecture code remains intact.

Use the [deployment skill](../../tools/enzian/skills/enzian-deploy/SKILL.md), EMG
and the platform harness. Check live reservations and preserved experiment state
before choosing a CX-5 board. In particular, earlier work used zuestoll07/14 for
hot microbenchmarks; the candidate list does not authorize interrupting them.
Choose a free CX-5 board where possible. Record PCI address, firmware, link,
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
| ARM build and primitives | Libraries/tests build; AAPCS64 context/SIMD preservation, stack ownership, TLS/migration, atomics/barriers and timing work | Pending; previous first ARM compile failed on unconditional `immintrin.h` in `inc/asm/ops.h` |
| Kernel handoff | Matched ARM modules; repeated yield, signal/IPI preemption of a busy process, resume and idle handling | Pending |
| mlx5 directpath | Patched rdma-core/DPDK cross-build; DMA/MMIO ordering, queue creation, RX/TX/completions, buffer ownership, ARP and steering | Pending |
| Scheduling integration | Arrival to zero-core process wakes it; core reassignment and busy-owner reclamation preserve packet and runtime progress | Pending |
| Hot baseline | Correct UDP request/reply at selected workers/loads; unloaded and loaded controls; validated clocks and instrumentation | Pending |
| Memcached | Pinned runtime-native port, bounded single/multi-instance GET workload and actual scheduling progress | Pending |

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

## Current implementation checkpoint

Commit `bbc152f` established Nix packaging and successful x86 userspace builds
before this pivot. Those are historical toolchain evidence, not current baseline
acceptance. Commit `f8f5b41` removes calibration exports and optional
centralized-build gating, retain IAS/ARM build gating, and compose the existing
mlx5 recipes for AArch64 instead of ThunderX NICVF. Platform and research-note
edits remain in its working tree. No successful ARM runtime/module/image build,
Caladan board test or measurement has been established. Nix syntax and diff
whitespace checks pass, and the narrowed AArch64 runtime and image derivations
evaluate successfully; derivation evaluation is not build acceptance.

Update this checkpoint and stage table as work proceeds, recording revisions,
checks actually run and remaining failures. Keep temporary diagnostics in ignored
`out/`, preserve unrelated experiments, and retain accepted measurement recipes
and data through the platform's tracked measurement workflow.
