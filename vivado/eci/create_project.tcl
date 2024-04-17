# adapted from https://gitlab.inf.ethz.ch/project-openenzian/fpga-stack/sample-application/-/blob/e9dafb04963e7cd019cbd75aa4cc3d1fa39030cb/create_sample_application_project.tcl

variable script_file
set script_file "create_project.tcl"

proc print_help {} {
    variable script_file
    puts "Create vivado project for the ECI PIO NIC, as an ECI application.\n"
    puts "Syntax:"
    puts "$script_file -tclargs --origin_dir <path>"
    exit 0
}

set origin_dir [file normalize "[file dirname [info script]]"]

if { $::argc > 0 } {
    for {set i 0} {$i < $::argc} {incr i} {
        set option [string trim [lindex $::argv $i]]
        switch -regexp -- $option {
            "--origin_dir"    { incr i; set origin_dir [lindex $::argv $i] }
            default {
                if { [regexp {^-} $option] } {
                    puts "ERROR: Unknown option '$option' specified, please type '$script_file -tclargs --help' for usage info.\n"
                    return 1
                }
            }
        }
    }
}

set project "pio-nic-eci"
set part "xcvu9p-flgb2104-3-e"
set top_module "pio_nic_eci"

# repo for custom IPs, if any
# set ip_dir {...}

set src_dir "$origin_dir"
set spinal_gen_dir "$src_dir/../../hw/gen/eci/"
set hw_deps_dir "$src_dir/../../deps/spinal-blocks/deps"
set build_dir [file normalize "."]

set dcs_src_dir "${src_dir}/directory-controller-slice"

create_project $project ./$project -part $part
set proj [current_project]

set_property "default_lib" "xil_defaultlib"                 $proj
set_property "ip_cache_permissions" "read write"            $proj
set_property "ip_output_repo" "${build_dir}/${project}/${project}.cache/ip"  $proj
set_property "sim.ip.auto_export_scripts" "1"               $proj
set_property "simulator_language" "Mixed"                   $proj
set_property "target_language" "VHDL"                       $proj
set_property "xpm_libraries" "XPM_CDC XPM_MEMORY"           $proj
# set_property "ip_repo_paths" "${ip_dir}"                      $proj
set_property -name {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} -value {-mode out_of_context} -objects [get_runs synth_1]

# Make sure any repository IP is visible.
# update_ip_catalog

# Add ECI toolkit sources and synth constraints
add_files -fileset [get_filesets sources_1] "${src_dir}/eci-toolkit/hdl"
add_files -fileset [get_filesets constrs_1] "${src_dir}/eci-toolkit/xdc"

# Add DCS sources.
# eci_cmd_defs is got from eci_toolkit.
add_files -fileset [get_filesets sources_1] -norecurse \
    "$dcs_src_dir/dcs/dcu/eci_dirc_defs/eci_cc_defs.sv" \
    "$dcs_src_dir/dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv" \
    "$dcs_src_dir/dcs/dcu/eci_dirc_defs/eci_dirc_defs.sv" \
    "$dcs_src_dir/dcs/dcu/arb_4_ecih/rtl/arb_4_ecih.sv" \
    "$dcs_src_dir/dcs/dcu/arb_4_ecih/rtl/arb_3_ecih.sv" \
    "$dcs_src_dir/dcs/dcu/arb_4_ecih/rtl/arb_2_ecih.sv" \
    "$dcs_src_dir/common/axis_comb_rr_arb/rtl/axis_comb_rr_arb.sv" \
    "$dcs_src_dir/common/axis_comb_router/rtl/axis_comb_router.sv" \
    "$dcs_src_dir/common/axis_pipeline_stage/rtl/axis_pipeline_stage.sv" \
    "$dcs_src_dir/common/vr_pipe_stage/rtl/vr_pipe_stage.sv" \
    "$dcs_src_dir/dcs/dcs_data_path/dp_data_store/rtl/dp_data_store.sv" \
    "$dcs_src_dir/dcs/dcs_data_path/dp_gate/rtl/dp_gate.sv" \
    "$dcs_src_dir/dcs/dcs_data_path/dp_gen_path/rtl/dp_gen_path.sv" \
    "$dcs_src_dir/dcs/dcs_data_path/dp_mem/rtl/dp_mem.sv" \
    "$dcs_src_dir/dcs/dcs_data_path/map_ecid_to_wrd/rtl/map_ecid_to_wrd.sv" \
    "$dcs_src_dir/dcs/dcs_data_path/dp_wr_ser/rtl/dp_wr_ser.sv" \
    "$dcs_src_dir/dcs/dcs_data_path/rd_data_path/rtl/rd_data_path.sv" \
    "$dcs_src_dir/dcs/dcs_data_path/wr_data_path/rtl/wr_data_path.sv" \
    "$dcs_src_dir/dcs/dcs_rr_arb/rtl/dcs_rr_arb.sv" \
    "$dcs_src_dir/dcs/dc_to_vc_router/rtl/dc_to_vc_router.sv" \
    "$dcs_src_dir/dcs/dcu/dcu_controller/rtl/dcu_controller.sv" \
    "$dcs_src_dir/dcs/dcu/dcu_tsu/rtl/dcu_tsu.sv" \
    "$dcs_src_dir/dcs/dcu/decode_eci_req/rtl/decode_eci_req.sv" \
    "$dcs_src_dir/dcs/dcu/eci_cc_table/rtl/eci_cc_table.sv" \
    "$dcs_src_dir/dcs/dcu/gen_out_header/rtl/gen_out_header.sv" \
    "$dcs_src_dir/dcs/dcu/ram_tdp/rtl/ram_tdp.sv" \
    "$dcs_src_dir/dcs/dcu/rd_trmgr/rtl/rd_trmgr.sv" \
    "$dcs_src_dir/dcs/dcu/eci_trmgr/rtl/eci_trmgr.sv" \
    "$dcs_src_dir/dcs/dcu/tag_state_ram/rtl/tag_state_ram.sv" \
    "$dcs_src_dir/dcs/dcu/wr_trmgr/rtl/wr_trmgr.sv" \
    "$dcs_src_dir/dcs/dcu/dcu/rtl/dcu.sv" \
    "$dcs_src_dir/dcs/dcu/dcu_top/rtl/dcu_top.sv" \
    "$dcs_src_dir/dcs/dcs_dcus/rtl/dcs_dcus.sv" \
    "$dcs_src_dir/dcs/dcs/rtl/dcs.sv" \
    "$dcs_src_dir/dcs/perf_sim_modules/perf_gen_seq_aliased/rtl/perf_gen_seq_aliased.sv" \
    "$dcs_src_dir/dcs/perf_sim_modules/cli_tput_load_gen/rtl/cli_tput_load_gen.sv" \
    "$dcs_src_dir/dcs/perf_sim_modules/cli_lat_load_gen/rtl/cli_lat_load_gen.sv" \
    "$dcs_src_dir/common/axis_xpm_fifo/rtl/axis_xpm_fifo.sv"

# Add DCS descriptors to AXI converter modules.
add_files -fileset [get_filesets sources_1] -norecurse \
    "$dcs_src_dir/desc_to_axi/axi_rd_cl/rtl/axi_rd_cl.sv" \
    "$dcs_src_dir/desc_to_axi/axi_wr_cl/rtl/axi_wr_cl.sv"

# Add sources from us
add_files -fileset [get_filesets sources_1] -norecurse \
    "$src_dir/rtl/dcs_2_axi.sv" \
    "$src_dir/rtl/${top_module}.vhd" \
    "$src_dir/rtl/axil_regs_interconnect.v" \
    "$spinal_gen_dir/NicEngine_ips.v" \
    "$spinal_gen_dir/NicEngine.v" \
    "$hw_deps_dir/verilog-axis/rtl/sync_reset.v" \
    "$hw_deps_dir/verilog-axi/rtl/axil_interconnect.v" \
    "$hw_deps_dir/verilog-axi/rtl/axil_adapter.v" \
    "$hw_deps_dir/verilog-axi/rtl/axil_adapter_rd.v" \
    "$hw_deps_dir/verilog-axi/rtl/axil_adapter_wr.v" \
    "$hw_deps_dir/verilog-axi/rtl/axil_cdc.v" \
    "$hw_deps_dir/verilog-axi/rtl/axil_cdc_rd.v" \
    "$hw_deps_dir/verilog-axi/rtl/axil_cdc_wr.v"

# Add constraints from us necessary for synthesis
set synth_constrs [list \
    "$hw_deps_dir/verilog-axis/syn/vivado/axis_async_fifo.tcl" \
    "$hw_deps_dir/verilog-axis/syn/vivado/sync_reset.tcl" \
    "$hw_deps_dir/verilog-axi/syn/vivado/axil_cdc.tcl"]
add_files -fileset [get_filesets constrs_1] -norecurse $synth_constrs

# implementation constraints are separately loaded during impl
set_property used_in_implementation false [get_files -of_objects [get_filesets constrs_1]]

# implementation-only constraints: copied to out dir
set impl_constrs_dir "$project/xdc/impl"
file mkdir $impl_constrs_dir
file copy -force "$spinal_gen_dir/NicEngine.xdc" "$impl_constrs_dir"
foreach tclf $synth_constrs {
    set xdcf "[file tail [file rootname $tclf]].xdc"
    file copy -force "$tclf" "$impl_constrs_dir/$xdcf"
}

set_property "top" "${top_module}" [get_filesets sources_1]

# use newer version of VHDL -- from Manuel Hässig
set_property file_type {VHDL 2008} [get_files -filter {FILE_TYPE == VHDL}]

puts "Regenerating IPs..."
source "${src_dir}/eci-toolkit/create_ips.tcl"

# create block design for CMAC and clocking
source "${src_dir}/bd/design_1.tcl"
set bd_file [get_files design_1.bd]
generate_target all $bd_file

# create ILA for ECI channels ready/valid
create_ip -name ila -vendor xilinx.com -library ip -version 6.2 -module_name ila_eci_chans_arb
set ila_ip [get_ips ila_eci_chans_arb]
set_property -dict [list \
    CONFIG.C_ADV_TRIGGER {true} \
    CONFIG.C_EN_STRG_QUAL {1} \
    CONFIG.C_NUM_OF_PROBES {34} \
    CONFIG.C_TRIGIN_EN {false} \
    CONFIG.C_TRIGOUT_EN {false} \
    ] $ila_ip
generate_target all $ila_ip

close_project

# local config file for static-shell flows
set conffile [open "enzian.conf.tcl" "a"]
puts $conffile "set enzian_app \"${project}\""
puts $conffile "set project_dir \"[file normalize ${project}]\""
close $conffile
