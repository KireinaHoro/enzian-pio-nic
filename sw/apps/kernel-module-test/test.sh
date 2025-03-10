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
for pid in "${cases[@]}"; do
    printf " %i" $pid
done
printf "\n"

read -p "Press enter to continue"

for pid in "${cases[@]}"; do
    kill $pid
done
