{
  stdenv,
  lib,
  kernel,
  src,
  devHdrs,
  genVerilog,
  queueDepth ? 64,
  rxWaitMs ? 1,
}:
assert queueDepth > 0 && rxWaitMs > 0;
stdenv.mkDerivation {
  pname = "lauberhorn-vm-module";
  version = "0.1";
  inherit src;
  nativeBuildInputs = kernel.moduleBuildDependencies;
  buildPhase = ''
    runHook preBuild
    make -C ${kernel.dev}/lib/modules/${kernel.modDirVersion}/build \
      M=$PWD/sw/kmod modules LH_BACKEND_VM=1 \
      HW_CFG_HDRS=${genVerilog.headers} MACKEREL_DEV_HDRS=${devHdrs} \
      KCFLAGS="-DLH_VM_QUEUE_DEPTH=${toString queueDepth} -DLH_VM_RX_WAIT_MS=${toString rxWaitMs}"
    runHook postBuild
  '';
  installPhase = ''
    install -Dm644 sw/kmod/lauberhorn.ko $out/lib/modules/${kernel.modDirVersion}/extra/lauberhorn.ko
  '';
  meta.platforms = lib.platforms.linux;
}
