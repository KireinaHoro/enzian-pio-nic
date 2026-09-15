#!/usr/bin/env bash
# Publish a fresh matching artifact set at the established CI paths.
set -euo pipefail
: "${CI_COMMIT_SHA:?set CI_COMMIT_SHA to the expected full source revision}"
[[ $CI_COMMIT_SHA =~ ^[0-9a-f]{40}$ ]] || { echo 'Expected a full source revision' >&2; exit 2; }
for path in out/ci/eci-inputs out/ci/deploy out/deploy.img out/eci/vivado-inputs out/eci/generateVerilog.dest; do
  [[ ! -e $path && ! -L $path ]] || {
    echo "$path already exists; use a fresh workspace or explicitly remove the previous artifacts" >&2
    exit 1
  }
done
ci-build eci-inputs .#eciVivadoInputs
ci-build deploy .#deployFs
[[ $(cat out/ci/eci-inputs/output/git-revision) == "$CI_COMMIT_SHA" ]] || {
  echo 'Bundle/source revision mismatch; artifacts remain under out/ci for inspection' >&2
  exit 1
}
mkdir -p out/eci
mv -T out/ci/deploy/output out/deploy.img
mv -T out/ci/eci-inputs/output out/eci/vivado-inputs
cp -r out/eci/vivado-inputs/generated out/eci/generateVerilog.dest
