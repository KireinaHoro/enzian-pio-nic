# Build and CI continuation

The [CI guide](../docs/development/ci.md) owns pipeline, maintenance and image
publication procedures. No build, image publication or hardware job is pending
merely because a historical result is recorded here. Check actual pipeline state
before acting; [timing optimization remains paused](physical-implementation.md).

## Image and packaging evidence

- The Nix image referenced by the original guide was published by
  [job 2824055](https://gitlab.inf.ethz.ch/project-openenzian/tools/ci-images/-/jobs/2824055)
  with environment revision `2aaa537`. Read `.gitlab-ci.yml` for the selected
  digest and distinguish that warmup revision from each job's source revision.
- The September 16 dependency audit reported 48/48 external dependency outputs
  cached, both warmup targets realized and 12 workflow tests passing. Its scope
  was local cache coverage, not image publication or digest adoption. The audit
  corrected native-versus-cross libtirpc selection and rooted the exact kernel,
  static-shell and cross-tool dependencies. On a dependency change, regenerate
  the complete graph using the [audit procedure](../docs/development/ci.md#image-dependency-audit),
  inspect missing outputs, then publish and adopt a new image only through the
  documented workflow. A changed warmup target does not change an existing image.
- The deployment-helper architecture correction built a local image and checked
  3,497 links: the helper derivation was x86_64 and its packaged Bash interpreter
  AArch64. The attempted local platform restriction was ignored by the daemon;
  the result came from derivation/image inspection. Preserve this host/target
  distinction when changing `nix/interactive/tools.nix` and run packaging checks.
- The license-default correction passed actual Vivado 2025.1 xcvu9p synthesis in
  a fresh bridge-network container (`LICENSE_SYNTHESIS_PASS`) using the ETH
  server. Workflow checks covered defaults and host overrides. That finite test
  does not establish current license availability.

These records come from `49229ba8:docs/development/ci.md`; Git preserves the
resolved incident narrative. Original diagnostic references include prepare job
2832057 (dependency audit), jobs 2831283/2831304 (helper architecture), and
2831913/2831924 (license acquisition). No diagnostics were deleted by the scrub.

## Continuation checks

Before an authorized CI change, compare preparation dependencies and artifact
paths with `.gitlab-ci.yml` and `tools/ci/prepare-eci.sh`. Include conditional
memcached coverage; an omitted application is not a passing application gate.
Validate image-cache changes locally, then use a newly created platform pipeline
to check publication/digest adoption and runner restrictions. GitLab configuration
changes do not rewrite existing pipelines. Retain refs until every job finishes.

Private-input fetch failures require the platform project's job-token allowlist,
not a personal diagnostic token: memcached job 2879237 failed before compilation
when that permission was missing. Keep credentials out of source and artifacts.
No credential or remote permission changes are authorized by this handoff.

## CMAC regression references

`tools/cmac/check-routing.py` exercises the production aperture;
`tools/cmac/check-startup.py` uses generated headers and fake MMIO to check
startup and callback failure/retry/DOWN/UP in IRQ and polling modes. The former
CMAC note records passing local tests and a 6.8.0-64 arm64 module build;
these do not model analog alignment or establish the later physical UP/retry
case. Use current `dev.SYSTEM.hardware.deviceHeaders` and
`dev.SYSTEM.kernel.lauberhornModule` outputs when reproducing local checks.
For a future statistics change, audit mixed status latches and adjacent 32-bit
reads through the width adapter. There is no periodic carrier monitor or
automatic link-loss recovery. Board provenance is in
[hardware state](hardware-state.md#cmac-initialization-evidence-reference).

## September 15 Docker workflow validation

This workflow change passed all-system flake evaluation, Nix formatting,
offline RTL/ABI and portable-bundle builds, the `prepareEci` package build,
ShellCheck and 11 workflow regression tests. `nix develop -c mill --no-daemon
resolve 'eci._'` lists only `eci.generateVerilog` and `eci.gitHash`.

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

## September 15 packaging board evidence

Implementation `e908897affc565088af96bc145c89533864896b3` passed Linux/Darwin
flake evaluation (no Darwin build), formatting, four helper tests/ShellCheck,
and 29 fast ECI tests across five suites. Blocks, trace and trace-FIFO checks
resolved to cached successful outputs rather than executing again. RTL/config,
headers, runtime, module, installed-interface demo, portable Vivado bundle and
historical `deployFs` built. Dependency pins, generated Maven lock and hardware
implementation were unchanged by this packaging task.

External-consumer checks verified installed headers/pkg-config and application
input overrides affecting the application, shell and image without changing
runtime/module/RTL or the consumer lock. Image inspection checked 3,498 links,
146 closure paths, all 426 deployed tool entry points as AArch64, interpreter/
dynamic dependencies and vermagic. Native compiler debug files in the closure
were not deployed tool entry points. Delivery image SHA256:
`3289391696c27145c8a61cde4ca9c13dd8fe00d34a9dc6353739def139e94fea`.

Board trials used pipeline 509533 / job 2812450 at
`aa32b1af04f2fcdd9191c94784eba5bbad1ea25f`, with a packaging overlay on that
historical source, not the refactor revision's different hardware/runtime.
The downloaded archive lacked generated RTL and C headers. Reconstructed
collateral from the exact baseline and dependency pins matched the original
RTL/XDC/JSON, generated headers and device descriptions byte-for-byte; the map
matched the downloaded artifact. No revision bypass or ABI exception was used.
The independently exercised `build_deploy.py --packaging-patch` reconstruction
had a different source/store identity and was not counted as the board-tested
image. Keep those identities separate.

| Artifact | SHA256 |
| --- | --- |
| Downloaded bitstream | `6ae889584a3367e07c2fec5612ead75b6ee8dab5439c573aa463430224aec9f3` |
| Downloaded probes | `0a5b45a6d6aad642e148e6024c8619eeab215b19dd831523b78da828fa27972e` |
| Downloaded trace map | `87abddeffae7bbf9d1049addbc05d78b97c3c818accaef485a7e5c0bafc309a8` |
| Board-tested baseline image | `f71f0fee2d0aa0f01e001eff88231b722545eef408d84480b9a804c546a5a9cb` |

Four verified-reset trials passed 400 adder calls: helper configure/run and
manual stable-executable paths twice each. Each checked rails off, BDK/program,
image mount, module load/vermagic (`6.8.0-64-generic`), hardware revision and
100 external replies, with no detected kernel faults. The initial console
preflight timed out before reset and is separate from those passes. This is
packaging/adder smoke acceptance on the matched baseline, not timing closure,
current-master hardware acceptance, broad application coverage or reliability.
No timing/PnR run was launched. Later board observations supersede the old
running-server note.

Evidence under `out/nix-refactor/`: `delivery-paths.json`, `delivery-summary.json`,
`delivery-image-check.json`, `external/`, build/evaluation/format logs,
`baseline-provenance.json`, `lh-baseline-abi-comparison.json`,
`baseline-packaging.patch`, `board-trials/summary.json` and per-trial logs,
plus `servers/`. The early aggregate regression log contains a fixture failure;
`lh-portable-checks-v2.log` and `lh-fast-complete.log` record corrected passes.
Shared board results: `/scratch/pengxu/lh-02-nix-shared-baseline-direct`.
Full historical record: `ff6d8957:docs/development/nix-refactor-validation.md`.
No raw artifacts were removed by this consolidation.

## Retired packaging references

The retired student-runtime recipe and two adapter patches are recoverable from
`93ee25a6709fb8508d30e8298368cc11ecbc6f93` under `tools/enzian/`.
Current application packaging supersedes them. The remaining immutable
`tools/enzian/nullrpc_inline_build.nix` is retained for active paper provenance;
migrate that provenance together before retiring the recipe. Current output
names and image ownership are in the [Nix reference](../docs/development/nix.md).
Historical reproduction records retain the output names from their pinned revision.
