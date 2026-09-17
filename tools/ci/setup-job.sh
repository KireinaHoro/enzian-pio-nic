#!/usr/bin/env bash
# Checkout-specific trust and ephemeral authentication belong to the current job.
set -euo pipefail
: "${CI_PROJECT_DIR:?set CI_PROJECT_DIR to the runner checkout}"
: "${CI_JOB_TOKEN:?set CI_JOB_TOKEN for private source fetches}"
# FORCE_HTTPS is runner-local; Nix fetches through a separate Git cache.
git config --global --add url."https://gitlab.inf.ethz.ch/".insteadOf 'git@gitlab.inf.ethz.ch:'
git config --global --add url."https://gitlab.inf.ethz.ch/".insteadOf 'ssh://git@gitlab.inf.ethz.ch/'
# Trust only this runner-owned checkout and its initialized submodules.
git config --global --add safe.directory "$CI_PROJECT_DIR"
# PWD must expand in each submodule, not here.
# shellcheck disable=SC2016
git -C "$CI_PROJECT_DIR" submodule foreach --recursive 'git config --global --add safe.directory "$PWD"'
# Store an environment reference, never a token value or credential-bearing URL.
# shellcheck disable=SC2016
git config --global credential."https://gitlab.inf.ethz.ch".helper '!f() { if test "$1" = get; then printf "%s\n" "username=gitlab-ci-token" "password=$CI_JOB_TOKEN"; fi; }; f'
