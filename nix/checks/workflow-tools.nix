{
  lib,
  runCommand,
  python3,
  bash,
  shellcheck,
}:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      (lib.fileset.fileFilter (f: f.hasExt "sh" || f.hasExt "py") ../../tools/ci)
      ../../tools/hardware
      ../../tools/enzian/boot.py
      ../../tools/enzian/console.py
      ../../tools/enzian/tests/test_boot.py
      ../../vivado/eci/container.yml
    ];
  };
in
runCommand "lauberhorn-workflow-tools-tests"
  {
    nativeBuildInputs = [
      (python3.withPackages (ps: [ ps.pexpect ]))
      bash
      shellcheck
    ];
  }
  ''
    cp -r ${source}/. .
    shellcheck -x tools/ci/*.sh tools/ci/image/*.sh tools/hardware/*.sh
    python3 -m unittest discover -s tools/ci/tests -v
    python3 -m unittest discover -s tools/enzian/tests -p test_boot.py -v
    touch $out
  ''
