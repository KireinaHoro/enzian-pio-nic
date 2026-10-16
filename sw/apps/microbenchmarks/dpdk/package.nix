{ lib, stdenv, pkg-config, dpdk, python3, rpcsvc-proto, libtirpc }:
stdenv.mkDerivation {
  pname = "lauberhorn-traffic-generator";
  version = "0.1";
  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions [
      ../bench.x ../echo_registry.py
      (lib.fileset.fileFilter (f: builtins.elem f.name [
        "bench_dpdk.c" "echo_server.c" "benchmarks.c" "benchmark.h"
        "aggregate.h" "aggregate_test.c" "wire.h" "load.h" "open_loop.h" "open_loop_test.c" "wire_test.c" "schema_test.c" "Makefile"
      ]) ./.)
    ];
  };
  preBuild = "cd dpdk";
  nativeBuildInputs = [ pkg-config python3 rpcsvc-proto ];
  buildInputs = [ dpdk libtirpc ];
  makeFlags = [ "PKG_CONFIG=${stdenv.cc.targetPrefix}pkg-config" ];
  postBuild = ''
    if ${stdenv.cc.targetPrefix}nm -u echo_dpdk_uninstrumented | grep -E 'clock_gettime'; then
      echo "Software probe hooks survived the uninstrumented responder build" >&2
      exit 1
    fi
  '';
  doCheck = stdenv.buildPlatform.canExecute stdenv.hostPlatform;
  checkPhase = "make check";
  installPhase = ''
    install -Dm755 bench_dpdk $out/bin/bench_dpdk
    install -Dm755 echo_dpdk_uninstrumented $out/bin/echo_dpdk_uninstrumented
    install -Dm755 echo_dpdk $out/bin/echo_dpdk
  '';
  meta.platforms = lib.platforms.linux;
}
