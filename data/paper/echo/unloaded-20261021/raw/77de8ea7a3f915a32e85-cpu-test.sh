set -eu
cpu() (
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

)
cpu mount /var/tmp/lauberhorn-kernel146/deploy.img
test "$(/nix/store/lauberhorn/bin/jq -er .platform.revision /nix/store/lauberhorn/manifest.json)" = 0e58f0582b6f0039ceeff2c4714ceb4451cca490
/nix/store/lauberhorn/bin/lh-test info --json
echo TEST_STAGE:mount
dmesg -n 8
cpu load
echo TEST_STAGE:load
cpu verify
echo TEST_STAGE:verify
set -eu
ip link set cpu40g1 down
cpu configure 0c:53:31:03:00:e8 192.168.128.232/18
ip neigh replace 192.168.129.193 lladdr 0c:53:31:03:01:c1 nud permanent dev lauberhorn0
mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
printf '0\n' > /sys/kernel/tracing/tracing_on
systemctl start rpcbind
python3 - <<'ECHOSTART'
import hashlib,os,urllib.request
from pathlib import Path
def sha(path):
 value=hashlib.sha256()
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
 return value.hexdigest()
image=Path('/dev/shm/echo-shared-54b71b54c959.img')
if image.exists():
 if sha(image)!='54b71b54c959851db39a3210f2384d4eba01833f1564a910c2d11b9ede30561c':raise ValueError('Existing image differs; retain it for diagnosis')
else:
 temporary=image.with_name(image.name+'.partial-'+str(os.getpid()))
 with urllib.request.urlopen('http://192.168.255.254:18859/client.img',timeout=120) as source,temporary.open('xb') as target:
  while True:
   block=source.read(1024*1024)
   if not block:break
   target.write(block)
 if sha(temporary)!='54b71b54c959851db39a3210f2384d4eba01833f1564a910c2d11b9ede30561c':raise ValueError('Image transfer hash mismatch')
 temporary.rename(image)

import hashlib,urllib.request
data=urllib.request.urlopen('http://192.168.255.254:18859/echo-4c4df4c0-e5194c8677d1-d9025b93e53cbc04-dut.py',timeout=30).read()
if hashlib.sha256(data).hexdigest()!='fa0be265b623b1dfbb8262688dddf29fcf60069e86cdc19d1f5a9c97ff87155d':raise ValueError('DUT helper mismatch')
namespace={'__name__':'campaign_dut'};exec(compile(data,'dut.py','exec'),namespace)
namespace['start']({'image': '/dev/shm/echo-shared-54b71b54c959.img', 'image_sha256': '54b71b54c959851db39a3210f2384d4eba01833f1564a910c2d11b9ede30561c', 'service_root': '/dev/shm/echo-4c4df4c0-e5194c8677d1-d9025b93e53cbc04-service', 'binary_path': '/nix/store/7sa629l4s813gfw70dbnrr8c447jw4sq-lauberhorn-app-microbenchmarks-aarch64-unknown-linux-gnu-0.0.1/bin/microbenchmarks', 'binary_sha256': '9af5665a49f8e722a71f2c087bc1e743db5657e5c636794361d0bfca3c3a7517', 'runtime': '/nix/store/82lkh8xzdmx5p1v5442wfrhdxkvin40v-lauberhorn-rt-aarch64-unknown-linux-gnu-0.0.1', 'tracing_compiled': True, 'payload_bytes': 128, 'workers': 1, 'mode': 'unloaded', 'instrumentation': 'breakdown'})

ECHOSTART
echo TEST_STAGE:ready
