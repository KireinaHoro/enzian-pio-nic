# Caladan owns its recipes; this platform composes the Enzian target and image.
{ pkgs, target, kernelBuildTree, kernelRelease, source,
  identity ? { revision = null; local = true; } }:
let
  rdmaCore = target.callPackage (source + "/nix/rdma-core.nix") { };
  dpdk = target.callPackage (source + "/nix/dpdk-mlx5.nix") { inherit rdmaCore; };
  runtime = target.callPackage (source + "/package.nix") {
    inherit source dpdk rdmaCore;
  };
  ksched = target.callPackage (source + "/ksched/package.nix") {
    inherit source kernelBuildTree kernelRelease;
  };
  utilities = map pkgs.lib.getBin (with target; [
    bash coreutils iproute2 kmod util-linuxMinimal numactl jq
  ]);
  manifest = pkgs.writeText "caladan-manifest.json" (builtins.toJSON {
    schemaVersion = 1;
    platform = identity;
    inherit kernelRelease;
    system = target.stdenv.hostPlatform.config;
    source = runtime.sourceIdentity;
    optimizationFlags = runtime.optimizationFlags or [];
    packages = {
      caladan = "${runtime}";
      ksched = "${ksched}";
      dpdk = "${dpdk}";
      rdmaCore = "${rdmaCore}";
    };
    iokernelArgs = [ "simple" "noht" "nobw" ];
    datapath = "mlx5-directpath";
    runtimeConfig = { enable_directpath = "1"; };
    hostRequirements = [ "matching mlx5_core and mlx5_ib kernel drivers" "CX-5 PCI address" ];
    acceptance = "pending-arm-port";
  });
  environment = pkgs.buildEnv {
    name = "caladan-enzian-environment";
    paths = [ runtime ] ++ utilities;
    pathsToLink = [ "/bin" "/sbin" ];
    postBuild = ''
      mkdir -p $out/modules
      ln -s ${manifest} $out/manifest.json
      ln -s ${runtime} $out/caladan
      for module in ${ksched}/lib/modules/${kernelRelease}/extra/*.ko; do
        ln -s "$module" $out/modules/
      done
    '';
  };
  image = pkgs.callPackage "${pkgs.path}/nixos/lib/make-squashfs.nix" {
    storeContents = [ environment ];
    fileName = "caladan-enzian";
    pseudoFiles = [ "caladan s 0777 0 0 ${baseNameOf environment}" ];
  };
in {
  inherit runtime ksched environment image dpdk rdmaCore;
  shell = target.mkShell { inputsFrom = [ runtime ksched ]; };
}
