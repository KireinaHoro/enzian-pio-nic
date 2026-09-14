# Snapshot identity is pinned by the dandelion flake input. Integration patches
# live here rather than modifying the student's repository.
{ lib, pkgs, crane, runtimePkg, source }:
let
  patched = pkgs.applyPatches {
    name = "dandelion-lauberhorn-source";
    src = source;
    patches = [ ./0001-registered-endpoint-and-worker-placement.patch ];
  };
  upstream = pkgs.callPackage (patched + "/package.nix") {
    inherit crane runtimePkg;
  };
in upstream
