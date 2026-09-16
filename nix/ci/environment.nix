{
  lib,
  mkShell,
  shell,
  linuxTools,
  ivyCache,
  configure-mill-env-hook,
  iverilog,
  libpcap,
  squashfsTools,
  pkg-config,
  targetTirpc,
  nix,
  bash,
  coreutils,
  findutils,
  shellcheck,
}:
mkShell {
  inputsFrom = [ shell ];
  packages = linuxTools ++ [
    ivyCache
    configure-mill-env-hook
    iverilog
    libpcap
    squashfsTools
    pkg-config
    targetTirpc
    # Cache wrapper dependencies, but execute wrappers from each job checkout.
    (lib.getBin nix)
    bash
    coreutils
    findutils
    shellcheck
  ];
}
