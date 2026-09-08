#!/usr/bin/env bash
# Independent CPU-side steps. Run as root; mount the closure at /nix/store.
set -euo pipefail
[[ $EUID == 0 ]] || { echo 'Run as root' >&2; exit 1; }
shopt -s nullglob
one() {
    [[ $# == 1 ]] || { echo 'Expected exactly one matching store path' >&2; return 1; }
    printf '%s\n' "$1"
}
case ${1:-} in
mount)
    [[ $# == 2 ]] || exit 2
    image=$2
    if mountpoint -q /nix/store; then
    echo '/nix/store already mounted; inspect and unmount explicitly before this script' >&2
    exit 1
    fi
    mkdir -p /nix/store
    mount -t squashfs -o loop,ro "$image" /nix/store
;;
load)
    [[ $# == 1 ]] || exit 2
    module=$(one /nix/store/*-lauberhorn-kmod/lauberhorn.ko)
    [[ $(modinfo -F vermagic "$module") == "$(uname -r) "* ]] || {
        echo 'Module/kernel ABI mismatch' >&2; exit 1;
    }
    [[ ! -d /sys/module/lauberhorn ]] || { echo 'Module already loaded' >&2; exit 1; }
    insmod "$module"
;;
verify)
    [[ $# == 1 ]] || exit 2
    marker=$(one /nix/store/*-git-hash)
    revision=$(cat "$marker")
    [[ $revision =~ ^[0-9a-f]{40}$ ]] || exit 1
    expected_hw=$(printf '%08x' "0x${revision:0:16}")
    actual_hw=$(dmesg | awk '/Lauberhorn NIC version:/ {v=$NF} END {print v}')
    [[ $actual_hw == "$expected_hw" ]] || { echo "Hardware revision mismatch: $actual_hw != $expected_hw" >&2; exit 1; }
    [[ -c /dev/lauberhorn && -d /sys/class/net/lauberhorn0 ]]
;;
configure)
    [[ $# == 3 ]] || exit 2
    mac=$2
    cidr=$3
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
;;
serve)
    [[ $# == 4 ]] || exit 2
    operation=$2
    workers=$3
    logs=$4
    [[ $operation == add || $operation == mul ]]
    [[ $workers =~ ^[1-9][0-9]*$ ]]
    mkdir "$logs"
    app=$(one /nix/store/*-lauberhorn-app-microbenchmarks/microbenchmarks)
    systemctl start rpcbind
    rpcinfo -p localhost
    # Keep the service running for a separate, externally routed correctness client.
    # Timeout/kill recovery is not certified; do not automatically kill RPC workers.
    nohup "$app" "$operation" "$workers" "$logs/server.csv" > "$logs/server.log" 2>&1 < /dev/null &
    server_pid=$!
    echo "$server_pid" > "$logs/server.pid"
    sleep 2
    kill -0 "$server_pid"
    cat "$logs/server.log"
    rpcinfo -p localhost
    printf 'SERVER_STARTED pid=%s; run the external client to establish RPC correctness\n' "$server_pid"
;;
*)
    echo "usage: $0 {mount IMAGE|load|verify|configure MAC CIDR|serve add/mul WORKERS NEW_LOG_DIR}" >&2
    exit 2
;;
esac
