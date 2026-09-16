{
  description = "devShell for Lauberhorn";
  inputs = {
    self.submodules = true;
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
    mill-ivy-fetcher = {
      url = "github:Avimitin/mill-ivy-fetcher";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    mackerel = {
      url = "git+https://gitlab.inf.ethz.ch/project-opensockeye/mackerel2";
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
          inherit (platform) genVerilog devHdrs;
        }
        // pkgs.lib.optionalAttrs linux (
          {
            inherit (platform)
              runtime
              kmod
              deployFs
              eciVivadoInputs
              ciEnvironment
              ciDependencies
              ;
            dummy-app-build-with-nix = platform.applications.nix-build-demo;
          }
          // ci
        );
        devShells.default = platform.shell;
      }
    );
}
