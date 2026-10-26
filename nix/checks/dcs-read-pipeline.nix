{ lib, runCommand, verilator, gcc, gnumake, python3 }:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      ../../hw/test/rtl/dcs_read_pipeline_tb.sv
      ../../hw/test/rtl/dcs_elastic_pipeline_tb.sv
      ../../vivado/eci/rtl/dcs_axis_pipeline_stage.sv
      ../../vivado/eci/rtl/lauberhorn_axi_rd_cl.sv
      ../../vivado/eci/directory-controller-slice/desc_to_axi/axi_rd_cl/rtl/axi_rd_cl.sv
      ../../deps/blocks/deps/verilog-axis/rtl/axis_register.v
    ];
  };
in runCommand "lauberhorn-dcs-read-pipeline" {
  nativeBuildInputs = [ verilator gcc gnumake python3 ];
} ''
  cp -r ${source}/. .
  chmod -R u+w .
  verilator --binary --timing --assert -Wno-fatal --top-module dcs_read_pipeline_tb \
    --Mdir obj hw/test/rtl/dcs_read_pipeline_tb.sv \
    vivado/eci/rtl/lauberhorn_axi_rd_cl.sv \
    vivado/eci/directory-controller-slice/desc_to_axi/axi_rd_cl/rtl/axi_rd_cl.sv \
    deps/blocks/deps/verilog-axis/rtl/axis_register.v > build.log 2>&1 || { cat build.log; exit 1; }
  obj/Vdcs_read_pipeline_tb > run.log 2>&1 || { cat run.log; exit 1; }
  verilator --binary --timing --assert -Wno-fatal --top-module dcs_elastic_pipeline_tb \
    --Mdir obj-elastic hw/test/rtl/dcs_elastic_pipeline_tb.sv \
    vivado/eci/rtl/dcs_axis_pipeline_stage.sv \
    deps/blocks/deps/verilog-axis/rtl/axis_register.v > elastic-build.log 2>&1 || { cat elastic-build.log; exit 1; }
  obj-elastic/Vdcs_elastic_pipeline_tb > elastic-run.log 2>&1 || { cat elastic-run.log; exit 1; }
  mkdir -p $out
  cp build.log run.log elastic-build.log elastic-run.log $out/
''
