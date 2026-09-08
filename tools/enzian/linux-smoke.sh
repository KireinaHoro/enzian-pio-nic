#!/usr/bin/env bash
# Run as root on the Enzian CPU after programming and booting Linux.
# Arguments: image image-sha256 revision nic-mac nic-ip/prefix log-dir
set -euo pipefail
if [[ $# != 6 ]]; then
    echo "usage: $0 image sha256 revision mac ip/prefix log-dir" >&2
    exit 2
fi
image=$1
image_sha=$2
revision=$3
mac=$4
cidr=$5
logs=$6
[[ $revision =~ ^[0-9a-f]{40}$ && $image_sha =~ ^[0-9a-f]{64}$ ]] || exit 2
[[ $EUID == 0 ]] || { echo 'Run as root' >&2; exit 1; }
[[ $(uname -m) == aarch64 ]] || { echo 'Expected aarch64 CPU' >&2; exit 1; }
[[ $(uname -r) == 6.8.0-64-generic ]] || { echo 'Unexpected kernel ABI' >&2; exit 1; }
[[ $(cat /sys/devices/system/cpu/isolated) == 44-47 ]] || { echo 'Expected isolated CPUs 44-47' >&2; exit 1; }
[[ $(sha256sum "$image" | cut -d ' ' -f 1) == "$image_sha" ]] || { echo 'Image checksum mismatch' >&2; exit 1; }
mkdir "$logs"
exec > >(tee "$logs/setup.log") 2>&1
uname -a
cat /proc/cmdline
if mountpoint -q /nix/store; then
    echo '/nix/store already mounted; inspect and unmount explicitly before this script' >&2
    exit 1
fi
mkdir -p /nix/store
mount -t squashfs -o loop,ro "$image" /nix/store
shopt -s nullglob
markers=(/nix/store/*-git-hash)
modules=(/nix/store/*-lauberhorn-kmod/lauberhorn.ko)
apps=(/nix/store/*-lauberhorn-app-microbenchmarks/microbenchmarks)
[[ ${#markers[@]} == 1 && ${#modules[@]} == 1 && ${#apps[@]} == 1 ]]
[[ $(cat "${markers[0]}") == "$revision" ]] || { echo 'Image revision mismatch' >&2; exit 1; }
[[ $(modinfo -F vermagic "${modules[0]}") == "$(uname -r) "* ]]
if [[ -d /sys/module/lauberhorn ]]; then
    echo 'Lauberhorn already loaded; inspect existing state first' >&2
    exit 1
fi
insmod "${modules[0]}"
dmesg | tail -80
expected_hw=$(printf '%08x' "0x${revision:0:16}")
actual_hw=$(dmesg | awk '/Lauberhorn NIC version:/ {v=$NF} END {print v}')
[[ $actual_hw == "$expected_hw" ]] || { echo "Hardware revision mismatch: $actual_hw != $expected_hw" >&2; exit 1; }
[[ -c /dev/lauberhorn && -d /sys/class/net/lauberhorn0 ]]
ip link set lauberhorn0 address "$mac"
ip link set lauberhorn0 mtu 1500
ip addr add "$cidr" dev lauberhorn0
# CMAC initialization may need another attempt while the link settles.
for attempt in 1 2 3; do
    if ip link set lauberhorn0 up; then break; fi
    [[ $attempt != 3 ]] || exit 1
    sleep 2
done
ip -br addr show dev lauberhorn0
systemctl start rpcbind
rpcinfo -p localhost
# Keep the service running for a separate, externally routed correctness client.
# Timeout/kill recovery is not certified; do not automatically kill RPC workers.
nohup "${apps[0]}" add 4 "$logs/server.csv" > "$logs/server.log" 2>&1 < /dev/null &
server_pid=$!
echo "$server_pid" > "$logs/server.pid"
sleep 2
kill -0 "$server_pid"
cat "$logs/server.log"
rpcinfo -p localhost
printf 'SERVER_STARTED pid=%s; run the external client to establish RPC correctness\n' "$server_pid"
