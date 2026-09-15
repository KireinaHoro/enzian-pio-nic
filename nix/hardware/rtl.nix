{
  stdenvNoCC,
  ivy-gather,
  mill,
  configure-mill-env-hook,
  src,
  gitRev,
  lockFile,
}:
let
  ivyCache = ivy-gather lockFile;
in
stdenvNoCC.mkDerivation {
  name = "lauberhorn-hw-rtl-config";
  inherit src;
  outputs = [
    "out"
    "devices"
    "headers"
  ];
  buildInputs = [ ivyCache ];
  nativeBuildInputs = [
    mill
    configure-mill-env-hook
  ];
  buildPhase = ''
    mill --no-daemon --offline \
      -Dnix-git-hash=${gitRev} eci.generateVerilog
  '';
  installPhase = ''
    mkdir -p $out $devices $headers
    mv out/eci/generateVerilog.dest/*.{v,sv,xdc} $out/
    mv out/eci/generateVerilog.dest/*.json      $out/
    mv out/eci/generateVerilog.dest/*.h          $headers/
    mv out/eci/generateVerilog.dest/*.dev        $devices/
  '';
}
