# Nix packaging refactor validation — 2026-09-15

The packaging and shared interactive workflow passed local checks and four real
Enzian trials. **Board evidence validates packaging on the matched historical CI
baseline, not the final master's different hardware/runtime.** No timing/PnR run
was launched. See [the workflow](interactive-testing.md) for usage.

## Local implementation checks

Built implementation: `e908897affc565088af96bc145c89533864896b3`. Subsequent delivery
edits record documentation/evidence. Existing dependency pins, generated Maven
lock, hardware submodules and hardware implementation were unchanged by this task.

- `nix flake check --no-build --all-systems`: passed Linux/Darwin evaluation.
  Darwin support was evaluated, not built on a Darwin machine.
- `nix fmt -- --check flake.nix nix/*.nix nix/*/*.nix sw/apps/nix-build-demo/package.nix`:
  passed; generated lock excluded.
- `nix develop -c python3 tools/enzian/tests/test_lh_test.py`: four tests passed.
  The pure `interactive` check also passed ShellCheck and four tests after fixing
  the fixture's interpreter path for the Nix sandbox.
- `fast-tests-eci`: executed 29 tests in five suites, all passed. Blocks, trace and trace-FIFO
  checks resolved to existing successful outputs for matching derivations;
  they were not re-executed.
- Built RTL/config, Mackerel headers, runtime, module, installed-interface demo,
  portable Vivado bundle and `deployFs`. The bundle's recorded revision and RTL
  derivation match its generated files; its lock matches the checkout.
- `tools/nix/check-external.py` built a separate `flake = false` application from
  installed headers/pkg-config. A local source/dependency override changed the
  application, development shell and image derivations, while runtime/module/RTL
  derivations and the consumer lock stayed unchanged.
- `tools/nix/check-image.py` checked 3,498 resolving image links, target ELF
  architecture, executable interpreters, dynamic dependencies and module vermagic.
  All 146 environment closure paths were present. All 426 ELF tool entry points
  were AArch64. Preserved debug information also retains native compiler files
  in the closure; these are not the deployed tool entry points. The intentional
  systemd configuration link to Ubuntu's `/etc/environment` is reported separately.

Local evidence is retained in ignored `out/nix-refactor/`: `delivery-paths.json`,
`delivery-summary.json`, `delivery-image-check.json`, build/evaluation/format logs,
`external/`, and regression logs. The early aggregate regression log ends with an
obsolete fixture failure; `lh-portable-checks-v2.log` and `lh-fast-complete.log`
record the corrected successful commands. These failures were not hidden or
counted as passing tests.

Delivery SquashFS SHA-256 at the implementation revision:
`3289391696c27145c8a61cde4ca9c13dd8fe00d34a9dc6353739def139e94fea`.

## Baseline selection and compatibility

Downloaded successful [CI job 2812450](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/jobs/2812450),
pipeline **509533**, revision `aa32b1af04f2fcdd9191c94784eba5bbad1ea25f`.
Its earlier successful adder tests are documented in
[hardware results](hardware-test-results.md). Final master differs substantively
in trace FIFO hardware/dependencies and runtime/API sources; its image was not
combined with this bitstream.

The downloaded archive contains bitstream, probes, routed checkpoint and trace
map, but **no generated RTL or C headers**. Reconstructed original collateral
using that exact revision and original pinned dependencies. Applied the new
packaging to a separate copy of the same baseline, preserving its hardware,
application/runtime C sources, dependency locks and generation revision. Its
baseline runtime Makefile retains its original source list; only compiler/flag
and link handling changed. No student runtime code was merged into master.

The original and refactored baseline RTL/XDC/JSON, generated C headers, Mackerel
device descriptions and compiled Mackerel headers matched **byte-for-byte**.
The trace map also matched the downloaded CI artifact. Both generators used the
original full revision, so no revision-field exception or bypass was needed.
This is reconstructed-collateral evidence, not a claim that RTL was downloaded.
`baseline-provenance.json`, `lh-baseline-abi-comparison.json` and the reviewed
`baseline-packaging.patch` retain paths and hashes; the patch applies cleanly to
the original source. The updated `build_deploy.py --packaging-patch` workflow
was also exercised successfully. That reconstruction has a different source-copy
metadata/store identity from the manually staged tree and was not counted as the
board-tested image; its sidecar and log are retained. Image identities are
recorded, not assumed interchangeable.

| Artifact | SHA-256 |
| --- | --- |
| Downloaded bitstream | `6ae889584a3367e07c2fec5612ead75b6ee8dab5439c573aa463430224aec9f3` |
| Downloaded probes | `0a5b45a6d6aad642e148e6024c8619eeab215b19dd831523b78da828fa27972e` |
| Downloaded trace map | `87abddeffae7bbf9d1049addbc05d78b97c3c818accaef485a7e5c0bafc309a8` |
| Tested baseline image | `f71f0fee2d0aa0f01e001eff88231b722545eef408d84480b9a804c546a5a9cb` |

Hashes of the staged programmer files and shared-storage image matched locally
recorded hashes. The manifest identifies underlying baseline source; the sidecar
provenance identifies its local packaging overlay separately.

## Board campaign

Reservation was checked: zuestoll14 belonged to `pengxu`. Used the established
`tools/enzian/test.py` routine, downloaded bitstream/probes, matching baseline
image, and a client compiled from the baseline's protocol sources. The initial
console preflight timed out before reset; normal verified reset recovered it.
After boot, default routing through `cpu40g0` was checked before disabling the
overlapping `cpu40g1` route. No existing mount was replaced silently.

| Trial | Path | Correct adder replies | Result |
| --- | --- | --- | --- |
| Round 1 | `lh-test` configure/run | 100 | Pass |
| Round 1 | Direct stable executable and manual IP commands | 100 | Pass |
| Round 2 | `lh-test` configure/run | 100 | Pass |
| Round 2 | Direct stable executable and manual IP commands | 100 | Pass |

Every trial checked power-off rails (CPU 0–0.00390625 V, FPGA 0 V), held BDK,
programmed the downloaded bitstream, booted Linux, mounted the same read-only
image, printed helper JSON, loaded through `lh-test`, verified hardware revision,
and passed the external correctness client. Module vermagic matched
`6.8.0-64-generic`; no detected kernel faults occurred. The four CSV files each
contain 100 results. This establishes packaging/adder smoke behavior on this
baseline, not timing closure, application coverage or long-term reliability.

Logs, scripts and summary: `out/nix-refactor/board-trials/summary.json` and sibling
trial directories. Collected server/client files: `out/nix-refactor/servers/`.
The last direct-path server remains running; shared results remain under
`/scratch/pengxu/lh-02-nix-shared-baseline-direct` on the gateway/CPU. No automatic
worker teardown was added. Do not deploy the final-master image with this older
bitstream; final-master hardware validation remains separate work.
