#!/bin/bash
set -euo pipefail
role=$1
label=$2
count=$3
warmup=$4
profile=${5:-1}
base=/dev/shm/dpdk-breakdown-20261014
mkdir -p "$base"
if [[ $role == server ]]; then
 image=/dev/shm/dpdk-breakdown-20261014.img
else
 image=/dev/shm/dpdk-breakdown-20261014.img
fi
mount --make-rprivate /
mkdir -p /nix/store
mount -o loop,ro "$image" /nix/store
cp /nix/store/lauberhorn-client/manifest.json "$base/$label-$role-manifest.json"
if [[ $role == server ]]; then
 export LH_NULLRPC_READY_FILE="$base/$label-ready"
 if [[ $profile == 1 ]]; then export LH_NULLRPC_PROFILE=1; fi
 /nix/store/lauberhorn-client/bin/lh-dpdk-run --logs "$base/$label-server" --lcore 45 --no-iommu --ptp-bgx 4 --manifest /nix/store/lauberhorn-client/manifest.json /nix/store/lauberhorn-client/bin/nullrpc_dpdk -- 192.168.129.193 192.168.128.225 0c:53:31:03:00:e1 49152 "$count" "$warmup" 2000000 add "$base/$label-server.csv"
else
 /nix/store/lauberhorn-client/bin/lh-dpdk-run --logs "$base/$label-client" --lcore 6 --no-iommu /nix/store/lauberhorn-client/bin/bench_dpdk -- 192.168.128.225 192.168.129.193 0c:53:31:03:01:c1 12345 "$count" "$warmup" 2000000 add "$base/$label-client.csv"
fi
