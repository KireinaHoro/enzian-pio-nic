#!/usr/bin/env bash
# Pure derivation build with logs and failure evidence outside the Nix store.
set -euo pipefail
[[ $# == 2 && $1 =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] || {
  echo 'usage: ci-build NAME INSTALLABLE (NAME must be a single safe directory name)' >&2
  exit 2
}
name=$1
installable=$2
mkdir -p out/ci
mkdir "out/ci/$name" || {
  echo "out/ci/$name already exists; choose a new name or explicitly remove the previous run" >&2
  exit 1
}
if nix build --no-update-lock-file --keep-failed --print-build-logs \
    --out-link "out/ci/$name/result" "$installable" 2>&1 | tee "out/ci/$name/build.log"; then
  cp -rL "out/ci/$name/result" "out/ci/$name/output"
  rm "out/ci/$name/result"
else
  # --keep-failed retains the sandbox on the container's filesystem. Copy only
  # useful diagnostics, not compiler products or the dependency tree.
  # Nix versions differ: recent builders retain /nix/var/nix/builds/nix-*,
  # older builders use /tmp/nix-build-*. Follow this invocation's reported
  # directories rather than guessing a location or collecting another build.
  while IFS= read -r failed; do
    [[ -d "$failed" ]] || continue
    while IFS= read -r -d '' file; do
      dest="out/ci/$name/failed/${file#"$failed"/}"
      mkdir -p "$(dirname "$dest")"
      cp "$file" "$dest"
    done < <(find "$failed" -type f \( -name test-report.xml -o -name sim_transcript.log.gz -o -name wave.fst -o -name 'simulation-*.txt' -o -name simulation-inputs.sha256 -o -name lauberhorn_trace.pcapng \) -print0)
  done < <(sed -nE "s/^.*keeping build directory [\"']([^\"']+)[\"'].*/\1/p" "out/ci/$name/build.log" | sort -u)
  exit 1
fi
