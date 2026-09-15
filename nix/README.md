# Nix composition

`flake.nix` exports `lib.mkPlatform { pkgs; }` and per-system packages/shells.
`platform.nix` selects source files and supplies the matching hardware/software
packages. Leaf recipes use `callPackage`; composition returning package sets uses
`import`, so override functions do not leak into flake outputs.

- `toolchain/`: development shell and the pinned Spinal-compatible Verilator overlay.
- `hardware/`: native Mill RTL/config generation and native Mackerel header generation.
- `software/`: Ubuntu kernel preparation, target module and installed runtime.
- `applications/`: simple in-tree application recipes; external recipes live upstream.
- `images/`: collision-checked target tools, environment/manifest and SquashFS constructors.
- `checks/`, `ci-checks.nix`: portable helper checks and existing simulation gates.
- `ci/`, `eci-vivado-inputs.nix`: stable CI command and artifact-bundle contracts.

Authoritative tools: Nixpkgs supplies rpcsvc-proto/rpcgen, pkg-config, compilers and
standard target tools. The unused private rpcsvc-proto derivation was removed.
Mackerel comes from the pinned mackerel input; Mill dependencies come from the
unchanged generated `project-lock.nix`. `build.mill` owns generator targets and
static shell version; the portable bundle's content hash remains pinned. No
hardware dependency or input pin was changed by this refactor. Native AArch64
Nixpkgs binaries supply image utilities; applications/runtime use the platform's
cross stdenv, and generators use build-machine tools.

See [interactive testing](../docs/development/interactive-testing.md) for package
contracts, local overrides and the common human/agent workflow. Run
`nix fmt flake.nix nix/*.nix nix/*/*.nix sw/apps/nix-build-demo/package.nix` for the
handwritten definitions; do not reformat or regenerate the Maven lock casually.
