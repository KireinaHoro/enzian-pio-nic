{
  runCommand,
  ghdl,
  eciToolkit,
}:
runCommand "lauberhorn-eci-toolkit-rx-tests"
  {
    nativeBuildInputs = [ ghdl ];
  }
  ''
    bash ${eciToolkit}/tests/rx/run.sh "$out"
    mkdir gsync-work
    ghdl -a --std=08 --workdir=gsync-work \
      ${../../vivado/eci/rtl/gsync_release_cdc.vhd} \
      ${../../vivado/eci/tests/gsync_release_cdc_tb.vhd}
    ghdl --elab-run --std=08 --workdir=gsync-work gsync_release_cdc_tb --assert-level=error
  ''
