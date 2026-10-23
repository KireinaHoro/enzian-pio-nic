{
  description = "Lauberhorn hardware, software and Enzian deployment";
  inputs = {
    self.submodules = true;
    thunderx-ptp = {
      url = "git+ssh://git@gitlab.inf.ethz.ch/project-openenzian/enzian-software/thunderx-dpdk-ptp.git";
      flake = false;
    };
    dandelion = {
      url = "git+ssh://git@gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/benchmarking/dandelion.git?ref=fix/explicit-inline-codec";
      flake = false;
    };
    microbenchmarks = {
      url = "git+ssh://git@gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/benchmarking/microbenchmarks.git?ref=master";
      flake = false;
    };
    memcached = {
      url = "git+ssh://git@gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/benchmarking/memcached.git?ref=lauberhorn";
      flake = false;
    };
    memcachedUpstream = {
      url = "git+ssh://git@gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/benchmarking/memcached.git?ref=master";
      flake = false;
    };
    crane = {
      url = "github:ipetkov/crane";
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
        ciCommands = import ./nix/ci { inherit pkgs; };
        linux = pkgs.stdenv.hostPlatform.isLinux;
      in
      rec {
        formatter = pkgs.nixfmt;
        checks =
          platform.checks
          // {
            # Custom output sets are otherwise ignored by `nix flake check`.
            output-interface =
              let
                collect =
                  value:
                  if pkgs.lib.isDerivation value then
                    [ value.drvPath ]
                  else if builtins.isAttrs value then
                    builtins.concatMap collect (builtins.attrValues value)
                  else
                    throw "dev and ci output leaves must be derivations";
                paths = collect { inherit dev ci; };
              in
              builtins.deepSeq paths (
                pkgs.writeText "lauberhorn-output-interface.json" (
                  builtins.unsafeDiscardStringContext (builtins.toJSON paths)
                )
              );
          }
          // pkgs.lib.optionalAttrs linux {
            thunderx-ptp = platform.thunderx.checks;
          };
        # Standard package outputs are complete deployable artifacts.
        packages = pkgs.lib.optionalAttrs linux platform.images;
        # Intermediate builds are explicit conveniences, not deployment products.
        dev = {
          hardware = {
            rtl = platform.genVerilog;
            deviceHeaders = platform.devHdrs;
            inherit (platform) dcsRom;
          };
        }
        // pkgs.lib.optionalAttrs linux {
          kernel = {
            buildTree = platform.kernelBuildTree;
            lauberhornModule = platform.kmod;
            thunderxPtpModules = platform.thunderx.modules;
          };
          software = {
            inherit (platform) runtime runtimeUninstrumented microbenchmarksUninstrumented dandelion memcached;
            inherit (platform.applications) microbenchmarks recovery-test nested-validation;
            memcachedReferenceNative = platform.memcachedReference;
            inherit (platform) mlx5TrafficGenerator mlx5TrafficGeneratorNative
              mlx5Server mlx5ServerNative dpdkServer dpdkServerNative;
            trafficGenerator = platform.trafficGenerator;
            trafficGeneratorNative = platform.trafficGeneratorNative;
            dpdk = platform.thunderx.dpdk;
          };
        };
        ci = pkgs.lib.optionalAttrs linux {
          environment = platform.ciEnvironment;
          dependencies = platform.ciDependencies;
        };
        apps = pkgs.lib.optionalAttrs linux (
          pkgs.lib.mapAttrs (_: package: {
            type = "app";
            inherit (package) meta;
            program = pkgs.lib.getExe package;
          }) ciCommands
        );
        devShells.default = platform.shell;
      }
    );
}
