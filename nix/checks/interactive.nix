{
  runCommand,
  python3,
  bash,
  jq,
  shellcheck,
}:
runCommand "lauberhorn-interactive-tests"
  {
    nativeBuildInputs = [
      python3
      bash
      jq
      shellcheck
    ];
  }
  ''
    shellcheck -s bash ${../../tools/enzian/lh-test.sh}
    mkdir -p tools/tests
    cp ${../../tools/enzian/lh-test.sh} tools/lh-test.sh
    cp ${../../tools/enzian/tests/test_lh_test.py} tools/tests/test_lh_test.py
    python3 tools/tests/test_lh_test.py
    touch $out
  ''
