{ lib, runCommand, python3, verilator, gnumake, gcc, dcsRom ? null }:
let
  dcs = ../../vivado/eci/directory-controller-slice;
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      ../../tools/progress/check_dcs_pressure.py
      ../../tools/progress/check_lock_tables.py
      ../../data/eci/sys_trace/trace_metadata.py
      ../../tools/progress/directory_witness.py
      ../../tools/progress/capacity.py
      ../../model/steady/selected-8k.json
      ../../hw/test/rtl/dcs_pressure
      ../../vivado/eci/eci-toolkit/hdl/eci_cmd_defs.sv
      (dcs + "/dcs/dcu/eci_dirc_defs/eci_dirc_defs.sv")
      (dcs + "/dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv")
      (dcs + "/dcs/dcs_data_path/dp_gate/rtl/dp_gate.sv")
      (dcs + "/gen_dc_protocol/dc_stmc_auto_lock/cc_rom_from_cc_tbl/eci_cc_defs.sv")
      (dcs + "/gen_dc_protocol/dc_stmc_auto_lock/cc_rom_from_cc_tbl/eci_cc_table.sv")
      (dcs + "/gen_dc_protocol/dc_stmc_auto_no_lock/cc_rom_from_cc_tbl/eci_cc_defs.sv")
      (dcs + "/gen_dc_protocol/dc_stmc_auto_no_lock/cc_rom_from_cc_tbl/eci_cc_table.sv")
      (dcs + "/dcs/dcu/dcu_controller/rtl/dcu_controller.sv")
      (dcs + "/dcs/dcu/dcu_tsu/rtl/dcu_tsu.sv")
      (dcs + "/dcs/dcu/tag_state_ram/rtl/tag_state_ram.sv")
      (dcs + "/dcs/dcu/ram_tdp/rtl/ram_tdp.sv")
      (dcs + "/dcs/dcu/dcu/rtl/dcu.sv")
      (dcs + "/dcs/dcu/decode_eci_req/rtl/decode_eci_req.sv")
      (dcs + "/dcs/dcu/gen_out_header/rtl/gen_out_header.sv")
      (dcs + "/dcs/dcu/rd_trmgr/rtl/rd_trmgr.sv")
      (dcs + "/dcs/dcu/wr_trmgr/rtl/wr_trmgr.sv")
      (dcs + "/dcs/dcu/eci_trmgr/rtl/eci_trmgr.sv")
      (dcs + "/dcs/dcs_dcus/rtl/dcs_dcus.sv")
      (dcs + "/dcs/dcu/arb_4_ecih/rtl/arb_2_ecih.sv")
      (dcs + "/dcs/dcu/arb_4_ecih/rtl/arb_3_ecih.sv")
      (dcs + "/dcs/dcu/arb_4_ecih/rtl/arb_4_ecih.sv")
    ];
  };
in runCommand "lauberhorn-dcs-pressure" {
  nativeBuildInputs = [ python3 verilator gnumake gcc ];
} ''
  cp -r ${source}/. .
  chmod -R u+w .
  ${if dcsRom != null then ''
  python3 tools/progress/check_dcs_pressure.py --rom ${dcsRom} --output selected-results
  mkdir -p "$out"
  cp selected-results/summary.json selected-results/run.log "$out/"
  '' else ''
  python3 tools/progress/check_dcs_pressure.py --output results
  python3 tools/progress/check_dcs_pressure.py --no-auto-lock --output nonlocking-results
  python3 tools/progress/check_dcs_pressure.py --directory --output directory-results
  python3 tools/progress/check_dcs_pressure.py --trace --output trace-results
  mkdir -p "$out"
  cp trace-results/summary.json "$out/trace-summary.json"
  cp trace-results/run.log "$out/trace-events.log"
  cp directory-results/summary.json "$out/directory-summary.json"
  cp directory-results/run.log "$out/directory-events.log"
  python3 tools/progress/check_lock_tables.py > "$out/lock-tables.json"
  cp nonlocking-results/summary.json "$out/nonlocking-summary.json"
  cp results/summary.json "$out/summary.json"
  python3 tools/progress/directory_witness.py model/steady/selected-8k.json > "$out/address-witness.json"
  ''}
''
