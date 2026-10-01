{
  lib,
  runCommand,
  python3,
  jre_headless,
  tlc,
  racesOnly ? false,
  axiOnly ? false,
  fullOnly ? false,
  killOnly ? false,
  killTransportOnly ? false,
  cacheContractsOnly ? false,
  composedOnly ? false,
  composedInvariants ? false,
  transportOnly ? false,
}:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      (lib.fileset.fileFilter (f: f.hasExt "py") ../../tools/scheduling)
      ../../tools/progress/capacity.py
      (lib.fileset.fileFilter (f: f.hasExt "tla") ../../model/tla/steady)
      (lib.fileset.fileFilter (f: f.hasExt "tla") ../../model/tla/scheduling)
      ../../model/config/scheduling/suite.json
      ../../model/data/selected-8k.json
    ];
  };
  run = names: runCommand (if names != [] then "lauberhorn-scheduling-${builtins.head names}" else if fullOnly then "lauberhorn-scheduling-full" else if killTransportOnly then "lauberhorn-scheduling-kill-transport" else if axiOnly then "lauberhorn-scheduling-axi" else if transportOnly then "lauberhorn-scheduling-transport" else if cacheContractsOnly then "lauberhorn-scheduling-cache-contracts" else if killOnly then "lauberhorn-scheduling-kill-exit" else if composedInvariants then "lauberhorn-scheduling-composed-invariants" else if composedOnly then "lauberhorn-scheduling-composed" else if racesOnly then "lauberhorn-scheduling-races" else "lauberhorn-scheduling-progress")
  {
    nativeBuildInputs = [ python3 jre_headless tlc ];
    TLA2TOOLS_JAR = "${tlc}/share/java/tla2tools.jar";
  }
  ''
    cp -r ${source}/. .
    chmod -R u+w .
    python3 tools/scheduling/addresses.py --check
    python3 tools/scheduling/check_models.py --output results --timeout ${if fullOnly && builtins.elem "full-stalled-progress" names then "21600" else if fullOnly then "7200" else if composedOnly then "7200" else if composedInvariants then "1200" else "900"} --heap-mib ${if fullOnly || composedOnly then "4096" else "2048"} --workers 4 ${if names != [] then lib.concatMapStringsSep " " (name: "--case ${lib.escapeShellArg name}") names else lib.optionalString fullOnly "--full-only"} ${lib.optionalString axiOnly "--axi-only"} ${lib.optionalString transportOnly "--transport-only"} ${lib.optionalString cacheContractsOnly "--cache-contracts-only"} ${lib.optionalString killOnly "--kill-only"} ${lib.optionalString killTransportOnly "--kill-transport-only"} ${lib.optionalString racesOnly "--races-only"} ${lib.optionalString composedOnly "--composed-only"} ${lib.optionalString composedInvariants "--composed-invariants"}
    mkdir -p "$out"
    ${lib.optionalString (cacheContractsOnly || fullOnly) ''
      cp results/summary.json "$out/detailed-summary.json"
      for case_dir in results/*/; do
        mkdir -p "$out/traces/$(basename "$case_dir")"
        cp "$case_dir"/*.cfg "$case_dir"/TLC.log "$out/traces/$(basename "$case_dir")/"
      done
    ''}
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
  '';
  fullCases = (builtins.fromJSON (builtins.readFile ../../model/config/scheduling/suite.json)).groups.full;
  outputs = map (name: run [ name ]) fullCases;
in
if !fullOnly then run [] else
runCommand "lauberhorn-scheduling-full" { nativeBuildInputs = [ python3 ]; } ''
  mkdir -p "$out/traces"
  python3 - <<'PYTHON'
  import json
  import os
  import shutil
  from pathlib import Path
  roots = ${builtins.toJSON (map (path: toString path) outputs)}
  merged = None
  for root in roots:
      root = Path(root)
      result = json.loads((root / "detailed-summary.json").read_text())
      if merged is None:
          merged = {k: v for k, v in result.items() if k != "cases"}
          merged["cases"] = {}
      assert {k: v for k, v in result.items() if k != "cases"} == {k: v for k, v in merged.items() if k != "cases"}
      assert not (result["cases"].keys() & merged["cases"].keys())
      assert all(case["passed"] for case in result["cases"].values())
      merged["cases"].update(result["cases"])
      shutil.copytree(root / "traces", Path(os.environ["out"]) / "traces", dirs_exist_ok=True)
  assert set(merged["cases"]) == set(${builtins.toJSON fullCases})
  out = Path(os.environ["out"])
  (out / "detailed-summary.json").write_text(json.dumps(merged, indent=2, sort_keys=True) + "\n")
  for case in merged["cases"].values():
      for field in ("elapsed_seconds", "command", "log_sha256", "statistics"):
          case.pop(field)
  (out / "summary.json").write_text(json.dumps(merged, indent=2, sort_keys=True) + "\n")
  PYTHON
''
