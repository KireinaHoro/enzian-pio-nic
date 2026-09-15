{ lib, root }:
let
  select =
    predicate: paths:
    lib.fileset.toSource {
      inherit root;
      fileset = lib.fileset.unions (map (p: lib.fileset.fileFilter predicate (root + "/${p}")) paths);
    };
in
{
  c = select (
    f:
    f.name == "Makefile"
    || lib.any f.hasExt [
      "mk"
      "c"
      "h"
      "x"
    ]
  );
  spinal = select (
    f:
    lib.any f.hasExt [
      "scala"
      "java"
      "xml"
      "conf"
      "mill"
      "v"
      "sv"
      "h"
      "hpp"
      "cpp"
      "cxx"
      "i"
      "sh"
    ]
  );
  pcaps = select (f: f.hasExt "pcap") [ "data/eci/iladata" ];
  vivado = lib.fileset.toSource {
    inherit root;
    fileset = lib.fileset.unions (
      map (p: root + "/${p}") [
        "vivado/eci"
        "deps/blocks/deps/verilog-axis"
        "deps/blocks/deps/verilog-axi"
        "tools/physical/checkpoint.tcl"
        "flake.lock"
      ]
    );
  };
}
