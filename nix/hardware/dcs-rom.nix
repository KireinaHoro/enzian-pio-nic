{ lib, runCommand, perl, perlPackages, python3 }:
let
  dcs = ../../vivado/eci/directory-controller-slice/gen_dc_protocol;
  generator = dcs + "/dc_stmc_lauberhorn/eci_stmc_auto.pl";
  converter = lib.fileset.toSource {
    root = dcs;
    fileset = lib.fileset.unions (map (name:
      dcs + "/dc_stmc_auto_no_lock/cc_rom_from_cc_tbl/${name}") [
      "cc_rom_from_cc_tbl.pl" "eci_cc_defs.tmpl" "eci_cc_table.tmpl"
    ]);
  };
in runCommand "lauberhorn-dcs-rom" {
  nativeBuildInputs = [ (perl.withPackages (_: [ perlPackages.autovivification ])) python3 ];
} ''
  export TZ=UTC LC_ALL=C SOURCE_DATE_EPOCH=1
  mkdir -p "$out"
  cp ${generator} eci_stmc_auto.pl
  cp ${converter}/dc_stmc_auto_no_lock/cc_rom_from_cc_tbl/* .
  # The upstream converter embeds wall-clock date. Use a fixed date, with
  # derivation/source hashes supplying provenance instead.
  substituteInPlace cc_rom_from_cc_tbl.pl \
    --replace-fail 'localtime;' 'gmtime(1);' \
    --replace-fail '$req_t_hash->{"F32"} = 1;' ""
  perl eci_stmc_auto.pl > "$out/generator.log"
  perl cc_rom_from_cc_tbl.pl ECI_CC.csv > "$out/converter.log"
  cp ECI_CC.csv eci_cc_defs.sv eci_cc_table.sv "$out/"
  sha256sum eci_stmc_auto.pl cc_rom_from_cc_tbl.pl eci_cc_defs.tmpl eci_cc_table.tmpl > "$out/source-sha256.txt"
  python3 ${../../tools/progress/dcs_rom_metadata.py} "$out"
''
