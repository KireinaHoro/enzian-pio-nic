# nixpkgs' generic cross file uses cpu='aarch64', which DPDK treats as
# a compiler -march value, and omits the required Arm SoC property.
{ dpdk, lib, stdenv, writeText }:
let
  armConfig = writeText "dpdk-thunderxt88.ini" ''
    [host_machine]
    cpu = 'armv8-a'
    [properties]
    platform = 'thunderxt88'
  '';
in dpdk.overrideAttrs (old: {
  # This package serves the benchmark, not every DPDK application/PMD.
  # Avoid the large unrelated driver and Sphinx builds on the build host.
  outputs = [ "out" ];
  mesonFlags = (old.mesonFlags or []) ++ [
    "-Denable_docs=false" "-Denable_libs=ethdev"
    "-Denable_drivers=net/thunderx,net/pcap" "-Ddisable_apps=*"
  ];
  patches = (old.patches or []) ++ [ ./patches/dpdk-thunderx-linux-cam.patch ];
  postPatch = (old.postPatch or "") + ''
    substituteInPlace buildtools/meson.build \
      --replace-fail "pmdinfo += 'ar'" "pmdinfo += '${stdenv.cc.targetPrefix}ar'"
  '';
  preConfigure = (old.preConfigure or "") + lib.optionalString
    (stdenv.hostPlatform.isAarch64 && stdenv.buildPlatform != stdenv.hostPlatform) ''
      mesonFlagsArray+=(--cross-file=${armConfig})
    '';
})
