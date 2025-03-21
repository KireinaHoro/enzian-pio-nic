#!/usr/bin/env bash

set -eu

usage() {
    echo "usage: $0 <pcie device>" >&2
    exit 1
}

die() {
    echo "FATAL: $@" >&2
    exit 1
}

if [[ $# != 1 ]]; then
    usage
fi

pcie_dev="$1"

if [[ -d /sys/bus/pci/devices/$1 ]]; then
    echo "Removing device $pcie_dev..."
    echo 1 > "/sys/bus/pci/devices/$pcie_dev/remove"
fi

echo "Rescanning bus..."
echo 1 > "/sys/bus/pci/rescan"
