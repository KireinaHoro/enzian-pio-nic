{
  lib,
  runCommand,
  python3,
  jre_headless,
  tlc,
  racesOnly ? false,
  killOnly ? false,
  composedOnly ? false,
  composedInvariants ? false,
}:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      (lib.fileset.fileFilter (f: f.hasExt "py") ../../tools/scheduling)
      ../../tools/progress/capacity.py
      (lib.fileset.fileFilter (f: f.hasExt "tla") ../../model/steady)
      (lib.fileset.fileFilter (f: f.hasExt "tla") ../../model/scheduling)
      ../../model/steady/selected-8k.json
    ];
  };
in
runCommand (if killOnly then "lauberhorn-scheduling-kill-exit" else if composedInvariants then "lauberhorn-scheduling-composed-invariants" else if composedOnly then "lauberhorn-scheduling-composed" else if racesOnly then "lauberhorn-scheduling-races" else "lauberhorn-scheduling-progress")
  {
    nativeBuildInputs = [ python3 jre_headless tlc ];
    TLA2TOOLS_JAR = "${tlc}/share/java/tla2tools.jar";
  }
  ''
    cp -r ${source}/. .
    chmod -R u+w .
    python3 tools/scheduling/addresses.py --check
    python3 tools/scheduling/check_models.py --output results --timeout ${if composedOnly then "7200" else if composedInvariants then "1200" else "900"} --heap-mib ${if composedOnly then "4096" else "2048"} --workers 4 ${lib.optionalString killOnly "--kill-only"} ${lib.optionalString racesOnly "--races-only"} ${lib.optionalString composedOnly "--composed-only"} ${lib.optionalString composedInvariants "--composed-invariants"}
    mkdir -p "$out"
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
