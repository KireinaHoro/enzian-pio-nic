# Development workflow

Run commands from the repository root. Build checks and real-board validation are separate; record which checks ran.

## Environment and ownership

- Use `nix develop` (or `nix develop -c <command>`). `.envrc` enables the same flake through direnv. Recursive submodules are required; relevant local libraries are `deps/blocks` and `deps/spinalhdl`.
- `flake.nix` exposes `x86_64-linux` and `aarch64-darwin` outputs; Linux is the documented kernel-module build host. Do not infer that every output builds on Darwin.
- `.mill-version` pins Mill 1.1.8; `build.mill` pins Scala 2.13.12. `nix/hardware/static-shell.nix` pins static shell v0.1.5 and its content hash. The shell supplies JDK, Mill, Verilator, GHDL, GTKWave, formal tools, Mackerel, aarch64 cross GCC, and libpcap runtime lookup.
- `build.mill` owns Scala compilation, simulation and RTL/ABI generation; `flake.nix` composes the functionality groups under `nix/` for reproducible RTL/header/software packaging. `sw/*/Makefile` are lower-level builds. Vivado is separately installed/licensed; it is not supplied by the flake. The shared Docker runner uses Vivado 2025.1.
- `nix develop` sets `XDG_CACHE_HOME=$PWD/out/xdg-cache-home/`. After changing Mill dependencies, follow the [clean-cache lock regeneration procedure](ci.md#local-validation-and-maintenance): clear the XDG cache, resolve targets with fresh Mill outputs, then run `update-mill-lock`. The tool captures stale versions if the cache is not cleaned; review removals as well as additions in `project-lock.nix`. Use `--no-daemon` when changed environment variables must reach Mill subprocesses.

## IDE setup

Generate an IntelliJ IDEA project from the repository root:

```sh
nix develop -c mill mill.idea.GenIdea/idea
```

## Build commands and outputs

See the [Nix output inventory](../../nix/README.md) for deployment products,
`dev.SYSTEM` build conveniences, `checks`, `apps`, and CI cache closures.

| Intent | Command | Output / prerequisites |
| --- | --- | --- |
| Compile Scala hardware | `nix develop -c mill gen.compile` | First-level compile check; does not elaborate RTL. |
| Generate ECI RTL and ABI descriptions | `nix develop -c mill eci.generateVerilog` | `out/eci/generateVerilog.dest/`: RTL, XDC, `config.h`, `regblock_bases.h`, Mackerel `.dev`, trace-map JSON. |
| Package generated RTL | `nix build .#dev.x86_64-linux.hardware.rtl -L` | `result` contains RTL/XDC; derivation also has `headers` and `devices` outputs. The default output also retains trace-map JSON. |
| Package all Vivado inputs | `nix build .#eciVivadoInputs -L` | Portable source/RTL/XDC/static-shell bundle; see [CI](ci.md). |
| Generate Mackerel C headers | `nix build .#dev.x86_64-linux.hardware.deviceHeaders -L` | Compiles static `sw/devices/*.dev` and RTL-generated device descriptions. |
| Create Vivado project / build ECI FPGA image | [Docker hardware build](hardware-build.md) | Same bundled runner and image as CI; installed/licensed Vivado required on Docker host. |
| Cross-build kernel module | `nix build .#dev.x86_64-linux.kernel.lauberhornModule -L` | `result/lauberhorn.ko`; pinned Ubuntu arm64 kernel 6.8.0-146.146. Match the target kernel ABI. |
| Cross-build runtime | `nix build .#dev.x86_64-linux.software.runtime -L` | `result/lib/liblauberhorn.so`; public headers/pkg-config in `runtime.dev`; aarch64, ThunderX tuning, libtirpc. |
| Build deployment filesystem | `nix build .#microbenchmarksImage -L` | SquashFS containing microbenchmarks, kernel module, provenance manifest and closure dependencies. |

`pcie.generateVerilog` remains a legacy RTL target; PCIe hardware CI is disabled.
Mill Vivado/project/bitstream targets have been removed. Use the Nix bundle and
Docker runner for ECI hardware builds.

Do not invoke plain `make` after RTL generation and assume all prerequisites exist. Both runtime and kernel Makefiles require explicit `HW_CFG_HDRS` and `MACKEREL_DEV_HDRS`; the latter must contain compiled C headers, not `.dev` inputs. Manual runtime builds also need target libtirpc/pkg-config setup. Manual module builds need the correct `KDIR`, `ARCH=arm64`, and `CROSS_COMPILE`. Prefer the Nix derivations, which wire these together.

## Deployment and debugging

The deprecated `sw/utils/run_container.sh` and `sw/kmod/reload_kmod.sh` have been
removed. Use `nix develop` for development and the shared image's `lh-test load`
for module loading. If replacing a loaded module is intended, stop applications
and explicitly unload it first; the helper never unloads a working module.
See [interactive testing](interactive-testing.md) for helper and manual equivalents.

- For reservation, automated BDK hold, JTAG programming and RPC smoke testing, see [automated hardware test quickstart](hardware-test.md).
- Keep FPGA image, generated headers, module and runtime from a matching revision/configuration. Generation embeds a shortened Git revision; dirty Nix builds use an unknown-version marker. Preserve provenance separately for experiments.
- The module build must pass Ubuntu's `KERNELRELEASE=6.8.0-146-generic` during kernel preparation and module compilation; the source Makefile's upstream version is not the boot kernel release. The flake checks the built module's vermagic. Verify it against `uname -r` before loading.
- On the target, `insmod lauberhorn.ko` initializes `/dev/lauberhorn` and bypass netdev `lauberhorn0`. Follow [kernel module usage](../../sw/kmod/README.md) for interface setup; MAC addresses in examples are machine-specific. Hardware access and module loading require target access/privilege.
- For trace acquisition/export, use [trace tool instructions](../../data/eci/sys_trace/README.md) and [trace architecture](tracing.md). Pair dumps with the trace-map JSON from the matching FPGA build. The Nix `genVerilog` default output retains this map.

## CI and documentation drift

See [the CI handoff contract](ci.md): Nix runs regression checks, generates RTL and
builds matching software in a pinned Nix container. A separate hosted Vivado job
consumes the portable source/checkpoint bundle without a checkout or regeneration.
Full slow suites and TLA+ are not CI gates. See [interactive testing](interactive-testing.md) for the installed application
contract, shared images, local overrides and helper/manual commands.

Evidence: [`build.mill`](../../build.mill), [`flake.nix`](../../flake.nix), [CI](../../.gitlab-ci.yml), [generator](../../hw/src/lauberhorn/GenEngineVerilog.scala), [static-shell bitstream writer](../../vivado/eci/static-shell/write_bitstream_app.tcl), [runtime Makefile](../../sw/rt/Makefile), [kernel Makefile](../../sw/kmod/Makefile).

For routed checkpoint analysis and source-level timing closure, use the
[physical implementation guide](../hardware/physical-implementation.md).

## Nix source and client closure hygiene

Use `lib.cleanSource` plus an explicit `cleanSourceWith` allowlist (or the
existing source filesets) for new derivations. Do not pass the whole checkout
as a build input: exclude generated `result`/`out` trees, captures, build
products and unrelated submodules. Audit the actual store source contents;
filtering a derivation cannot undo an earlier full-checkout flake snapshot.

The DPDK generator selects benchmark build files and shared platform RPC/tracing
sources explicitly; its client image selects four platform Python tools and the
benchmark repository’s replay tool. Build `.#trafficGeneratorImage` on the build host and deploy
the cross-built runtime closure. DPDK headers/static archives and Python setup
tools have separate `dev`/`tools` outputs, which are not shipped to the client.
Do not ship a toolchain or compile/install Ubuntu build dependencies on Enzian.

## ThunderX CPU-NIC timestamps

The `thunderx-ptp` flake input supplies reusable patches from
[`../thunderx-dpdk-ptp`](../../../thunderx-dpdk-ptp/README.md). Its `package.nix`
accepts the prepared `linux-noble` tree already pinned by this platform.
`nix build .#dev.x86_64-linux.kernel.thunderxPtpModules .#dev.x86_64-linux.software.dpdk` builds the arm64 modules and
DPDK runtime; `nix build .#checks.x86_64-linux.thunderx-ptp` exercises the
actual PMD's RX/TX descriptor handling with synthetic rings.

Application deploy images include `cavium_ptp.ko`, `nicvf.ko`, and
`thunder_bgx.ko` under `/lauberhorn/modules`, plus the patched DPDK runtime at
`/lauberhorn/apps/thunderx-dpdk`. The traffic-generator image includes the
same modules under `/lauberhorn-client/modules` and links its generator against
that DPDK. Both manifests record package paths and the required kernel release.
These files are shipped for an explicit matched driver transition, not
implicitly loaded over a running management NIC. No nicpf patch is required.

`nix build .#bootFiles -o result-boot` produces a directory ready for
`rsync -rL result-boot/ enzian-gateway:/srv/tftp/userkernels/$USER/lauberhorn-6.8.0-146-ptp/`.
It contains `vmlinuz`, `initrd.img`, `manifest.json`, `modules.sha256`, and
`SHA256SUMS`. [Ubuntu pins](../../nix/software/ubuntu-noble.nix) are shared by
kernel preparation and [boot assembly](../../nix/images/boot.nix). The assembly
downloads the stock Ubuntu arm64 cloud image, extracts its initramfs userspace,
and replaces its entire module tree with the pinned 6.8.0-146 packages plus the
patched modules. The signed stock kernel is unchanged. Module selection, ABI,
byte identity and iSCSI boot support are checked during the build. No target-side
compilation or manually generated initramfs is an input.

Select these files only through `emg acquire` with
`-k "$USER/lauberhorn-6.8.0-146-ptp/vmlinuz"` and
`-i "$USER/lauberhorn-6.8.0-146-ptp/initrd.img"`, retaining the intended root
volume and isolation arguments, and adding `copymods=force` to the kernel
arguments. Migrating an existing reservation requires
preserving measurements, shutting down, and releasing/reacquiring through EMG;
never edit boot configuration directly. Use the normal cold-boot test harness.
The stock `copymods` hook mounts the initramfs module tree in RAM over
`/lib/modules` in the running system. Thus boot-time and later module loads
use the same packaged files without modifying the persistent root module tree.

For DPDK hardware timestamp runs, `lh-dpdk-run --ptp-bgx 4` requires
`--manifest /nix/store/lauberhorn-client/manifest.json`. Before rebinding the data
VF it checks the running kernel, benchmark package, installed module hashes,
loaded module source versions, and active PHC. Its `before.json` records this
provenance. The old standalone test driver and exploratory probe recipes have
been retired; all supported builds use the shared package input.

The sibling README documents PHC fallback activation, BGX/LMAC selection,
packet-kind mapping, API scope, and the outstanding board acceptance. For local iteration before
local changes are published, override the pinned Git input with the local sibling
checkout. Refresh it after sibling edits with
`nix flake lock --override-input thunderx-ptp path:"$(realpath ../thunderx-dpdk-ptp)"`.
After publishing that repository, restore the Git pin using
`nix flake update thunderx-ptp`. The no-commit/no-push iteration rule applies
to plotting changes; keep those separate from driver and measurement commits.

## Microbenchmark sources

The non-flake `microbenchmarks` input owns the schema, handlers, Lauberhorn
server and DPDK clients/responders in `../microbenchmarks`, packaged like
memcached. DPDK responder builds receive the filtered platform
RPC/tracing sources explicitly; client builds are independent of that runtime; the Lauberhorn server receives the matching
runtime package. Recovery tests use the schema from that same input.
For local edits use `--override-input microbenchmarks path:../microbenchmarks`;
published builds use the Git revision in `flake.lock`. Caladan support is
reserved for a later change in the benchmark repository.

The benchmark repository now separates `server/lauberhorn`, `server/dpdk`,
and the DPDK-only `client`. Each has its own Makefile and Nix package.
`trafficGenerator{,Native}` contains only `bench_dpdk`; `dpdkServer{,Native}`
contains the two responder instrumentation variants. The mlx5 equivalents are
`mlx5TrafficGenerator{,Native}` and `mlx5Server{,Native}`, replacing the bundled
`mlx5Benchmark{,Native}` outputs. Deployment images explicitly compose client
and responder packages. The socket client is retired; historical reproduction
recipes remain pinned to their original revisions.
