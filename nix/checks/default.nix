{
  pkgs,
  tlc,
  src,
  ivyCache,
  replayPcaps,
  runtimeInterface,
  eciToolkit,
}:
let
  check =
    name: commands:
    pkgs.callPackage ./simulation.nix {
      inherit
        src
        ivyCache
        replayPcaps
        name
        commands
        ;
    };
in
{
  axi-decerr =
    pkgs.runCommand "axi-decerr-regression"
      {
        nativeBuildInputs = [
          pkgs.verilator
          pkgs.gcc
          pkgs.gnumake
          pkgs.bash
          pkgs.python3
        ];
      }
      ''
        bash ${src}/deps/blocks/deps/verilog-axi/tb/axi_crossbar/run_decerr.sh
        touch $out
      '';
  scheduling-progress = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; };
  steady-progress = pkgs.callPackage ./steady-progress.nix { inherit tlc; };
  workflow-tools = pkgs.callPackage ./workflow-tools.nix { };
  interactive = pkgs.callPackage ./interactive.nix { };
  eci-toolkit-rx = pkgs.callPackage ./eci-toolkit-rx.nix { inherit eciToolkit; };
  trace-fifo-tests = pkgs.callPackage ./trace-fifo.nix { inherit src; };
  fast-tests-eci = check "fast-tests-eci" ''
    mill --no-daemon --offline gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci
    mill --no-daemon --offline gen.test.testOnly \
      lauberhorn.RecoveryStreamBufferSim lauberhorn.RecoveryInFlightSim \
      lauberhorn.ExecutionWatchdogSim lauberhorn.RpcReplyContextsSim
  '';
  blocks-tests = check "blocks-tests" ''
    mill --no-daemon --offline 'blocks[2.13.12].test'
  '';
  trace-tests = check "trace-tests" ''
    mill --no-daemon --offline 'blocks[2.13.12].test.testOnly' jsteward.blocks.misc.TraceBufferDMATests
    mill --no-daemon --offline gen.test.testOnly lauberhorn.LauberhornTraceDumpTests
  '';
}
// pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
  runtime-interface = runtimeInterface;
}
