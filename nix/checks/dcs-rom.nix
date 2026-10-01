{ lib, runCommand, python3, verilator, dcsRom }:
let
  thx = lib.fileset.toSource {
    root = ../../deps/thx-fsms;
    fileset = lib.fileset.unions [
      ../../deps/thx-fsms/legacy/check_DirC_csv.py
      ../../deps/thx-fsms/legacy/remote_fsm.txt
    ];
  };
in runCommand "lauberhorn-dcs-rom-thx" {
  nativeBuildInputs = [ python3 verilator ];
} ''
  python3 ${../../tools/progress/check_lauberhorn_rom.py} \
    --rom ${dcsRom} --thx ${thx} --output "$out"
''
