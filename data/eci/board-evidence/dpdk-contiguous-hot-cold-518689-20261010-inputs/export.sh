set -eu
export PATH=/nix/store/lauberhorn/bin:/nix/store/lauberhorn/sbin:$PATH
b=/scratch/pengxu/nullrpc-contiguous
for label in hot-on2 cold-on hot-off cold-off; do
 test "$(cat /scratch/pengxu/nullrpc-contiguous/$label-done)" = 0
done
test ! -e "$b/server/server.csv"
kill -USR2 "$(cat "$b/server/server.pid")"
for i in $(seq 1 100); do
 test ! -f "$b/server/server.csv.status.json" || break
 sleep .1
done
python3 - "$b/server/server.csv.status.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]));assert r['loss_free'] and r['dropped']==0 and r['rows']==4032,r
print('RUNTIME_EXPORT_VERIFIED',json.dumps(r))
PY
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > "$b/final-tad"
dmesg --raw > "$b/final-kernel"
python3 "$b/assess_tad.py" --before "$b/hot-on2-tad-before" --after "$b/final-tad" --kernel-before "$b/hot-on2-kernel-before" --kernel-after "$b/final-kernel" --output "$b/final-health.json"
