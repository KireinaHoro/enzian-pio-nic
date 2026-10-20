{ stdenv, lib, lauberhornRuntime, pkg-config, rpcsvc-proto, microbenchmarkSource }:
stdenv.mkDerivation {
  pname = "lauberhorn-recovery-test";
  version = "0.0.1";
  src = (import ../../../nix/sources.nix { inherit lib; root = ../../..; }).c [
    "sw/apps/recovery-test"
    "sw/rt" "sw/core" "sw/include"
  ];
  nativeBuildInputs = [ pkg-config rpcsvc-proto ];
  buildInputs = [ lauberhornRuntime ];
  buildPhase = ''
    cp ${microbenchmarkSource}/bench.x .
    rpcgen -h bench.x -o bench.h
    rpcgen -c bench.x -o bench_xdr.c
    $CC -O2 -g -pthread -march=armv8-a+crc+lse -I. -Isw/rt -Isw/core \
      -I${lauberhornRuntime.genVerilog.headers} -I${lauberhornRuntime.devHdrs} \
      $($PKG_CONFIG --cflags lauberhorn) sw/apps/recovery-test/main.c bench_xdr.c \
      $($PKG_CONFIG --libs lauberhorn) -o recovery-test
  '';
  installPhase = "install -Dm755 recovery-test $out/bin/recovery-test";
  dontStrip = true;
  meta.mainProgram = "recovery-test";
}
