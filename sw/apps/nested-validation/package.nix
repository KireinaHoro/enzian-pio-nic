{ stdenv, lib, lauberhornRuntime, pkg-config }:
stdenv.mkDerivation {
  pname = "lauberhorn-nested-validation";
  version = "0.0.1";
  src = (import ../../../nix/sources.nix { inherit lib; root = ../../..; }).c [
    "sw/apps/nested-validation" "sw/rt" "sw/core" "sw/include"
  ];
  nativeBuildInputs = [ pkg-config ];
  buildInputs = [ lauberhornRuntime ];
  buildPhase = ''
    $CC -O2 -g -pthread -march=armv8-a+crc+lse -Isw/rt -Isw/core \
      -I${lauberhornRuntime.genVerilog.headers} -I${lauberhornRuntime.devHdrs} \
      $($PKG_CONFIG --cflags lauberhorn) sw/apps/nested-validation/main.c \
      $($PKG_CONFIG --libs lauberhorn) -o nested-validation
  '';
  installPhase = "install -Dm755 nested-validation $out/bin/nested-validation";
  dontStrip = true;
  meta.mainProgram = "nested-validation";
}
