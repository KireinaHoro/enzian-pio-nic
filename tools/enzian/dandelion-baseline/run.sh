#!@bash@/bin/bash
# Explicit commands only: no SSH, provisioning, mounting, or background services.
set -euo pipefail
export PATH=@coreutils@/bin:$PATH
case "${1:-}" in
server|server-timestamps)
    mode=$1
    : "${2:?fresh local state directory}" "${3:?total CPU count (absolute CPUs 0 through N-1)}"
    if test -e /tmp/dandelion_server || compgen -G '/dev/shm/shm_*' >/dev/null; then
        echo 'Refusing to share upstream fixed registry/shared-memory names with existing state.' >&2
        exit 1
    fi
    mkdir "$2"
    cd "$2"
    if test "$mode" = server; then server=@server@; else server=@serverTimestamps@; fi
    exec "$server/bin/dandelion_server" --total-cores "$3" --frontend-cores 2 --dispatcher-cores 1
    ;;
unloaded|figure6)
    mode=$1
    : "${2:?server IP}" "${3:?fresh output directory}"
    mkdir "$3"
    if test "$mode" = unloaded; then
        # Table-1-like workload; sample/instance count is our explicit choice.
        args=(--model closed-unloaded --size 1 --hotpercent 0 --cold-instances 1000 --duration 1000)
    else
        # Original artifact Figure 6 process-backend parameters.
        args=(--model open-sweep --size 128 --hotpercent 0.97 --cold-instances 10000
              --duration 180 --step-fine 10 --step-coarse 200 --service-timeout 540)
    fi
    exec @client@/bin/rload --target dandelion-process --requestformat matmul \
        --ip "$2" --outputdir "$3" --workload-path @fixtures@/share/dandelion-baseline/matmul "${args[@]}"
    ;;
diagnostic)
    shift
    exec @python@/bin/python3 @diagnostic@ --binary @fixtures@/share/dandelion-baseline/matmul "$@"
    ;;
*) echo 'Usage: dandelion-baseline {server|server-timestamps} NEW_STATE_DIR TOTAL_CORES' >&2
   echo '       dandelion-baseline {unloaded|figure6} SERVER_IP NEW_RESULTS_DIR' >&2
   echo '       dandelion-baseline diagnostic --host SERVER_IP --out NEW_RESULTS_DIR [--samples N]' >&2
   exit 2 ;;
esac
