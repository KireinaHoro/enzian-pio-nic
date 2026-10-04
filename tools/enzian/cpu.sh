#!/usr/bin/env bash
# Independent CPU-side steps. Run as root; mount the closure at /nix/store.
set -euo pipefail
[[ $EUID == 0 ]] || { echo 'Run as root' >&2; exit 1; }
LH=/nix/store/lauberhorn
export PATH="$LH/bin:$LH/sbin:$PATH"
case ${1:-} in
mount)
    [[ $# == 2 ]] || exit 2
    image=$2
    # The golden image marks this NFS mount nofail; boot can finish before
    # it succeeds. Use its existing fstab entry before allocating a loop.
    if [[ $image == /scratch/* ]] && ! mountpoint -q /scratch; then
        mount /scratch
    fi
    [[ -r $image ]] || { echo "Deployment image is not readable: $image" >&2; exit 1; }
    if mountpoint -q /nix/store; then
    echo '/nix/store already mounted; inspect and unmount explicitly before this script' >&2
    exit 1
    fi
    mkdir -p /nix/store
    mount -t squashfs -o loop,ro "$image" /nix/store
;;
load)
    [[ $# == 1 ]] || exit 2
    # Workers use SCHED_FIFO and may spend most of a second polling ECI.
    # The default 950 ms RT budget introduces a 50 ms stall each second.
    # Apply on every test boot; the matched deployment image stays unchanged.
    previous_rt_runtime=$(cat /proc/sys/kernel/sched_rt_runtime_us)
    printf '%s\n' -1 > /proc/sys/kernel/sched_rt_runtime_us
    test "$(cat /proc/sys/kernel/sched_rt_runtime_us)" = -1
    printf 'RT_BANDWIDTH previous_runtime_us=%s runtime_us=-1 period_us=%s\n' \
        "$previous_rt_runtime" "$(cat /proc/sys/kernel/sched_rt_period_us)"
    "$LH/bin/lh-test" load
;;
verify)
    [[ $# == 1 ]] || exit 2
    revision=$(jq -er .platform.revision "$LH/manifest.json")
    [[ $revision =~ ^[0-9a-f]{40}$ ]] || exit 1
    expected_hw=$(printf '%08x' "0x${revision:0:16}")
    actual_hw=$(dmesg | awk '/Lauberhorn NIC version:/ {v=$NF} END {print v}')
    [[ $actual_hw == "$expected_hw" ]] || { echo "Hardware revision mismatch: $actual_hw != $expected_hw" >&2; exit 1; }
    [[ -c /dev/lauberhorn && -d /sys/class/net/lauberhorn0 ]]
;;
configure)
    [[ $# == 3 ]] || exit 2
    "$LH/bin/lh-test" configure "$2" "$3"
;;
serve)
    [[ $# == 4 ]] || exit 2
    operation=$2
    workers=$3
    logs=$4
    [[ $operation == add || $operation == mul ]]
    [[ $workers =~ ^[1-9][0-9]*$ ]]
    mkdir "$logs"
    app="$LH/apps/microbenchmarks/bin/microbenchmarks"
    systemctl start rpcbind
    rpcinfo -p localhost
    # Keep the service running for a separate, externally routed correctness client.
    # Timeout/kill recovery is not certified; do not automatically kill RPC workers.
    launcher=("$LH/bin/lh-test" run microbenchmarks --)
    [[ ${LH_DIRECT:-0} != 1 ]] || launcher=("$app")
    nohup "${launcher[@]}" "$operation" "$workers" "$logs/server.csv" > "$logs/server.log" 2>&1 < /dev/null &
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
