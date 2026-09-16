{
  stdenvNoCC,
  src,
  mackerel,
  genVerilog,
}:
stdenvNoCC.mkDerivation {
  name = "lauberhorn-dev-hdrs";
  inherit src;
  nativeBuildInputs = [ mackerel ];
  buildPhase = ''
    mkdir -p $out
    for a in *.dev ${genVerilog.devices}/*; do
      echo "Compiling $a..."
      fn=$(basename $a)
      mackerel2 -c $a -I$(dirname $a) -o $out/''${fn%.dev}_dev.h
    done
  '';
}
