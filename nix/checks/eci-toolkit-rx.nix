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
  ''
