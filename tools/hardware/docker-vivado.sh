#!/usr/bin/env bash
# Launch the canonical CI environment on a Docker host; Nix is not needed here.
set -euo pipefail
[[ $# == 2 || $# == 3 ]] || { echo 'usage: docker-vivado.sh BUNDLE NEW_OUTPUT_ROOT [build|project-only|report]' >&2; exit 2; }
bundle=$(realpath "$1")
output=$(realpath -m "$2")
mode=${3:-build}
case $mode in build|project-only|report) ;; *) echo "Unknown mode: $mode" >&2; exit 2 ;; esac
[[ -f $bundle/tools/hardware/run-vivado.sh && -f $bundle/vivado/eci/container.yml ]] || {
  echo 'Incomplete input bundle; build eciVivadoInputs with Nix first' >&2; exit 1;
}
image=$(sed -n 's/^  image: //p' "$bundle/vivado/eci/container.yml")
[[ $image =~ ^[^[:space:]]+@sha256:[0-9a-f]{64}$ ]] || { echo 'Invalid pinned container image in bundle' >&2; exit 1; }
installation=$(realpath "${VIVADO_INSTALLATION:-/opt/Xilinx}")
# Docker's --mount format cannot represent commas in these paths.
[[ $bundle$output$installation != *,* ]] || { echo 'Docker mount paths must not contain commas' >&2; exit 2; }
if [[ $mode == report ]]; then
  [[ -d $output ]] || { echo 'Report requires an existing build output directory' >&2; exit 1; }
else
  mkdir -p "$(dirname "$output")"
  mkdir "$output" || { echo 'Output already exists; choose a fresh directory' >&2; exit 1; }
fi
args=(run --rm --init --user "$(id -u):$(id -g)" --network host
  --mount "type=bind,src=$bundle,dst=/bundle,readonly"
  --mount "type=bind,src=$output,dst=/work"
  --mount "type=bind,src=$installation,dst=/opt/Xilinx,readonly"
  --workdir /work --env HOME=/tmp --entrypoint /bin/bash)
for variable in XILINXD_LICENSE_FILE LM_LICENSE_FILE VIVADO_ROOT; do
  [[ ! -v $variable ]] || args+=(--env "$variable")
done
run() { docker "${args[@]}" "$image" /bundle/tools/hardware/run-vivado.sh /bundle /work "$1"; }
status=0
run "$mode" || status=$?
# Match CI's separate after_script, retaining the build failure if both fail.
if [[ $mode == build && -f $output/eci/vivadoProject.dest/shell_lauberhorn-eci_routed.dcp ]]; then
  report_status=0
  run report || report_status=$?
  [[ $status != 0 ]] || status=$report_status
fi
exit "$status"
