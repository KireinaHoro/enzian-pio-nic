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
      ../../vivado/eci/container.yml
    ];
  };
in
runCommand "lauberhorn-workflow-tools-tests"
  {
    nativeBuildInputs = [
      python3
      bash
      shellcheck
    ];
  }
  ''
    cp -r ${source}/. .
    shellcheck -x tools/ci/*.sh tools/ci/image/*.sh tools/hardware/*.sh
    python3 -m unittest discover -s tools/ci/tests -v
    touch $out
  ''
