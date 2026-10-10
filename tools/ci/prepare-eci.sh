#!/usr/bin/env bash
# Publish a fresh matching artifact set at the established CI paths.
set -euo pipefail
: "${CI_COMMIT_SHA:?set CI_COMMIT_SHA to the expected full source revision}"
[[ $CI_COMMIT_SHA =~ ^[0-9a-f]{40}$ ]] || { echo 'Expected a full source revision' >&2; exit 2; }
skip_memcached=${LAUBERHORN_SKIP_MEMCACHED:-0}
[[ $skip_memcached == 0 || $skip_memcached == 1 ]] || { echo 'LAUBERHORN_SKIP_MEMCACHED must be 0 or 1' >&2; exit 2; }
for path in out/ci-scope.json out/ci/eci-inputs out/ci/deploy out/ci/memcached-deploy out/deploy.img out/memcached-deploy.img out/eci/vivado-inputs out/eci/generateVerilog.dest; do
  [[ ! -e $path && ! -L $path ]] || {
    echo "$path already exists; use a fresh workspace or explicitly remove the previous artifacts" >&2
    exit 1
  }
done
ci-build eci-inputs .#eciVivadoInputs
ci-build deploy .#deployFs
if [[ $skip_memcached == 0 ]]; then
  ci-build memcached-deploy .#memcachedDeployFs
fi
[[ $(cat out/ci/eci-inputs/output/git-revision) == "$CI_COMMIT_SHA" ]] || {
  echo 'Bundle/source revision mismatch; artifacts remain under out/ci for inspection' >&2
  exit 1
}
mkdir -p out/eci
mv -T out/ci/deploy/output out/deploy.img
if [[ $skip_memcached == 0 ]]; then
  mv -T out/ci/memcached-deploy/output out/memcached-deploy.img
fi
printf '{"revision":"%s","memcached_omitted":%s}\n' "$CI_COMMIT_SHA" "$([[ $skip_memcached == 1 ]] && echo true || echo false)" > out/ci-scope.json
mv -T out/ci/eci-inputs/output out/eci/vivado-inputs
cp -r out/eci/vivado-inputs/generated out/eci/generateVerilog.dest
