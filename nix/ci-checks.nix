{ pkgs, src, ivyCache, replayPcaps }:
let
  # Derivations, not dev shells: compilers/JVM/JNI/simulator and Maven inputs
  # are locked and the actual tests execute without network access.
  check = name: commands: pkgs.stdenv.mkDerivation {
    name = "lauberhorn-${name}";
    inherit src;
    nativeBuildInputs = with pkgs; [ mill configure-mill-env-hook verilator cmake ];
    dontUseCmakeConfigure = true;
    buildInputs = [ ivyCache pkgs.zlib ];
    LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.libpcap ];
    LAUBERHORN_PCAP_DIR = "${replayPcaps}/data/eci/iladata";
    buildPhase = ''
      runHook preBuild
      ${commands}
      runHook postBuild
    '';
    installPhase = ''
      mkdir -p $out
      find out -name test-report.xml -exec cp --parents '{}' $out/ \;
      echo passed > $out/PASSED
    '';
  };
in {
  trace-fifo-tests = pkgs.runCommand "lauberhorn-trace-fifo-tests" {
    nativeBuildInputs = [ pkgs.iverilog ];
  } ''
    mkdir -p $out
    for enabled in 0 1; do
      iverilog -g2012 -s tb -Ptb.OUTPUT_FIFO_ENABLE=$enabled -o sim \
        ${src}/hw/test/rtl/trace_tx_fifo_tb.sv \
        ${src}/deps/blocks/deps/verilog-axis/rtl/axis_async_fifo.v
      vvp sim | tee $out/output-fifo-$enabled.log
    done
    echo passed > $out/PASSED
  '';
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
