# Rebuild the application (and optionally userspace runtime) on immutable HW.
# Kernel, transport core, ioctl contract and generated collateral stay pinned.
{ platformRef, applicationRevision, applicationBranch ? "feat/lauberhorn-rx-wait",
  runtimeRef ? null }:
let
  platform = builtins.getFlake platformRef;
  software = if runtimeRef == null then platform else builtins.getFlake runtimeRef;
  pkgs = import platform.inputs.nixpkgs { system = "x86_64-linux"; };
  deploymentTarget = import platform.inputs.nixpkgs { system = "aarch64-linux"; };
  hardware = platform.lib.mkPlatform { inherit pkgs; };
  application = builtins.fetchGit {
    url = "ssh://git@gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/benchmarking/memcached.git";
    ref = applicationBranch;
    rev = applicationRevision;
  };
  runtimeSource = pkgs.runCommand "lauberhorn-matched-runtime-source" { } ''
    mkdir -p $out/sw/kmod
    cp -r ${software.outPath}/sw/rt ${software.outPath}/sw/include $out/sw/
    cp -r ${platform.outPath}/sw/core $out/sw/
    cp ${platform.outPath}/sw/usr-common.mk $out/sw/
    cp ${platform.outPath}/sw/kmod/ioctl.h $out/sw/kmod/
  '';
  runtime = if runtimeRef == null then hardware.runtime else
    hardware.runtime.overrideAttrs (_: { src = runtimeSource; });
  identity = {
    revision = platform.rev;
    local = false;
    narHash = platform.narHash;
  } // pkgs.lib.optionalAttrs (runtimeRef != null) {
    runtimeOverride = {
      revision = software.rev;
      paths = [ "sw/rt" "sw/include" ];
      transportCoreRevision = platform.rev;
    };
  };
  memcached = hardware.target.callPackage (application + "/package.nix") {
    source = application;
    lauberhornRuntime = runtime;
  };
  memcachedTools = pkgs.callPackage (platform.outPath + "/nix/software/memcached-tools.nix") {
    source = platform.outPath;
    inherit identity;
    inherit (deploymentTarget) python3 bash;
  };
  deployment = pkgs.callPackage (platform.outPath + "/nix/images/default.nix") {
    target = deploymentTarget;
    inherit (hardware) kmod genVerilog devHdrs kernelRelease;
    inherit runtime identity;
  };
in
assert platform ? rev && software ? rev;
assert builtins.match "[0-9a-f]{40}" applicationRevision != null;
assert toString runtime.genVerilog == toString hardware.genVerilog;
assert toString runtime.devHdrs == toString hardware.devHdrs;
deployment.mkTestImage {
  name = "memcached";
  applications = { inherit memcached; memcached-tools = memcachedTools; };
}
