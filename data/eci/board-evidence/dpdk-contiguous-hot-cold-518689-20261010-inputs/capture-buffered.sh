set -eu
label=$1
case "$label" in *[!a-zA-Z0-9_-]*|'') exit 2;; esac
base=/scratch/pengxu/nullrpc-contiguous
export PATH=/nix/store/lauberhorn/bin:/nix/store/lauberhorn/sbin:$PATH
measured=$(cat /scratch/pengxu/nullrpc-contiguous/server/server.pid)
flush=$(cat /scratch/pengxu/nullrpc-contiguous/flush/server.pid)
trap 'kill -CONT "$measured" "$flush"' EXIT
kill -STOP "$measured" "$flush"
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > "$base/$label-tad-before"
dmesg --raw > "$base/$label-kernel-before"
export LH_PHASE=$label LH_BASE=$base LH_MEASURED=$measured LH_FLUSH=$flush
/usr/bin/python3 /scratch/pengxu/nullrpc-contiguous/activation_trace.py --ipg-cpus 44,45,46,47 --buffer-kb 16384 --output "$base/$label-kernel" -- bash -c '
kill -CONT "$LH_MEASURED" "$LH_FLUSH"
touch "$LH_BASE/$LH_PHASE-ready"
for i in $(seq 1 3000); do
 if test -f "$LH_BASE/$LH_PHASE-done"; then
  kill -STOP "$LH_MEASURED" "$LH_FLUSH"
  exit 0
 fi
 sleep .2
done
kill -STOP "$LH_MEASURED" "$LH_FLUSH"
exit 1'
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > "$base/$label-tad-after"
dmesg --raw > "$base/$label-kernel-after"
python3 /scratch/pengxu/nullrpc-contiguous/assess_tad.py --before "$base/$label-tad-before" --after "$base/$label-tad-after" --kernel-before "$base/$label-kernel-before" --kernel-after "$base/$label-kernel-after" --output "$base/$label-assessment.json"
