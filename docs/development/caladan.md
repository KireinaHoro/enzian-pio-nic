# Caladan build, validation and tracing

Run these commands from the sibling `../caladan` checkout. `default.nix` reuses the
platform flake lock and exports `runtime`, `ksched`, `shell`, `environment` and
`image` through `platform.mkCaladan { source = caladanSource; }`.

```sh
nix build --impure --file default.nix runtime
nix build --impure --file default.nix image
nix develop --impure --file default.nix shell
```

The ARMv8 runtime and scheduler modules build. Native CPU checks, module/API
checks and one-/two-kthread runtime smoke tests pass on zuestoll07; broader
scheduling and traffic acceptance remains separate. See the [baseline and evidence](../research/caladan-enzian.md).
The platform composes AArch64 Caladan, mlx5-enabled DPDK and the pinned patched
rdma-core with a matching `ksched` module. There is no ThunderX NICVF dependency
or x86 calibration target. Upstream native x86 implementations remain intact.
IAS/PCM, SPDK and UINTR are disabled; directpath remains enabled as upstream.
The Nix build enables `CONFIG_OPTIMIZE=y` with explicit `-mcpu=thunderx+crc`
and LTO (`-flto=auto -ffat-lto-objects`), overriding upstream native targeting
and fast-math. The shared echo link inherits these flags from the runtime.
Native x86 Nix builds also use LTO, without ThunderX flags or build-host targeting.

Use the simple policy: `simple noht nobw nicpci <CX-5 PCI-address>`.
Add `enable_directpath 1` to runtime configurations and record the negotiated
steering mode. IOKernel scheduling and core allocation remain active. Validate
ARM DMA/MMIO ordering, NIC/firmware, kernel mlx5 support, hugepages and matching
kernel modules before measurements. The image uses `/nix/store/caladan` as its
stable entry point; host mlx5 kernel drivers must match the selected boot kernel.
The selected rdma-core revision is `53ee89b4abb1463e7ae713103f552b2bef1307ce`
with the five Caladan patches; DPDK retains its mlx5 dependencies and memseg setting.
The fixed 2 GiB ingress pool needs sufficient hugepages before startup. The
recorded focused checks used 4096 × 2 MiB pages and a 45-second readiness
deadline; size capacity for the actual configuration and restore temporary
reservations after testing.

User-reported CX-5 boards are zuestoll03, zuestoll05, zuestoll06 and zuestoll07.
Check live reservations before selection; do not interrupt active experiments.
Use a checked-unused golden-derived EMG volume and the
[canonical board-validation workflow](validation.md#board-validation).
Current scope is in the [baseline](../research/caladan-enzian.md).
Keep diagnostic outputs in ignored `out/`.

The ARM implementation uses AAPCS64 callee-save frames, libc-owned TPIDR_EL0
with migration-aware accessors, and kernel signal frames via libc sigaction.
Custom per-user-thread libc TLS (`thread_set_fsbase` with a nonzero value) is not
supported on ARM. Signal return restarts an interrupted per-thread read on its destination
pthread; counter-increment windows defer scheduling until preemption is enabled. Architectural counter frequency must be
an integral number of ticks per microsecond for the existing scheduler units;
unsupported frequencies fail initialization instead of silently drifting.

Run `nix build --impure --file default.nix checks` for the CPU-only QEMU checks
in `tests/arm`. Focused hardware checks passed on the designated CX-5 DUT;
busy-owner preemption and broader scheduling acceptance remain.
The shared echo backend is packaged separately from these primitive checks.

The ARM runtime and CPU checks require ThunderX-1 CRC instructions at build time
(`-march=armv8-a+crc`, ACLE `__crc32cd`). No runtime code patching or software
CRC fallback is used. Downstream ARM code including these headers must also
enable the CRC extension.

See [AArch64 port differences](../research/caladan-enzian.md#arm-port-differences) for the design and
machine-level cost differences from x86 Caladan.
ARM runtime atomics compile with `-mno-outline-atomics` to retain inline LL/SC.

For focused board tests, `nix build --impure --file default.nix testImage`
packages the same runtime/modules plus native CPU checks at
`/nix/store/caladan-checks/bin`. Run `platform` first to check the counter,
page size and CRC requirements. `test_runtime_smoke CONFIG` exercises four
threads with 128 mutex/yield iterations and short timer sleeps; it initializes
the normal IOKernel/directpath connection but sends no benchmark traffic. An
optional second argument holds the runtime for 1–60000 ms for external packet
probes through the existing network stack.

Build `nix build --impure --file default.nix echoImage` for the shared
`../microbenchmarks/server/caladan` service, or select `echoServer` for only the
application. `microbenchmarksSource` can select another checkout. The image
exposes `/nix/store/caladan-echo/bin/echo_caladan CONFIG UDP_PORT` alongside the
normal runtime/modules. It uses the shared schema, handler and RPC codec with
software probes compiled out; use the shared backend configuration and the deployment manifest for client and
static-neighbor settings.

For stage profiling, `echoTraceImage` builds the matching `CALADAN_TRACE`
runtime and application with shared RPC probes and buffered CSV export. The matching shared collector adapter hooks are in platform `master`. Select
them with `--arg rpcSource /path/to/platform/sw`; remaining campaign work is
recorded in [continuation](../../agents/caladan.md). `echoImage` remains probe-free.

Set `LAUBERHORN_SERVER_TRACE_CSV` to a new output file and
`LAUBERHORN_TRACE_CAPACITY` to a bound covering the entire request cohort before
starting the traced server. After traffic stops, send `SIGRTMIN+4` and wait for
`CSV.status.json` with `loss_free=true`. The signal is blocked before runtime
workers are created and consumed by the collector's dedicated pthread. It does
not replace Caladan's SIGUSR1/SIGUSR2 preemption handlers. Export is a single
snapshot; use a new process/output file for each cohort. PMU and event-stream
profiling are outside this adapter.

## CPU checks and their limits

Run `nix build --impure --file default.nix checks` from the Caladan repository root.
The Nix check cross-compiles and runs these bounded tests under QEMU user mode:

- `crc.c`: 10,000 hardware CRC32C results match a bitwise reference, including
  seed truncation and byte order. Built with `-march=armv8-a+crc`.
- `context.c` / `register_probe.S`: integer x19–x29 and low halves of v8–v15
  survive 10,000 runtime switches inside live signal handlers.
- `migration.c`: 999 live signal frames migrate between two pthreads; the real
  per-thread allocator/accessors and preemption counter follow the destination.
  Only memory allocation and logging are substituted to avoid hugepages.
- `direct.c`: 20,000 direct switches preserve stack-local state and clear the
  outgoing stack's busy flag, including 6,666 pending-preemption cold paths.
- `read_restart.c`: 50,000 iterations across all four TLS widths, interrupted
  asynchronously with QEMU single-instruction translation blocks; saved
  address registers are poisoned inside the read window to verify restart from
  the original key. At least one actual interruption is required to pass.
- `queues.c`: production LRPC publishes 100,000 ordered messages between pthreads.
- `checksum.c`: byte-reference comparisons for aligned and unaligned loads,
  odd tails, zero/all-ones/patterned buffers and long carry-heavy buffers.
- `signal.c`: 10,000 unmasked nested deliveries through production entry/prepare,
  disabled-counter deferral and synthetic PCs at each entry-region instruction.

Checks use unconditional assertions even in release builds. Context/direct tests
use real TLS and assembly counter operations; the cold preemption endpoint is a
stub that services the pending flag without the hardware scheduler.
These checks do not load ksched, emulate mlx5 DMA, prove ARM weak-memory behavior
on hardware, or force every asynchronous preemption instruction boundary. The
production ARM signal handler's critical-window deferral requires board stress.
These CPU fixtures contain no network echo benchmark.

The built check binaries are retained in the Nix output for native board runs.
`platform.c` checks the selected board's counter frequency, page size and CRC
capability; it is built but not run under QEMU because the emulator's timer
need not meet the ThunderX runtime's integral ticks/us requirement. The
`testImage` target combines these binaries with the matched runtime/modules.

For analysis, use the [shared hot-path methodology](../research/hot-measurement.md).
A loss-free CPU trace alone is not an accepted wire-latency campaign. Tofino
pairing, instrumentation controls and workload acceptance remain required.

## Unattended hot echo campaigns

The platform `mkCaladanCampaign { source; microbenchmarkSource; rpcSource; }`
composition provides `image`, `off` and `breakdown` outputs. The image includes
the shared traffic generator and both server variants at
`/nix/store/caladan-echo-{off,breakdown}`. Use the
[campaign configuration and lifecycle](benchmark-campaign.md#baseline-selection-and-launch-gates)
with `systems: ["CS-A64"]`. The ordinary Caladan `echoImage` remains useful for
functional checks; the campaign image supplies the pinned Python and shared
NIC runner required by unattended roles.

`CALADAN_ECHO_ACTIVITY` enables per-kthread request counts and CPU masks in both
server variants. Export with `SIGRTMIN+5` after traffic drains. Counts include
warmups and establish placement/activity, not CPU utilization. This counter
path has no clock reads; timing probes remain compiled out of the loaded server.
