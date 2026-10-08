#!/usr/bin/env bash
# Execute on the reserved client using the cross-built deployment image.
# warmup requires functions already registered; measure reuses that registry.
set -eu
: "${DANDELION_APP:?path to cross-built application output}"
: "${DANDELION_TESTS:?path to cross-built packaged fixtures}"
: "${DANDELION_RUN:?fresh output directory shared with the DUT}"
: "${LAUBERHORN_CLIENT_IP:?client FPGA-VLAN address}"
: "${LAUBERHORN_IP:?DUT FPGA address}"
: "${DANDELION_SERVER:?DUT HTTP address}"
run=$DANDELION_RUN
app=$DANDELION_APP
suite=$DANDELION_TESTS
export LAUBERHORN_CLIENT_IP DANDELION_SKIP_REGISTER=1
case "${1:?warmup or measure}" in
warmup)
mkdir "$run/residency-warmup-sustained"
export DANDELION_SKIP_REGISTER=1
pids=""
for i in $(seq 1 12); do
 mkdir "$run/residency-warmup-sustained/$i"
 (
  while ! test -f "$run/residency-warmup-sustained/go"; do sleep 0.05; done
  export DANDELION_TRACE_ID_BASE=$((500000+i*1000))
  "$app/bin/latency" --rpc --function matmul --niters 20 --nwarmup 0 \
   --lauberhorn-ip "$LAUBERHORN_IP" --dandelion-ip "$DANDELION_SERVER" \
   --isolation mmu --binary-arch aarch64 --binary-path "$suite/share/dandelion-tests/binaries" \
   --dump-path "$run/residency-warmup-sustained/$i/" > "$run/residency-warmup-sustained/$i/client.log" 2>&1
 ) &
 pids="$pids $!"
done
sleep 1
touch "$run/residency-warmup-sustained/go"
for pid in $pids; do wait "$pid"; done
printf '{"concurrent_clients":12,"requests_each":20,"exit":0}\n' > "$run/residency-warmup-sustained/summary.json"
;;
measure)
base=100000
for function in matmul graph graph_2; do
  dir="$run/$function"
  mkdir "$dir"
  export DANDELION_TRACE_ID_BASE="$base"
  set +e
  "$app/bin/latency" --rpc --function "$function" --niters 1000 --nwarmup 100 \
    --lauberhorn-ip "$LAUBERHORN_IP" --lauberhorn-port 12345 \
    --dandelion-ip "$DANDELION_SERVER" --dandelion-port 6000 --isolation mmu \
    --binary-arch aarch64 --binary-path "$suite/share/dandelion-tests/binaries" \
    --dump-path "$dir/" > "$dir/client.log" 2>&1
  rc=$?
  printf '{"function":"%s","exit":%s,"warmups":100,"measured":1000}\n' "$function" "$rc" > "$dir/summary.json"
  set -e
  test "$rc" = 0
  export DANDELION_SKIP_REGISTER=1
  base=$((base+100000))
done
;;
*) echo "Expected warmup or measure" >&2; exit 2;;
esac
