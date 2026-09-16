#!/usr/bin/env bash
# Evaluate the pinned platform once; export sources + derivations without building.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
: "${LAUBERHORN_REV:?Set LAUBERHORN_REV to the full platform commit}"
[[ "$LAUBERHORN_REV" =~ ^[0-9a-f]{40}$ ]]
flake="${LAUBERHORN_FLAKE:-git+https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform.git?rev=$LAUBERHORN_REV&submodules=1}"
context="${1:-lauberhorn-context}"
# Refuse stale plans or unrelated files in the Docker context.
mkdir "$context"
context=$(realpath "$context")
output="$context/lauberhorn-inputs"
mkdir "$output"
targets=("$flake#packages.x86_64-linux.ciEnvironment")
nix path-info --no-update-lock-file --derivation "${targets[@]}" > "$output/derivations"
nix copy --no-update-lock-file --derivation --to "file://$output/cache?compression=zstd&parallel-compression=true" "${targets[@]}"
printf '%s\n' "$LAUBERHORN_REV" > "$output/revision"
cp "$script_dir/Dockerfile" "$context/Dockerfile"
cp "$script_dir/prewarm.sh" "$context/prewarm.sh"
