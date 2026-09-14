{ pkgs, source, configHeaders, deviceHeaders }:
pkgs.stdenv.mkDerivation {
  name = "lauberhorn-runtime-tests";
  src = source;
  nativeBuildInputs = [ pkgs.pkg-config ];
  buildInputs = [ pkgs.libtirpc ];
  buildPhase = ''
    runHook preBuild
    flags="-pthread -I sw/include -I sw/rt -I sw/core -I ${configHeaders} -I ${deviceHeaders} $(pkg-config --cflags libtirpc)"
    $CC $flags sw/tests/endpoint.c sw/rt/endpoint.c -o endpoint-test
    $CC $flags sw/tests/pending-call.c sw/rt/pending_call_table.c sw/rt/utils/bitmap.c -o pending-call-test
    $CC $flags sw/tests/codec.c sw/rt/oncrpc.c $(pkg-config --libs libtirpc) -o codec-test
    $CC $flags sw/tests/endpoint-control.c -o endpoint-control-test
    $CC $flags sw/tests/rpc.c sw/rt/future.c sw/rt/pending_call_table.c sw/rt/fiber/*.c sw/rt/utils/bitmap.c -o rpc-test
    ./rpc-test
    ./endpoint-control-test
    ./endpoint-test
    ./pending-call-test
    ./codec-test
    runHook postBuild
  '';
  installPhase = ''mkdir -p $out; echo passed > $out/result'';
}
