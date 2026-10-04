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
  # Keep SDK metadata and Python setup tools out of the client runtime closure.
  outputs = [ "out" "dev" "tools" ];
  nativeBuildInputs = builtins.filter
    (package: !(lib.hasInfix "sphinx" (lib.getName package)) && lib.getName package != "doxygen")
    (old.nativeBuildInputs or [ ]);
  postInstall = (old.postInstall or "") + ''
    moveToOutput bin "$tools"
    if [ -d "$out/share/dpdk" ]; then
      moveToOutput share/dpdk "$tools"
    fi
    for archive in "$out"/lib/*.a "$out"/lib/dpdk/pmds-*/*.a; do
      if [ -f "$archive" ]; then moveToOutput "''${archive#"$out/"}" "$dev"; fi
    done
  '';
  postFixup = (old.postFixup or "") + ''
    # Static users still find the archives after separating the SDK output.
    substituteInPlace "$dev/lib/pkgconfig/libdpdk.pc" \
      --replace-fail "Libs.private: " "Libs.private: -L$dev/lib "
  '';
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
