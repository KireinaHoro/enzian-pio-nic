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
Both Nix and Vivado Docker images are pinned by digest. No lock updates occur in CI.
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

After changing Mill or Maven dependencies, resolve from an **empty XDG cache**
before generating the lock. `mif codegen` inventories everything in that cache,
including obsolete versions; running it on a warmed development cache produces
a misleadingly enlarged lock. Use a fresh Mill output directory as well so
cached resolution tasks cannot bypass fetching the required artifacts:

```sh
nix develop -c bash -euc '
  rm -rf "$XDG_CACHE_HOME" out/ivy-lock-mill
  export MILL_OUTPUT_DIR="$PWD/out/ivy-lock-mill"
  mill --no-daemon gen.test.compile "blocks[2.13.12].test.compile"
  update-mill-lock
'
```

Resolve additional source-defined targets here if their dependencies are needed.
Do not run other Mill jobs against this cache during regeneration. Review added
**and removed** versions in `project-lock.nix`; unexplained growth or retained
old Mill versions is a signal to repeat from a clean cache. Then run the Nix
checks offline; a warm interactive build is not evidence of lock completeness.
`nix run .#ciBuild -- NAME INSTALLABLE` reproduces the CI logging wrapper.
The Vivado report hook requires duration syntax `RUNNER_AFTER_SCRIPT_TIMEOUT: "20m"`.
Hardware runs take hours; collect once after notification or a scheduled trigger.
Use the [physical experiment ledger](../hardware/physical-experiments.md) for SHAs
and results, and [hardware testing](hardware-test.md) for verified-reset RPC trials.

Mackerel uses the standard Nixpkgs Cargo vendoring implementation. The Nixpkgs
pin includes the upstream switch from the rate-limited crates.io API to
`static.crates.io`; no platform-specific crate-fetch override is needed.
Cargo.lock versions and checksums remain unchanged. Mill is pinned to 1.1.8
in `.mill-version`, matching the Nixpkgs package; regenerate `project-lock.nix`
using the clean-cache procedure above.

Verilator remains explicitly pinned to 5.048: the current Spinal simulation
wrapper uses `WData`, which Verilator 5.052 removed. Remove this compatibility
pin only after updating and validating the Spinal simulation backend.

## Prewarmed Nix image

All jobs extending `.nix` use the digest-pinned `lauberhorn-flakes` image built
in `project-openenzian/tools/ci-images` with `LauberhornFlakes.Dockerfile`.
The current image was published by [job 2824055](https://gitlab.inf.ethz.ch/project-openenzian/tools/ci-images/-/jobs/2824055),
with the environment from platform revision `2aaa537`.

The prewarmed image builds `packages.x86_64-linux.ciEnvironment`, a shell
containing build tools and locked Maven dependencies. It does not realize test,
RTL, or deployment targets. Tests run in the platform pipeline, not while
publishing its CI image. Tool packages may run their own packaging checks.

The image preserves the Nix store, database and build dependencies. Jobs still
build their own checkout against its lockfiles; missing or changed dependencies
are fetched/built normally. Warming the environment does not cache project test
results or replace the platform regression gates.

To refresh, update `lauberhorn.rev` in the image repository and run its main
pipeline. After successful publication, use `lauberhorn-image.digest` from
`lauberhorn-flakes-image` to update `.nix.image.name` here. The tag is the full
image-repository commit, not the platform commit. Keep an image switch on its
branch until publication succeeds; then run the platform pipeline to validate
all four regression jobs and the matching RTL/software handoff.
