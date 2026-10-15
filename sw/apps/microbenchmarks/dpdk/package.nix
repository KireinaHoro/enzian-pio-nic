{ lib, stdenv, pkg-config, dpdk, python3, rpcsvc-proto, libtirpc }:
stdenv.mkDerivation {
  pname = "lauberhorn-traffic-generator";
  version = "0.1";
  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions [
      ../bench.x ../echo_registry.py
      (lib.fileset.fileFilter (f: builtins.elem f.name [
        "bench_dpdk.c" "nullrpc_server.c" "benchmarks.c" "benchmark.h"
        "wire.h" "load.h" "open_loop.h" "open_loop_test.c" "wire_test.c" "schema_test.c" "Makefile"
      ]) ./.)
    ];
  };
  preBuild = "cd dpdk";
  nativeBuildInputs = [ pkg-config python3 rpcsvc-proto ];
  buildInputs = [ dpdk libtirpc ];
  makeFlags = [ "PKG_CONFIG=${stdenv.cc.targetPrefix}pkg-config" ];
  postBuild = ''
    if ${stdenv.cc.targetPrefix}nm -u nullrpc_dpdk_uninstrumented | grep -E 'clock_gettime|rte_eth_timesync|rte_mbuf_dyn_rx_timestamp'; then
      echo "Software/PTP timestamp hooks survived the uninstrumented responder build" >&2
      exit 1
    fi
  '';
  doCheck = stdenv.buildPlatform.canExecute stdenv.hostPlatform;
  checkPhase = "make check";
  installPhase = ''
    install -Dm755 bench_dpdk $out/bin/bench_dpdk
    install -Dm755 nullrpc_dpdk_uninstrumented $out/bin/nullrpc_dpdk_uninstrumented
    install -Dm755 nullrpc_dpdk $out/bin/nullrpc_dpdk
  '';
  meta.platforms = lib.platforms.linux;
}
