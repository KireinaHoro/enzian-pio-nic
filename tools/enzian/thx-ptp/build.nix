{ repo ? ../../.. }:
let
  flake = builtins.getFlake ("git+file://" + toString repo);
  pkgs = import flake.inputs.nixpkgs { system = "x86_64-linux"; };
  cross = pkgs.pkgsCross.aarch64-multiplatform.buildPackages.gcc;
  linuxTools = with pkgs; [ flex bison bc openssl elfutils.dev cross pahole python3 zlib.dev pkg-config ];
  kernel = import ../../../nix/software/kernel.nix {
    inherit pkgs linuxTools; kernelRelease = "6.8.0-64-generic";
  };
in pkgs.stdenv.mkDerivation {
  name = "thx-ptp-test";
  dontUnpack = true;
  nativeBuildInputs = linuxTools;
  buildPhase = ''
    python3 ${./prepare.py} ${kernel} module
    make -C ${kernel} M=$PWD/module ARCH=arm64 CROSS_COMPILE=aarch64-unknown-linux-gnu- KERNELRELEASE=6.8.0-64-generic modules
    aarch64-unknown-linux-gnu-readelf -p .modinfo module/lh_nicvf.ko | grep 'vermagic=6.8.0-64-generic '
  '';
  installPhase = ''
    mkdir -p $out
    cp module/lh_nicvf.ko $out/
    cp -r module $out/source
  '';
  dontFixup = true;
}
