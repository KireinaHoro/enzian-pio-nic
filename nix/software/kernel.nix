{
  pkgs,
  linuxTools,
  kernelRelease,
}:
let
  genericDeb = pkgs.fetchurl {
    url = "http://launchpadlibrarian.net/799672062/linux-headers-6.8.0-64-generic_6.8.0-64.67_arm64.deb";
    hash = "sha256-x375IU9XFmuVJEoocimnR5qRUS8ya6sWqs3jYPeaTKM=";
  };
in
pkgs.stdenv.mkDerivation {
  name = "linux-noble-src";
  version = "6.8.0-64.67";
  src = pkgs.fetchgit {
    url = "https://git.launchpad.net/~ubuntu-kernel/ubuntu/+source/linux/+git/noble";
    tag = "Ubuntu-6.8.0-64.67";
    hash = "sha256-F2bcvzxlE2wzSj2kr+Fj9Ui6ht6GRv4p/hRSuSyglCc=";
  };
  nativeBuildInputs = linuxTools ++ [ pkgs.dpkg ];
  buildPhase = ''
    patchShebangs scripts/bpf_doc.py

    export ARCH=arm64
    export CROSS_COMPILE=aarch64-unknown-linux-gnu-

    mkdir sysroot
    dpkg-deb -x ${genericDeb} sysroot/
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
