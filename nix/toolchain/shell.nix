{
  pkgs,
  crossGcc,
  mackerel,
}:
with pkgs;
let
  # hammer a test that failed on CI but can't be easily reproduced locally
  repeatTest = writeShellApplication {
    name = "repeat-test";
    runtimeInputs = [ mill ];
    text = ''
      test_name="$1"
      if [[ $# == 2 ]]; then
        test_suite="$2"
      else
        test_suite="lauberhorn.host.eci.NicSim"
      fi
      while mill gen.test.testOnly "$test_suite" -- -t "$test_name"; do
        echo "Test succeeded, retrying..."
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
    mill
    updateMillLockFile
    crossGcc
    mackerel
    # quick script to repeat known failing test to find a good reproducer
    repeatTest
  ];
  env.LD_LIBRARY_PATH = makeLibraryPath [ libpcap ];
  shellHook = ''
    export XDG_CACHE_HOME=$PWD/out/xdg-cache-home/
  '';
}
