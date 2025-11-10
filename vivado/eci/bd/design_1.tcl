
################################################################
# This is a generated script based on design: design_1
#
# Though there are limitations about the generated script,
# the main purpose of this utility is to make learning
# IP Integrator Tcl commands easier.
################################################################

namespace eval _tcl {
proc get_script_folder {} {
   set script_path [file normalize [info script]]
   set script_folder [file dirname $script_path]
   return $script_folder
}
}
variable script_folder
set script_folder [_tcl::get_script_folder]

################################################################
# Check if script is running in correct Vivado version.
################################################################
set scripts_vivado_version 2025.1
set current_vivado_version [version -short]

if { [string first $scripts_vivado_version $current_vivado_version] == -1 } {
   puts ""
   if { [string compare $scripts_vivado_version $current_vivado_version] > 0 } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2042 -severity "ERROR" " This script was generated using Vivado <$scripts_vivado_version> and is being run in <$current_vivado_version> of Vivado. Sourcing the script failed since it was created with a future version of Vivado."}

   } else {
     catch {common::send_gid_msg -ssname BD::TCL -id 2041 -severity "ERROR" "This script was generated using Vivado <$scripts_vivado_version> and is being run in <$current_vivado_version> of Vivado. Please run the script in Vivado <$scripts_vivado_version> then open the design in Vivado <$current_vivado_version>. Upgrade the design by running \"Tools => Report => Report IP Status...\", then run write_bd_tcl to create an updated script."}

   }

   return 1
}

################################################################
# START
################################################################

# To test this script, run the following commands from Vivado Tcl console:
# source design_1_script.tcl

# If there is no project opened, this script will create a
# project, but make sure you do not have an existing project
# <./myproj/project_1.xpr> in the current working folder.

set list_projs [get_projects -quiet]
if { $list_projs eq "" } {
   create_project project_1 myproj -part xcvu9p-flgb2104-3-e
}


# CHANGE DESIGN NAME HERE
variable design_name
set design_name design_1

# If you do not already have an existing IP Integrator design open,
# you can create a design using the following command:
#    create_bd_design $design_name

# Creating design if needed
set errMsg ""
set nRet 0

set cur_design [current_bd_design -quiet]
set list_cells [get_bd_cells -quiet]

if { ${design_name} eq "" } {
   # USE CASES:
   #    1) Design_name not set

   set errMsg "Please set the variable <design_name> to a non-empty value."
   set nRet 1

} elseif { ${cur_design} ne "" && ${list_cells} eq "" } {
   # USE CASES:
   #    2): Current design opened AND is empty AND names same.
   #    3): Current design opened AND is empty AND names diff; design_name NOT in project.
   #    4): Current design opened AND is empty AND names diff; design_name exists in project.

   if { $cur_design ne $design_name } {
      common::send_gid_msg -ssname BD::TCL -id 2001 -severity "INFO" "Changing value of <design_name> from <$design_name> to <$cur_design> since current design is empty."
      set design_name [get_property NAME $cur_design]
   }
   common::send_gid_msg -ssname BD::TCL -id 2002 -severity "INFO" "Constructing design in IPI design <$cur_design>..."

} elseif { ${cur_design} ne "" && $list_cells ne "" && $cur_design eq $design_name } {
   # USE CASES:
   #    5) Current design opened AND has components AND same names.

   set errMsg "Design <$design_name> already exists in your project, please set the variable <design_name> to another value."
   set nRet 1
} elseif { [get_files -quiet ${design_name}.bd] ne "" } {
   # USE CASES: 
   #    6) Current opened design, has components, but diff names, design_name exists in project.
   #    7) No opened design, design_name exists in project.

   set errMsg "Design <$design_name> already exists in your project, please set the variable <design_name> to another value."
   set nRet 2

} else {
   # USE CASES:
   #    8) No opened design, design_name not in project.
   #    9) Current opened design, has components, but diff names, design_name not in project.

   common::send_gid_msg -ssname BD::TCL -id 2003 -severity "INFO" "Currently there is no design <$design_name> in project, so creating one..."

   create_bd_design $design_name

   common::send_gid_msg -ssname BD::TCL -id 2004 -severity "INFO" "Making design <$design_name> as current_bd_design."
   current_bd_design $design_name

}

common::send_gid_msg -ssname BD::TCL -id 2005 -severity "INFO" "Currently the variable <design_name> is equal to \"$design_name\"."

if { $nRet != 0 } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2006 -severity "ERROR" $errMsg}
   return $nRet
}

set bCheckIPsPassed 1
##################################################################
# CHECK IPs
##################################################################
set bCheckIPs 1
if { $bCheckIPs == 1 } {
   set list_check_ips "\ 
xilinx.com:ip:cmac_usplus:3.1\
xilinx.com:ip:xpm_cdc_gen:1.0\
xilinx.com:ip:vio:3.0\
xilinx.com:inline_hdl:ilconstant:1.0\
xilinx.com:ip:clk_wiz:6.0\
xilinx.com:ip:proc_sys_reset:5.0\
xilinx.com:ip:system_ila:1.1\
xilinx.com:ip:c_counter_binary:12.0\
"

   set list_ips_missing ""
   common::send_gid_msg -ssname BD::TCL -id 2011 -severity "INFO" "Checking if the following IPs exist in the project's IP catalog: $list_check_ips ."

   foreach ip_vlnv $list_check_ips {
      set ip_obj [get_ipdefs -all $ip_vlnv]
      if { $ip_obj eq "" } {
         lappend list_ips_missing $ip_vlnv
      }
   }

   if { $list_ips_missing ne "" } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2012 -severity "ERROR" "The following IPs are not found in the IP Catalog:\n  $list_ips_missing\n\nResolution: Please add the repository containing the IP(s) to the project." }
      set bCheckIPsPassed 0
   }

}

if { $bCheckIPsPassed != 1 } {
  common::send_gid_msg -ssname BD::TCL -id 2023 -severity "WARNING" "Will not continue with creation of design due to the error(s) above."
  return 3
}

##################################################################
# DESIGN PROCs
##################################################################


# Hierarchical cell: hier_ilas
proc create_hier_cell_hier_ilas { parentCell nameHier } {

  variable script_folder

  if { $parentCell eq "" || $nameHier eq "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2092 -severity "ERROR" "create_hier_cell_hier_ilas() - Empty argument(s)!"}
     return
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj

  # Create cell and set as current instance
  set hier_obj [create_bd_cell -type hier $nameHier]
  current_bd_instance $hier_obj

  # Create interface pins
  create_bd_intf_pin -mode Monitor -vlnv xilinx.com:interface:aximm_rtl:1.0 SLOT_0_AXI

  create_bd_intf_pin -mode Monitor -vlnv xilinx.com:interface:aximm_rtl:1.0 SLOT_1_AXI

  create_bd_intf_pin -mode Monitor -vlnv xilinx.com:interface:axis_rtl:1.0 SLOT_0_AXIS

  create_bd_intf_pin -mode Monitor -vlnv xilinx.com:interface:axis_rtl:1.0 SLOT_0_AXIS1


  # Create pins
  create_bd_pin -dir I -type clk app_clk
  create_bd_pin -dir I -from 16 -to 0 -type data core0_states
  create_bd_pin -dir I -from 16 -to 0 -type data core1_states
  create_bd_pin -dir I -from 16 -to 0 -type data core2_states
  create_bd_pin -dir I -from 16 -to 0 -type data core3_states
  create_bd_pin -dir I -from 16 -to 0 -type data core4_states
  create_bd_pin -dir I -from 75 -to 0 -type data lci_even
  create_bd_pin -dir I -from 75 -to 0 -type data lcia_even
  create_bd_pin -dir I -from 75 -to 0 -type data ul_even
  create_bd_pin -dir I -from 75 -to 0 -type data lci_odd
  create_bd_pin -dir I -from 75 -to 0 -type data lcia_odd
  create_bd_pin -dir I -from 75 -to 0 -type data ul_odd
  create_bd_pin -dir I -from 57 -to 0 -type data dcs_even_trace_0
  create_bd_pin -dir I -from 57 -to 0 -type data dcs_even_trace_1
  create_bd_pin -dir I -from 57 -to 0 -type data dcs_odd_trace_0
  create_bd_pin -dir I -from 57 -to 0 -type data dcs_odd_trace_1
  create_bd_pin -dir I -type rst resetn
  create_bd_pin -dir I -type clk txclk
  create_bd_pin -dir I -type rst resetn1
  create_bd_pin -dir I -type clk rxclk
  create_bd_pin -dir I -type rst resetn2

  # Create instance: ila_app, and set properties
  set ila_app [ create_bd_cell -type ip -vlnv xilinx.com:ip:system_ila:1.1 ila_app ]
  set_property -dict [list \
    CONFIG.C_ADV_TRIGGER {true} \
    CONFIG.C_DATA_DEPTH {4096} \
    CONFIG.C_EN_STRG_QUAL {1} \
    CONFIG.C_INPUT_PIPE_STAGES {2} \
    CONFIG.C_MON_TYPE {MIX} \
    CONFIG.C_NUM_MONITOR_SLOTS {2} \
    CONFIG.C_NUM_OF_PROBES {16} \
    CONFIG.C_PROBE0_WIDTH {17} \
    CONFIG.C_PROBE10_WIDTH {76} \
    CONFIG.C_PROBE11_WIDTH {58} \
    CONFIG.C_PROBE12_WIDTH {58} \
    CONFIG.C_PROBE13_WIDTH {58} \
    CONFIG.C_PROBE14_WIDTH {58} \
    CONFIG.C_PROBE15_TYPE {1} \
    CONFIG.C_PROBE15_WIDTH {48} \
    CONFIG.C_PROBE1_WIDTH {17} \
    CONFIG.C_PROBE2_WIDTH {17} \
    CONFIG.C_PROBE3_WIDTH {17} \
    CONFIG.C_PROBE4_WIDTH {17} \
    CONFIG.C_PROBE5_WIDTH {76} \
    CONFIG.C_PROBE6_WIDTH {76} \
    CONFIG.C_PROBE7_WIDTH {76} \
    CONFIG.C_PROBE8_WIDTH {76} \
    CONFIG.C_PROBE9_WIDTH {76} \
    CONFIG.C_PROBE_WIDTH_PROPAGATION {MANUAL} \
    CONFIG.C_SLOT {0} \
    CONFIG.C_SLOT_0_APC_EN {1} \
    CONFIG.C_SLOT_0_AXI_ADDR_WIDTH {38} \
    CONFIG.C_SLOT_0_AXI_DATA_WIDTH {512} \
    CONFIG.C_SLOT_0_AXI_ID_WIDTH {7} \
    CONFIG.C_SLOT_0_MAX_RD_BURSTS {8} \
    CONFIG.C_SLOT_0_MAX_WR_BURSTS {8} \
    CONFIG.C_SLOT_1_APC_EN {1} \
    CONFIG.C_SLOT_1_AXI_ADDR_WIDTH {38} \
    CONFIG.C_SLOT_1_AXI_DATA_WIDTH {512} \
    CONFIG.C_SLOT_1_AXI_ID_WIDTH {7} \
    CONFIG.C_SLOT_1_MAX_RD_BURSTS {8} \
    CONFIG.C_SLOT_1_MAX_WR_BURSTS {8} \
  ] $ila_app


  # Create instance: ila_cmac_tx, and set properties
  set ila_cmac_tx [ create_bd_cell -type ip -vlnv xilinx.com:ip:system_ila:1.1 ila_cmac_tx ]
  set_property -dict [list \
    CONFIG.C_ADV_TRIGGER {true} \
    CONFIG.C_EN_STRG_QUAL {1} \
    CONFIG.C_INPUT_PIPE_STAGES {2} \
    CONFIG.C_SLOT_0_APC_EN {1} \
    CONFIG.C_SLOT_0_INTF_TYPE {xilinx.com:interface:axis_rtl:1.0} \
  ] $ila_cmac_tx


  # Create instance: app_clock, and set properties
  set app_clock [ create_bd_cell -type ip -vlnv xilinx.com:ip:c_counter_binary:12.0 app_clock ]
  set_property -dict [list \
    CONFIG.CE {false} \
    CONFIG.Implementation {DSP48} \
    CONFIG.Load {false} \
    CONFIG.Output_Width {48} \
    CONFIG.SCLR {false} \
  ] $app_clock


  # Create instance: ila_cmac_rx, and set properties
  set ila_cmac_rx [ create_bd_cell -type ip -vlnv xilinx.com:ip:system_ila:1.1 ila_cmac_rx ]
  set_property -dict [list \
    CONFIG.C_ADV_TRIGGER {true} \
    CONFIG.C_EN_STRG_QUAL {1} \
    CONFIG.C_INPUT_PIPE_STAGES {2} \
    CONFIG.C_MON_TYPE {INTERFACE} \
    CONFIG.C_NUM_OF_PROBES {4} \
    CONFIG.C_SLOT_0_APC_EN {1} \
    CONFIG.C_SLOT_0_INTF_TYPE {xilinx.com:interface:axis_rtl:1.0} \
  ] $ila_cmac_rx


  # Create interface connections
  connect_bd_intf_net -intf_net axis_tx_0_1 [get_bd_intf_pins SLOT_0_AXIS] [get_bd_intf_pins ila_cmac_tx/SLOT_0_AXIS]
  connect_bd_intf_net -intf_net cmac_usplus_0_axis_rx [get_bd_intf_pins SLOT_0_AXIS1] [get_bd_intf_pins ila_cmac_rx/SLOT_0_AXIS]
  connect_bd_intf_net -intf_net dcs_even [get_bd_intf_pins SLOT_0_AXI] [get_bd_intf_pins ila_app/SLOT_0_AXI]
  connect_bd_intf_net -intf_net dcs_odd [get_bd_intf_pins SLOT_1_AXI] [get_bd_intf_pins ila_app/SLOT_1_AXI]

  # Create port connections
  connect_bd_net -net app_clk_reset_peripheral_aresetn  [get_bd_pins resetn] \
  [get_bd_pins ila_app/resetn]
  connect_bd_net -net app_clock_Q  [get_bd_pins app_clock/Q] \
  [get_bd_pins ila_app/probe15]
  connect_bd_net -net clk_wiz_0_clk_out2  [get_bd_pins app_clk] \
  [get_bd_pins app_clock/CLK] \
  [get_bd_pins ila_app/clk]
  connect_bd_net -net cmac_usplus_0_gt_rxusrclk2  [get_bd_pins rxclk] \
  [get_bd_pins ila_cmac_rx/clk]
  connect_bd_net -net cmac_usplus_0_gt_txusrclk2  [get_bd_pins txclk] \
  [get_bd_pins ila_cmac_tx/clk]
  connect_bd_net -net core0_states_1  [get_bd_pins core0_states] \
  [get_bd_pins ila_app/probe0]
  connect_bd_net -net core1_states_1  [get_bd_pins core1_states] \
  [get_bd_pins ila_app/probe1]
  connect_bd_net -net core2_states_1  [get_bd_pins core2_states] \
  [get_bd_pins ila_app/probe2]
  connect_bd_net -net core3_states_1  [get_bd_pins core3_states] \
  [get_bd_pins ila_app/probe3]
  connect_bd_net -net core4_states_1  [get_bd_pins core4_states] \
  [get_bd_pins ila_app/probe4]
  connect_bd_net -net dcs_even_trace_0_1  [get_bd_pins dcs_even_trace_0] \
  [get_bd_pins ila_app/probe11]
  connect_bd_net -net dcs_even_trace_1_1  [get_bd_pins dcs_even_trace_1] \
  [get_bd_pins ila_app/probe12]
  connect_bd_net -net dcs_odd_trace_0_1  [get_bd_pins dcs_odd_trace_0] \
  [get_bd_pins ila_app/probe13]
  connect_bd_net -net dcs_odd_trace_1_1  [get_bd_pins dcs_odd_trace_1] \
  [get_bd_pins ila_app/probe14]
  connect_bd_net -net lci_even_1  [get_bd_pins lci_even] \
  [get_bd_pins ila_app/probe5]
  connect_bd_net -net lci_odd_1  [get_bd_pins lci_odd] \
  [get_bd_pins ila_app/probe8]
  connect_bd_net -net lcia_even_1  [get_bd_pins lcia_even] \
  [get_bd_pins ila_app/probe6]
  connect_bd_net -net lcia_odd_1  [get_bd_pins lcia_odd] \
  [get_bd_pins ila_app/probe9]
  connect_bd_net -net rst_cmac_usplus_0_322M_1_peripheral_aresetn  [get_bd_pins resetn1] \
  [get_bd_pins ila_cmac_tx/resetn]
  connect_bd_net -net rst_cmac_usplus_0_322M_peripheral_aresetn  [get_bd_pins resetn2] \
  [get_bd_pins ila_cmac_rx/resetn]
  connect_bd_net -net ul_even_1  [get_bd_pins ul_even] \
  [get_bd_pins ila_app/probe7]
  connect_bd_net -net ul_odd_1  [get_bd_pins ul_odd] \
  [get_bd_pins ila_app/probe10]

  # Restore current instance
  current_bd_instance $oldCurInst
}

# Hierarchical cell: hier_clk_rst
proc create_hier_cell_hier_clk_rst { parentCell nameHier } {

  variable script_folder

  if { $parentCell eq "" || $nameHier eq "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2092 -severity "ERROR" "create_hier_cell_hier_clk_rst() - Empty argument(s)!"}
     return
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj

  # Create cell and set as current instance
  set hier_obj [create_bd_cell -type hier $nameHier]
  current_bd_instance $hier_obj

  # Create interface pins

  # Create pins
  create_bd_pin -dir I -type clk clk_io
  create_bd_pin -dir O -type clk app_clk
  create_bd_pin -dir I -type clk rxclk
  create_bd_pin -dir I -type rst reset
  create_bd_pin -dir O -from 0 -to 0 -type rst rxclk_rstn
  create_bd_pin -dir O -from 0 -to 0 -type rst clk_io_rst
  create_bd_pin -dir I -type clk txclk
  create_bd_pin -dir O -from 0 -to 0 -type rst txclk_rstn
  create_bd_pin -dir O -type rst app_clk_reset
  create_bd_pin -dir O -from 0 -to 0 -type rst app_clk_resetn
  create_bd_pin -dir O -from 0 -to 0 no_rst

  # Create instance: clk_wiz_app, and set properties
  set clk_wiz_app [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_app ]
  set_property -dict [list \
    CONFIG.CLKOUT1_JITTER {102.086} \
    CONFIG.CLKOUT1_PHASE_ERROR {87.180} \
    CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200} \
    CONFIG.CLKOUT2_JITTER {130.958} \
    CONFIG.CLKOUT2_PHASE_ERROR {98.575} \
    CONFIG.CLKOUT2_USED {false} \
    CONFIG.MMCM_CLKFBOUT_MULT_F {12.000} \
    CONFIG.MMCM_CLKOUT0_DIVIDE_F {6.000} \
    CONFIG.MMCM_CLKOUT1_DIVIDE {1} \
    CONFIG.MMCM_DIVCLK_DIVIDE {1} \
    CONFIG.NUM_OUT_CLKS {1} \
    CONFIG.OPTIMIZE_CLOCKING_STRUCTURE_EN {true} \
    CONFIG.PRIM_SOURCE {No_buffer} \
    CONFIG.USE_LOCKED {false} \
    CONFIG.USE_RESET {false} \
  ] $clk_wiz_app


  # Create instance: rst_cmac_rx, and set properties
  set rst_cmac_rx [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_cmac_rx ]

  # Create instance: rst_cmac_init, and set properties
  set rst_cmac_init [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_cmac_init ]

  # Create instance: rst_cmac_tx, and set properties
  set rst_cmac_tx [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_cmac_tx ]

  # Create instance: rst_app, and set properties
  set rst_app [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_app ]

  # Create instance: ilconstant_1, and set properties
  set ilconstant_1 [ create_bd_cell -type inline_hdl -vlnv xilinx.com:inline_hdl:ilconstant:1.0 ilconstant_1 ]
  set_property CONFIG.CONST_VAL {0} $ilconstant_1


  # Create port connections
  connect_bd_net -net app_clk_reset_mb_reset  [get_bd_pins rst_app/mb_reset] \
  [get_bd_pins app_clk_reset]
  connect_bd_net -net app_clk_reset_peripheral_aresetn  [get_bd_pins rst_app/peripheral_aresetn] \
  [get_bd_pins app_clk_resetn]
  connect_bd_net -net clk_io_2  [get_bd_pins clk_io] \
  [get_bd_pins rst_cmac_init/slowest_sync_clk] \
  [get_bd_pins clk_wiz_app/clk_in1]
  connect_bd_net -net clk_wiz_0_clk_out2  [get_bd_pins clk_wiz_app/clk_out1] \
  [get_bd_pins app_clk] \
  [get_bd_pins rst_app/slowest_sync_clk]
  connect_bd_net -net cmac_init_clk_reset_peripheral_reset  [get_bd_pins rst_cmac_init/peripheral_reset] \
  [get_bd_pins clk_io_rst]
  connect_bd_net -net cmac_usplus_0_gt_rxusrclk2  [get_bd_pins rxclk] \
  [get_bd_pins rst_cmac_rx/slowest_sync_clk]
  connect_bd_net -net cmac_usplus_0_gt_txusrclk2  [get_bd_pins txclk] \
  [get_bd_pins rst_cmac_tx/slowest_sync_clk]
  connect_bd_net -net ilconstant_1_dout  [get_bd_pins ilconstant_1/dout] \
  [get_bd_pins no_rst]
  connect_bd_net -net reset_sys_1  [get_bd_pins reset] \
  [get_bd_pins rst_app/ext_reset_in] \
  [get_bd_pins rst_cmac_init/ext_reset_in] \
  [get_bd_pins rst_cmac_tx/ext_reset_in] \
  [get_bd_pins rst_cmac_rx/ext_reset_in]
  connect_bd_net -net rst_cmac_usplus_0_322M_1_peripheral_aresetn  [get_bd_pins rst_cmac_tx/peripheral_aresetn] \
  [get_bd_pins txclk_rstn]
  connect_bd_net -net rst_cmac_usplus_0_322M_peripheral_aresetn  [get_bd_pins rst_cmac_rx/peripheral_aresetn] \
  [get_bd_pins rxclk_rstn]

  # Restore current instance
  current_bd_instance $oldCurInst
}

# Hierarchical cell: hier_cmac_ctrl_stat
proc create_hier_cell_hier_cmac_ctrl_stat { parentCell nameHier } {

  variable script_folder

  if { $parentCell eq "" || $nameHier eq "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2092 -severity "ERROR" "create_hier_cell_hier_cmac_ctrl_stat() - Empty argument(s)!"}
     return
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj

  # Create cell and set as current instance
  set hier_obj [create_bd_cell -type hier $nameHier]
  current_bd_instance $hier_obj

  # Create interface pins

  # Create pins
  create_bd_pin -dir I -type clk rxclk
  create_bd_pin -dir I -type clk txclk
  create_bd_pin -dir I -from 0 -to 0 stat_rx_aligned
  create_bd_pin -dir O -from 0 -to 0 stat_rx_aligned_txclk
  create_bd_pin -dir I -from 0 -to 0 stat_rx_local_fault
  create_bd_pin -dir O -from 0 -to 0 stat_rx_local_fault_txclk
  create_bd_pin -dir I -from 0 -to 0 stat_rx_remote_fault
  create_bd_pin -dir O -from 0 -to 0 stat_rx_remote_fault_txclk
  create_bd_pin -dir I -from 0 -to 0 stat_rx_status
  create_bd_pin -dir O -from 11 -to 0 gt_loopback_in
  create_bd_pin -dir O -from 0 -to 0 gtwiz_reset_tx_datapath
  create_bd_pin -dir O -from 0 -to 0 gtwiz_reset_rx_datapath
  create_bd_pin -dir O -from 0 -to 0 core_rx_reset
  create_bd_pin -dir O -from 0 -to 0 core_tx_reset
  create_bd_pin -dir O -from 0 -to 0 hi
  create_bd_pin -dir O -from 3 -to 0 gt_polarity

  # Create instance: xpm_cdc_gen_1, and set properties
  set xpm_cdc_gen_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xpm_cdc_gen:1.0 xpm_cdc_gen_1 ]
  set_property CONFIG.CDC_TYPE {xpm_cdc_single} $xpm_cdc_gen_1


  # Create instance: xpm_cdc_gen_2, and set properties
  set xpm_cdc_gen_2 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xpm_cdc_gen:1.0 xpm_cdc_gen_2 ]
  set_property CONFIG.CDC_TYPE {xpm_cdc_single} $xpm_cdc_gen_2


  # Create instance: xpm_cdc_gen_3, and set properties
  set xpm_cdc_gen_3 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xpm_cdc_gen:1.0 xpm_cdc_gen_3 ]
  set_property CONFIG.CDC_TYPE {xpm_cdc_single} $xpm_cdc_gen_3


  # Create instance: vio_0, and set properties
  set vio_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:vio:3.0 vio_0 ]
  set_property -dict [list \
    CONFIG.C_NUM_PROBE_IN {4} \
    CONFIG.C_NUM_PROBE_OUT {5} \
    CONFIG.C_PROBE_OUT0_WIDTH {12} \
  ] $vio_0


  # Create instance: ilconstant_2, and set properties
  set ilconstant_2 [ create_bd_cell -type inline_hdl -vlnv xilinx.com:inline_hdl:ilconstant:1.0 ilconstant_2 ]

  # Create instance: ilconstant_0, and set properties
  set ilconstant_0 [ create_bd_cell -type inline_hdl -vlnv xilinx.com:inline_hdl:ilconstant:1.0 ilconstant_0 ]
  set_property -dict [list \
    CONFIG.CONST_VAL {0b0011} \
    CONFIG.CONST_WIDTH {4} \
  ] $ilconstant_0


  # Create port connections
  connect_bd_net -net cmac_usplus_0_gt_rxusrclk2  [get_bd_pins rxclk] \
  [get_bd_pins vio_0/clk] \
  [get_bd_pins xpm_cdc_gen_2/src_clk] \
  [get_bd_pins xpm_cdc_gen_3/src_clk] \
  [get_bd_pins xpm_cdc_gen_1/src_clk]
  connect_bd_net -net cmac_usplus_0_gt_txusrclk2  [get_bd_pins txclk] \
  [get_bd_pins xpm_cdc_gen_2/dest_clk] \
  [get_bd_pins xpm_cdc_gen_3/dest_clk] \
  [get_bd_pins xpm_cdc_gen_1/dest_clk]
  connect_bd_net -net cmac_usplus_0_stat_rx_aligned  [get_bd_pins stat_rx_aligned] \
  [get_bd_pins vio_0/probe_in0] \
  [get_bd_pins xpm_cdc_gen_1/src_in]
  connect_bd_net -net cmac_usplus_0_stat_rx_local_fault  [get_bd_pins stat_rx_local_fault] \
  [get_bd_pins vio_0/probe_in2] \
  [get_bd_pins xpm_cdc_gen_2/src_in]
  connect_bd_net -net cmac_usplus_0_stat_rx_remote_fault  [get_bd_pins stat_rx_remote_fault] \
  [get_bd_pins vio_0/probe_in3] \
  [get_bd_pins xpm_cdc_gen_3/src_in]
  connect_bd_net -net cmac_usplus_0_stat_rx_status  [get_bd_pins stat_rx_status] \
  [get_bd_pins vio_0/probe_in1]
  connect_bd_net -net core_rx_reset  [get_bd_pins vio_0/probe_out3] \
  [get_bd_pins core_rx_reset]
  connect_bd_net -net core_tx_reset  [get_bd_pins vio_0/probe_out4] \
  [get_bd_pins core_tx_reset]
  connect_bd_net -net gt_loopback_in  [get_bd_pins vio_0/probe_out0] \
  [get_bd_pins gt_loopback_in]
  connect_bd_net -net gtwiz_reset_rx_datapath  [get_bd_pins vio_0/probe_out2] \
  [get_bd_pins gtwiz_reset_rx_datapath]
  connect_bd_net -net gtwiz_reset_tx_datapath  [get_bd_pins vio_0/probe_out1] \
  [get_bd_pins gtwiz_reset_tx_datapath]
  connect_bd_net -net ilconstant_0_dout  [get_bd_pins ilconstant_0/dout] \
  [get_bd_pins gt_polarity]
  connect_bd_net -net ilconstant_2_dout  [get_bd_pins ilconstant_2/dout] \
  [get_bd_pins hi]
  connect_bd_net -net xpm_cdc_gen_1_dest_out  [get_bd_pins xpm_cdc_gen_1/dest_out] \
  [get_bd_pins stat_rx_aligned_txclk]
  connect_bd_net -net xpm_cdc_gen_2_dest_out  [get_bd_pins xpm_cdc_gen_2/dest_out] \
  [get_bd_pins stat_rx_local_fault_txclk]
  connect_bd_net -net xpm_cdc_gen_3_dest_out  [get_bd_pins xpm_cdc_gen_3/dest_out] \
  [get_bd_pins stat_rx_remote_fault_txclk]

  # Restore current instance
  current_bd_instance $oldCurInst
}


# Procedure to create entire design; Provide argument to make
# procedure reusable. If parentCell is "", will use root.
proc create_root_design { parentCell } {

  variable script_folder
  variable design_name

  if { $parentCell eq "" } {
     set parentCell [get_bd_cells /]
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj


  # Create interface ports
  set gt [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:gt_rtl:1.0 gt ]

  set gt_ref_clk [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 gt_ref_clk ]
  set_property -dict [ list \
   CONFIG.FREQ_HZ {322265625} \
   ] $gt_ref_clk

  set rx_axis [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:axis_rtl:1.0 rx_axis ]

  set tx_axis [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:axis_rtl:1.0 tx_axis ]
  set_property -dict [ list \
   CONFIG.HAS_TKEEP {1} \
   CONFIG.HAS_TLAST {1} \
   CONFIG.HAS_TREADY {1} \
   CONFIG.HAS_TSTRB {0} \
   CONFIG.LAYERED_METADATA {undef} \
   CONFIG.TDATA_NUM_BYTES {64} \
   CONFIG.TDEST_WIDTH {0} \
   CONFIG.TID_WIDTH {0} \
   CONFIG.TUSER_WIDTH {1} \
   ] $tx_axis

  set dcs_even_mon [ create_bd_intf_port -mode Monitor -mon_dir SlaveType -vlnv xilinx.com:interface:aximm_rtl:1.0 dcs_even_mon ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {38} \
   CONFIG.DATA_WIDTH {512} \
   CONFIG.FREQ_HZ {200000000} \
   CONFIG.HAS_QOS {0} \
   CONFIG.HAS_REGION {0} \
   CONFIG.ID_WIDTH {7} \
   CONFIG.NUM_READ_OUTSTANDING {8} \
   CONFIG.NUM_WRITE_OUTSTANDING {8} \
   CONFIG.PROTOCOL {AXI4} \
   ] $dcs_even_mon

  set dcs_odd_mon [ create_bd_intf_port -mode Monitor -mon_dir SlaveType -vlnv xilinx.com:interface:aximm_rtl:1.0 dcs_odd_mon ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {38} \
   CONFIG.DATA_WIDTH {512} \
   CONFIG.FREQ_HZ {200000000} \
   CONFIG.HAS_QOS {0} \
   CONFIG.HAS_REGION {0} \
   CONFIG.ID_WIDTH {7} \
   CONFIG.NUM_READ_OUTSTANDING {8} \
   CONFIG.NUM_WRITE_OUTSTANDING {8} \
   CONFIG.PROTOCOL {AXI4} \
   ] $dcs_odd_mon


  # Create ports
  set app_clk [ create_bd_port -dir O -type clk app_clk ]
  set_property -dict [ list \
   CONFIG.ASSOCIATED_BUSIF {} \
   CONFIG.ASSOCIATED_RESET {app_clk_reset} \
 ] $app_clk
  set app_clk_reset [ create_bd_port -dir O -type rst app_clk_reset ]
  set clk_io [ create_bd_port -dir I -type clk -freq_hz 100000000 clk_io ]
  set reset [ create_bd_port -dir I -type rst reset ]
  set_property -dict [ list \
   CONFIG.POLARITY {ACTIVE_HIGH} \
 ] $reset
  set rxclk [ create_bd_port -dir O -type clk rxclk ]
  set_property -dict [ list \
   CONFIG.ASSOCIATED_BUSIF {rx_axis} \
 ] $rxclk
  set txclk [ create_bd_port -dir O -type clk txclk ]
  set_property -dict [ list \
   CONFIG.ASSOCIATED_BUSIF {tx_axis} \
 ] $txclk
  set_property CONFIG.ASSOCIATED_BUSIF.VALUE_SRC DEFAULT $txclk

  set core0_states [ create_bd_port -dir I -from 16 -to 0 -type data core0_states ]
  set core1_states [ create_bd_port -dir I -from 16 -to 0 -type data core1_states ]
  set core2_states [ create_bd_port -dir I -from 16 -to 0 -type data core2_states ]
  set core3_states [ create_bd_port -dir I -from 16 -to 0 -type data core3_states ]
  set core4_states [ create_bd_port -dir I -from 16 -to 0 -type data core4_states ]
  set lci_even [ create_bd_port -dir I -from 75 -to 0 -type data lci_even ]
  set lci_odd [ create_bd_port -dir I -from 75 -to 0 -type data lci_odd ]
  set lcia_even [ create_bd_port -dir I -from 75 -to 0 -type data lcia_even ]
  set lcia_odd [ create_bd_port -dir I -from 75 -to 0 -type data lcia_odd ]
  set ul_even [ create_bd_port -dir I -from 75 -to 0 -type data ul_even ]
  set ul_odd [ create_bd_port -dir I -from 75 -to 0 -type data ul_odd ]
  set dcs_even_trace_0 [ create_bd_port -dir I -from 57 -to 0 -type data dcs_even_trace_0 ]
  set dcs_even_trace_1 [ create_bd_port -dir I -from 57 -to 0 -type data dcs_even_trace_1 ]
  set dcs_odd_trace_0 [ create_bd_port -dir I -from 57 -to 0 -type data dcs_odd_trace_0 ]
  set dcs_odd_trace_1 [ create_bd_port -dir I -from 57 -to 0 -type data dcs_odd_trace_1 ]

  # Create instance: cmac_usplus_0, and set properties
  set cmac_usplus_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:cmac_usplus:3.1 cmac_usplus_0 ]
  set_property -dict [list \
    CONFIG.ADD_GT_CNRL_STS_PORTS {1} \
    CONFIG.CMAC_CAUI4_MODE {1} \
    CONFIG.CMAC_CORE_SELECT {CMACE4_X0Y1} \
    CONFIG.ENABLE_AXI_INTERFACE {0} \
    CONFIG.GT_DRP_CLK {100} \
    CONFIG.GT_GROUP_SELECT {X0Y8~X0Y11} \
    CONFIG.GT_REF_CLK_FREQ {322.265625} \
    CONFIG.INCLUDE_AUTO_NEG_LT_LOGIC {0} \
    CONFIG.INCLUDE_RS_FEC {1} \
    CONFIG.INS_LOSS_NYQ {25} \
    CONFIG.NUM_LANES {4x25} \
    CONFIG.RX_CHECK_PREAMBLE {1} \
    CONFIG.RX_CHECK_SFD {1} \
    CONFIG.RX_EQ_MODE {DFE} \
    CONFIG.RX_FLOW_CONTROL {0} \
    CONFIG.RX_GT_BUFFER {1} \
    CONFIG.RX_MAX_PACKET_LEN {9622} \
    CONFIG.TX_FLOW_CONTROL {0} \
    CONFIG.USER_INTERFACE {AXIS} \
  ] $cmac_usplus_0


  # Create instance: hier_cmac_ctrl_stat
  create_hier_cell_hier_cmac_ctrl_stat [current_bd_instance .] hier_cmac_ctrl_stat

  # Create instance: hier_clk_rst
  create_hier_cell_hier_clk_rst [current_bd_instance .] hier_clk_rst

  # Create instance: hier_ilas
  create_hier_cell_hier_ilas [current_bd_instance .] hier_ilas

  # Create interface connections
  connect_bd_intf_net -intf_net axis_tx_0_1 [get_bd_intf_ports tx_axis] [get_bd_intf_pins cmac_usplus_0/axis_tx]
connect_bd_intf_net -intf_net [get_bd_intf_nets axis_tx_0_1] [get_bd_intf_ports tx_axis] [get_bd_intf_pins hier_ilas/SLOT_0_AXIS]
  connect_bd_intf_net -intf_net cmac_usplus_0_axis_rx [get_bd_intf_ports rx_axis] [get_bd_intf_pins cmac_usplus_0/axis_rx]
connect_bd_intf_net -intf_net [get_bd_intf_nets cmac_usplus_0_axis_rx] [get_bd_intf_ports rx_axis] [get_bd_intf_pins hier_ilas/SLOT_0_AXIS1]
  connect_bd_intf_net -intf_net cmac_usplus_0_gt_serial_port [get_bd_intf_ports gt] [get_bd_intf_pins cmac_usplus_0/gt_serial_port]
connect_bd_intf_net -intf_net dcs_even [get_bd_intf_ports dcs_even_mon] [get_bd_intf_pins hier_ilas/SLOT_0_AXI]
connect_bd_intf_net -intf_net dcs_odd [get_bd_intf_ports dcs_odd_mon] [get_bd_intf_pins hier_ilas/SLOT_1_AXI]
  connect_bd_intf_net -intf_net gt_ref_clk_0_1 [get_bd_intf_ports gt_ref_clk] [get_bd_intf_pins cmac_usplus_0/gt_ref_clk]

  # Create port connections
  connect_bd_net -net app_clk_reset_mb_reset  [get_bd_pins hier_clk_rst/app_clk_reset] \
  [get_bd_ports app_clk_reset]
  connect_bd_net -net app_clk_reset_peripheral_aresetn  [get_bd_pins hier_clk_rst/app_clk_resetn] \
  [get_bd_pins hier_ilas/resetn]
  connect_bd_net -net clk_io_2  [get_bd_ports clk_io] \
  [get_bd_pins cmac_usplus_0/gt_drpclk] \
  [get_bd_pins cmac_usplus_0/init_clk] \
  [get_bd_pins cmac_usplus_0/drp_clk] \
  [get_bd_pins hier_clk_rst/clk_io]
  connect_bd_net -net clk_wiz_0_clk_out2  [get_bd_pins hier_clk_rst/app_clk] \
  [get_bd_ports app_clk] \
  [get_bd_pins hier_ilas/app_clk]
  connect_bd_net -net cmac_init_clk_reset_peripheral_reset  [get_bd_pins hier_clk_rst/clk_io_rst] \
  [get_bd_pins cmac_usplus_0/sys_reset]
  connect_bd_net -net cmac_usplus_0_gt_rxusrclk2  [get_bd_pins cmac_usplus_0/gt_rxusrclk2] \
  [get_bd_ports rxclk] \
  [get_bd_pins cmac_usplus_0/rx_clk] \
  [get_bd_pins hier_cmac_ctrl_stat/rxclk] \
  [get_bd_pins hier_clk_rst/rxclk] \
  [get_bd_pins hier_ilas/rxclk]
  connect_bd_net -net cmac_usplus_0_gt_txusrclk2  [get_bd_pins cmac_usplus_0/gt_txusrclk2] \
  [get_bd_ports txclk] \
  [get_bd_pins hier_cmac_ctrl_stat/txclk] \
  [get_bd_pins hier_clk_rst/txclk] \
  [get_bd_pins hier_ilas/txclk]
  connect_bd_net -net cmac_usplus_0_stat_rx_aligned  [get_bd_pins cmac_usplus_0/stat_rx_aligned] \
  [get_bd_pins hier_cmac_ctrl_stat/stat_rx_aligned]
  connect_bd_net -net cmac_usplus_0_stat_rx_local_fault  [get_bd_pins cmac_usplus_0/stat_rx_local_fault] \
  [get_bd_pins hier_cmac_ctrl_stat/stat_rx_local_fault]
  connect_bd_net -net cmac_usplus_0_stat_rx_remote_fault  [get_bd_pins cmac_usplus_0/stat_rx_remote_fault] \
  [get_bd_pins hier_cmac_ctrl_stat/stat_rx_remote_fault]
  connect_bd_net -net cmac_usplus_0_stat_rx_status  [get_bd_pins cmac_usplus_0/stat_rx_status] \
  [get_bd_pins hier_cmac_ctrl_stat/stat_rx_status]
  connect_bd_net -net core0_states_1  [get_bd_ports core0_states] \
  [get_bd_pins hier_ilas/core0_states]
  connect_bd_net -net core1_states_1  [get_bd_ports core1_states] \
  [get_bd_pins hier_ilas/core1_states]
  connect_bd_net -net core2_states_1  [get_bd_ports core2_states] \
  [get_bd_pins hier_ilas/core2_states]
  connect_bd_net -net core3_states_1  [get_bd_ports core3_states] \
  [get_bd_pins hier_ilas/core3_states]
  connect_bd_net -net core4_states_1  [get_bd_ports core4_states] \
  [get_bd_pins hier_ilas/core4_states]
  connect_bd_net -net core_rx_reset  [get_bd_pins hier_cmac_ctrl_stat/core_rx_reset] \
  [get_bd_pins cmac_usplus_0/core_rx_reset]
  connect_bd_net -net core_tx_reset  [get_bd_pins hier_cmac_ctrl_stat/core_tx_reset] \
  [get_bd_pins cmac_usplus_0/core_tx_reset]
  connect_bd_net -net dcs_even_trace_0_1  [get_bd_ports dcs_even_trace_0] \
  [get_bd_pins hier_ilas/dcs_even_trace_0]
  connect_bd_net -net dcs_even_trace_1_1  [get_bd_ports dcs_even_trace_1] \
  [get_bd_pins hier_ilas/dcs_even_trace_1]
  connect_bd_net -net dcs_odd_trace_0_1  [get_bd_ports dcs_odd_trace_0] \
  [get_bd_pins hier_ilas/dcs_odd_trace_0]
  connect_bd_net -net dcs_odd_trace_1_1  [get_bd_ports dcs_odd_trace_1] \
  [get_bd_pins hier_ilas/dcs_odd_trace_1]
  connect_bd_net -net gt_loopback_in  [get_bd_pins hier_cmac_ctrl_stat/gt_loopback_in] \
  [get_bd_pins cmac_usplus_0/gt_loopback_in]
  connect_bd_net -net gtwiz_reset_rx_datapath  [get_bd_pins hier_cmac_ctrl_stat/gtwiz_reset_rx_datapath] \
  [get_bd_pins cmac_usplus_0/gtwiz_reset_rx_datapath]
  connect_bd_net -net gtwiz_reset_tx_datapath  [get_bd_pins hier_cmac_ctrl_stat/gtwiz_reset_tx_datapath] \
  [get_bd_pins cmac_usplus_0/gtwiz_reset_tx_datapath]
  connect_bd_net -net hier_clk_rst_dout  [get_bd_pins hier_clk_rst/no_rst] \
  [get_bd_pins cmac_usplus_0/core_drp_reset]
  connect_bd_net -net ilconstant_2_dout  [get_bd_pins hier_cmac_ctrl_stat/hi] \
  [get_bd_pins cmac_usplus_0/ctl_rx_enable] \
  [get_bd_pins cmac_usplus_0/ctl_rsfec_ieee_error_indication_mode] \
  [get_bd_pins cmac_usplus_0/ctl_rx_rsfec_enable] \
  [get_bd_pins cmac_usplus_0/ctl_rx_rsfec_enable_correction] \
  [get_bd_pins cmac_usplus_0/ctl_rx_rsfec_enable_indication] \
  [get_bd_pins cmac_usplus_0/ctl_tx_rsfec_enable]
  connect_bd_net -net lci_even_1  [get_bd_ports lci_even] \
  [get_bd_pins hier_ilas/lci_even]
  connect_bd_net -net lci_odd_1  [get_bd_ports lci_odd] \
  [get_bd_pins hier_ilas/lci_odd]
  connect_bd_net -net lcia_even_1  [get_bd_ports lcia_even] \
  [get_bd_pins hier_ilas/lcia_even]
  connect_bd_net -net lcia_odd_1  [get_bd_ports lcia_odd] \
  [get_bd_pins hier_ilas/lcia_odd]
  connect_bd_net -net reset_sys_1  [get_bd_ports reset] \
  [get_bd_pins hier_clk_rst/reset]
  connect_bd_net -net rst_cmac_usplus_0_322M_1_peripheral_aresetn  [get_bd_pins hier_clk_rst/txclk_rstn] \
  [get_bd_pins hier_ilas/resetn1]
  connect_bd_net -net rst_cmac_usplus_0_322M_peripheral_aresetn  [get_bd_pins hier_clk_rst/rxclk_rstn] \
  [get_bd_pins hier_ilas/resetn2]
  connect_bd_net -net ul_even_1  [get_bd_ports ul_even] \
  [get_bd_pins hier_ilas/ul_even]
  connect_bd_net -net ul_odd_1  [get_bd_ports ul_odd] \
  [get_bd_pins hier_ilas/ul_odd]
  connect_bd_net -net xlconstant_0_dout  [get_bd_pins hier_cmac_ctrl_stat/gt_polarity] \
  [get_bd_pins cmac_usplus_0/gt_rxpolarity] \
  [get_bd_pins cmac_usplus_0/gt_txpolarity]
  connect_bd_net -net xpm_cdc_gen_1_dest_out  [get_bd_pins hier_cmac_ctrl_stat/stat_rx_aligned_txclk] \
  [get_bd_pins cmac_usplus_0/ctl_tx_enable]
  connect_bd_net -net xpm_cdc_gen_2_dest_out  [get_bd_pins hier_cmac_ctrl_stat/stat_rx_local_fault_txclk] \
  [get_bd_pins cmac_usplus_0/ctl_tx_send_rfi]
  connect_bd_net -net xpm_cdc_gen_3_dest_out  [get_bd_pins hier_cmac_ctrl_stat/stat_rx_remote_fault_txclk] \
  [get_bd_pins cmac_usplus_0/ctl_tx_send_idle]

  # Create address segments


  # Restore current instance
  current_bd_instance $oldCurInst

  validate_bd_design
  save_bd_design
}
# End of create_root_design()


##################################################################
# MAIN FLOW
##################################################################

create_root_design ""


