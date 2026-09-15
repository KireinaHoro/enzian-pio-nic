{
  stdenv,
  src,
  linuxTools,
  nukeReferences,
  kernel,
  kernelRelease,
  devHdrs,
  genVerilog,
}:
stdenv.mkDerivation {
  name = "lauberhorn-kmod";
  version = "0.0.1";
  inherit src;
  nativeBuildInputs = linuxTools ++ [ nukeReferences ];
  buildPhase = ''
    export ARCH=arm64
    export CROSS_COMPILE=aarch64-unknown-linux-gnu-
    export KDIR=${kernel}
    cd sw/kmod
    make V=1 KERNELRELEASE=${kernelRelease} \
      MACKEREL_DEV_HDRS=${devHdrs} \
      HW_CFG_HDRS=${genVerilog.headers}
  '';
  doCheck = true;
  checkPhase = ''
    runHook preCheck
    aarch64-unknown-linux-gnu-readelf -p .modinfo lauberhorn.ko \
      | grep -F 'vermagic=${kernelRelease} '
    runHook postCheck
  '';
  installPhase = ''
    mkdir -p $out
    nuke-refs lauberhorn.ko
    mv lauberhorn.ko $out/
  '';
  dontFixup = true;
}
