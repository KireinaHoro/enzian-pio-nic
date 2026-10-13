#!/usr/bin/env bash
# Snapshot runtime rows only after traffic has drained; never terminate workers.
# Arguments: LABEL SERVER_PID SERVER_CSV TRANSFER_URL
set -euo pipefail
label=$1 pid=$2 csv=$3 transfer=$4
[[ $label =~ ^[A-Za-z0-9_-]+$ && $pid =~ ^[0-9]+$ ]]
out=/dev/shm/paper-nullrpc-export-$label
mkdir "$out"
if [[ -e $csv || -e $csv.status.json ]]; then
    echo 'Runtime export is one-shot; refusing to present an old snapshot as new' >&2
    exit 1
fi
kill -USR2 "$pid"
for ((attempt=0; attempt<60; attempt++)); do
    [[ -f $csv.status.json ]] && break
    sleep 1
done
test -f "$csv.status.json"
cp "$csv" "$csv.status.json" "$out/"
cat /sys/module/lauberhorn/parameters/l2c_tad_snapshot > "$out/tad.txt"
dmesg > "$out/kernel.txt"
cat /nix/store/lauberhorn/manifest.json > "$out/manifest.json"
for name in tad-before.txt kernel-before.txt; do
    [[ ! -f $(dirname "$csv")/$name ]] || cp "$(dirname "$csv")/$name" "$out/"
done
python3 - "$out" "$transfer" "$label" <<'PY'
import io,sys,tarfile,urllib.request
from pathlib import Path
root=Path(sys.argv[1]);data=io.BytesIO()
with tarfile.open(fileobj=data,mode='w:gz') as f:f.add(root,arcname=root.name)
req=urllib.request.Request(sys.argv[2]+'/'+sys.argv[3]+'-dut.tar.gz',data=data.getvalue(),method='PUT')
urllib.request.urlopen(req,timeout=120).read()
PY
