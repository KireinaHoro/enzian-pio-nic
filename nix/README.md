# Nix composition

`flake.nix` exports `lib.mkPlatform { pkgs; }` and per-system packages/shells.
`platform.nix` selects source files and supplies the matching hardware/software
packages. Leaf recipes use `callPackage`; composition returning package sets uses
`import`, so override functions do not leak into flake outputs.

- `toolchain/`: development shell and the pinned Spinal-compatible Verilator overlay.
- `hardware/`: native Mill RTL/config generation and native Mackerel header generation,
  and portable Vivado input bundles.
- `software/`: Ubuntu kernel preparation, target module and installed runtime.
- `applications/`: simple in-tree application recipes; external recipes live upstream.
- `images/`: environment/manifest and SquashFS constructors.
- `interactive/`: shared helper and collision-checked target tools.
- `checks/`: check-set composition, a shared Mill simulation recipe, trace-FIFO
  regression and portable helper checks.
- `ci/`: stable CI commands and the CI development environment.

Authoritative tools: Nixpkgs supplies rpcsvc-proto/rpcgen, pkg-config, compilers and
standard target tools. The unused private rpcsvc-proto derivation was removed.
Mackerel comes from the pinned mackerel input; Mill dependencies come from the
unchanged generated `project-lock.nix`. `build.mill` owns generator targets; `hardware/vivado-inputs.nix` owns the
static-shell version and content hash. `vivado/eci/container.yml` owns the Docker
image pin shared by CI and interactive hardware builds. No
hardware dependency or input pin was changed by this refactor. Native AArch64
Nixpkgs binaries supply image utilities; applications/runtime use the platform's
cross stdenv, and generators use build-machine tools.

See [interactive testing](../docs/development/interactive-testing.md) for package
contracts, local overrides and the common human/agent workflow. Run
`nix fmt flake.nix nix/*.nix nix/*/*.nix sw/apps/nix-build-demo/package.nix` for the
handwritten definitions; do not reformat or regenerate the Maven lock casually.
