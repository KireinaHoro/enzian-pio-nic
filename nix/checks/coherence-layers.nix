{ lib, runCommand, python3, jre_headless, tlc, dcsRom }:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      (lib.fileset.fileFilter (f: f.hasExt "tla") ../../model/tla)
      ../../model/config/worker/suite.json
      ../../model/config/scheduling/suite.json
      ../../tools/model/check_layers.py
      ../../tools/model/coherence_tables.py
      ../../deps/thx-fsms/specifications/remote_fsm.json
    ];
  };
in runCommand "lauberhorn-coherence-layer-contracts" {
  nativeBuildInputs = [ python3 jre_headless tlc ];
  TLA2TOOLS_JAR = "${tlc}/share/java/tla2tools.jar";
} ''
  cp -r ${source}/. .
  chmod -R u+w .
  python3 tools/model/check_layers.py --rom ${dcsRom} --output results --timeout 600
  mkdir -p "$out"
  cp results/summary.json "$out/"
  cp -r results/tables "$out/"
  for case_dir in results/*/; do
    if test -f "$case_dir/TLC.log"; then
      mkdir -p "$out/$(basename "$case_dir")"
      cp "$case_dir/TLC.log" "$case_dir/Check.cfg" "$out/$(basename "$case_dir")/"
    fi
  done
''
