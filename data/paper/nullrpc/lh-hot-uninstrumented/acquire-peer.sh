#!/usr/bin/env bash
# Run through tools/enzian/run.py on the reserved peer. Writes only fresh RAM paths.
# Arguments: RUN_LABEL COUNT WARMUP WINDOW GAP_NS MODE TRANSFER_URL
# MODE is hot or flush:1 (per-request eviction, single outstanding request).
set -euo pipefail
label=$1 count=$2 warmup=$3 window=$4 gap=$5 mode=$6 transfer=$7
[[ $label =~ ^[A-Za-z0-9_-]+$ ]]
mount --make-rprivate /
mount -o loop,ro /dev/shm/dpdk-breakdown-20261014.img /nix/store
base=/dev/shm/paper-hot-uninstrumented-20261014
mkdir "$base"
cp /nix/store/lauberhorn-client/bin/bench_dpdk "$base/bench_dpdk"
cp /nix/store/lauberhorn-client/manifest.json "$base/client-manifest.json"
sha256sum "$base/bench_dpdk" /dev/shm/dpdk-breakdown-20261014.img > "$base/client-hashes.txt"
out=$base/$label
mkdir "$out"
cp "$base/client-manifest.json" "$base/client-hashes.txt" "$out/"
export PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin
unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME
export LH_BENCH_WINDOW="$window" LH_BENCH_GAP_NS="$gap"
args=()
[[ $mode == hot ]] || args+=("$mode")
set +e
lh-dpdk-run --logs "$out/runner" --lcore 6 --no-iommu "$base/bench_dpdk" -- \
  192.168.128.225 192.168.129.200 0c:53:31:03:01:c8 12345 \
  "$count" "$warmup" 100000 add "$out/client.csv" "${args[@]}" \
  > "$out/runner.log" 2>&1
rc=$?
set -e
printf '%s\n' "$rc" > "$out/exit-code"
python3 - "$out" "$transfer" "$label" <<'PY'
import io,sys,tarfile,urllib.request
from pathlib import Path
root=Path(sys.argv[1]); data=io.BytesIO()
with tarfile.open(fileobj=data, mode='w:gz') as archive:
    archive.add(root,arcname=root.name)
request=urllib.request.Request(sys.argv[2]+'/'+sys.argv[3]+'.tar.gz',data=data.getvalue(),method='PUT')
urllib.request.urlopen(request,timeout=120).read()
PY
exit "$rc"
