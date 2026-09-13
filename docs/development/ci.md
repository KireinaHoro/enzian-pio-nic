# CI: Nix builds, artifact-only Vivado

ECI uses the same locked Nix derivations locally and in CI. `flake.lock` pins
Nixpkgs/Mill/Mackerel, `project-lock.nix` pins Maven artifacts, gitlinks pin RTL
dependencies, and the input bundle pins static shell v0.1.5 by content hash.
The Nix job trusts the runner-owned checkout and uses a host-scoped Git credential
helper backed by the ephemeral `CI_JOB_TOKEN` for private submodule fetches.
Runner-local Git credentials and `GIT_SUBMODULE_FORCE_HTTPS` alone do not cover
Nix's separate Git cache. The job also globally rewrites both SCP-style and
`ssh://git@` GitLab URLs to HTTPS, including nested static-shell submodules.
The helper stores an environment reference, not the token; no personal token is
passed to builds or included in artifacts.
The prewarmed Nix image is selected by its image-repository commit tag; pin its
published digest before merging this image switch. The Vivado image is pinned by digest. No lock updates occur in CI.
The hosted tools image receives Vivado through the runner's `/opt/Xilinx` mount;
the Tcl entry point checks version 2025.1 and software build 6140274. The image
digest alone does not pin that separately maintained installation.

## Pipeline and handoff

1. `fast-tests-eci`, `blocks-tests`, `trace-tests`, `trace-fifo-tests` run
   `checks.x86_64-linux.<job>` in the Nix image. All four gate preparation.
   Mill runs offline against the locked Ivy cache. The FIFO test exercises both
   output-stage settings at the real trace width/depth under stalls and reset.
2. `prepare-eci` builds `eciVivadoInputs` and `deployFs`. Both share `genVerilog`,
   so the bitstream inputs, ABI headers and software use the same generation.
   It verifies the embedded revision marker against `CI_COMMIT_SHA`.
3. `build-hw-eci` uses the hosted Vivado image, with `GIT_STRATEGY: empty` and
   submodule checkout disabled. It consumes the previous job's artifacts and
   runs only Vivado 2025.1: project/IP creation, implementation, bitstream and STA.
   It does not run Mill/Nix, compile Scala/C, or download a checkpoint.
4. `report-hw-eci` runs the pinned Python summarizer through Nix. Missing COMPLETE
   fails this job, exposing an interrupted STA hook even if bitstream creation
   succeeded. Negative slack remains a reported experimental result, not a gate.
5. Tag publication uses the existing release service; it performs no compilation.

Nix build environments supply the compiler/JVM/simulator/JNI tools independently
of the container PATH. Ordinary Docker on ba2 disallows nested Nix namespaces;
CI therefore uses Docker isolation with `sandbox = false`, not privileged Docker.
Local Nix builds use the host's sandbox. This is dependency/environment isolation,
not a claim that the CI builder has a second network sandbox. Mill's offline mode
and fixed-output fetches prevent implicit dependency updates.

| Producer | Artifact |
| --- | --- |
| prepare-eci | `out/eci/vivado-inputs/`: relocatable sources, generated RTL/XDC/headers/devices/trace map, Tcl, static-shell DCP, lockfile and revision/derivation metadata |
| prepare-eci | `out/deploy.img`: matching aarch64 kernel module, runtime/application closure and commit marker |
| build-hw-eci | `out/eci/generateVerilog.dest/`: ABI/trace collateral at the existing downloader path |
| build-hw-eci | `out/eci/vivadoProject.dest/shell_lauberhorn-eci.{bit,ltx}`, `shell_lauberhorn-eci_routed.dcp` |
| build-hw-eci / report-hw-eci | `out/physical/` raw STA reports / `summary.json` |
| Nix test jobs | `out/ci/JOB/` build log, JUnit XML; failed-build transcripts/waves when available |

The bundle materializes internal source symlinks for ZIP portability and omits
broken links in unused DCS examples. Every actual project input must still resolve
in Vivado. No `/nix/store` dependency is needed on the Vivado host. The recorded
RTL derivation path is provenance text, not a runtime dependency.

## Local validation and maintenance

```sh
nix build -L .#checks.x86_64-linux.fast-tests-eci
nix build -L .#checks.x86_64-linux.blocks-tests .#checks.x86_64-linux.trace-tests
nix build -L .#checks.x86_64-linux.trace-fifo-tests
nix build -L .#eciVivadoInputs --out-link out/vivado-inputs
nix build -L .#deployFs --out-link out/deploy.img
# Copy the bundle, not the result symlink, to a Vivado host:
rsync -rLt out/vivado-inputs/ enzian-ba2:/tmp/eci-inputs-UNIQUE/
ssh enzian-ba2 'source /opt/Xilinx/2025.1/Vivado/settings64.sh && \
  /opt/Xilinx/2025.1/Vivado/bin/vivado -mode batch -nojournal -nolog \
  -source /tmp/eci-inputs-UNIQUE/vivado/eci/ci_build.tcl \
  -tclargs /tmp/eci-inputs-UNIQUE /tmp/eci-project-UNIQUE project-only'
```

Omit `project-only` for implementation. Use a fresh output directory. Dirty local
flake builds deliberately retain the unknown Git marker; use a clean committed
checkout for a board-testable image with matching revision checks.

After adding Maven dependencies, resolve the relevant Mill targets with
`nix develop`, then run `nix develop -c update-mill-lock` and review the lock diff.
Re-run the Nix checks offline; a warm interactive cache is not proof of CI coverage.
`nix run .#ciBuild -- NAME INSTALLABLE` reproduces the CI logging wrapper.
The Vivado report hook requires duration syntax `RUNNER_AFTER_SCRIPT_TIMEOUT: "20m"`.
Hardware runs take hours; collect once after notification or a scheduled trigger.
Use the [physical experiment ledger](../hardware/physical-experiments.md) for SHAs
and results, and [hardware testing](hardware-test.md) for verified-reset RPC trials.

## Prewarmed Nix image

The image is built in `project-openenzian/tools/ci-images` using
`LauberhornFlakes.Dockerfile`. Its prepare job evaluates the pinned platform
revision and exports a derivation/source closure; the Docker build realizes the
four test targets, deployment filesystem, Vivado bundle and CI wrappers.
The image preserves the Nix store, database and build dependencies. A fresh
container can reuse these without fetching them again. Changed derivations
still build normally against the current checkout's lockfiles.

Refresh by updating `lauberhorn.rev` in the image repository, building its main
pipeline, then using `lauberhorn-image.digest` from `lauberhorn-flakes-image`
to update the shared `.nix.image.name` here. The image tag is the full
image-repository commit, not the platform commit. Keep the platform switch on
its branch until the image is published and its digest verified.

The official `nixos/nix` base avoids an additional APK-based Nix installation
and channel update. Compilers and libraries still come from our flake.
The Vivado input fileset excludes unrelated recorded data: the evaluated image
input handoff was 49 MB instead of 3.0 GB after this change.
