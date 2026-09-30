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
    python3 tools/progress/check_models.py --output results --timeout 300
    mkdir -p "$out"
    cp rtg-bound.json "$out/rtg-bound.json"
    python3 - <<'PYTHON'
    import json
    import os
    from pathlib import Path
    result = json.loads(Path("results/summary.json").read_text())
    for case in result["cases"].values():
        for field in ("elapsed_seconds", "command", "log_sha256", "statistics"):
            case.pop(field)
    Path(os.environ["out"], "summary.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    PYTHON
  ''
