set -eu
base=/scratch/pengxu/nullrpc-inline
export PATH=/nix/store/lauberhorn-client/bin:/nix/store/lauberhorn-client/sbin
unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME
export LH_BENCH_READY_FILE="$base/cold-off-client-ready" LH_BENCH_START_FILE="$base/cold-off-go"
set +e
lh-dpdk-run --logs "$base/cold-off-client" --lcore 6 --no-iommu /nix/store/lauberhorn-client/bin/bench_dpdk -- 192.168.128.225 192.168.129.200 0c:53:31:03:01:c8 12345 1000 0 100000 add "$base/cold-off.csv" flush:1:4
rc=$?
printf '%s\n' "$rc" > "$base/cold-off-done"
exit "$rc"
