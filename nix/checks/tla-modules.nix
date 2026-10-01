{ lib, runCommand, python3, jre_headless, tlc }:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      ../../tools/model/check_modules.py
      ../../model/config/modules.json
      ../../model/legacy/tla/lauberhorn.tla
      (lib.fileset.fileFilter (f: f.hasExt "tla") ../../model/tla/steady)
      (lib.fileset.fileFilter (f: f.hasExt "tla") ../../model/tla/scheduling)
    ];
  };
in
runCommand "lauberhorn-tla-module-boundaries" {
  nativeBuildInputs = [ python3 jre_headless tlc ];
  TLA2TOOLS_JAR = "${tlc}/share/java/tla2tools.jar";
} ''
  cp -r ${source}/. .
  chmod -R u+w .
  python3 tools/model/check_modules.py --output results
  mkdir -p "$out"
  cp results/summary.json "$out/summary.json"
''
