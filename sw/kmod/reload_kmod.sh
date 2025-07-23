#!/bin/bash

# This script unloads the kmod (if already loaded), compiles the kmod, loads it,
# and bring up the dmesg in the following mode.

set -e

# Current directory of this shell script
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "${DIR}"

# Unload the kmod if it is loaded
if lsmod | grep -wq "lauberhorn"; then
    echo "Unload kmod..."
    sudo rmmod lauberhorn
fi

echo "Compiling kmod..."
make

echo "Installing kmod..."
sudo insmod lauberhorn.ko

echo "Entering follow mode of dmesg... Ctrl+C to exit"
dmesg -wH || true
