# Embedded in the target wrapper; LH_MANIFEST is fixed by the image.
set -euo pipefail
fail() { echo "lh-test: $*" >&2; exit 1; }
usage() { echo 'usage: lh-test {info [--json]|load|configure MAC CIDR|run APP [--executable NAME] [--] ARGS...}' >&2; exit 2; }
case ${1:-} in
info)
    [[ $# == 1 || ( $# == 2 && $2 == --json ) ]] || usage
    jq . "$LH_MANIFEST"
    ;;
load)
    [[ $# == 1 ]] || usage
    [[ $EUID == 0 ]] || fail 'load requires root'
    [[ ! -d /sys/module/lauberhorn ]] || fail 'module already loaded; unload explicitly if intended'
    module=$(jq -er '.packages.module' "$LH_MANIFEST")
    [[ $(modinfo -F vermagic "$module") == "$(uname -r) "* ]] || fail 'module/kernel ABI mismatch; build for the running kernel'
    insmod "$module"
    ;;
configure)
    [[ $# == 3 ]] || usage
    [[ $EUID == 0 ]] || fail 'configure requires root'
    ip link show dev lauberhorn0 >/dev/null || fail 'lauberhorn0 missing; load the matching module first'
    ip link set lauberhorn0 address "$2"
    ip link set lauberhorn0 mtu 1500
    ip addr add "$3" dev lauberhorn0
    for attempt in 1 2 3; do
        if ip link set lauberhorn0 up; then break; fi
        [[ $attempt != 3 ]] || fail 'link did not come up'
        sleep 2
    done
    ip -br addr show dev lauberhorn0
    ;;
run)
    [[ $# -ge 2 ]] || usage
    app=$2; shift 2
    path=$(jq -er --arg app "$app" '.applications[$app].path // empty' "$LH_MANIFEST") || fail "unknown application: $app (see info)"
    if [[ ${1:-} == --executable ]]; then
        [[ $# -ge 2 && $2 =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] || usage
        executable="$path/bin/$2"; shift 2
    else
        executable=$(jq -er --arg app "$app" '.applications[$app].entryPoint // empty' "$LH_MANIFEST") || fail 'application has no default; use --executable NAME'
    fi
    [[ ${1:-} != -- ]] || shift
    [[ -x $executable ]] || fail "executable missing: $executable"
    exec "$executable" "$@"
    ;;
*) usage ;;
esac
