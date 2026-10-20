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
Both Nix and Vivado Docker images are pinned by digest.
`vivado/eci/container.yml` owns the Vivado image pin, included by CI and read by
the bundled Docker launcher. CI invokes `tools/hardware/run-vivado.sh` inside
that image; local launches use the same runner, without Docker-in-Docker. No lock updates occur in CI.
The minimal hosted `xilinx-tools` image supplies Vivado runtime dependencies
without the Spinal/Verilator layer. `vivado/eci/container.yml` also supplies the
license-server default shared with the local Docker launcher. It receives Vivado through the runner's `/opt/Xilinx` mount;
the Tcl entry point checks version 2025.1 and software build 6140274. The image
digest alone does not pin that separately maintained installation.

## Scheduled hardware-build follow-up

After launching an authorized hardware CI pipeline, schedule a **30-minute**
check in the same agent thread. Use the local user timer helper (requires the
host and Codex app-server to remain available):

```sh
python3 tools/ci/schedule-hw-check.py PIPELINE_ID --stage preflight
```

It uses `CODEX_THREAD_ID` or an explicit `--thread THREAD_ID`; `--dry-run` prints
the exact timer/queued task without installing it. Timers are named
`lauberhorn-ci-PIPELINE_ID-STAGE`. Inspect with `systemctl --user list-timers`;
cancel a pending check with `systemctl --user stop NAME.timer`. The helper refuses
a duplicate live timer; `--rearm` explicitly replaces that stage's timer but
never interrupts a running queue invocation. These are local one-shot timers,
not an unattended script that programs a board without rechecking its state.

1. At the 30-minute check, read all test gates, `prepare-eci`, and `build-hw-eci`
   in that pipeline. Record SHA and job IDs, including retry jobs. When all gates
   passed and the hardware build is **running**, schedule collection **five hours
   from that check**, then stop polling:

   ```sh
   python3 tools/ci/schedule-hw-check.py PIPELINE_ID --stage results
   ```

2. If tests or preparation failed, inspect the relevant failed-stage log. Fix,
   validate and relaunch only a trivial, understood cause, then install a new
   30-minute check for the replacement pipeline. For a nontrivial problem,
   identify the cause/evidence, report it and stop; do not expand into an
   unrequested redesign. Never treat skipped hardware as a running build.
3. If tests/preparation or runner allocation are still pending, re-arm the
   preflight check for 30 minutes with `--rearm`; do not start the five-hour wait
   until hardware is running. If hardware has already succeeded, collect/test
   immediately. If it failed, diagnose and report rather than queue a board test.
4. At the five-hour check, collect hardware results and matching bitstream,
   software and trace-map artifacts. On success, follow the
   [Enzian test procedure](validation.md#board-validation): recheck reservation, preserve any
   frozen failure evidence before reset, and run matched-artifact RPC tests with
   at least two repeats and tracing through module load. Report compact results;
   capture and diagnose a failure before resetting. Do not claim timing closure
   from a successful build or silently restart timing optimization.
5. If hardware is still running after five hours, use `--stage results-recheck`
   for a 30-minute recheck (`--rearm` for subsequent checks). Do not repeatedly
   restart the five-hour wait. Stop after completion or a diagnosed blocker;
   cancel any obsolete timers when replacing/canceling a pipeline or test.

**Retain the pipeline branch until every job has finished**, even after merging
its commit into master. Jobs can fetch the branch when they start. Delete the
merged temporary ref only after its jobs finish. The read-only project token
must not be used to retry or mutate CI; use the authorized Git workflow or a
separately authorized write-capable interface.

## Explicit transport-only application scope

For an explicitly selected transport-only pipeline, launch with
`git push -o ci.variable=LAUBERHORN_SKIP_MEMCACHED=1 gitlab BRANCH`.
This opt-in omits only `memcached-adapter` and the memcached deployment image;
all other test gates, matching RPC software, RTL and hardware reporting remain
required. Preparation publishes `out/ci-scope.json` with the exact revision and
`memcached_omitted` flag. Never count an omitted application as passing.
The default remains the full pipeline. Values other than 0 or 1 fail preparation.

## Pipeline and handoff

1. `fast-tests-eci`, `dcs-rom-thx`, `dcs-lauberhorn`, `blocks-tests`, `trace-tests`, `trace-fifo-tests`,
   `eci-toolkit-rx`, `output-interface`, `interactive`, `runtime-interface`,
   `runtime-tests`, `eci-address-map`, `scheduling-cooperative`,
   `memcached-adapter`, and `workflow-tools` run
   `checks.x86_64-linux.<job>` in the Nix image. All gate preparation;
   `memcached-adapter` is omitted only by the explicit transport-only scope.
   Mill runs offline against the locked Ivy cache. The FIFO test exercises both
   output-stage settings at the real trace width/depth under stalls and reset.
2. `prepare-eci` builds `eciVivadoInputs`, `microbenchmarksImage` and, unless
   explicitly omitted, `memcachedImage`. They share generated RTL and headers,
   so bitstream inputs, ABI headers and software use the same generation.
   It verifies the embedded revision marker against `CI_COMMIT_SHA`.
3. `build-hw-eci` uses the hosted Vivado image, with `GIT_STRATEGY: empty` and
   submodule checkout disabled. It consumes the previous job's artifacts and
   runs only Vivado 2025.1: project/IP creation, implementation, bitstream and STA.
   It does not run Mill/Nix, compile Scala/C, or download a checkpoint.
4. `report-hw-eci` runs only after `build-hw-eci` succeeds (`when: on_success`).
   Its `needs: artifacts: true` dependency downloads that pipeline's hardware
   artifacts, including those from a successful retry. A failed hardware attempt
   leaves reporting skipped rather than failing on absent metadata; retrying the
   hardware job can unblock the skipped report. The pinned Python summarizer runs
   through Nix. Missing COMPLETE still fails reporting after a successful build,
   exposing an interrupted STA hook. Negative slack remains a reported
   experimental result, not a gate.
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
| prepare-eci | `out/deploy.img`: matching aarch64 kernel module, runtime/application closure and provenance manifest |
| prepare-eci | `out/memcached-deploy.img`: matching memcached image unless explicitly omitted; `out/ci-scope.json` records revision and omission |
| build-hw-eci | `out/eci/generateVerilog.dest/`: ABI/trace collateral at the existing downloader path |
| build-hw-eci | `out/eci/vivadoProject.dest/shell_lauberhorn-eci.{bit,ltx}`, `shell_lauberhorn-eci_routed.dcp` |
| build-hw-eci / report-hw-eci | `out/physical/` raw STA reports / `summary.json` |
| Nix test jobs | `out/ci/JOB/` build log, JUnit XML; retained failed-build transcripts, waves, traces and simulation seed/input/runtime metadata |

The bundle materializes internal source symlinks for ZIP portability and omits
broken links in unused DCS examples. Every actual project input must still resolve
in Vivado. No `/nix/store` dependency is needed on the Vivado host. The recorded
RTL derivation path is provenance text, not a runtime dependency.

### Report scheduling after hardware retries

Reporting uses `on_success` with an explicit same-pipeline hardware-artifact
dependency. Hardware and report jobs retain `artifacts: when: always` for useful
failure diagnostics; artifact-upload policy does not control job scheduling.

GitLab snapshots CI configuration when creating a pipeline. Existing pipelines
retain their original configuration; an already-failed report needs its own
retry after hardware succeeds. Do not restart an expensive hardware build solely
to adopt changed report scheduling.
See GitLab's [job run conditions](https://docs.gitlab.com/ci/yaml/#when),
[artifact dependencies](https://docs.gitlab.com/ci/yaml/#needsartifacts), and
[job retries](https://docs.gitlab.com/ci/jobs/#retry-jobs).

## Local validation and maintenance

```sh
nix build -L .#checks.x86_64-linux.fast-tests-eci
nix build -L .#checks.x86_64-linux.blocks-tests .#checks.x86_64-linux.trace-tests
nix build -L .#checks.x86_64-linux.trace-fifo-tests
nix build -L .#eciVivadoInputs --out-link out/vivado-inputs
nix build -L .#microbenchmarksImage --out-link out/deploy.img
nix build -L .#checks.x86_64-linux.workflow-tools
```

Follow [the Docker hardware-build guide](hardware-build.md) to copy the bundle
and run the same runner as CI on a Docker host. `project-only` creates the project
without synthesis or implementation. Dirty local flake builds retain the unknown
Git marker; use a clean committed checkout for board-testable matching artifacts.

`ci-build NAME INSTALLABLE` requires a fresh `out/ci/NAME` directory. `prepare-eci`
also refuses existing published artifacts before starting either build. Choose a
new name/workspace or explicitly remove old outputs; failures retain diagnostics
and never silently publish a previous result. The failure collector follows the
`keeping build directory` path reported by that Nix invocation, including recent
`/nix/var/nix/builds/` locations. See [simulation reproducibility](validation.md#simulation-reproducibility)
for seed and reproducer handling.

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
Private application inputs must allow the platform project (30605) in the
application repository’s **Settings → CI/CD → Job token permissions** allowlist.
The CI setup uses the ephemeral job token for Nix Git fetches; local SSH access
does not establish this permission. Keep the
read-only diagnostic token out of source-fetch credentials.
The Vivado report hook requires duration syntax `RUNNER_AFTER_SCRIPT_TIMEOUT: "20m"`.
Hardware runs take hours; use the [30-minute / five-hour follow-up](#scheduled-hardware-build-follow-up) above rather than frequent polling.
Use the [physical evaluation](../hardware/physical-implementation.md#evaluation-provenance) for SHAs
and results, and [hardware testing](validation.md#board-validation) for verified-reset RPC trials.

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
in `project-openenzian/tools/ci-images` using the platform-owned [`tools/ci/image/Dockerfile`](../../tools/ci/image/Dockerfile).
The image repository reads `lauberhorn.rev`, checks out that exact platform
commit, and runs its `tools/ci/image/prepare.sh`. That script exports the locked
derivation/source closure and copies the Dockerfile and warmup script into a
fresh `lauberhorn-context/` artifact. Kaniko uses only that context; credentials
and the bootstrap checkout are not included. The image repository owns registry
authentication, scheduling and publication, while this repository owns the build
recipe, Nix configuration and warmup targets.
The selected image digest is in `.gitlab-ci.yml`; its warmed platform revision
is provenance, while jobs build the current checkout.

The prewarmed image builds two targets:

- `ci.x86_64-linux.environment`: native build tools and locked Maven dependencies.
- `ci.x86_64-linux.dependencies`: the prepared Ubuntu kernel build tree,
  fixed static-shell checkpoint, exact cross-compilation tools/libraries, and
  AArch64 deployment utilities.

The kernel target runs `olddefconfig` and `modules_prepare` using the pinned
Ubuntu `.config`, `Module.symvers` and ABI release. It does not compile the Linux
kernel or Lauberhorn module. It is reused until its source/configuration/toolchain
changes. The static shell is a pinned download, never synthesized during warmup.
Cross and deployment packages are rooted as exact store paths in a link farm;
putting a cross package in native `mkShell.packages` can select its host variant.

Warmup does not realize project test, RTL, or deployment-image targets. Tests run in the platform pipeline, not while
publishing its CI image. Tool packages may run their own packaging checks.

The image/job boundary is deliberate:

| Image build (pinned by `lauberhorn.rev`) | Each platform job (current checkout) |
| --- | --- |
| Nix bootstrap, flake support and Docker-compatible sandbox defaults | Runner CPU/job limits via `NIX_CONFIG` (also supports the older pinned image) |
| Locked tools, Maven cache, prepared kernel, static shell and target dependencies | `nix run .#ciBuild`, `.#prepareEci` and `.#summarizePhysical` from the current flake |
| Store database, build inputs and GC roots | Tests, RTL/software generation, reports and artifact freshness checks |
| No credentials or checkout trust settings | `tools/ci/setup-job.sh`: scoped checkout trust, Git URL rewrites and ephemeral credential helper |

Git transport setup stays with the current job's authentication policy, including
the host-scoped URL rewrites needed by Nix's independent Git cache. Do not bake
`safe.directory`, tokens, a project checkout, test results or generated hardware
into the image. Prewarming does not install old platform wrappers onto PATH.
The export job needs Git authentication to fetch private inputs; the Kaniko job
needs registry authentication to publish, but receives no Git credentials.

The image preserves the Nix store, database and build dependencies. Jobs still
build their own checkout against its lockfiles; missing or changed dependencies
are fetched/built normally. Warming the environment does not cache project test
results or replace the platform regression gates.

To refresh, first push the platform commit containing the image scripts, then
update `lauberhorn.rev` in the image repository and run its main
pipeline. After successful publication, use `lauberhorn-image.digest` from
`lauberhorn-flakes-image` to update `.nix.image.name` here. The tag is the full
image-repository commit, not the platform commit. Keep an image switch on its
branch until publication succeeds; then run the platform pipeline to validate
all regression and packaging checks and the matching RTL/software handoff.

For local preparation from a clean committed platform checkout:

```sh
LAUBERHORN_REV=$(git rev-parse HEAD) \
  bash tools/ci/image/prepare.sh out/lauberhorn-context
docker build -f out/lauberhorn-context/Dockerfile \
  --build-arg LAUBERHORN_REV=$(git rev-parse HEAD) \
  -t lauberhorn-flakes out/lauberhorn-context
```

Preparation requires a fresh output directory and fetches the pinned remote
flake, including submodules. `LAUBERHORN_FLAKE` may override that URL for local
testing; use the same commit as `LAUBERHORN_REV` to keep provenance accurate.

## Image dependency audit

`ci.x86_64-linux.dependencies` shares the kernel derivation, static-shell pin and deployment
package list with their consumers. It roots the exact cross stdenv, pkg-config,
rpcgen (including its development output), libtirpc library/headers, `nuke-refs`,
native jq headers, minimal ShellCheck, build-environment support and target
deployment utilities. Deployment links only executable outputs, avoiding unused
target manual downloads. Project-dependent generated headers, module/runtime/app builds,
helper/manifest generation, tests and image assembly stay in platform jobs.
Rebuilding and publishing the CI image is required to make this cache available;
changing the warmup target alone does not alter an existing digest-pinned image.

To check the exact outputs needed by all jobs against locally realized warmup
closures (without executing any project tests or hardware builds):

```sh
nix build .#ci.x86_64-linux.environment .#ci.x86_64-linux.dependencies --no-link
mkdir -p out/ci-image-audit
nix eval --raw .#checks.x86_64-linux --apply \
  'checks: builtins.concatStringsSep "\n" (map (c: c.drvPath) (builtins.attrValues checks))' \
  > out/ci-image-audit/check-drvs
mapfile -t ci_checks < out/ci-image-audit/check-drvs
nix eval --json .#apps.x86_64-linux --apply '
  apps: builtins.concatMap
    (app: builtins.attrNames (builtins.getContext app.program))
    (builtins.attrValues apps)' > out/ci-image-audit/app-drvs.json
mapfile -t ci_apps < <(jq -r '.[]' out/ci-image-audit/app-drvs.json)
nix derivation show --recursive .#ci.x86_64-linux.environment .#ci.x86_64-linux.dependencies \
  .#microbenchmarksImage .#eciVivadoInputs "${ci_apps[@]}" \
  "${ci_checks[@]}" > out/ci-image-audit/graph.json
python3 tools/ci/image/audit.py out/ci-image-audit/graph.json
```

The audit checks selected outputs (such as `dev` versus `out`) and distinguishes
source-dependent Lauberhorn derivations from their external inputs. It counts
runtime closures plus build inputs guaranteed by non-substitutable warmup
builders, since the image retains those inputs. It does not assume that exporting
a derivation, or substituting a package, realizes that package's build dependencies.
The graph must include every CI target; new project-derived package names must
follow the `lauberhorn-` prefix or be classified explicitly in the audit tool.

## Packaging interface checks

`output-interface` evaluates every leaf of the custom `dev.SYSTEM` and
`ci.SYSTEM` sets and records derivation paths without building their closures.
It gates preparation alongside the existing packaging checks.

`interactive` checks helper JSON, arguments, executable selection and failure
status without NIC access. `runtime-interface` cross-compiles the standalone demo
against installed runtime headers and pkg-config. Both gate preparation alongside
the existing regressions. `workflow-tools` checks shell scripts and tests artifact
freshness and hardware-runner failure handling with fake tools. `ci.x86_64-linux.environment` retains native toolchain and wrapper
dependencies for image warming; wrappers themselves come from each job checkout. These checks do not replace board evidence.

### ECI toolkit RX regression gate

`eci-toolkit-rx` is a Nix/GHDL check and a prerequisite of `prepare-eci`.
It runs the high-VC pipeline's eight directed/random-stall comparison cases
from the pinned private toolkit feature commit. This does not validate physical
timing or board behavior. Low-VC staging and its additional tests remain on the
unmerged experiment branch. See the [timing continuation](../../agents/physical-implementation.md#retained-experiment-inputs)
for the private pin and outstanding upstream owner review.

## Deployment script builder architecture

`nix/interactive/tools.nix` writes `lh-test` with the native package set's
`writeTextFile`, while its shebang and runtime PATH reference AArch64 packages.
The helper derivation must use the build host's system; an evaluation assertion
checks this even if outputs are cached. Using `target.writeShellScriptBin`
incorrectly requires an AArch64 builder merely to write the script.

## Vivado license configuration

The shared [container configuration](../../vivado/eci/container.yml) tries the
ETH license server before HACC. The local Docker launcher passes the same
default when the host has no override. Host `XILINXD_LICENSE_FILE` and
`LM_LICENSE_FILE` overrides are forwarded explicitly; see the
[hardware-build guide](hardware-build.md). Diagnose license acquisition separately
from RTL errors; no runner-wide configuration or license-file edits are needed
for the shared default.
