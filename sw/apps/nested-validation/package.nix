{
  stdenv,
  lib,
  lauberhornRuntime,
  pkg-config,
  vmStatePollUs ? 10000,
}:
stdenv.mkDerivation {
  pname = "lauberhorn-nested-validation";
  version = "0.0.1";
  src =
    (import ../../../nix/sources.nix {
      inherit lib;
      root = ../../..;
    }).c
      [
        "sw/apps/nested-validation"
        "sw/rt"
        "sw/core"
        "sw/include"
      ];
  nativeBuildInputs = [ pkg-config ];
  buildInputs = [ lauberhornRuntime ];
  NIX_CFLAGS_COMPILE = lib.concatStringsSep " " lauberhornRuntime.cpuFlags;
  buildPhase = ''
    $CC -O2 -g -pthread -Isw/rt -Isw/core -DLH_VM_STATE_POLL_US=${toString vmStatePollUs} \
      -I${lauberhornRuntime.genVerilog.headers} -I${lauberhornRuntime.devHdrs} \
      $($PKG_CONFIG --cflags lauberhorn) sw/apps/nested-validation/main.c \
      $($PKG_CONFIG --libs lauberhorn) -o nested-validation
  '';
  installPhase = "install -Dm755 nested-validation $out/bin/nested-validation";
  dontStrip = true;
  meta.mainProgram = "nested-validation";
}
