{
  lib,
  runCommand,
  python3,
  jre_headless,
  tlc,
}:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      (lib.fileset.fileFilter (f: f.hasExt "py") ../../tools/progress)
      (lib.fileset.fileFilter (f: f.hasExt "tla" || f.hasExt "cfg") ../../model/steady)
      ../../model/steady/selected-8k.json
      ../../vivado/eci/directory-controller-slice/dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv
    ];
  };
in
runCommand "lauberhorn-steady-progress"
  {
    nativeBuildInputs = [
      python3
      jre_headless
      tlc
    ];
    TLA2TOOLS_JAR = "${tlc}/share/java/tla2tools.jar";
  }
  ''
    cp -r ${source}/. .
    chmod -R u+w .
    python3 -m unittest discover -s tools/progress -v
    python3 tools/progress/verify_config.py model/steady/selected-8k.json
    python3 tools/progress/audit_resources.py model/steady/selected-8k.json > rtg-bound.json
    python3 tools/progress/check_models.py --output "$out" --timeout 300
    cp rtg-bound.json "$out/rtg-bound.json"
  ''
