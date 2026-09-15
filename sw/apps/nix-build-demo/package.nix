{
  stdenv,
  lauberhornRuntime,
  pkg-config,
}:
stdenv.mkDerivation {
  pname = "lauberhorn-demo-app-nix";
  version = "0.0.1";
  src = ./.;
  nativeBuildInputs = [ pkg-config ];
  buildInputs = [ lauberhornRuntime ];
  buildPhase = ''
    $CC $CFLAGS $(pkg-config --cflags lauberhorn) test.c \
      $LDFLAGS $(pkg-config --libs lauberhorn) -o runtime-demo
  '';
  installPhase = ''
    install -Dm755 runtime-demo $out/bin/runtime-demo
  '';
  meta.mainProgram = "runtime-demo";
}
