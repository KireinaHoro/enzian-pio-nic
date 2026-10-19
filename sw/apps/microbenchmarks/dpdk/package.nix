{ lib, stdenv, pkg-config, dpdk, python3, rpcsvc-proto, libtirpc, probeDpdk ? dpdk, mlx5 ? false }:
stdenv.mkDerivation {
  pname = "lauberhorn-traffic-generator";
  version = "0.1";
  src = lib.fileset.toSource {
    root = ../../..;
    fileset = lib.fileset.unions [
      ../bench.x ../echo_registry.py ../echo_handler.h ../echo_handler.c
      ../../../include
      ../../../rt/oncrpc.c ../../../rt/server_trace.c ../../../rt/server_trace.h ../../../rt/pmu.c ../../../rt/pmu.h
      ../../../rt/utils/bitmap.h ../../../rt/vstate.h ../../../rt/rpc_request.h ../../../rt/rpc_reply.h ../../../rt/rpc_call.h ../../../rt/fiber
      (lib.fileset.fileFilter (f: builtins.elem f.name [
        "rpc_backend.h" "rpc_backend_test.c" "queue_probe_test.c" "bench_dpdk.c" "echo_server.c" "benchmarks.c" "benchmark.h"
        "aggregate.h" "aggregate_test.c" "wire.h" "load.h" "open_loop.h" "open_loop_test.c" "wire_test.c" "schema_test.c" "Makefile"
      ]) ./.)
    ];
  };
  preBuild = "cd apps/microbenchmarks/dpdk";
  nativeBuildInputs = [ pkg-config python3 rpcsvc-proto ];
  buildInputs = [ dpdk libtirpc ];
  makeFlags = [ "PKG_CONFIG=${stdenv.cc.targetPrefix}pkg-config" ]
    ++ lib.optionals mlx5 [ "EXTRA_DPDK_LIBS=-lrte_net_mlx5" ];
  RPC_ARCH_FLAGS = lib.optionalString stdenv.hostPlatform.isAarch64 "-march=armv8-a+crc+lse -mtune=thunderxt88";
  NIX_CFLAGS_COMPILE = lib.optionalString mlx5 "-DLH_HAVE_MLX5=1 -DALLOW_EXPERIMENTAL_API";
  postBuild = lib.optionalString mlx5 ''
    rm echo_dpdk
    PKG_CONFIG_PATH=${probeDpdk}/lib/pkgconfig:$PKG_CONFIG_PATH make echo_dpdk CFLAGS="-O3 -g -Wall -Wextra -Werror -std=gnu11 -DLH_HAVE_MLX5=1 -DALLOW_EXPERIMENTAL_API" EXTRA_DPDK_LIBS="-L${probeDpdk}/lib -Wl,-rpath,${probeDpdk}/lib -lrte_net_mlx5"
  '' + ''
    if ${stdenv.cc.targetPrefix}nm echo_dpdk_uninstrumented | grep -E 'server_trace_|lauberhorn_trace_|lh_pmu_| [tT] stamp$'; then
      echo "Software probe hooks survived the uninstrumented responder build" >&2
      exit 1
    fi
  '';
  postCheck = lib.optionalString mlx5 ''
    for pair in ${dpdk}:0 ${probeDpdk}:1; do
      package="''${pair%:*}"
      expected="''${pair##*:}"
      PKG_CONFIG_PATH=$package/lib/pkgconfig:$PKG_CONFIG_PATH $CC \
        -DALLOW_EXPERIMENTAL_API -pthread -I$package/include queue_probe_test.c \
        -L$package/lib -Wl,-rpath,$package/lib -lrte_net_mlx5 -o queue-probe-test
      ./queue-probe-test "$expected"
    done
  '';
  doCheck = stdenv.buildPlatform.canExecute stdenv.hostPlatform;
  checkPhase = "make check; runHook postCheck";
  installPhase = ''
    install -Dm755 bench_dpdk $out/bin/bench_dpdk
    install -Dm755 echo_dpdk_uninstrumented $out/bin/echo_dpdk_uninstrumented
    install -Dm755 echo_dpdk $out/bin/echo_dpdk
  '';
  meta.platforms = lib.platforms.linux;
}
