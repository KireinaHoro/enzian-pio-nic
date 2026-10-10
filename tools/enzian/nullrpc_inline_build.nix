# Reproduce the inline-TX measurement against pipeline 518689 hardware.
{ platformRoot ? ../.. }:
let
  hardwareRevision = "45cbfe52d6166e0b05b4434ab9c3aa9dfee3a7c4";
  hw = builtins.getFlake "git+file://${toString platformRoot}?rev=${hardwareRevision}&submodules=1";
  sw = builtins.getFlake "git+file://${toString platformRoot}?rev=8a1521eef730bbdf512ae87b455aa3cc547ee5af&submodules=1";
  pkgs = import hw.inputs.nixpkgs { system = "x86_64-linux"; };
  target = pkgs.pkgsCross.aarch64-multiplatform;
  base = hw.packages.x86_64-linux;
  runtime = base.runtime.overrideAttrs (_: {
    src = (import (sw.outPath + "/nix/sources.nix") { inherit (pkgs) lib; root = /. + builtins.unsafeDiscardStringContext sw.outPath; }).c [
      "sw/rt" "sw/include" "sw/core" "sw/usr-common.mk" "sw/kmod/ioctl.h"
    ];
  });
  benchmark = target.callPackage (sw.outPath + "/sw/apps/microbenchmarks/package.nix") {
    lauberhornRuntime = runtime;
  };
  image = import (hw.outPath + "/nix/images/default.nix") {
    inherit pkgs runtime;
    target = import hw.inputs.nixpkgs { system = "aarch64-linux"; };
    inherit (base) kmod genVerilog devHdrs;
    kernelRelease = "6.8.0-64-generic";
    identity = { revision = hardwareRevision; runtimeRevision = sw.rev; };
  };
in {
  inherit runtime benchmark;
  deploy = image.mkTestImage {
    name = "nullrpc-inline-518689";
    applications.microbenchmarks = benchmark.overrideAttrs (old: {
      passthru = (old.passthru or {}) // { sourceIdentity.revision = sw.rev; };
    });
  };
}
