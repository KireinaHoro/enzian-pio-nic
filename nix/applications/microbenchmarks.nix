{
  stdenv,
  src,
  lauberhornRuntime,
  pkg-config,
  rpcsvc-proto,
}:
stdenv.mkDerivation {
  pname = "lauberhorn-app-microbenchmarks";
  version = "0.0.1";
  inherit src;
  nativeBuildInputs = [
    pkg-config
    rpcsvc-proto
  ];
  buildInputs = [ lauberhornRuntime ];
  buildPhase = ''
    runHook preBuild
    make -C sw/apps/microbenchmarks
    runHook postBuild
  '';
  installPhase = ''
    install -Dm755 sw/apps/microbenchmarks/microbenchmarks $out/bin/microbenchmarks
  '';
  dontStrip = true;
  meta.mainProgram = "microbenchmarks";
}
