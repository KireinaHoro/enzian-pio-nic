#!/usr/bin/env bash
# Compose reusable CPU-side operations for a positive smoke test.
set -euo pipefail
[[ $# == 4 ]] || { echo "usage: $0 IMAGE MAC CIDR NEW_LOG_DIR" >&2; exit 2; }
steps=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/cpu.sh
[[ $(cat /sys/devices/system/cpu/isolated) == 44-47 ]]
bash "$steps" mount "$1"
bash "$steps" load
bash "$steps" verify
bash "$steps" configure "$2" "$3"
bash "$steps" serve add 4 "$4"
