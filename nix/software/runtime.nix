{
  stdenv,
  src,
  libtirpc,
  pkg-config,
  devHdrs,
  genVerilog,
  tracing ? true,
  cpuFlags ? [ ],
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
  NIX_CFLAGS_COMPILE = builtins.concatStringsSep " " cpuFlags;
  buildPhase = ''
    runHook preBuild
    make -C sw/rt MACKEREL_DEV_HDRS=${devHdrs} HW_CFG_HDRS=${genVerilog.headers} LH_ENABLE_TRACING=${if tracing then "1" else "0"}
    ${if tracing then "$CC -O2 -g -DLH_PMU_PROBE sw/rt/pmu.c -o lh-pmu-probe" else ""}
    ${if tracing then "" else ''
      if ${stdenv.cc.targetPrefix}nm sw/rt/liblauberhorn.so | grep -E 'server_trace_|lauberhorn_trace_|lh_pmu_'; then
        echo "Tracing symbols survived the uninstrumented build" >&2
        exit 1
      fi
      if ${stdenv.cc.targetPrefix}strings sw/rt/liblauberhorn.so | grep -E 'LAUBERHORN_(SERVER_TRACE|EVENT_TRACE|PMU)'; then
        echo "Tracing environment controls survived the uninstrumented build" >&2
        exit 1
      fi
    ''}
    runHook postBuild
  '';
  dontStrip = true;
  installPhase = ''
    runHook preInstall
    install -Dm755 sw/rt/liblauberhorn.so $out/lib/liblauberhorn.so
    ${if tracing then "install -Dm755 lh-pmu-probe $out/bin/lh-pmu-probe" else ""}
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
    Cflags: -I$dev/include -DLH_ENABLE_TRACING=${if tracing then "1" else "0"}
    PC
    runHook postInstall
  '';
  passthru = { inherit genVerilog devHdrs tracing cpuFlags; };
}
