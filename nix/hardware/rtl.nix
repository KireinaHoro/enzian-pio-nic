{
  stdenvNoCC,
  ivy-gather,
  mill,
  configure-mill-env-hook,
  src,
  gitRev,
  lockFile,
  dcsRom,
  python3,
  deadlineMinLeadNs ? 1000000,
}:
assert builtins.isInt deadlineMinLeadNs && deadlineMinLeadNs > 0 && deadlineMinLeadNs <= 2147483647;
let
  ivyCache = ivy-gather lockFile;
in
stdenvNoCC.mkDerivation {
  name = "lauberhorn-hw-rtl-config";
  inherit src;
  passthru = { inherit deadlineMinLeadNs; };
  LAUBERHORN_DEADLINE_MIN_LEAD_NS = toString deadlineMinLeadNs;
  outputs = [
    "out"
    "devices"
    "headers"
  ];
  buildInputs = [ ivyCache ];
  nativeBuildInputs = [
    mill
    python3
    configure-mill-env-hook
  ];
  buildPhase = ''
    mill --no-daemon --offline \
      -Dnix-git-hash=${gitRev} eci.generateVerilog
  '';
  installPhase = ''
    mkdir -p $out $devices $headers
    mv out/eci/generateVerilog.dest/*.{v,sv,xdc} $out/
    # Embed this ROM's enums, not the historical auto-lock numeric encoding.
    python3 ${../../tools/progress/dcs_rom_metadata.py} ${dcsRom} out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json
    mv out/eci/generateVerilog.dest/*.json      $out/
    mv out/eci/generateVerilog.dest/*.h          $headers/
    mv out/eci/generateVerilog.dest/*.dev        $devices/
  '';
}
