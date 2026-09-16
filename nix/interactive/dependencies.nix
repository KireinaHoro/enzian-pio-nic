{ target }:
# Shared by deployment packaging and CI image warmup; no generated helper here.
{
  inherit (target)
    bash
    coreutils
    jq
    kmod
    iproute2
    util-linux
    rpcbind
    libtirpc
    ;
}
