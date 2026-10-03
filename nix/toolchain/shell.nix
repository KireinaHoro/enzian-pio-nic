{
  pkgs,
  crossGcc,
  mackerel,
  tlc,
}:
with pkgs;
let
  # hammer a test that failed on CI but can't be easily reproduced locally
  repeatTest = writeShellApplication {
    name = "repeat-test";
    runtimeInputs = [
      mill
      coreutils
    ];
    text = ''
      test_name="$1"
      if [[ $# == 2 ]]; then
        test_suite="$2"
      else
        test_suite="lauberhorn.host.eci.OncRpcSim"
      fi
      # Mill clears a command's sandbox between invocations. Keep compiled RTL
      # outside it, but continue starting a fresh test JVM for each seed.
      export LAUBERHORN_VERILATOR_CACHE="''${LAUBERHORN_VERILATOR_CACHE:-$PWD/out/repeat-test-verilator-cache}"
      mkdir -p "$LAUBERHORN_VERILATOR_CACHE"
      max_runs="''${REPEAT_TEST_LIMIT:-0}"
      runs=0
      while true; do
        if ! mill gen.test.testOnly "$test_suite" -- -t "$test_name"; then
          echo "Test failed; preserving the failing workspace."
          exit 1
        fi
        runs=$((runs + 1))
        if (( max_runs > 0 && runs >= max_runs )); then
          echo "$runs repetitions passed."
          break
        fi
        echo "Test succeeded ($runs), retrying..."
      done
    '';
  };
  updateMillLockFile = writeShellApplication {
    name = "update-mill-lock";
    runtimeInputs = [
      mill-ivy-fetcher
      nixfmt
    ];
    text = ''
      mif codegen --cache "$XDG_CACHE_HOME" -o project-lock.nix
    '';
  };
in
mkShell {
  buildInputs = [
    zlib.dev
    verilator
    clang
    cmake
    ghdl
    gtkwave
    sby
    yices
    jdk
    # Enzian boot/test helpers drive the serial consoles through pexpect.
    (python3.withPackages (p: [ p.pexpect ]))
    (perl.withPackages (p: [ p.autovivification ]))
    tlc
    mill
    updateMillLockFile
    crossGcc
    mackerel
    # quick script to repeat known failing test to find a good reproducer
    repeatTest
  ];
  env.TLA2TOOLS_JAR = "${tlc}/share/java/tla2tools.jar";
  env.LD_LIBRARY_PATH = lib.makeLibraryPath [ libpcap ];
  shellHook = ''
    export XDG_CACHE_HOME=$PWD/out/xdg-cache-home/
  '';
}
