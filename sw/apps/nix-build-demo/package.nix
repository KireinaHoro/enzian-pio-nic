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
    $CC $CFLAGS $( $PKG_CONFIG --cflags lauberhorn) test.c \
      $LDFLAGS $( $PKG_CONFIG --libs lauberhorn) -o runtime-demo
  '';
  installPhase = ''
    install -Dm755 runtime-demo $out/bin/runtime-demo
  '';
  meta.mainProgram = "runtime-demo";
}
