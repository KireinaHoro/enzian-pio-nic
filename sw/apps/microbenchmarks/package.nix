{
  stdenv,
  lib,
  lauberhornRuntime,
  pkg-config,
  rpcsvc-proto,
  python3,
}:
stdenv.mkDerivation {
  pname = "lauberhorn-app-microbenchmarks";
  version = "0.0.1";
  # Keep the shared Makefile at its expected relative path, excluding outputs.
  src = lib.fileset.toSource {
    root = ../../..;
    fileset = lib.fileset.unions [
      ../../../sw/apps/microbenchmarks/echo_registry.py
      (lib.fileset.fromSource (
        (import ../../../nix/sources.nix {
          inherit lib;
          root = ../../..;
        }).c [
          "sw/apps/microbenchmarks"
          "sw/usr-common.mk"
        ]
      ))
    ];
  };
  nativeBuildInputs = [
    pkg-config
    rpcsvc-proto
    python3
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
