#!/usr/bin/env bash
set -euo pipefail
inputs=/opt/lauberhorn-inputs
test "$(cat "$inputs/revision")" = "$LAUBERHORN_REV"
# This local cache was produced by the preceding job in this same pipeline.
nix copy --all --no-check-sigs --from "file://$inputs/cache"
mkdir -p /nix/var/nix/gcroots/lauberhorn /opt/lauberhorn
# The plan contains only ciEnvironment, never project test/build targets.
index=0
while IFS= read -r drv; do
  [[ "$drv" == /nix/store/*.drv ]]
  nix build --print-build-logs --out-link "/nix/var/nix/gcroots/lauberhorn/result-$index" "$drv^*"
  ln -s "$drv" "/nix/var/nix/gcroots/lauberhorn/derivation-$index"
  index=$((index + 1))
done < "$inputs/derivations"
cp "$inputs/revision" "$inputs/derivations" /opt/lauberhorn/
# Keep the complete store and its database, including build-time dependencies.
# No garbage collection: later platform revisions can reuse these dependencies.
rm -rf "$inputs"
