# Caladan Enzian baseline design and progress

Use **SH-A64** as the chart label for the AArch64 Caladan baseline, alongside
**DPDK** and **LH**. Add its series only after implementation and acceptance.

**Scope: ARMv8 + mlx5 directpath; ARM builds and CPU-only checks pass, board acceptance pending.**
Updated October 19, 2026. Port the maintained Caladan tree to Enzian AArch64
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
| ARM build and primitives | Libraries/tests build; AAPCS64 context/SIMD preservation, stack ownership, TLS/migration, atomics/barriers and timing work | ARM userspace build and CPU-only QEMU checks pass; hardware stress pending |
| Kernel handoff | Matched ARM modules; repeated yield, signal/IPI preemption of a busy process, resume and idle handling | ARM modules build with matching vermagic; loading/handoff tests pending |
| mlx5 directpath | Patched rdma-core/DPDK cross-build; DMA/MMIO ordering, queue creation, RX/TX/completions, buffer ownership, ARP and steering | Dependencies and userspace cross-build pass; DMA ordering ported; NIC tests pending |
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

## Current implementation checkpoint — October 19, 2026

Follow-up fast-path work is committed as Caladan `6f27ec7` on `enzian-port`. The separate
[ARM port deficiencies document](../../../caladan/docs/arm-port-deficiencies.md)
concisely lists potential weaknesses; implementation progress is recorded here.
Hardware CRC uses ACLE `__crc32cd` and `-march=armv8-a+crc` (ThunderX-1 CRC was
confirmed by the user's `lscpu`). Atomics use inline LL/SC with
`-mno-outline-atomics`. TLS reads retain PC restart with fixed-width assembly
entry points. Preemption enable uses inline TLS addressing and the decrement
result, and switch assembly calls C only for pending preemption. Signal entry
handles nested delivery without an unmask syscall. Checksums use 64-bit loads;
ordinary clock reads omit ISB while ordered reads retain it.

ARM idle handoff now performs at most 64 acquire polls without `udelay(1)` gaps.
Load-exclusive + WFE is an architectural address-wait mechanism, but WFE lacks
WFI's general masked-IRQ wake guarantee. Because ksched enters with IRQs masked,
a safe event/interrupt protocol remains unresolved; bounded polling is the
current implementation and its idle power cost is explicit.

Caladan commit `65dd661` implements the initial port; `0df0f96` includes
build-status documentation and module closure cleanup. Implementation is `6f27ec7`; review HEAD `06d21d6` condenses the deficiencies note.
The ARM port now has AAPCS64 context switching (x19–x30 and the low 64 bits of
v8–v15), ARM raw syscalls, checksum/CRC32C helpers, acquire/release and coherent
DMA barriers, and architectural-counter timekeeping. The existing integer
scheduler time units require an integral ticks/us frequency; initialization
rejects unsupported frequencies instead of rounding. Custom user-thread libc TLS
is excluded: nonzero `thread_set_fsbase` is unsupported on ARM.

Libc retains TPIDR_EL0. Per-thread offsets use libc TLS, and out-of-line accessors
prevent compiler caching across user-thread migration. ARM signal entry acquires
a preemption-counter reference in assembly before entering C. Signals remain
unmasked; nested entry or an already-disabled counter defers scheduling through
atomic pending-bit updates. Entry-PC checks cover the pre-acquisition window.
Signal return restarts interrupted assembly per-thread reads from the original
key on the destination pthread. The preemption-counter increment instead defers
scheduling until the matching enable. This prevents migration between TLS
address calculation and access without indefinitely deferring an enabled reader. Kernel signal frames supply full interrupted
register/SIMD state; runtime frames preserve the AAPCS64 callee-save subset.
This design still needs asynchronous stress on the real ARM kernel.

`ksched` preserves the existing Linux task, signal/IPI and cpuidle handoff paths,
uses bounded acquire polling without microsecond gaps, and excludes x86 UINTR/MSR/PMC code
on ARM. UINTR ioctls explicitly return unsupported. Both scheduler modules build
against platform-prepared `6.8.0-146-generic`; they have not been loaded.

mlx5 completion consumption now orders DMA reads after observing ownership, and
CQ arming orders the doorbell record before MMIO and flushes the write. The
patched rdma-core needed an ARM spin hint and a bounded `/run` socket path;
DPDK needed the cross-prefixed archiver, and Caladan needed cross-prefixed
pkg-config. Patched rdma-core, mlx5 DPDK and Caladan userspace cross-build.
The native x86 userspace build also passes as a source regression check, not a
reintroduced calibration experiment.

CPU-only acceptance (`default.nix checks`, QEMU user mode):

- 10,000 register/SIMD-preserving runtime switches inside live signal handlers.
- 999 live signal-frame migrations between pthreads using production per-thread
  allocation/access and preemption-counter operations.
- 20,000 direct switches checking outgoing stack ownership release, including
  6,666 pending-preemption cold paths with a substituted scheduler endpoint.
- 100,000 ordered messages through the production LRPC queue between pthreads.
- 50,000 iterations across four TLS read widths with asynchronous signals and
  QEMU single-instruction translation blocks; interrupted reads poison the saved
  address register and require successful PC restart.
- 10,000 hardware CRC comparisons against a reference, plus checksum tests for
  alignment, odd tails and carry-heavy buffers.
- 10,000 unmasked nested signal deliveries through production entry/prepare,
  disabled-counter deferral and synthetic entry-PC boundary checks.

These are finite mechanism checks, not full-runtime hardware acceptance. The
standalone upstream `test_base_lrpc` attempt stopped at base initialization
because host hugepages were unavailable; no host hugepage configuration was
changed. The isolated production-LRPC test above avoids that dependency. QEMU
cannot establish real ARM weak-memory, kernel scheduling or mlx5 DMA correctness.
No actual CX-5 queue creation, RX/TX, idle wakeup, busy-owner preemption or
memcached result exists yet. Local diagnostics remain in `../caladan/out/`.

The updated Nix runtime, matching modules, CPU checks and standalone SquashFS
image all build successfully. Earlier checkpoint ELF/loader checks identified
AArch64 executables and stopped at the non-root guard before touching hardware.
The module recipe checks matching vermagic and removes build-tree references.

| Fast-path build artifact (source committed as `6f27ec7`) | Store output |
| --- | --- |
| Runtime | `/nix/store/g1csap9vh8njyy73nszllkq312f2y6sc-caladan-directpath-aarch64-unknown-linux-gnu-0.1` |
| Modules | `/nix/store/hcdb1lkaazfb4r3vnla1s4viylg081s0-caladan-ksched-aarch64-unknown-linux-gnu-6.8.0-146-generic` |
| CPU-only checks | `/nix/store/ixzlvpvm54agzhgm1gn9q7qlq6wd9g2k-caladan-arm-context-checks` |
| Image | `/nix/store/3q5cg0hchd7ljyw90g1g5y5sziyc7mlc-caladan-enzian.img` |

These outputs supersede the earlier `0df0f96` artifact set. Exact results are in
`../caladan/out/arm-fastpaths-results.json`, with validation recorded in
`arm-fastpaths-checks.log` and `arm-fastpaths-build.log`. All eight CPU checks
pass; native x86 userspace also builds (`native-fastpaths-build.log`). Disassembly
confirms CRC32CX and inline LL/SC without outlined atomic-add helpers. These
artifacts remain undeployed; their source is now committed as `6f27ec7`.
No board timing or power measurements have been made.

**Hardware gate:** October 19 live EMG showed zuestoll03 reserved by jkaufman,
zuestoll05/06 by hanghu and zuestoll07 by pengxu. The user explicitly instructed
that zuestoll07 remain untouched. No board, root volume or running experiment
was changed. Hardware portions of stages 1–3 await an available CX-5 board.
Recheck reservations at that time; this is dated inventory, not future authority.

**Review gate:** finish/review stages 1–3 before starting stage 4. The user is
extracting the platform Lauberhorn/DPDK echo microbenchmark for cross-repository
reuse. Wait for that implementation; do not create a separate Caladan echo
benchmark or start measurement/scheduling-integration campaigns meanwhile.

Update this checkpoint and stage table as work proceeds, recording revisions,
checks actually run and remaining failures. Keep temporary diagnostics in ignored
`out/`, preserve unrelated experiments, and retain accepted measurement recipes
and data through the platform's tracked measurement workflow.
