#!/usr/bin/env bash
# Run a bounded client command while capturing only traffic involving the test NIC.
set -euo pipefail
[[ $# -ge 4 ]] || { echo 'usage: capture-client.sh IFACE DUT_MAC LOG_DIR COMMAND [ARGS...]' >&2; exit 2; }
iface=$1
mac=$2
logs=$3
shift 3
mkdir -p "$logs"
[[ ! -e $logs/peer.pcap ]] || { echo 'Capture already exists' >&2; exit 1; }
python3 "$(dirname "$0")/peer_capture.py" "$iface" "$mac" "$logs/peer.pcap" > "$logs/capture.log" 2>&1 &
capture=$!
cleanup() {
    kill -INT "$capture" 2>/dev/null || true
    wait "$capture" || true
}
trap cleanup EXIT
for attempt in {1..100}; do
    kill -0 "$capture"
    if grep -q '^CAPTURE_READY$' "$logs/capture.log"; then break; fi
    sleep 0.1
done
grep -q '^CAPTURE_READY$' "$logs/capture.log"
set +e
"$@" 2>&1 | tee "$logs/client-result.log"
status=${PIPESTATUS[0]}
set -e
exit "$status"
