{ lib, stdenv, pkg-config, dpdk }:
stdenv.mkDerivation {
  pname = "lauberhorn-traffic-generator";
  version = "0.1";
  src = lib.cleanSourceWith {
    src = ./.;
    filter = path: type: type == "directory" ||
      lib.any (suffix: lib.hasSuffix suffix path) [ ".c" ".h" "/Makefile" ];
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
