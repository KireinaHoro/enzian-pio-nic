#!/bin/bash

set -e

make handler
make activator

pids=()

for i in {0..3}; do
    sudo ./handler &
    pids+=( $! )
done

printf "Handlers:"
for pid in "${pids[@]}"; do
    printf " %i" $pid
done
printf "\n"

sleep 1
read -p "Press enter to continue\n"

set +e
for pid in "${pids[@]}"; do
    echo "Killing $pid..."
    sudo kill $pid
done
