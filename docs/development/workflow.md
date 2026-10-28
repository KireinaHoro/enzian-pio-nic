# Development workflow

Run commands from the repository root. This guide builds images and their
matching collateral. Continue with [validation](validation.md#board-validation)
for board provisioning, automated orchestration and interactive testing. Record
which checks actually ran.

## Choose a development flow

These flows run from a terminal without an agent. `nix build` realizes a pinned
product or check; `nix develop -c COMMAND` runs an interactive development tool
with the repository dependencies. Replace uppercase placeholders with reviewed
local paths or configuration values before execution.

| Flow | Start here | Main command |
| --- | --- | --- |
| RTL simulation | [Select a suite and inspect failures](validation.md#local-checks-and-simulation) | `nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim` |
| Software-only VM test | [KVM prerequisites and test driver](../software/vm-backend.md#validation) | `nix build .#checks.x86_64-linux.userspace-vm -L` |
| Build and deploy on Enzian | [Build products](#build-commands-and-outputs), then [reserve, stage and test](validation.md#board-validation) | `nix build .#microbenchmarksImage -L`, then `nix develop -c python3 tools/enzian/test.py CASES.json --logs out/board-RUN --repeats 2` |
| Echo measurement campaign | [Configure hardware and launch](benchmark-campaign.md#terminal-use-without-an-agent) | `nix develop -c python3 tools/paper/benchmark_campaign.py launch --config CAMPAIGN.json --root out/echo-RUN` |

The VM tests software transport and lifecycle; RTL simulation exercises hardware
models. Neither substitutes for matched-image Enzian acceptance. Deployment and
campaign commands require reserved boards and prepared configuration, not just
a successful image build.

The [maintained tools reference](tools.md) identifies the source entry points,
Nix owners, inputs and results behind these flows. Follow the flow guides for
operating procedures; skills are not required to invoke the tools.

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

See the [Nix output inventory](nix.md) for deployment products,
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

After building an image, continue with [board validation](validation.md#board-validation).
That guide owns provisioning, matching-artifact checks, automated orchestration,
interactive `lh-test` commands, boot acceptance and failure preservation.
For application-only iteration, use the [package interface](#package-interface)
and [local input overrides](#compose-an-external-repository) below. Keep generated
hardware/ABI inputs fixed unless the change intentionally updates that contract.

## CI and documentation drift

See [the CI handoff contract](ci.md): Nix runs regression checks, generates RTL and
builds matching software in a pinned Nix container. A separate hosted Vivado job
consumes the portable source/checkpoint bundle without a checkout or regeneration.
See the CI guide for gated checks and opt-in suites. The
[application packaging contract](#application-packaging) defines images and local
overrides; [validation](validation.md) defines how to exercise them.

Evidence: [`build.mill`](../../build.mill), [`flake.nix`](../../flake.nix), [CI](../../.gitlab-ci.yml), [generator](../../hw/src/lauberhorn/GenEngineVerilog.scala), [static-shell bitstream writer](../../vivado/eci/static-shell/write_bitstream_app.tcl), [runtime Makefile](../../sw/rt/Makefile), [kernel Makefile](../../sw/kmod/Makefile).

For routed checkpoint analysis and source-level timing closure, use the
[physical implementation guide](../hardware/physical-implementation.md).

## Nix source and client closure hygiene

Use `lib.cleanSource` plus an explicit `cleanSourceWith` allowlist (or the
existing source filesets) for new derivations. Do not pass the whole checkout
as a build input: exclude generated `result`/`out` trees, captures, build
products and unrelated submodules. Audit the actual store source contents;
filtering a derivation cannot undo an earlier full-checkout flake snapshot.
Measurement archives belong in the separate sibling `../data` repository, not
in the platform Git tree, a submodule, or a flake input. Run analysis with
`nix develop -c python3 ... --input ../data/...`: Nix supplies tools while Python
reads evidence from the filesystem. Only the two small capture regression
fixtures under `hw/test/fixtures/` are retained as build inputs. Trace capture
and decoding tools live under `tools/trace/`.

Use `--no-link` for checks whose outputs need no persistent result link. Named
`out/` build links are GC roots; remove them after their experiment and any CI
consumers finish, preserving required evidence and deployment artifacts first.

The data-history migration rewrites `master` only. Do not merge an old-history
branch directly into rewritten master: that would make its archived data
history reachable again. Replay the branch's unique work onto the new master
with `git rebase --onto master OLD_BASE BRANCH` (or cherry-pick selected commits),
where `OLD_BASE` is its merge base with pre-migration master
`8bf844fa2fbdde162cae94d1d62b04f144241b86`. Use `--rebase-merges` when the branch's
merge structure needs preserving. Resolve dataset changes into `../data` and
tool changes into `tools/trace`; then validate before merging. The data
repository's `platform-commit-map.tsv` maps original acquisition/source hashes
to rewritten commits. Other refs continue retaining old data history until the
branch scrub removes or migrates them.

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

This boot bundle selects the following gateway-relative kernel/initrd paths:

```sh
boot_kernel="$USER/lauberhorn-6.8.0-146-ptp/vmlinuz"
boot_initrd="$USER/lauberhorn-6.8.0-146-ptp/initrd.img"
```

Workflows using this bundle include `copymods=force` in their kernel command
line and match deployment modules to `6.8.0-146-generic`. The board-validation or
campaign workflow supplies its root volume and remaining kernel options. Apply
those values using the [Enzian skill's EMG command forms](../../tools/enzian/skills/enzian-deploy/references/emg.md#select-a-kernel-and-supply-its-command-line);
follow its release/reacquire procedure for a boot-selection change. Use the
normal cold-boot test harness.
The stock `copymods` hook mounts the initramfs module tree in RAM over
`/lib/modules` in the running system. Thus boot-time and later module loads
use the same packaged files without modifying the persistent root module tree.

For DPDK hardware timestamp runs, `lh-dpdk-run --ptp-bgx 4` requires
`--manifest /nix/store/lauberhorn-client/manifest.json`. Before rebinding the data
VF it checks the running kernel, benchmark package, installed module hashes,
loaded module source versions, and active PHC. Its `before.json` records this
provenance. Supported builds use the shared package input.

The sibling README documents PHC fallback activation, BGX/LMAC selection,
packet-kind mapping, API scope, and the outstanding board acceptance. For local iteration before
local changes are published, override the pinned Git input with the local sibling
checkout. Refresh it after sibling edits with
`nix flake lock --override-input thunderx-ptp path:"$(realpath ../thunderx-dpdk-ptp)"`.
After publishing that repository, restore the Git pin using
`nix flake update thunderx-ptp`.

## Microbenchmark sources

The non-flake `microbenchmarks` input owns the schema, handlers, Lauberhorn
server and DPDK clients/responders in `../microbenchmarks`, packaged like
memcached. DPDK responder builds receive the filtered platform
RPC/tracing sources explicitly; client builds are independent of that runtime; the Lauberhorn server receives the matching
runtime package. Recovery tests use the schema from that same input.
For local edits use `--override-input microbenchmarks path:../microbenchmarks`;
published builds use the Git revision in `flake.lock`. The Caladan echo server
has its own `server/caladan/package.nix`; the platform exposes `mkCaladan`
for integration. See the [Caladan baseline](../research/caladan-enzian.md) for
the selected configuration and acceptance scope.

The benchmark repository separates `server/lauberhorn`, `server/dpdk`,
and the DPDK-only `client`. Each has its own Makefile and Nix package.
`trafficGenerator{,Native}` contains only `bench_dpdk`; `dpdkServer{,Native}`
contains the two responder instrumentation variants. The mlx5 equivalents are
`mlx5TrafficGenerator{,Native}` and `mlx5Server{,Native}`. Deployment images
explicitly compose client and responder packages.

## Application packaging

Linux deployment images contain target AArch64 binaries and their runtime closure.
Use the following contract for external applications, then follow
[board validation](validation.md#board-validation) to test the built image.

### Package interface

An application repository supplies a root `package.nix` accepting named arguments:

```nix
{ stdenv, pkg-config, lauberhornRuntime }:
stdenv.mkDerivation {
  pname = "my-service";
  version = "0.1";
  src = ./.;
  nativeBuildInputs = [ pkg-config ];
  buildInputs = [ lauberhornRuntime ];
  buildPhase = ''
    $CC $CFLAGS $( $PKG_CONFIG --cflags lauberhorn) main.c \
      $LDFLAGS $( $PKG_CONFIG --libs lauberhorn) -o my-service
  '';
  installPhase = ''
    install -Dm755 my-service $out/bin/my-service
    mkdir -p $out/share/my-service
    cp -r assets/. $out/share/my-service/
  '';
  meta.mainProgram = "my-service";
}
```

The platform supplies the target compiler, libraries and matching runtime through
`platform.callPackage`. Do not select `pkgsCross` inside the application. Runtime
`out/lib/liblauberhorn.so` and `dev/include`/`dev/lib/pkgconfig/lauberhorn.pc` form
the public interface; libtirpc is propagated and declared in pkg-config. Generated
headers are private runtime/module inputs from the same RTL generation. The
[in-tree demo](../../sw/apps/nix-build-demo/package.nix) compiles and links against
the installed public interface, without handwritten ABI declarations.
The [microbenchmarks recipe](../../../microbenchmarks/server/lauberhorn/package.nix) also lives
in the sibling repository and owns source selection and its standalone Makefile. `nix/platform.nix` supplies the matching runtime and composes
the applications into deployment images.

Return one derivation. Install executables in `$out/bin`, immutable assets in
`$out/share/<application>`, and set `meta.mainProgram` for the default executable.
Resolve assets from their installed paths: execution must work without a checkout
or a particular working directory. Accept writable state, configuration overrides
and log destinations outside `$out` (arguments or documented environment variables).
Optional `passthru.interactiveTests = { smoke = testPackage; };` contains runnable
packages; images include them only when explicitly selected.

### Compose an external repository

In a consumer flake, pin the platform and applications as inputs. Commit the lock
file. Keep substantial applications in their own repositories; hardware libraries
and simple in-tree demos stay here. Example input and output fragments:

```nix
inputs.platform.url = "git+ssh://git@gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform?submodules=1";
inputs.application = { url = "git+https://YOUR-SERVER/YOUR-APPLICATION"; flake = false; };

# Inside outputs, with a build-machine pkgs imported from the pinned nixpkgs:
platform = inputs.platform.lib.mkPlatform { inherit pkgs; };
application = (platform.callPackage (inputs.application + "/package.nix") {}).overrideAttrs (old: {
  passthru = (old.passthru or {}) // { sourceIdentity = {
    revision = inputs.application.rev or null;
    local = !(inputs.application ? rev);
    narHash = inputs.application.narHash or null;
  }; };
});
# Expose these in packages.${system}:
service = application;
image = platform.mkApplicationImage {
  name = "my-service";
  applications = { my-service = application; };
  extraContents = [];
};
# Expose in devShells.${system}; target compilation, native build tools:
shell = platform.target.mkShell { inputsFrom = [ application ]; };
```

Use a complete flake's usual `let ... in`/output structure around these fragments.
An optional standalone application flake reuses its root recipe. Input source
identity is explicit because a derivation's `src` alone cannot establish its Git
revision or whether a local checkout was used. Absent metadata is recorded as
unknown, never assumed clean. The manifest records source paths and package paths,
platform revision/local status, collateral store identities and kernel release.

Local overrides affect application packages, their shells and images together:

```sh
nix build .#service --override-input application path:/absolute/application --no-write-lock-file
nix develop .#shell --override-input application path:/absolute/application --no-write-lock-file
nix build .#image --override-input application path:/absolute/application --no-write-lock-file
```

Do not change platform or hardware inputs to iterate on an application. Select
`application.interactiveTests.smoke` under a separate image application name when
wanted. `platform.mkDeploymentEnvironment { applications; extraContents = []; }`
constructs just the stable environment; `mkApplicationImage` includes it and its closure.
Extra packages contribute bin/sbin tools with collision checking; applications
retain all their executables below their own named link.

## DPDK microbenchmark baseline images

Build the maintained ConnectX-5 responder and board image for the
[DPDK research baseline](../research/dpdk-baseline.md):

```sh
nix build .#dev.x86_64-linux.software.mlx5ServerNative -o out/mlx5-native
nix build .#mlx5BenchmarkImage -o out/mlx5-image
```

The shared `traffic-generator.nix` image includes both responder instrumentation
variants and the generator. Continue with
[DPDK board readiness](validation.md#dpdk-baseline-readiness); building the native
responder alone does not validate the target NIC or the AArch64 image.
