# Nix build and deployment interface

[`flake.nix`](../../flake.nix) is the public output inventory.
[`nix/platform.nix`](../../nix/platform.nix) composes matching
hardware, kernel modules, runtime and applications. Leaf recipes use
`callPackage`; constructors returning sets use `import`.

Paths such as `software/` and `images/` below are relative to `nix/`; other
repository paths are identified explicitly. Use the [development workflow](workflow.md)
for image builds and [validation](validation.md#board-validation) for testing them.

## Outputs

| Set | Purpose | Example |
| --- | --- | --- |
| `packages.SYSTEM` | Complete deployment products | `nix build .#microbenchmarksImage` |
| `dev.SYSTEM.hardware` | RTL, device headers and DCS ROM | `nix build .#dev.x86_64-linux.hardware.rtl` |
| `dev.SYSTEM.kernel` | Prepared build tree and individual modules | `nix build .#dev.x86_64-linux.kernel.lauberhornModule` |
| `dev.SYSTEM.software` | Individual applications, runtime and DPDK | `nix build .#dev.x86_64-linux.software.runtime` |
| `checks.SYSTEM` | Regression and interface tests | `nix build .#checks.x86_64-linux.runtime-tests` |
| `apps.SYSTEM` | CI/build/report commands | `nix run .#prepareEci` |
| `ci.SYSTEM` | CI cache-warming closures | `nix build .#ci.x86_64-linux.dependencies` |
| `devShells.SYSTEM.default` | Development environment | `nix develop` |
| `formatter.SYSTEM` | Nix formatter | `nix fmt -- flake.nix` |
| `lib.mkPlatform` | Composition API for external applications | See [application packaging](workflow.md#application-packaging) |

Standard `packages`, `checks` and `apps` stay flat within each system. Custom
`dev` and `ci` sets require explicit system paths. `checks.SYSTEM.output-interface`
forces evaluation of their leaf derivations during `nix flake check --no-build`;
its build records those derivation paths without realizing the packages. Linux exposes deployment/software outputs; Darwin exposes the shell,
hardware development outputs and applicable checks. Exporting an attribute does
not build it or imply Darwin deployment support.

The old flat `deployFs`/`*DeployFs` names are now `microbenchmarksImage`,
`dandelionImage`, `memcachedImage`, and `trafficGeneratorImage`. `bootFiles` and
`eciVivadoInputs` retain their names. Individual packages moved to `dev`:
`genVerilog` → `hardware.rtl`, `devHdrs` → `hardware.deviceHeaders`,
`kmod` → `kernel.lauberhornModule`, and `memcachedReference` →
`software.memcachedReferenceNative`. Other software retains its descriptive name.
`runtimeTests` and `dummy-app-build-with-nix` are available only as
`checks.SYSTEM.runtime-tests` and `checks.SYSTEM.runtime-interface`.
Dated measurement records retain the commands supported by their pinned revision.

## Kernel and image ownership

`software/ubuntu-noble.nix` owns the single Ubuntu ABI pin and downloads.
`software/kernel.nix` prepares `kernelBuildTree` from full source, using only
`.config` and `Module.symvers` from the headers package, then running
`olddefconfig` and `modules_prepare`. It does not compile a kernel.
`software/module.nix` builds Lauberhorn against that tree; the pinned ThunderX
input copies and patches Cavium driver sources and builds PTP/BGX/NICVF against
that same tree. No standalone headers-only build is used.

`images/default.nix` selects eight deployment products:

- `bootFiles`: `images/boot.nix` combines the signed Ubuntu kernel and packaged
  modules with patched ThunderX modules and stock cloud-image initramfs userspace.
  It emits kernel/initrd files and hashes for EMG, not a root filesystem/container.
- `microbenchmarksImage`, `dandelionImage`, `memcachedImage`: the common
  `images/application.nix` constructor packages applications, runtime, Lauberhorn
  module, helpers and generated collateral as a SquashFS Nix-store closure.
  These currently also include patched ThunderX modules and DPDK.
- `trafficGeneratorImage`: `images/traffic-generator.nix` packages the cross-built
  separate DPDK generator and responder packages, Python tools, utilities and
  patched modules as a SquashFS closure.
- `mlx5BenchmarkImage`: the same traffic-generator constructor with mlx5 DPDK,
  separate generator/responder packages and probe-enabled responder dependencies.
- `echoCampaignImage`: the mlx5 image plus matching Lauberhorn `off` and
  `breakdown` application/runtime variants for the shared campaign workflow.
- `eciVivadoInputs`: `hardware/vivado-inputs.nix` packages sources, generated
  collateral and the pinned static-shell checkpoint. Vivado builds the FPGA image
  separately using the image pinned in `vivado/eci/container.yml`.

The persistent root volume is provisioned separately through EMG. The CI Docker
image is built from `tools/ci/image/Dockerfile` by the external `ci-images`
repository; its Nix inputs are `ci.SYSTEM.environment` and `ci.SYSTEM.dependencies`.
See [CI](ci.md) for publication and cache auditing.

## Maintained special-purpose recipes

- `images/memcached-matched.nix`: application/runtime overrides on immutable
  hardware; supports both historical and current platform constructors.
- `hardware/worker-configurations.nix`: modules/runtime for generated worker
  layouts, using the selected platform's composition API.
- `benchmarks/dandelion-baseline/`: upstream baseline package, source locks and
  runner/validation helpers. This is independent of the Lauberhorn runtime.

Nix recipes belong with these build owners, not in `tools/`. The sole retained
exception is `tools/enzian/nullrpc_inline_build.nix`, an immutable historical
recipe referenced by active paper provenance. Keep it until that provenance can
be migrated together.
`tools/enzian/build_deploy.py` remains a supported orchestration command for
rebuilding matching CI images, including historical output names.

`toolchain/` owns native development tools, `interactive/` target helper tools,
`checks/` validation, and `ci/` CI closures and commands. Application recipes live
beside `sw/apps` sources or in their upstream repositories. Use the installed
runtime contract and `lib.mkPlatform` for external applications; do not import
private image internals. Nixpkgs supplies compilers and standard tools, Mackerel
comes from its pinned input, and `build.mill` owns RTL/ABI generation.

See [microbenchmark sources](workflow.md#microbenchmark-sources) for source
ownership and [campaigns](benchmark-campaign.md) for measurement orchestration.
Microbenchmark software packages are separated by role: `trafficGenerator`
contains only `bench_dpdk`, while `dpdkServer` contains `echo_dpdk` and
`echo_dpdk_uninstrumented`. Each has a `Native` variant. For mlx5 use
`mlx5TrafficGenerator` and `mlx5Server` (also with `Native` variants). The
previous bundled `mlx5Benchmark` and `mlx5BenchmarkNative` outputs are retired.
