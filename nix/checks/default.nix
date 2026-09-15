{
  pkgs,
  src,
  ivyCache,
  replayPcaps,
  runtimeInterface,
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
  interactive = pkgs.callPackage ./interactive.nix { };
  trace-fifo-tests = pkgs.callPackage ./trace-fifo.nix { inherit src; };
  fast-tests-eci = check "fast-tests-eci" ''
    mill --no-daemon --offline gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci
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
