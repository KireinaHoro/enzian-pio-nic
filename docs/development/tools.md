# Maintained development tools

Start with the [development flows](workflow.md#choose-a-development-flow) to
choose a task, and the [Nix output inventory](nix.md) to select a build product.
This reference identifies the maintained tools behind those flows. It is not an
inventory of every script under `tools/`: historical experiments and retired
paper acquisition recipes are not supported entry points.

Run host-side Python, Mill and analysis commands with `nix develop -c` from the
repository root, or enter `nix develop` once and run the commands there. Nix build
commands do not require an enclosing development shell. Target-side commands
run in the staged image on the reserved Enzian. Build recipes belong in `nix/`
and application packages; tools orchestrate those products without replacing
source-defined build targets.

## RTL simulation

Flow: [local checks and simulation](validation.md#local-checks-and-simulation).

| Entry point | Owner and purpose | Inputs / results |
| --- | --- | --- |
| `mill gen.test.testOnly SUITE` | [build.mill](../../build.mill), [hardware tests](../../hw/test/src/lauberhorn): targeted Scala/SpinalHDL RTL simulation | Suite/test selector; Mill results under `out/`, simulation evidence described in the validation guide |
| `mill gen.test -l org.scalatest.tags.Slow` | Same test module, excluding slow tests | Fast local regression; not every CI check |
| `nix build .#checks.x86_64-linux.CHECK` | [check definitions](../../nix/checks/default.nix), [simulation builder](../../nix/checks/simulation.nix) | Pinned sources and dependencies; successful check output or retained build failure logs |
| `mill eci.generateVerilog` | [RTL generator](../../hw/src/lauberhorn/GenEngineVerilog.scala) | Generated RTL, headers, device descriptions and trace map; generation alone is not simulation |

Example: `nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim`.
Keep test selection in Mill and Nix; no parallel ad-hoc simulation launcher is
needed under `tools/`. [CMAC checks](../../tools/cmac),
[address-map checks](../../tools/address-map), [progress checks](../../tools/progress)
and [model checks](../../tools/model) support their corresponding Nix checks;
use the [validation guide](validation.md) to select them.

## Software-only VM tests

Flow: [VM backend acceptance](../software/vm-backend.md#validation).

| Entry point | Owner and purpose | Inputs / results |
| --- | --- | --- |
| `nix build .#checks.x86_64-linux.userspace-vm -L` | [VM test recipe](../../nix/checks/vm.nix): NixOS guests running the software backend and network peer | Linux builder with KVM; functional kernel/runtime/application acceptance |
| `nix build .#checks.x86_64-linux.userspace-vm.driver -o out/userspace-vm` | Same recipe's test driver for direct execution | Run `out/userspace-vm/bin/nixos-test-driver -o out/userspace-vm-run` with accessible `/dev/kvm`; retain driver logs in that directory |

The VM recipe owns guest CPUs, packages and test scenarios. It uses the shared
kernel/runtime with the VM backend; it does not exercise FPGA RTL or ECI timing.
No Enzian reservation is required.

## Enzian deployment and acceptance

Flows: [image builds](workflow.md#build-commands-and-outputs),
[FPGA build](hardware-build.md), [board acceptance](validation.md#board-validation)
and [trace preservation](tracing.md#default-agent-capture-pipeline).

| Tool / build entry point | Responsibility | Inputs / results |
| --- | --- | --- |
| `nix build .#microbenchmarksImage` / `.#bootFiles` / `.#eciVivadoInputs` | [Image composition](../../nix/images/default.nix), [boot bundle](../../nix/images/boot.nix), [Vivado inputs](../../nix/hardware/vivado-inputs.nix) | Matching software closure, kernel/initrd or portable FPGA build inputs; none deploys by itself |
| [hardware/docker-vivado.sh](../../tools/hardware/docker-vivado.sh), [hardware/run-vivado.sh](../../tools/hardware/run-vivado.sh) | Shared local/CI FPGA build runner | Prepared bundle, licensed Vivado host; bitstream, LTX, finalized trace map and reports |
| [enzian/ci_artifacts.py](../../tools/enzian/ci_artifacts.py) | Read CI status and fetch successful hardware artifacts | Job ID, local token file, new destination; artifacts and SHA256 manifest |
| [enzian/build_deploy.py](../../tools/enzian/build_deploy.py) | Rebuild an image from an immutable CI revision | Revision and output link; image and build provenance; historical packaging changes still require ABI review |
| `emg` on the gateway | Reservations, boot selection and root volumes | [Recorded command forms](../../tools/enzian/skills/enzian-deploy/references/emg.md); explicit board, volume and boot settings |
| [enzian/test.py](../../tools/enzian/test.py) | Reset/program/boot and run image/client acceptance cases | Reviewed case JSON, new logs directory, repeat count; compact stages and `summary.json` |
| [enzian/boot.py](../../tools/enzian/boot.py), [console.py](../../tools/enzian/console.py), [run.py](../../tools/enzian/run.py) | Shared boot gates and bounded serial command execution | Reserved machine and programmer/command configuration; stage output and retained logs |
| [enzian/cpu.sh](../../tools/enzian/cpu.sh), [lh-test.sh](../../tools/enzian/lh-test.sh) | CPU-side mount/load/verify and interactive application operations | Staged matching image; use [interactive instructions](validation.md#interactive-image-testing) |
| [enzian/trace_capture.py](../../tools/enzian/trace_capture.py), [trace package](../../tools/enzian/trace/README.md) | Shared manual/campaign freeze, CMAC dump, integrity checks and persistence | Matching trace configuration/map; preserved capture and validation result |

Build an image, reserve/provision using EMG, stage the matching FPGA/software
artifacts, then invoke the harness:

```sh
nix develop -c python3 tools/enzian/test.py CASES.json --logs out/board-RUN --repeats 2
cat out/board-RUN/summary.json
```

The [example case](../../tools/enzian/adder.example.json) is historical and must
be updated using the board guide; its paths and identities are not defaults for
a new run. Preserve failed captures before resetting. The harness owns cold
boot and lane acceptance; Linux login alone is not ECI acceptance.

## Echo campaigns

Flow: [terminal campaign setup and launch](benchmark-campaign.md#terminal-use-without-an-agent).

| Tool | Responsibility | Inputs / results |
| --- | --- | --- |
| [paper/benchmark_campaign.py](../../tools/paper/benchmark_campaign.py) | Detached controller, locks, stage receipts, status, resume and notification | Campaign JSON and fresh root; `status.json`, `events.jsonl`, stage evidence and notification status |
| [paper/echo_hardware.py](../../tools/paper/echo_hardware.py), [campaign/adapter.py](../../tools/paper/campaign/adapter.py), [campaign/hardware.py](../../tools/paper/campaign/hardware.py) | Concrete hardware adapter and acquisition | Hardware JSON, controller stage request; validated observations using the shared Enzian harness |
| [campaign/tofino.py](../../tools/paper/campaign/tofino.py) | Adapter to the sibling Tofino collector | Unloaded capture configuration; external wire-duration pairs |
| [paper/transfer_results.py](../../tools/paper/transfer_results.py), [enzian/image.py](../../tools/enzian/image.py) | Dedicated transfer endpoint and hash-verified image execution | Campaign directories, staged images and expected hashes |
| [paper/qualify_loaded.py](../../tools/paper/qualify_loaded.py) | Bounded duration qualification | Hardware configuration and qualification settings; diagnostics outside paper cohorts |
| [campaign/import_loaded.py](../../tools/paper/campaign/import_loaded.py), [import_unloaded.py](../../tools/paper/campaign/import_unloaded.py), [finalize.py](../../tools/paper/campaign/finalize.py) | Validate observations and assemble reproducible archives | Raw evidence and provenance; validated cohorts, archives and figures |
| [paper/plot_echo.py](../../tools/paper/plot_echo.py), [plot_echo_unloaded.py](../../tools/paper/plot_echo_unloaded.py) | Reproduce loaded/unloaded figures | Validated inputs read at runtime from `../data`; PDFs and supporting results |
| [paper/check_charts.py](../../tools/paper/check_charts.py), [paper_chart.py](../../tools/paper/paper_chart.py) | Inventory validation and shared publication style | [Chart contract](paper-charts.md), inventory and paper sources |

`campaign/` modules are implementation components, not independent alternative
campaign launchers. The controller invokes the hardware adapter; invoke the
controller from the devshell. The campaign guide documents required manifests,
notifications without Codex, host prerequisites, resume policy and measurements.
Measurement archives remain in `../data` and are never Nix build inputs.

## Maintenance boundary

A maintained tool has a named flow, source-owned build dependencies, explicit
inputs/results and validation appropriate to its behavior. Change its flow
instructions and this reference when its CLI or responsibility changes. Keep
regression tests beside their owners. Put one-off probes and transient manifests
under ignored `out/`; preserve scientific datasets and reproduction provenance
in the data repository. Do not promote historical scripts to supported entry
points merely because they remain in the tree.
