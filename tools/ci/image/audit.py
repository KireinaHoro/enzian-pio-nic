#!/usr/bin/env python3
"""Check a CI derivation graph against the realized image warmup outputs.

Input is `nix derivation show --recursive` JSON for both warmup targets and all
platform CI targets. No project builds are run. Cache coverage includes runtime closures and inputs guaranteed to be realized
by non-substitutable warmup builders. Merely exporting a recipe is insufficient.
"""
import argparse
import json
import subprocess

WARMUP = {"nix-shell", "lauberhorn-ci-dependencies"}
JOB_GLUE = {"ci-build", "prepare-eci", "summarize-physical", "lh-test", "closure-info"}


def is_project(name):
    return name.startswith("lauberhorn-") or name in JOB_GLUE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("graph", help="recursive derivation JSON, including warmup and job targets")
    args = parser.parse_args()
    with open(args.graph) as source:
        graph = json.load(source)
    warm = {d["name"]: d for d in graph.values() if d["name"] in WARMUP}
    if set(warm) != WARMUP:
        parser.error("graph must include ciEnvironment and ciDependencies")
    roots = {o["path"] for d in warm.values() for o in d["outputs"].values()}
    # The image preserves build inputs. A local/non-substitutable derivation
    # always realizes its inputs, even when other dependencies are substituted.
    # Do not assume the build dependencies of a substitutable package exist.
    pending = list(warm.values())
    visited = set()
    while pending:
        d = pending.pop()
        key = tuple(o["path"] for o in d["outputs"].values())
        if key in visited:
            continue
        visited.add(key)
        attrs = d.get("structuredAttrs") or {}
        substitutes = attrs.get("allowSubstitutes", d["env"].get("allowSubstitutes", "1"))
        if substitutes not in (False, "", "0"):
            continue
        for drv, selection in d["inputDrvs"].items():
            dependency = graph[drv]
            outputs = selection["outputs"] if isinstance(selection, dict) else selection
            roots.update(dependency["outputs"][output]["path"] for output in outputs)
            pending.append(dependency)
    present = set(subprocess.check_output(
        ["nix-store", "--query", "--requisites", *roots], text=True).splitlines())
    required = {}
    for d in graph.values():
        if not is_project(d["name"]) or d["name"] in WARMUP:
            continue
        for drv, selection in d["inputDrvs"].items():
            dependency = graph[drv]
            if is_project(dependency["name"]):
                continue
            # Nix >= 2.34 includes dynamic-output metadata; older Nix uses a list.
            outputs = selection["outputs"] if isinstance(selection, dict) else selection
            for output in outputs:
                path = dependency["outputs"][output]["path"]
                required.setdefault(path, set()).add(d["name"])
    missing = sorted(set(required) - present)
    for path in missing:
        print(f"MISSING {path} (used by {', '.join(sorted(required[path]))})")
    print(f"{len(required) - len(missing)}/{len(required)} external dependency outputs cached")
    raise SystemExit(bool(missing))


if __name__ == "__main__":
    main()
