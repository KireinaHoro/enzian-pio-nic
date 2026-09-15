{
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
  ciBuild,
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
    ciBuild
  ];
}
