# Development workflow

Scope: commands and artifact contracts inspected in source on 2026-09-08; these notes do not certify a successful build on this checkout. Run commands from the repository root.

## Environment and ownership

- Use `nix develop` (or `nix develop -c <command>`). `.envrc` enables the same flake through direnv. Recursive submodules are required; relevant local libraries are `deps/blocks` and `deps/spinalhdl`.
- `flake.nix` exposes `x86_64-linux` and `aarch64-darwin` outputs; Linux is the documented kernel-module build host. Do not infer that every output builds on Darwin.
- `.mill-version` pins Mill 1.1.8; `build.mill` pins Scala 2.13.12 and ECI static shell v0.1.5. The shell supplies JDK, Mill, Verilator, GHDL, GTKWave, formal tools, Mackerel, aarch64 cross GCC, and libpcap runtime lookup.
- `build.mill` is the hardware build graph; `flake.nix` composes the functionality groups under `nix/` for reproducible RTL/header/software packaging. `sw/*/Makefile` are lower-level builds. Vivado is separately installed/licensed; it is not supplied by the flake. CI sources Vivado 2025.1.
- `nix develop` sets `XDG_CACHE_HOME=$PWD/out/xdg-cache-home/`. After changing Mill dependencies, follow the [clean-cache lock regeneration procedure](ci.md#local-validation-and-maintenance): clear the XDG cache, resolve targets with fresh Mill outputs, then run `update-mill-lock`. The tool captures stale versions if the cache is not cleaned; review removals as well as additions in `project-lock.nix`. Use `--no-daemon` when changed environment variables must reach Mill subprocesses.

## Build commands and outputs

| Intent | Command | Output / prerequisites |
| --- | --- | --- |
| Compile Scala hardware | `nix develop -c mill gen.compile` | First-level compile check; does not elaborate RTL. |
| Generate ECI RTL and ABI descriptions | `nix develop -c mill eci.generateVerilog` | `out/eci/generateVerilog.dest/`: RTL, XDC, `config.h`, `regblock_bases.h`, Mackerel `.dev`, trace-map JSON. |
| Package generated RTL | `nix build .#genVerilog -L` | `result` contains RTL/XDC; derivation also has `headers` and `devices` outputs. The default output also retains trace-map JSON. |
| Package all Vivado inputs | `nix build .#eciVivadoInputs -L` | Portable source/RTL/XDC/static-shell bundle; see [CI](ci.md). |
| Generate Mackerel C headers | `nix build .#devHdrs -L` | Compiles static `sw/devices/*.dev` and RTL-generated device descriptions. |
| Create Vivado project | `nix develop -c mill --no-daemon eci.vivadoProject` | Requires `vivado` on PATH; project below `out/eci/vivadoProject.dest/`. |
| Build ECI FPGA image | `nix develop -c mill --no-daemon eci.generateBitstream` | Downloads static-shell checkpoint from ETH GitLab. Tcl writes `shell_lauberhorn-eci.{bit,ltx}` and routed `.dcp` under `out/eci/vivadoProject.dest/`. |
| Cross-build kernel module | `nix build .#kmod -L` | `result/lauberhorn.ko`; pinned Ubuntu arm64 kernel 6.8.0-64.67. Match the target kernel ABI. |
| Cross-build runtime | `nix build .#runtime -L` | `result/lib/liblauberhorn.so`; public headers/pkg-config in `runtime.dev`; aarch64, ThunderX tuning, libtirpc. |
| Build deployment filesystem | `nix build .#deployFs -L` | SquashFS containing microbenchmarks, kernel module, commit marker and closure dependencies. |

`pcie.generateVerilog` and `pcie.generateBitstream` remain legacy targets; PCIe hardware CI is disabled. ECI bitstream task return paths in `build.mill` still describe the ordinary Vivado `impl_1/lauberhorn-eci.bit` layout, while the sourced static-shell Tcl emits the combined shell filenames above. Check this discrepancy when diagnosing task completion/caching; documentation cannot establish that the current task succeeds.

Do not invoke plain `make` after RTL generation and assume all prerequisites exist. Both runtime and kernel Makefiles require explicit `HW_CFG_HDRS` and `MACKEREL_DEV_HDRS`; the latter must contain compiled C headers, not `.dev` inputs. Manual runtime builds also need target libtirpc/pkg-config setup. Manual module builds need the correct `KDIR`, `ARCH=arm64`, and `CROSS_COMPILE`. Prefer the Nix derivations, which wire these together.

## Deployment and debugging

- For reservation, automated BDK hold, JTAG programming and RPC smoke testing, see [automated hardware test quickstart](hardware-test.md).
- Keep FPGA image, generated headers, module and runtime from a matching revision/configuration. Generation embeds a shortened Git revision; dirty Nix builds use an unknown-version marker. Preserve provenance separately for experiments.
- The module build must pass Ubuntu's `KERNELRELEASE=6.8.0-64-generic` during kernel preparation and module compilation; the source Makefile's upstream `6.8.12` version is not the boot kernel release. The flake checks the built module's vermagic. Verify it against `uname -r` before loading.
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
