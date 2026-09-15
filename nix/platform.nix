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
  target = pkgs.pkgsCross.aarch64-multiplatform;
  crossGcc = target.buildPackages.gcc;
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
  callPackage = target.lib.callPackageWith (target // { lauberhornRuntime = runtime; });
  applications = {
    microbenchmarks = callPackage ./applications/microbenchmarks.nix {
      src = sources.c [
        "sw/apps/microbenchmarks"
        "sw/usr-common.mk"
      ];
    };
    nix-build-demo = callPackage (source + "/sw/apps/nix-build-demo/package.nix") { };
  };
  deployment = pkgs.callPackage ./images/default.nix {
    inherit
      target
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
    applications = { inherit (applications) microbenchmarks; };
  };
  eciVivadoInputs = pkgs.callPackage ./eci-vivado-inputs.nix {
    inherit genVerilog;
    source = sources.vivado;
    gitRev = identity.revision;
  };
  checks =
    (pkgs.callPackage ./ci-checks.nix {
      src = sources.spinal [
        "build.mill"
        "hw"
        "deps"
      ];
      ivyCache = pkgs.ivy-gather (source + "/project-lock.nix");
      replayPcaps = sources.pcaps;
    })
    // {
      interactive = pkgs.callPackage ./checks/interactive.nix { };
    };
  shell = pkgs.callPackage ./toolchain/shell.nix { inherit crossGcc mackerel; };
  ciEnvironment = pkgs.mkShell {
    inputsFrom = [ (pkgs.callPackage ./toolchain/shell.nix { inherit crossGcc mackerel; }) ];
    packages =
      linuxTools
      ++ (with pkgs; [
        (ivy-gather (source + "/project-lock.nix"))
        configure-mill-env-hook
        iverilog
        libpcap
        squashfsTools
        pkg-config
      ])
      ++ [ target.libtirpc ];
  };
}
