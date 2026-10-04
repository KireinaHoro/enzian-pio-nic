{ lib, stdenv, pkg-config, dpdk }:
stdenv.mkDerivation {
  pname = "lauberhorn-traffic-generator";
  version = "0.1";
  src = lib.cleanSourceWith {
    src = lib.cleanSource ./.;
    # Only direct build inputs: exclude binaries, captures and nested trees.
    filter = path: type: type == "regular" && builtins.elem (baseNameOf path) [
      "bench_dpdk.c" "benchmarks.c" "benchmark.h" "wire.h" "wire_test.c" "Makefile"
    ];
  };
  nativeBuildInputs = [ pkg-config ];
  buildInputs = [ dpdk ];
  makeFlags = [ "PKG_CONFIG=${stdenv.cc.targetPrefix}pkg-config" ];
  doCheck = stdenv.buildPlatform.canExecute stdenv.hostPlatform;
  checkPhase = "make check";
  installPhase = ''
    install -Dm755 bench_dpdk $out/bin/bench_dpdk
  '';
  meta.platforms = lib.platforms.linux;
}
