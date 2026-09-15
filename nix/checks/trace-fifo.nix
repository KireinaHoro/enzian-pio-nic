{
  runCommand,
  iverilog,
  src,
}:
runCommand "lauberhorn-trace-fifo-tests"
  {
    nativeBuildInputs = [ iverilog ];
  }
  ''
    mkdir -p $out
    for enabled in 0 1; do
      iverilog -g2012 -s tb -Ptb.OUTPUT_FIFO_ENABLE=$enabled -o sim \
        ${src}/hw/test/rtl/trace_tx_fifo_tb.sv \
        ${src}/deps/blocks/deps/verilog-axis/rtl/axis_async_fifo.v
      vvp sim | tee $out/output-fifo-$enabled.log
    done
    echo passed > $out/PASSED
  ''
