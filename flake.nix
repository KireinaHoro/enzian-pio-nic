{
  description = "devShell for Lauberhorn";
  inputs = {
    self.submodules = true;
    dandelion = {
      url = "git+ssh://git@gitlab.inf.ethz.ch/OU-ROSCOE/Students/2026-bsc-sglinz/dandelion.git?rev=69c3088f8f5333eab653ed5c9bd2f6da8f7f944a";
      flake = false;
    };
    crane = {
      url = "github:ipetkov/crane/469fd08d0bcf6926321fa973c6777fbc87785dd7";
    };
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
    mill-ivy-fetcher = {
      url = "github:Avimitin/mill-ivy-fetcher";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    mackerel = {
      url = "git+https://gitlab.inf.ethz.ch/project-opensockeye/mackerel2?ref=fix/datatype-boundary-access";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "flake-utils";
    };
  };

  outputs =
    inputs@{
      self,
      nixpkgs,
      flake-utils,
      ...
    }:
    let
      overlays = [
        inputs.mill-ivy-fetcher.overlays.default
        inputs.mill-ivy-fetcher.overlays.mill-overlay
        (import ./nix/toolchain/overlay.nix)
      ];
      mkPlatform =
        { pkgs }:
        import ./nix/platform.nix {
          pkgs = pkgs.appendOverlays overlays;
          inherit inputs;
          source = ./.;
          identity = {
            revision = self.rev or "dirty";
            local = !(self ? rev);
            narHash = self.narHash or null;
          };
        };
    in
    {
      lib = { inherit mkPlatform; };
    }
    // flake-utils.lib.eachSystem [ "x86_64-linux" "aarch64-darwin" ] (
      system:
      let
        pkgs = import nixpkgs { inherit system overlays; };
        platform = mkPlatform { pkgs = import nixpkgs { inherit system; }; };
        ci = import ./nix/ci { inherit pkgs; };
        linux = pkgs.stdenv.hostPlatform.isLinux;
      in
      {
        formatter = pkgs.nixfmt;
        checks = platform.checks;
        packages = {
          inherit (platform) genVerilog devHdrs tlc;
        }
        // pkgs.lib.optionalAttrs linux (
          {
            inherit (platform)
              runtime
              runtimeTests
              dandelion
              dandelionDeployFs
              kmod
              deployFs
              eciVivadoInputs
              ciEnvironment
              ciDependencies
              ;
            recovery-test = platform.applications.recovery-test;
            nested-validation = platform.applications.nested-validation;
            dummy-app-build-with-nix = platform.applications.nix-build-demo;
          }
          // ci
        );
        devShells.default = platform.shell;
      }
    );
}
