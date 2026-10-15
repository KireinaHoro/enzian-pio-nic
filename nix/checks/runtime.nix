{
  pkgs,
  source,
  configHeaders,
  deviceHeaders,
}:
pkgs.stdenv.mkDerivation {
  name = "lauberhorn-runtime-tests";
  src = source;
  nativeBuildInputs = [ pkgs.pkg-config ];
  buildInputs = [ pkgs.libtirpc ];
  buildPhase = ''
    runHook preBuild
    flags="-pthread -I sw/include -I sw/rt -I sw/core -I ${configHeaders} -I ${deviceHeaders} $(pkg-config --cflags libtirpc)"
    $CC $flags sw/tests/eci-buffer.c -o eci-buffer-test
    ./eci-buffer-test
    $CC $flags -fsanitize=address,undefined -fno-sanitize-recover=all sw/tests/eci-buffer.c -o eci-buffer-sanitized-test
    ASAN_OPTIONS=detect_leaks=0 ./eci-buffer-sanitized-test
    $CC $flags -I sw/kmod sw/tests/udp.c sw/rt/server_trace.c sw/rt/pmu.c sw/rt/fiber/*.c sw/rt/utils/bitmap.c -o udp-test
    ./udp-test
    $CC $flags sw/tests/endpoint.c sw/rt/endpoint.c -o endpoint-test
    $CC $flags sw/tests/pending-call.c sw/rt/pending_call_table.c sw/rt/utils/bitmap.c -o pending-call-test
    $CC $flags sw/tests/codec.c sw/rt/oncrpc.c $(pkg-config --libs libtirpc) -o codec-test
    $CC $flags sw/tests/endpoint-control.c -o endpoint-control-test
    $CC $flags -I sw/kmod sw/tests/activation.c sw/rt/activation.c -o activation-test
    $CC $flags sw/tests/rpc.c sw/rt/server_trace.c sw/rt/pmu.c sw/rt/future.c sw/rt/pending_call_table.c sw/rt/fiber/*.c sw/rt/utils/bitmap.c -o rpc-test
    $CC $flags -DNDEBUG sw/tests/rpc.c sw/rt/server_trace.c sw/rt/pmu.c sw/rt/future.c sw/rt/pending_call_table.c sw/rt/fiber/*.c sw/rt/utils/bitmap.c -o rpc-release-test
    ./rpc-release-test
    ./rpc-test
    ./endpoint-control-test
    ./activation-test
    ./endpoint-test
    ./pending-call-test
    ./codec-test
    $CC $flags -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer sw/tests/codec.c sw/rt/oncrpc.c $(pkg-config --libs libtirpc) -o codec-sanitized-test
    # LeakSanitizer cannot attach inside the Nix sandbox; keep bounds/UB checks.
    ASAN_OPTIONS=detect_leaks=0 ./codec-sanitized-test
    for test in rpc udp; do
      extra=""
      if [ "$test" = rpc ]; then extra="sw/rt/future.c sw/rt/pending_call_table.c"; fi
      $CC $flags -DLH_ENABLE_TRACING=0 -I sw/kmod sw/tests/$test.c $extra sw/rt/fiber/*.c sw/rt/utils/bitmap.c -o $test-uninstrumented-test
      ./$test-uninstrumented-test
      if nm $test-uninstrumented-test | grep -E 'server_trace_|lauberhorn_trace_|lh_pmu_'; then exit 1; fi
    done
    $CC $flags -DLH_ENABLE_TRACING=0 -I sw/kmod sw/tests/activation.c sw/rt/activation.c -o activation-uninstrumented-test
    ./activation-uninstrumented-test
    runHook postBuild
  '';
  installPhase = "mkdir -p $out; echo passed > $out/result";
}
