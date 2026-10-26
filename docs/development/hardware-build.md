# Hardware builds with Docker

Nix packages the complete ECI input bundle. Vivado project creation,
implementation and checkpoint reporting use one bundled runner, shared by CI
and manual builds. Mill provides compilation, simulation and RTL/ABI generation.
No bare-host Mill/Vivado build flow is maintained.

## Build and copy inputs

On the development machine, with recursive submodules available:

```sh
nix build .#eciVivadoInputs --out-link out/vivado-inputs
rsync -rLt out/vivado-inputs/ enzian-ba2:/tmp/eci-inputs-UNIQUE/
```

Choose unique staging/output names. Copy the contents of the result link.
The bundle contains source, generated collateral, the hash-pinned static-shell
checkpoint, scripts and provenance. No Nix installation or source checkout is
needed on the destination host. Build `.#microbenchmarksImage` from the same clean revision
for a matching deployment image; preserve its manifest and bundle provenance.

## Run on the Docker host

The host needs Docker access, access to the private ETH container registry, and
a licensed Vivado installation at `/opt/Xilinx/2025.1/Vivado`. The bundle's
`vivado/eci/container.yml` pins the runtime image by digest. The mounted Vivado
installation is checked separately for version 2025.1 / software build 6140274.

Create a project **without synthesis or place-and-route**:

```sh
ssh -t enzian-ba2 'bash /tmp/eci-inputs-UNIQUE/tools/hardware/docker-vivado.sh \
  /tmp/eci-inputs-UNIQUE /tmp/eci-project-UNIQUE project-only'
```

For an authorized full hardware build, choose another fresh output directory:

```sh
bash /tmp/eci-inputs-UNIQUE/tools/hardware/docker-vivado.sh \
  /tmp/eci-inputs-UNIQUE /tmp/eci-build-UNIQUE build
```

`build` runs implementation and bitstream generation, then reports the routed
checkpoint if one exists, including after a failed build. It retains the build's
failure status; a report failure also fails an otherwise successful invocation.
Existing outputs are refused. There is no automatic cleanup or resume; preserve
failed results before explicitly removing them or choosing a new destination.

The launcher mounts inputs and `/opt/Xilinx` read-only, writes outputs as the
invoking UID/GID, and streams build logs. Set `VIVADO_INSTALLATION` to change the
host installation mount; set `VIVADO_ROOT` to its path **inside the container**.
Host `XILINXD_LICENSE_FILE` and `LM_LICENSE_FILE` are passed when set. Without
a host override, the launcher uses the `XILINXD_LICENSE_FILE` default from
`vivado/eci/container.yml`, also inherited by CI. This includes the ETH license
server before HACC; the pinned image itself has an older HACC-only default. Mount paths containing commas are rejected.

## Outputs and CI correspondence

Under the chosen output root:

- `vivado-build.log`: combined build output.
- `eci/vivadoProject.dest/`: project and, after a successful full build,
  `shell_lauberhorn-eci.bit`, `.ltx` and `shell_lauberhorn-eci_routed.dcp`.
- `eci/vivadoProject.dest/lauberhorn_trace_dma_map.json`: implemented trace map,
  with measured per-source auto-pipeline depths and checkpoint/bitstream hashes.
  The adjacent `.depths.tsv` is the per-bit extraction evidence. The generated
  input map is an unresolved template and must not be used for capture analysis.
- `physical-vivado.log` and `physical/`: checkpoint reports; `physical/COMPLETE`
  marks complete collection. Use the [physical guide](../hardware/physical-implementation.md)
  to summarize and interpret them.

To collect reports from an existing routed checkpoint when report outputs do
not already exist:

```sh
bash /tmp/eci-inputs-UNIQUE/tools/hardware/docker-vivado.sh \
  /tmp/eci-inputs-UNIQUE /tmp/eci-build-UNIQUE report
```

CI already runs inside the shared pinned image, so it invokes the bundled
`run-vivado.sh BUNDLE out build` directly. Its separate `after_script` calls
`report`, allowing collection after a build timeout when the runner permits it.
The [CI guide](ci.md) records the stable artifact paths and report gate.

Project creation validates input portability and tool setup. It does not prove
implementation success, timing closure or board functionality. Use the
[hardware-test routine](validation.md#board-validation) for matching-artifact board validation.

The Tcl build entry point reopens the final routed checkpoint and runs
`tools/physical/trace_latency.tcl` before declaring build completion. The report
runner invokes the same extraction before writing `physical/COMPLETE`, including
when collecting a routed checkpoint from a failed build. Missing endpoints,
non-register transport logic, unequal payload/valid depths or out-of-range
latency fail extraction. No manual stage-count editing is supported.
