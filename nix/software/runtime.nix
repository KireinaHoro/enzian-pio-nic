{
  stdenv,
  src,
  libtirpc,
  pkg-config,
  devHdrs,
  genVerilog,
}:
stdenv.mkDerivation {
  pname = "lauberhorn-rt";
  version = "0.0.1";
  inherit src;
  outputs = [
    "out"
    "dev"
  ];
  propagatedBuildInputs = [ libtirpc ];
  nativeBuildInputs = [ pkg-config ];
  buildPhase = ''
    runHook preBuild
    make -C sw/rt MACKEREL_DEV_HDRS=${devHdrs} HW_CFG_HDRS=${genVerilog.headers}
    $CC -O2 -g -DLH_PMU_PROBE sw/rt/pmu.c -o lh-pmu-probe
    runHook postBuild
  '';
  dontStrip = true;
  installPhase = ''
    runHook preInstall
    install -Dm755 sw/rt/liblauberhorn.so $out/lib/liblauberhorn.so
    install -Dm755 lh-pmu-probe $out/bin/lh-pmu-probe
    mkdir -p $dev/include $dev/lib/pkgconfig
    cp -r sw/include/. $dev/include/
    cat > $dev/lib/pkgconfig/lauberhorn.pc <<PC
    prefix=$out
    libdir=$out/lib
    includedir=$dev/include

    Name: lauberhorn
    Description: Lauberhorn ECI RPC runtime
    Version: 0.0.1
    Requires: libtirpc
    Libs: -L$out/lib -llauberhorn
    Cflags: -I$dev/include
    PC
    runHook postInstall
  '';
  passthru = { inherit genVerilog devHdrs; };
}
