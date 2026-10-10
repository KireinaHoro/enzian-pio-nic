set -eu
label=$1
case "$label" in *[!a-zA-Z0-9_-]*|'') exit 2;; esac
base=/scratch/pengxu/nullrpc-contiguous
export PATH=/nix/store/lauberhorn/bin:/nix/store/lauberhorn/sbin:$PATH
for f in /sys/kernel/tracing/events/lauberhorn/activation/enable /sys/kernel/tracing/instances/*/events/lauberhorn/activation/enable; do
 test ! -f "$f" || test "$(cat "$f")" = 0
done
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > "$base/$label-tad-before"
dmesg --raw > "$base/$label-kernel-before"
touch "$base/$label-ready"
finished=0
for i in $(seq 1 3000); do
 if test -f "$base/$label-done"; then finished=1;break;fi
 sleep .2
done
test "$finished" = 1
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > "$base/$label-tad-after"
dmesg --raw > "$base/$label-kernel-after"
python3 /scratch/pengxu/nullrpc-contiguous/assess_tad.py --before "$base/$label-tad-before" --after "$base/$label-tad-after" --kernel-before "$base/$label-kernel-before" --kernel-after "$base/$label-kernel-after" --output "$base/$label-assessment.json"
