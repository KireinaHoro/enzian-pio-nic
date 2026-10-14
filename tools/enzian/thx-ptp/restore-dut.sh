#!/usr/bin/env bash
# Restore only this campaign's data VF; never unload management nicvf.
set -euo pipefail
base=/dev/shm/lauberhorn-thx-ptp-20261014
bdf=0002:01:00.2
dev=/sys/bus/pci/devices/$bdf
[[ $(basename "$(readlink -f "$dev/driver")") == lh_nicvf ]]
iface=$(basename "$dev"/net/*)
dmesg > "$base/dmesg-after.txt"
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > "$base/tad-after.txt"
ip link set "$iface" down
ip addr flush dev "$iface"
echo "$bdf" > "$dev/driver/unbind"
echo nicvf > "$dev/driver_override"
echo "$bdf" > /sys/bus/pci/drivers_probe
echo > "$dev/driver_override"
udevadm settle
iface=$(basename "$dev"/net/*)
ip link set "$iface" down
ip addr flush dev "$iface"
rmmod lh_nicvf
[[ $(basename "$(readlink -f "$dev/driver")") == nicvf ]]
ip route get 192.168.255.254 | grep -q 'dev cpu40g0'
ip -j addr show dev "$iface" > "$base/interface-after.json"
ip -j route > "$base/routes-after.json"
ethtool -T "$iface" > "$base/timestamps-restored.txt"
echo RESTORED
