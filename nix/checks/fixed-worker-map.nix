{ lib, runCommand, python3, stdenv }:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      ../../tools/address-map
      ../../tools/progress/capacity.py
      ../../model/data/selected-8k.json
      ../../sw/include/fixed_worker_map.h
      ../../hw/src/lauberhorn/host/eci/FixedWorkerMap.scala
    ];
  };
in runCommand "lauberhorn-fixed-worker-map" {
  nativeBuildInputs = [ python3 stdenv.cc ];
} ''
  cp -r ${source}/. .
  python3 tools/address-map/generate.py --check
  python3 tools/address-map/test_map.py -v > results.txt 2>&1
  mkdir -p "$out"
  cp results.txt "$out/"
''
