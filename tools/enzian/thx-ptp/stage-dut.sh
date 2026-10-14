#!/usr/bin/env bash
# Reserved zuestoll14 only; the original data VF must be down and address-free.
set -euo pipefail
base=/dev/shm/lauberhorn-thx-ptp-20261014
bdf=0002:01:00.2
dev=/sys/bus/pci/devices/$bdf
mkdir "$base"
[[ $(uname -r) == 6.8.0-64-generic ]]
[[ $(basename "$(readlink -f "$dev/driver")") == nicvf ]]
ip route get 192.168.255.254 | grep -q 'dev cpu40g0'
[[ $(cat /sys/class/net/cpu40g1/operstate) == down ]]
[[ -z $(ip -o addr show dev cpu40g1) ]]
ip -j addr show dev cpu40g1 > "$base/interface-before.json"
ip -j route > "$base/routes-before.json"
dmesg > "$base/dmesg-before.txt"
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > "$base/tad-before.txt"
for file in lh_nicvf.ko packets.py SHA256SUMS; do
 curl -fsS "http://192.168.255.254:18844/$file" -o "$base/$file"
done
(cd "$base"; sha256sum --check SHA256SUMS > hashes-checked.txt)
insmod "$base/lh_nicvf.ko" test_bdf="$bdf"
echo lh_nicvf > "$dev/driver_override"
echo "$bdf" > "$dev/driver/unbind"
echo "$bdf" > /sys/bus/pci/drivers_probe
[[ $(basename "$(readlink -f "$dev/driver")") == lh_nicvf ]]
udevadm settle
iface=$(basename "$dev"/net/*)
printf '%s\n' "$iface" > "$base/interface"
ip link set "$iface" up
ethtool -T "$iface" > "$base/timestamps.txt"
cat /sys/class/ptp/ptp*/clock_name > "$base/clocks.txt"
echo STAGE_REPORT_BEGIN
{ cat "$base/interface" "$base/timestamps.txt" "$base/clocks.txt"; } | base64 -w0
echo
echo STAGE_REPORT_END
