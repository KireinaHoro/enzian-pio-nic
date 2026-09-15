#!/usr/bin/env bash
# Canonical in-container runner, shared by CI and docker-vivado.sh.
set -euo pipefail
[[ $# == 3 ]] || { echo 'usage: run-vivado.sh BUNDLE OUTPUT_ROOT build|project-only|report' >&2; exit 2; }
bundle=$(realpath "$1")
output=$(realpath -m "$2")
mode=$3
case $mode in build|project-only|report) ;; *) echo "Unknown mode: $mode" >&2; exit 2 ;; esac
vivado_root=${VIVADO_ROOT:-/opt/Xilinx/2025.1/Vivado}
[[ -x $vivado_root/bin/vivado && -f $vivado_root/settings64.sh ]] || {
  echo "Vivado installation missing at $vivado_root (set VIVADO_ROOT inside the container)" >&2; exit 1;
}
[[ -f $bundle/vivado/eci/ci_build.tcl && -f $bundle/git-revision ]] || {
  echo 'Incomplete input bundle; build eciVivadoInputs with Nix first' >&2; exit 1;
}
# Vendor settings scripts do not support nounset.
set +u
# shellcheck disable=SC1091
source "$vivado_root/settings64.sh"
set -u
mkdir -p "$output"
project="$output/eci/vivadoProject.dest"
if [[ $mode == report ]]; then
  [[ -f $project/shell_lauberhorn-eci_routed.dcp ]] || { echo 'No routed checkpoint to report' >&2; exit 1; }
  [[ ! -e $output/physical && ! -e $output/physical-vivado.log ]] || { echo 'Report outputs already exist; use a fresh report destination' >&2; exit 1; }
  "$vivado_root/bin/vivado" -mode batch -nojournal -nolog \
    -source "$bundle/tools/physical/checkpoint.tcl" \
    -tclargs "$project/shell_lauberhorn-eci_routed.dcp" "$output/physical" \
    > "$output/physical-vivado.log" 2>&1
  [[ -f $output/physical/COMPLETE ]] || { echo 'Checkpoint report incomplete; inspect physical-vivado.log' >&2; exit 1; }
else
  [[ ! -e $project && ! -L $project && ! -e $output/vivado-build.log ]] || {
    echo 'Build outputs already exist; choose a fresh output directory' >&2; exit 1;
  }
  mkdir -p "$output/eci"
  args=("$bundle" "$project")
  [[ $mode != project-only ]] || args+=(project-only)
  "$vivado_root/bin/vivado" -mode batch -nojournal -nolog \
    -source "$bundle/vivado/eci/ci_build.tcl" -tclargs "${args[@]}" \
    2>&1 | tee "$output/vivado-build.log"
fi
