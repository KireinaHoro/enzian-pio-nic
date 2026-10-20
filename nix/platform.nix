{
  pkgs,
  inputs,
  source,
  identity,
}:
let
  sources = import ./sources.nix {
    inherit (pkgs) lib;
    root = source;
  };
  tlc = pkgs.tlaplus18;
  dcsRom = pkgs.callPackage ./hardware/dcs-rom.nix { };
  dcsRomCheck = pkgs.callPackage ./checks/dcs-rom.nix { inherit dcsRom; };
  dcsRtlCheck = pkgs.callPackage ./checks/dcs-pressure.nix { inherit dcsRom; };
  target = pkgs.pkgsCross.aarch64-multiplatform;
  crossGcc = target.buildPackages.gcc;
  deploymentTarget = import inputs.nixpkgs { system = "aarch64-linux"; };
  staticShell = pkgs.callPackage ./hardware/static-shell.nix { };
  mackerel = inputs.mackerel.packages.${pkgs.stdenv.buildPlatform.system}.mackerel2;
  ubuntu = import ./software/ubuntu-noble.nix { inherit pkgs; };
  kernelRelease = ubuntu.release;
  linuxTools = with pkgs; [
    flex
    bison
    bc
    openssl
    elfutils.dev
    crossGcc
    pahole
    python3
    zlib.dev
    rpcsvc-proto
    pkg-config
  ];
  genVerilog = pkgs.callPackage ./hardware/rtl.nix {
    inherit dcsRom;
    src = sources.spinal [
      "build.mill"
      "hw"
      "deps"
    ];
    gitRev = identity.revision;
    lockFile = source + "/project-lock.nix";
  };
  devHdrs = pkgs.callPackage ./hardware/headers.nix {
    inherit genVerilog mackerel;
    src = pkgs.lib.cleanSource (source + "/sw/devices");
  };
  kernelBuildTree = pkgs.callPackage ./software/kernel.nix { inherit linuxTools kernelRelease; };
  thunderx = import (inputs.thunderx-ptp + "/package.nix") {
    inherit pkgs target kernelRelease;
    linux-noble = kernelBuildTree;
  };
  bootFiles = pkgs.callPackage ./images/boot.nix {
    inherit ubuntu;
    modules = thunderx.modules;
  };
  kmod = target.callPackage ./software/module.nix {
    nukeReferences = pkgs.nukeReferences;
    inherit
      kernelBuildTree
      linuxTools
      kernelRelease
      devHdrs
      genVerilog
      ;
    src = sources.c [
      "sw/kmod"
      "sw/core"
      "sw/include"
    ];
  };
  runtime = target.callPackage ./software/runtime.nix {
    inherit devHdrs genVerilog;
    src = sources.c [
      "sw/rt"
      "sw/include"
      "sw/core"
      "sw/usr-common.mk"
      "sw/kmod/ioctl.h"
    ];
  };
  runtimeUninstrumented = runtime.override { tracing = false; };
  microbenchmarkSource = inputs.microbenchmarks;
  benchmarkRuntimeSource = (sources.c [
    "sw/include"
    "sw/rt/oncrpc.c" "sw/rt/server_trace.c" "sw/rt/server_trace.h"
    "sw/rt/pmu.c" "sw/rt/pmu.h" "sw/rt/utils/bitmap.h" "sw/rt/vstate.h"
    "sw/rt/rpc_request.h" "sw/rt/rpc_reply.h" "sw/rt/rpc_call.h" "sw/rt/fiber"
  ]) + "/sw";
  microbenchmarks = callPackage (microbenchmarkSource + "/server/lauberhorn/package.nix") {
    source = microbenchmarkSource;
  };
  microbenchmarksUninstrumented = target.callPackage (microbenchmarkSource + "/server/lauberhorn/package.nix") {
    source = microbenchmarkSource;
    lauberhornRuntime = runtimeUninstrumented;
  };
  callPackage = recipe: args: target.callPackage recipe ({ lauberhornRuntime = runtime; } // args);
  applications =
    pkgs.lib.mapAttrs
      (
        _: app:
        app.overrideAttrs (old: {
          passthru = (old.passthru or { }) // {
            sourceIdentity = identity;
          };
        })
      )
      {
        nested-validation = callPackage (source + "/sw/apps/nested-validation/package.nix") { };
        recovery-test = callPackage (source + "/sw/apps/recovery-test/package.nix") { inherit microbenchmarkSource; };
        nix-build-demo = callPackage (source + "/sw/apps/nix-build-demo/package.nix") { };
      }
    // {
      inherit dandelion memcached microbenchmarks;
    };
  runtimeTests = import ./checks/runtime.nix {
    inherit pkgs;
    source = sources.c [
      "sw/tests"
      "sw/rt"
      "sw/include"
      "sw/core"
      "sw/kmod/ioctl.h"
      "sw/kmod/endpoints.h"
      "sw/kmod/retire_call.h"
    ];
    configHeaders = genVerilog.headers;
    deviceHeaders = devHdrs;
  };
  dandelion = callPackage (inputs.dandelion + "/package.nix") {
    crane = inputs.crane;
    source = inputs.dandelion;
  };
  memcached = callPackage (inputs.memcached + "/package.nix") {
    source = inputs.memcached;
  };
  memcachedTools = pkgs.callPackage ./software/memcached-tools.nix {
    inherit source identity;
    inherit (deploymentTarget) python3 bash;
  };
  memcachedReference = pkgs.callPackage (inputs.memcached + "/package.nix") {
    source = inputs.memcachedUpstream;
    enableLauberhorn = false;
  };
  echoCampaignDeployment = import ./images/traffic-generator.nix {
    inherit pkgs microbenchmarkSource;
    lauberhornSource = benchmarkRuntimeSource;
    nativeVariants = {
      breakdown = { app = applications.microbenchmarks; inherit runtime; };
      off = { app = microbenchmarksUninstrumented; runtime = runtimeUninstrumented; };
    };
    dpdk = mlx5Dpdk;
    probeDpdk = mlx5ProbeDpdk;
    mlx5 = true;
    inherit (thunderx) modules;
    identity = { inherit (identity) revision; };
  };
  mlx5Dpdk = target.callPackage ./software/mlx5 {
    nicvfCompatPatch = inputs.thunderx-ptp + "/patches/dpdk-thunderx-linux-cam.patch";
  };
  mlx5ProbeDpdk = mlx5Dpdk.override { tracing = true; };
  mlx5Deployment = import ./images/traffic-generator.nix {
    inherit pkgs microbenchmarkSource;
    lauberhornSource = benchmarkRuntimeSource;
    dpdk = mlx5Dpdk;
    probeDpdk = mlx5ProbeDpdk;
    mlx5 = true;
    inherit (thunderx) modules;
    identity = { inherit (identity) revision; };
  };
  trafficGeneratorDeployment = import ./images/traffic-generator.nix {
    inherit pkgs microbenchmarkSource;
    lauberhornSource = benchmarkRuntimeSource;
    inherit (thunderx) dpdk modules;
    identity = { inherit (identity) revision; };
  };
  deployment = import ./images/application.nix {
    inherit pkgs;
    target = deploymentTarget;
    inherit (thunderx) dpdk modules;
    inherit
      kmod
      runtime
      genVerilog
      devHdrs
      identity
      kernelRelease
      ;
  };
in
{
  inherit
    kernelBuildTree
    bootFiles
    thunderx
    tlc
    dcsRom
    runtimeTests
    dandelion
    memcached
    memcachedReference
    target
    callPackage
    applications
    runtime
    runtimeUninstrumented
    microbenchmarksUninstrumented
    kmod
    genVerilog
    devHdrs
    kernelRelease
    ;
  inherit (deployment) mkDeploymentEnvironment mkApplicationImage;
  mkCaladan = args: import ./benchmarks/caladan ({
    inherit pkgs target kernelBuildTree kernelRelease identity;
  } // args);
  images = import ./images {
    inherit
      bootFiles
      applications
      deployment
      memcachedTools
      ;
    eciVivadoInputs = pkgs.callPackage ./hardware/vivado-inputs.nix {
      inherit
        genVerilog
        staticShell
        dcsRom
        dcsRomCheck
        dcsRtlCheck
        ;
      source = sources.vivado;
      gitRev = identity.revision;
    };
    trafficGeneratorImage = trafficGeneratorDeployment.image;
    echoCampaignImage = echoCampaignDeployment.image;
    mlx5BenchmarkImage = mlx5Deployment.image;
  };
  mlx5TrafficGenerator = mlx5Deployment.generator;
  mlx5Server = mlx5Deployment.responder;
  mlx5TrafficGeneratorNative = pkgs.callPackage (microbenchmarkSource + "/client/package.nix") {
    source = microbenchmarkSource;
    dpdk = pkgs.callPackage ./software/mlx5 { nicvfCompatPatch = inputs.thunderx-ptp + "/patches/dpdk-thunderx-linux-cam.patch"; };
    mlx5 = true;
  };
  mlx5ServerNative = pkgs.callPackage (microbenchmarkSource + "/server/dpdk/package.nix") {
    source = microbenchmarkSource;
    lauberhornSource = benchmarkRuntimeSource;
    dpdk = pkgs.callPackage ./software/mlx5 { nicvfCompatPatch = inputs.thunderx-ptp + "/patches/dpdk-thunderx-linux-cam.patch"; };
    probeDpdk = pkgs.callPackage ./software/mlx5 { tracing = true; nicvfCompatPatch = inputs.thunderx-ptp + "/patches/dpdk-thunderx-linux-cam.patch"; };
    mlx5 = true;
  };
  trafficGenerator = trafficGeneratorDeployment.generator;
  dpdkServer = trafficGeneratorDeployment.responder;
  trafficGeneratorNative = pkgs.callPackage (microbenchmarkSource + "/client/package.nix") {
    source = microbenchmarkSource;
    dpdk = thunderx.dpdkNative;
  };
  dpdkServerNative = pkgs.callPackage (microbenchmarkSource + "/server/dpdk/package.nix") {
    source = microbenchmarkSource;
    lauberhornSource = benchmarkRuntimeSource;
    dpdk = thunderx.dpdkNative;
  };
  checks =
    (import ./checks {
      inherit pkgs tlc dcsRom;
      src = sources.spinal [
        "build.mill"
        "hw"
        "deps"
      ];
      ivyCache = pkgs.ivy-gather (source + "/project-lock.nix");
      replayPcaps = sources.pcaps;
      runtimeInterface = applications.nix-build-demo;
      inherit runtimeTests;
      eciToolkit = pkgs.lib.cleanSource (source + "/vivado/eci/eci-toolkit");
    })
    // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
      memcached-adapter = import ./checks/memcached.nix {
        inherit pkgs source;
        appSource = inputs.memcached;
        upstreamSource = inputs.memcachedUpstream;
      };
    };
  shell = pkgs.callPackage ./toolchain/shell.nix { inherit crossGcc mackerel tlc; };
  ciDependencies = import ./ci/dependencies.nix {
    inherit pkgs staticShell;
    kernel = kernelBuildTree;
    crossPkgs = target;
    deploymentPackages = import ./interactive/dependencies.nix { target = deploymentTarget; };
  };
  ciEnvironment = pkgs.callPackage ./ci/environment.nix {
    shell = pkgs.callPackage ./toolchain/shell.nix { inherit crossGcc mackerel tlc; };
    inherit linuxTools;
    ivyCache = pkgs.ivy-gather (source + "/project-lock.nix");
  };
}
