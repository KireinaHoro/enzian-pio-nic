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
  target = pkgs.pkgsCross.aarch64-multiplatform;
  crossGcc = target.buildPackages.gcc;
  deploymentTarget = import inputs.nixpkgs { system = "aarch64-linux"; };
  staticShell = pkgs.callPackage ./hardware/static-shell.nix { };
  mackerel = inputs.mackerel.packages.${pkgs.stdenv.buildPlatform.system}.mackerel2;
  kernelRelease = "6.8.0-64-generic";
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
  kernel = pkgs.callPackage ./software/kernel.nix { inherit linuxTools kernelRelease; };
  kmod = target.callPackage ./software/module.nix {
    nukeReferences = pkgs.nukeReferences;
    inherit
      kernel
      linuxTools
      kernelRelease
      devHdrs
      genVerilog
      ;
    src = sources.c [
      "sw/kmod"
      "sw/core"
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
        recovery-test = callPackage (source + "/sw/apps/recovery-test/package.nix") { };
        microbenchmarks = callPackage (source + "/sw/apps/microbenchmarks/package.nix") { };
        nix-build-demo = callPackage (source + "/sw/apps/nix-build-demo/package.nix") { };
      }
    // {
      inherit dandelion;
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
      "sw/kmod/recovery.h"
    ];
    configHeaders = genVerilog.headers;
    deviceHeaders = devHdrs;
  };
  dandelion = callPackage (source + "/sw/apps/dandelion/package.nix") {
    crane = inputs.crane;
    source = inputs.dandelion;
  };
  deployment = pkgs.callPackage ./images/default.nix {
    target = deploymentTarget;
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
    tlc
    runtimeTests
    dandelion
    target
    callPackage
    applications
    runtime
    kmod
    genVerilog
    devHdrs
    kernelRelease
    ;
  inherit (deployment) mkDeploymentEnvironment mkTestImage;
  deployFs = deployment.mkTestImage {
    name = "microbenchmarks";
    applications = { inherit (applications) microbenchmarks recovery-test nested-validation; };
  };
  dandelionDeployFs = deployment.mkTestImage {
    name = "dandelion";
    applications = {
      inherit dandelion;
      dandelion-tests = dandelion.interactiveTests.smoke;
    };
  };
  eciVivadoInputs = pkgs.callPackage ./hardware/vivado-inputs.nix {
    inherit genVerilog staticShell;
    source = sources.vivado;
    gitRev = identity.revision;
  };
  checks = import ./checks {
    inherit pkgs tlc;
    src = sources.spinal [
      "build.mill"
      "hw"
      "deps"
    ];
    ivyCache = pkgs.ivy-gather (source + "/project-lock.nix");
    replayPcaps = sources.pcaps;
    runtimeInterface = applications.nix-build-demo;
    eciToolkit = pkgs.lib.cleanSource (source + "/vivado/eci/eci-toolkit");
  };
  shell = pkgs.callPackage ./toolchain/shell.nix { inherit crossGcc mackerel tlc; };
  ciDependencies = import ./ci/dependencies.nix {
    inherit pkgs kernel staticShell;
    crossPkgs = target;
    deploymentPackages = import ./interactive/dependencies.nix { target = deploymentTarget; };
  };
  ciEnvironment = pkgs.callPackage ./ci/environment.nix {
    shell = pkgs.callPackage ./toolchain/shell.nix { inherit crossGcc mackerel tlc; };
    inherit linuxTools;
    ivyCache = pkgs.ivy-gather (source + "/project-lock.nix");
  };
}
