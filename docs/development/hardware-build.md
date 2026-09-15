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
needed on the destination host. Build `.#deployFs` from the same clean revision
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
Host `XILINXD_LICENSE_FILE` and `LM_LICENSE_FILE` are passed when set; otherwise
the image's configuration applies. Mount paths containing commas are rejected.

## Outputs and CI correspondence

Under the chosen output root:

- `vivado-build.log`: combined build output.
- `eci/vivadoProject.dest/`: project and, after a successful full build,
  `shell_lauberhorn-eci.bit`, `.ltx` and `shell_lauberhorn-eci_routed.dcp`.
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
[hardware-test routine](hardware-test.md) for matching-artifact board validation.

## Validation recorded 2026-09-15

This workflow change passed all-system flake evaluation, Nix formatting,
offline RTL/ABI and portable-bundle builds, the `prepareEci` package build,
ShellCheck and 11 workflow regression tests. `nix develop -c mill --no-daemon
resolve 'eci._'` lists only `eci.generateVerilog` and `eci.gitHash`.
An independent review found no blocking defects.

On `enzian-ba2`, Docker 29.8.0 ran the pinned image with the read-only bundle and
Vivado 2025.1 / SW Build 6140274. `project-only` exited zero with
`VIVADO_PROJECT_READY /work/eci/vivadoProject.dest`. Input staging was
`/tmp/lh-canonical-inputs-20260915`; outputs were
`/tmp/lh-canonical-project-20260915`. The tested bundle was
`/nix/store/xpaz8r0dc7i57w0ag1qkxlhpkbgvpi4j-lauberhorn-eci-vivado-inputs`,
from this patch over `8eb056a`, with revision marker `dirty` and RTL derivation
`/nix/store/abrkqzw3f746czdzq4nj86ng4g5w09dz-lauberhorn-hw-rtl-config.drv`.
A second invocation refused the existing output directory before launching Docker.
Logs are retained locally under `out/canonical-hardware-validation/`; the project
log SHA-256 is `3ff72fda7c0022ebc49d7a523ddf6be008e30b0e4d8cd4c1c9c60987cca8e59c`.

This establishes Docker project creation and bundle portability. Full CI,
synthesis/PnR, bitstream generation and board tests were not run for this change.
The dirty bundle is not a board-testable revision identity.
