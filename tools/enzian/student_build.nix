# Application-only test closures. See docs/development/hardware-test-results.md.
# Sources must be archived snapshots; platform HW/runtime must match runtimeRevision.
{ runtimeRevision, studentSource, dandelionSource, platformRoot ? ../.. }:
let
 ci = builtins.getFlake "git+file://${toString platformRoot}?rev=${runtimeRevision}&submodules=1";
 pkgs = import ci.inputs.nixpkgs {system="x86_64-linux";};
 cross = pkgs.pkgsCross.aarch64-multiplatform;
 rt = ci.packages.x86_64-linux.runtime;
 src = studentSource;
 apps = pkgs.stdenv.mkDerivation {
   name = "sven-runtime-hw-tests";
   src = src;
   nativeBuildInputs = [cross.buildPackages.gcc pkgs.rpcsvc-proto pkgs.pkg-config];
   buildInputs = [cross.libtirpc];
   postUnpack = ''
     cp -r ${ci.outPath}/sw/include $sourceRoot/sw/include
     cp ${ci.outPath}/sw/usr-common.mk $sourceRoot/sw/usr-common.mk
   '';
   buildPhase = ''
     for app in adder-demo nested_adder-demo; do
       make -C sw/apps/$app ARCH=arm64 LAUBERHORN_RT=${rt}
     done
   '';
   installPhase = ''
     mkdir -p $out/bin
     cp sw/apps/adder-demo/adder-demo sw/apps/nested_adder-demo/nested_adder-demo $out/bin/
   '';
 };
 peerClient = pkgs.stdenv.mkDerivation {
   name = "lauberhorn-peer-client";
   src = ci.outPath + "/sw/apps/microbenchmarks";
   nativeBuildInputs = [cross.buildPackages.gcc pkgs.rpcsvc-proto pkgs.pkg-config];
   buildInputs = [cross.libtirpc];
   buildPhase = ''
     mkdir client-build
     cp client/Makefile client/bench_client.c bench.x client-build/
     cd client-build
     make CC=aarch64-unknown-linux-gnu-gcc \
       CFLAGS="-O2 -Wall $(pkg-config --cflags libtirpc)" \
       LDLIBS="$(pkg-config --libs libtirpc)"
   '';
   installPhase = ''mkdir -p $out/bin; cp bench_client $out/bin/'';
 };
 crane = builtins.getFlake "github:ipetkov/crane/469fd08d0bcf6926321fa973c6777fbc87785dd7";
 dandelion = cross.callPackage (dandelionSource + "/package.nix") {inherit crane; runtimePkg = rt;};
 platform = ((import (platformRoot + "/flake.nix")).outputs (ci.inputs // {self=ci;})).packages.x86_64-linux;
 marker = pkgs.writeText "git-hash" "${runtimeRevision}\n";
 mkImage = extra: pkgs.callPackage "${ci.inputs.nixpkgs}/nixos/lib/make-squashfs.nix" {
   storeContents = [platform.kmod rt marker] ++ extra;
 };
in { inherit apps dandelion peerClient; dandelionTests = dandelion.tests;
 peerImage = mkImage [peerClient];
 studentImage = mkImage [apps];
 dandelionImage = mkImage [dandelion];
}
