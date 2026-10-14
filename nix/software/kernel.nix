{
  pkgs,
  linuxTools,
  kernelRelease,
}:
let
  ubuntu = import ./ubuntu-noble.nix { inherit pkgs; };
in
assert kernelRelease == ubuntu.release;
pkgs.stdenv.mkDerivation {
  name = "linux-noble-src";
  inherit (ubuntu) version;
  src = ubuntu.source;
  nativeBuildInputs = linuxTools ++ [ pkgs.dpkg ];
  buildPhase = ''
    patchShebangs scripts/bpf_doc.py

    export ARCH=arm64
    export CROSS_COMPILE=aarch64-unknown-linux-gnu-

    mkdir sysroot
    dpkg-deb -x ${ubuntu.headers} sysroot/
    for a in .config Module.symvers; do
      cp sysroot/usr/src/linux-headers-${kernelRelease}/$a .
    done
    rm -rf sysroot

    cp .config .config.bak
    # Ubuntu's ABI release differs from the upstream Makefile version.
    # Generate matching utsrelease.h and kernel.release through Kbuild.
    make KERNELRELEASE=${kernelRelease} olddefconfig
    make KERNELRELEASE=${kernelRelease} modules_prepare
  '';
  installPhase = ''
    mkdir -p $out
    cp -a . $out/
  '';
  dontFixup = true;
}
