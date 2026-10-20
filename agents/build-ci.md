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
