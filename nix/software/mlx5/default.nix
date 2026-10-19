# CPU-NIC benchmark PMDs. Instrumentation is a distinct build, not a runtime flag.
{ lib, stdenv, writeText, dpdk, tracing ? false, nicvfCompatPatch ? null }:
let
  armConfig = writeText "echo-thunderxt88.ini" ''
    [host_machine]
    cpu = 'armv8-a'
    [properties]
    platform = 'thunderxt88'
  '';
in dpdk.overrideAttrs (old: {
  pname = "echo-dpdk-mlx5" + lib.optionalString tracing "-instrumented";
  outputs = [ "out" ];
  nativeBuildInputs = builtins.filter
    (p: !(lib.hasInfix "sphinx" (lib.getName p)) && lib.getName p != "doxygen") old.nativeBuildInputs;
  patches = (old.patches or []) ++ [ ./queue-probes.patch ] ++ lib.optional (nicvfCompatPatch != null) nicvfCompatPatch;
  mesonFlags = old.mesonFlags ++ [
    "-Denable_docs=false" "-Denable_libs=ethdev,hash"
    "-Denable_drivers=net/mlx5,common/mlx5,bus/pci,bus/auxiliary,net/thunderx,net/pcap"
    "-Ddisable_apps=*"
  ] ++ lib.optional tracing "-Dc_args=-DLH_MLX5_PROBES=1";
  preConfigure = (old.preConfigure or "") + lib.optionalString
    (stdenv.hostPlatform.isAarch64 && stdenv.buildPlatform != stdenv.hostPlatform) ''
      mesonFlagsArray+=(--cross-file=${armConfig})
    '';
  postPatch = (old.postPatch or "") + ''
    substituteInPlace buildtools/meson.build \
      --replace-fail "pmdinfo += 'ar'" "pmdinfo += '${stdenv.cc.targetPrefix}ar'"
  '';
  passthru = (old.passthru or {}) // { inherit tracing; };
})
