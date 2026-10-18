#!/usr/bin/env bash
# LABEL SIZE COUNT WARMUP WINDOW GAP_NS TRANSFER_URL [GENERATOR_CPUS] [IMAGE]
# Peer image path is campaign-specific; keep it matched to the recorded manifest.
set -euo pipefail
label=$1 size=$2 count=$3 warmup=$4 window=$5 gap=$6 transfer=$7
cores=${8:-6}
image=${9:-/dev/shm/loopback-stage-20261014b/loopback-v2.img}
[[ $label =~ ^[A-Za-z0-9_-]+$ ]]
# The caller creates a private namespace; discard any inherited image mount.
mount --make-rprivate /
if mountpoint -q /nix/store; then umount /nix/store; fi
mount -o loop,ro "$image" /nix/store
export PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin
unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME
export LH_BENCH_WINDOW=$window LH_BENCH_GAP_NS=$gap LH_BENCH_FLOWS=1
extra=()
if [[ $cores == *,* ]]; then
  [[ $cores == 6,* ]]
  extra=(--worker-lcores "${cores#*,}")
  export LH_BENCH_FLOWS=64
fi
out=/dev/shm/$label
mkdir "$out"
cp /nix/store/lauberhorn-client/manifest.json "$out/manifest.json"
set +e
lh-dpdk-run --logs "$out/runner" --lcore 6 "${extra[@]}" --no-iommu /nix/store/lauberhorn-client/bin/bench_dpdk -- \
  192.168.128.225 192.168.129.200 0c:53:31:03:01:c8 12345 "$count" "$warmup" 2000000 "loopback$size" "$out/samples.csv" > "$out/runner.log" 2>&1
rc=$?
set -e
printf '%s\n' "$rc" > "$out/exit-code"
python3 - "$out" "$transfer" <<'PY'
import io,sys,tarfile,urllib.request
from pathlib import Path
root=Path(sys.argv[1]);data=io.BytesIO()
with tarfile.open(fileobj=data,mode='w:gz') as archive:archive.add(root,arcname=root.name)
r=urllib.request.Request(sys.argv[2]+'/'+root.name+'.tar.gz',data=data.getvalue(),method='PUT')
urllib.request.urlopen(r,timeout=120).read()
PY
exit "$rc"
