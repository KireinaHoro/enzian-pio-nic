
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
set scripts_vivado_version 2023.2
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
xilinx.com:ip:proc_sys_reset:5.0\
xilinx.com:ip:clk_wiz:6.0\
xilinx.com:ip:cmac_usplus:3.1\
xilinx.com:ip:xlconstant:1.1\
xilinx.com:ip:system_ila:1.1\
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

  set cmac_regs_axil [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:aximm_rtl:1.0 cmac_regs_axil ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {32} \
   CONFIG.ARUSER_WIDTH {0} \
   CONFIG.AWUSER_WIDTH {0} \
   CONFIG.BUSER_WIDTH {0} \
   CONFIG.DATA_WIDTH {32} \
   CONFIG.HAS_BRESP {1} \
   CONFIG.HAS_BURST {0} \
   CONFIG.HAS_CACHE {0} \
   CONFIG.HAS_LOCK {0} \
   CONFIG.HAS_PROT {0} \
   CONFIG.HAS_QOS {0} \
   CONFIG.HAS_REGION {0} \
   CONFIG.HAS_RRESP {1} \
   CONFIG.HAS_WSTRB {1} \
   CONFIG.ID_WIDTH {0} \
   CONFIG.MAX_BURST_LENGTH {1} \
   CONFIG.NUM_READ_OUTSTANDING {1} \
   CONFIG.NUM_READ_THREADS {1} \
   CONFIG.NUM_WRITE_OUTSTANDING {1} \
   CONFIG.NUM_WRITE_THREADS {1} \
   CONFIG.PROTOCOL {AXI4LITE} \
   CONFIG.READ_WRITE_MODE {READ_WRITE} \
   CONFIG.RUSER_BITS_PER_BYTE {0} \
   CONFIG.RUSER_WIDTH {0} \
   CONFIG.SUPPORTS_NARROW_BURST {0} \
   CONFIG.WUSER_BITS_PER_BYTE {0} \
   CONFIG.WUSER_WIDTH {0} \
   ] $cmac_regs_axil

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

  set dcs_odd_axi [ create_bd_intf_port -mode Monitor -vlnv xilinx.com:interface:aximm_rtl:1.0 dcs_odd_axi ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {38} \
   CONFIG.ARUSER_WIDTH {0} \
   CONFIG.DATA_WIDTH {512} \
   CONFIG.HAS_QOS {0} \
   CONFIG.HAS_REGION {0} \
   CONFIG.ID_WIDTH {7} \
   CONFIG.PROTOCOL {AXI4} \
   ] $dcs_odd_axi

  set dcs_even_axi [ create_bd_intf_port -mode Monitor -vlnv xilinx.com:interface:aximm_rtl:1.0 dcs_even_axi ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {38} \
   CONFIG.ARUSER_WIDTH {0} \
   CONFIG.DATA_WIDTH {512} \
   CONFIG.HAS_QOS {0} \
   CONFIG.HAS_REGION {0} \
   CONFIG.ID_WIDTH {7} \
   CONFIG.PROTOCOL {AXI4} \
   ] $dcs_even_axi

  set shell_io_axil [ create_bd_intf_port -mode Monitor -vlnv xilinx.com:interface:aximm_rtl:1.0 shell_io_axil ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {44} \
   CONFIG.DATA_WIDTH {64} \
   CONFIG.HAS_BURST {0} \
   CONFIG.HAS_CACHE {0} \
   CONFIG.HAS_LOCK {0} \
   CONFIG.HAS_QOS {0} \
   CONFIG.HAS_REGION {0} \
   CONFIG.MAX_BURST_LENGTH {1} \
   CONFIG.PROTOCOL {AXI4LITE} \
   ] $shell_io_axil


  # Create ports
  set app_clk [ create_bd_port -dir O -type clk app_clk ]
  set_property -dict [ list \
   CONFIG.ASSOCIATED_BUSIF {cmac_regs_axil:shell_io_axil:dcs_even_axi:dcs_odd_axi} \
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

  set dcsEven_cleanMaybeInvReq_valid [ create_bd_port -dir I -type data dcsEven_cleanMaybeInvReq_valid ]
  set dcsEven_cleanMaybeInvReq_ready [ create_bd_port -dir I -type data dcsEven_cleanMaybeInvReq_ready ]
  set dcsEven_cleanMaybeInvReq_payload_data [ create_bd_port -dir I -from 63 -to 0 -type data dcsEven_cleanMaybeInvReq_payload_data ]
  set dcsEven_cleanMaybeInvReq_payload_size [ create_bd_port -dir I -from 4 -to 0 -type data dcsEven_cleanMaybeInvReq_payload_size ]
  set dcsEven_cleanMaybeInvReq_payload_vc [ create_bd_port -dir I -from 4 -to 0 -type data dcsEven_cleanMaybeInvReq_payload_vc ]
  set dcsEven_cleanMaybeInvResp_valid [ create_bd_port -dir I -type data dcsEven_cleanMaybeInvResp_valid ]
  set dcsEven_cleanMaybeInvResp_ready [ create_bd_port -dir I -type data dcsEven_cleanMaybeInvResp_ready ]
  set dcsEven_cleanMaybeInvResp_payload_data [ create_bd_port -dir I -from 63 -to 0 -type data dcsEven_cleanMaybeInvResp_payload_data ]
  set dcsEven_cleanMaybeInvResp_payload_size [ create_bd_port -dir I -from 4 -to 0 -type data dcsEven_cleanMaybeInvResp_payload_size ]
  set dcsEven_cleanMaybeInvResp_payload_vc [ create_bd_port -dir I -from 4 -to 0 -type data dcsEven_cleanMaybeInvResp_payload_vc ]
  set dcsEven_unlockResp_valid [ create_bd_port -dir I -type data dcsEven_unlockResp_valid ]
  set dcsEven_unlockResp_ready [ create_bd_port -dir I -type data dcsEven_unlockResp_ready ]
  set dcsEven_unlockResp_payload_data [ create_bd_port -dir I -from 63 -to 0 -type data dcsEven_unlockResp_payload_data ]
  set dcsEven_unlockResp_payload_size [ create_bd_port -dir I -from 4 -to 0 -type data dcsEven_unlockResp_payload_size ]
  set dcsEven_unlockResp_payload_vc [ create_bd_port -dir I -from 4 -to 0 -type data dcsEven_unlockResp_payload_vc ]
  set dcsOdd_cleanMaybeInvReq_valid [ create_bd_port -dir I -type data dcsOdd_cleanMaybeInvReq_valid ]
  set dcsOdd_cleanMaybeInvReq_ready [ create_bd_port -dir I -type data dcsOdd_cleanMaybeInvReq_ready ]
  set dcsOdd_cleanMaybeInvReq_payload_data [ create_bd_port -dir I -from 63 -to 0 -type data dcsOdd_cleanMaybeInvReq_payload_data ]
  set dcsOdd_cleanMaybeInvReq_payload_size [ create_bd_port -dir I -from 4 -to 0 -type data dcsOdd_cleanMaybeInvReq_payload_size ]
  set dcsOdd_cleanMaybeInvReq_payload_vc [ create_bd_port -dir I -from 4 -to 0 -type data dcsOdd_cleanMaybeInvReq_payload_vc ]
  set dcsOdd_cleanMaybeInvResp_valid [ create_bd_port -dir I -type data dcsOdd_cleanMaybeInvResp_valid ]
  set dcsOdd_cleanMaybeInvResp_ready [ create_bd_port -dir I -type data dcsOdd_cleanMaybeInvResp_ready ]
  set dcsOdd_cleanMaybeInvResp_payload_data [ create_bd_port -dir I -from 63 -to 0 -type data dcsOdd_cleanMaybeInvResp_payload_data ]
  set dcsOdd_cleanMaybeInvResp_payload_size [ create_bd_port -dir I -from 4 -to 0 -type data dcsOdd_cleanMaybeInvResp_payload_size ]
  set dcsOdd_cleanMaybeInvResp_payload_vc [ create_bd_port -dir I -from 4 -to 0 -type data dcsOdd_cleanMaybeInvResp_payload_vc ]
  set dcsOdd_unlockResp_valid [ create_bd_port -dir I -type data dcsOdd_unlockResp_valid ]
  set dcsOdd_unlockResp_ready [ create_bd_port -dir I -type data dcsOdd_unlockResp_ready ]
  set dcsOdd_unlockResp_payload_data [ create_bd_port -dir I -from 63 -to 0 -type data dcsOdd_unlockResp_payload_data ]
  set dcsOdd_unlockResp_payload_size [ create_bd_port -dir I -from 4 -to 0 -type data dcsOdd_unlockResp_payload_size ]
  set dcsOdd_unlockResp_payload_vc [ create_bd_port -dir I -from 4 -to 0 -type data dcsOdd_unlockResp_payload_vc ]
  set txFsm_0_stateReg [ create_bd_port -dir I -from 2 -to 0 -type data txFsm_0_stateReg ]
  set rxFsm_0_stateReg [ create_bd_port -dir I -from 2 -to 0 -type data rxFsm_0_stateReg ]
  set txFsm_1_stateReg [ create_bd_port -dir I -from 2 -to 0 -type data txFsm_1_stateReg ]
  set rxFsm_1_stateReg [ create_bd_port -dir I -from 2 -to 0 -type data rxFsm_1_stateReg ]
  set txFsm_2_stateReg [ create_bd_port -dir I -from 2 -to 0 -type data txFsm_2_stateReg ]
  set rxFsm_2_stateReg [ create_bd_port -dir I -from 2 -to 0 -type data rxFsm_2_stateReg ]
  set txFsm_3_stateReg [ create_bd_port -dir I -from 2 -to 0 -type data txFsm_3_stateReg ]
  set rxFsm_3_stateReg [ create_bd_port -dir I -from 2 -to 0 -type data rxFsm_3_stateReg ]
  set cycles_bits [ create_bd_port -dir I -from 63 -to 0 -type data cycles_bits ]
  set rxCurrClIdx_0 [ create_bd_port -dir I -type data rxCurrClIdx_0 ]
  set txCurrClIdx_0 [ create_bd_port -dir I -type data txCurrClIdx_0 ]
  set rxCurrClIdx_1 [ create_bd_port -dir I -type data rxCurrClIdx_1 ]
  set txCurrClIdx_1 [ create_bd_port -dir I -type data txCurrClIdx_1 ]
  set rxCurrClIdx_2 [ create_bd_port -dir I -type data rxCurrClIdx_2 ]
  set txCurrClIdx_2 [ create_bd_port -dir I -type data txCurrClIdx_2 ]
  set rxCurrClIdx_3 [ create_bd_port -dir I -type data rxCurrClIdx_3 ]
  set txCurrClIdx_3 [ create_bd_port -dir I -type data txCurrClIdx_3 ]

  # Create instance: app_clk_reset, and set properties
  set app_clk_reset [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 app_clk_reset ]

  # Create instance: clk_wiz_0, and set properties
  set clk_wiz_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0 ]
  set_property -dict [list \
    CONFIG.CLKOUT1_JITTER {98.427} \
    CONFIG.CLKOUT1_PHASE_ERROR {87.466} \
    CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {250} \
    CONFIG.CLKOUT2_JITTER {130.958} \
    CONFIG.CLKOUT2_PHASE_ERROR {98.575} \
    CONFIG.CLKOUT2_USED {false} \
    CONFIG.MMCM_CLKFBOUT_MULT_F {11.875} \
    CONFIG.MMCM_CLKOUT0_DIVIDE_F {4.750} \
    CONFIG.MMCM_CLKOUT1_DIVIDE {1} \
    CONFIG.MMCM_DIVCLK_DIVIDE {1} \
    CONFIG.NUM_OUT_CLKS {1} \
    CONFIG.OPTIMIZE_CLOCKING_STRUCTURE_EN {true} \
    CONFIG.PRIM_SOURCE {No_buffer} \
    CONFIG.USE_LOCKED {false} \
    CONFIG.USE_RESET {false} \
  ] $clk_wiz_0


  # Create instance: cmac_init_clk_reset, and set properties
  set cmac_init_clk_reset [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 cmac_init_clk_reset ]

  # Create instance: cmac_usplus_0, and set properties
  set cmac_usplus_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:cmac_usplus:3.1 cmac_usplus_0 ]
  set_property -dict [list \
    CONFIG.ADD_GT_CNRL_STS_PORTS {1} \
    CONFIG.CMAC_CAUI4_MODE {1} \
    CONFIG.CMAC_CORE_SELECT {CMACE4_X0Y3} \
    CONFIG.ENABLE_AXI_INTERFACE {1} \
    CONFIG.GT_DRP_CLK {100} \
    CONFIG.GT_GROUP_SELECT {X0Y20~X0Y23} \
    CONFIG.GT_REF_CLK_FREQ {322.265625} \
    CONFIG.INCLUDE_STATISTICS_COUNTERS {1} \
    CONFIG.NUM_LANES {4x25} \
    CONFIG.RX_FLOW_CONTROL {0} \
    CONFIG.RX_GT_BUFFER {1} \
    CONFIG.RX_MAX_PACKET_LEN {9622} \
    CONFIG.TX_FLOW_CONTROL {0} \
    CONFIG.USER_INTERFACE {AXIS} \
  ] $cmac_usplus_0


  # Create instance: xlconstant_0, and set properties
  set xlconstant_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0 ]
  set_property -dict [list \
    CONFIG.CONST_VAL {0b0011} \
    CONFIG.CONST_WIDTH {4} \
  ] $xlconstant_0


  # Create instance: system_ila_0, and set properties
  set system_ila_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:system_ila:1.1 system_ila_0 ]
  set_property -dict [list \
    CONFIG.C_INPUT_PIPE_STAGES {3} \
    CONFIG.C_MON_TYPE {MIX} \
    CONFIG.C_NUM_MONITOR_SLOTS {4} \
    CONFIG.C_NUM_OF_PROBES {47} \
    CONFIG.C_SLOT {3} \
    CONFIG.C_SLOT_0_AXI_ADDR_WIDTH {44} \
    CONFIG.C_SLOT_0_AXI_DATA_WIDTH {64} \
    CONFIG.C_SLOT_0_AXI_ID_WIDTH {0} \
    CONFIG.C_SLOT_1_AXI_ADDR_WIDTH {38} \
    CONFIG.C_SLOT_1_AXI_DATA_WIDTH {512} \
    CONFIG.C_SLOT_1_AXI_ID_WIDTH {7} \
    CONFIG.C_SLOT_1_MAX_RD_BURSTS {16} \
    CONFIG.C_SLOT_1_MAX_WR_BURSTS {16} \
    CONFIG.C_SLOT_2_AXI_ADDR_WIDTH {38} \
    CONFIG.C_SLOT_2_AXI_DATA_WIDTH {512} \
    CONFIG.C_SLOT_2_AXI_ID_WIDTH {7} \
    CONFIG.C_SLOT_2_MAX_RD_BURSTS {16} \
    CONFIG.C_SLOT_2_MAX_WR_BURSTS {16} \
  ] $system_ila_0


  # Create interface connections
  connect_bd_intf_net -intf_net CMAC_REGS_AXIL [get_bd_intf_ports cmac_regs_axil] [get_bd_intf_pins cmac_usplus_0/s_axi]
connect_bd_intf_net -intf_net [get_bd_intf_nets CMAC_REGS_AXIL] [get_bd_intf_ports cmac_regs_axil] [get_bd_intf_pins system_ila_0/SLOT_3_AXI]
connect_bd_intf_net -intf_net DCS_EVEN_AXI [get_bd_intf_ports dcs_even_axi] [get_bd_intf_pins system_ila_0/SLOT_1_AXI]
connect_bd_intf_net -intf_net DCS_ODD_AXI [get_bd_intf_ports dcs_odd_axi] [get_bd_intf_pins system_ila_0/SLOT_2_AXI]
connect_bd_intf_net -intf_net SHELL_IO_AXIL [get_bd_intf_ports shell_io_axil] [get_bd_intf_pins system_ila_0/SLOT_0_AXI]
  connect_bd_intf_net -intf_net axis_tx_0_1 [get_bd_intf_ports tx_axis] [get_bd_intf_pins cmac_usplus_0/axis_tx]
  connect_bd_intf_net -intf_net cmac_usplus_0_axis_rx [get_bd_intf_ports rx_axis] [get_bd_intf_pins cmac_usplus_0/axis_rx]
  connect_bd_intf_net -intf_net cmac_usplus_0_gt_serial_port [get_bd_intf_ports gt] [get_bd_intf_pins cmac_usplus_0/gt_serial_port]
  connect_bd_intf_net -intf_net gt_ref_clk_0_1 [get_bd_intf_ports gt_ref_clk] [get_bd_intf_pins cmac_usplus_0/gt_ref_clk]

  # Create port connections
  connect_bd_net -net app_clk_reset_bus_struct_reset [get_bd_pins app_clk_reset/bus_struct_reset] [get_bd_pins cmac_usplus_0/s_axi_sreset]
  connect_bd_net -net app_clk_reset_mb_reset [get_bd_pins app_clk_reset/mb_reset] [get_bd_ports app_clk_reset]
  connect_bd_net -net app_clk_reset_peripheral_aresetn [get_bd_pins app_clk_reset/peripheral_aresetn] [get_bd_pins system_ila_0/resetn]
  connect_bd_net -net clk_io_2 [get_bd_ports clk_io] [get_bd_pins clk_wiz_0/clk_in1] [get_bd_pins cmac_init_clk_reset/slowest_sync_clk] [get_bd_pins cmac_usplus_0/gt_drpclk] [get_bd_pins cmac_usplus_0/init_clk] [get_bd_pins cmac_usplus_0/drp_clk]
  connect_bd_net -net clk_wiz_0_clk_out2 [get_bd_pins clk_wiz_0/clk_out1] [get_bd_ports app_clk] [get_bd_pins app_clk_reset/slowest_sync_clk] [get_bd_pins cmac_usplus_0/s_axi_aclk] [get_bd_pins system_ila_0/clk]
  connect_bd_net -net cmac_init_clk_reset_peripheral_reset [get_bd_pins cmac_init_clk_reset/peripheral_reset] [get_bd_pins cmac_usplus_0/sys_reset]
  connect_bd_net -net cmac_usplus_0_gt_rxusrclk2 [get_bd_pins cmac_usplus_0/gt_rxusrclk2] [get_bd_ports rxclk] [get_bd_pins cmac_usplus_0/rx_clk]
  connect_bd_net -net cmac_usplus_0_gt_txusrclk2 [get_bd_pins cmac_usplus_0/gt_txusrclk2] [get_bd_ports txclk]
  connect_bd_net -net cycles_bits_1 [get_bd_ports cycles_bits] [get_bd_pins system_ila_0/probe38]
  connect_bd_net -net dcsEven_cleanMaybeInvReq_payload_data_1 [get_bd_ports dcsEven_cleanMaybeInvReq_payload_data] [get_bd_pins system_ila_0/probe2]
  connect_bd_net -net dcsEven_cleanMaybeInvReq_payload_size_1 [get_bd_ports dcsEven_cleanMaybeInvReq_payload_size] [get_bd_pins system_ila_0/probe3]
  connect_bd_net -net dcsEven_cleanMaybeInvReq_payload_vc_1 [get_bd_ports dcsEven_cleanMaybeInvReq_payload_vc] [get_bd_pins system_ila_0/probe4]
  connect_bd_net -net dcsEven_cleanMaybeInvReq_ready_1 [get_bd_ports dcsEven_cleanMaybeInvReq_ready] [get_bd_pins system_ila_0/probe1]
  connect_bd_net -net dcsEven_cleanMaybeInvReq_valid_1 [get_bd_ports dcsEven_cleanMaybeInvReq_valid] [get_bd_pins system_ila_0/probe0]
  connect_bd_net -net dcsEven_cleanMaybeInvResp_payload_data_1 [get_bd_ports dcsEven_cleanMaybeInvResp_payload_data] [get_bd_pins system_ila_0/probe7]
  connect_bd_net -net dcsEven_cleanMaybeInvResp_payload_size_1 [get_bd_ports dcsEven_cleanMaybeInvResp_payload_size] [get_bd_pins system_ila_0/probe8]
  connect_bd_net -net dcsEven_cleanMaybeInvResp_payload_vc_1 [get_bd_ports dcsEven_cleanMaybeInvResp_payload_vc] [get_bd_pins system_ila_0/probe9]
  connect_bd_net -net dcsEven_cleanMaybeInvResp_ready_1 [get_bd_ports dcsEven_cleanMaybeInvResp_ready] [get_bd_pins system_ila_0/probe6]
  connect_bd_net -net dcsEven_cleanMaybeInvResp_valid_1 [get_bd_ports dcsEven_cleanMaybeInvResp_valid] [get_bd_pins system_ila_0/probe5]
  connect_bd_net -net dcsEven_unlockResp_payload_data_1 [get_bd_ports dcsEven_unlockResp_payload_data] [get_bd_pins system_ila_0/probe12]
  connect_bd_net -net dcsEven_unlockResp_payload_size_1 [get_bd_ports dcsEven_unlockResp_payload_size] [get_bd_pins system_ila_0/probe13]
  connect_bd_net -net dcsEven_unlockResp_payload_vc_1 [get_bd_ports dcsEven_unlockResp_payload_vc] [get_bd_pins system_ila_0/probe14]
  connect_bd_net -net dcsEven_unlockResp_ready_1 [get_bd_ports dcsEven_unlockResp_ready] [get_bd_pins system_ila_0/probe11]
  connect_bd_net -net dcsEven_unlockResp_valid_1 [get_bd_ports dcsEven_unlockResp_valid] [get_bd_pins system_ila_0/probe10]
  connect_bd_net -net dcsOdd_cleanMaybeInvReq_payload_data_1 [get_bd_ports dcsOdd_cleanMaybeInvReq_payload_data] [get_bd_pins system_ila_0/probe17]
  connect_bd_net -net dcsOdd_cleanMaybeInvReq_payload_size_1 [get_bd_ports dcsOdd_cleanMaybeInvReq_payload_size] [get_bd_pins system_ila_0/probe18]
  connect_bd_net -net dcsOdd_cleanMaybeInvReq_payload_vc_1 [get_bd_ports dcsOdd_cleanMaybeInvReq_payload_vc] [get_bd_pins system_ila_0/probe19]
  connect_bd_net -net dcsOdd_cleanMaybeInvReq_ready_1 [get_bd_ports dcsOdd_cleanMaybeInvReq_ready] [get_bd_pins system_ila_0/probe16]
  connect_bd_net -net dcsOdd_cleanMaybeInvReq_valid_1 [get_bd_ports dcsOdd_cleanMaybeInvReq_valid] [get_bd_pins system_ila_0/probe15]
  connect_bd_net -net dcsOdd_cleanMaybeInvResp_payload_data_1 [get_bd_ports dcsOdd_cleanMaybeInvResp_payload_data] [get_bd_pins system_ila_0/probe22]
  connect_bd_net -net dcsOdd_cleanMaybeInvResp_payload_size_1 [get_bd_ports dcsOdd_cleanMaybeInvResp_payload_size] [get_bd_pins system_ila_0/probe23]
  connect_bd_net -net dcsOdd_cleanMaybeInvResp_payload_vc_1 [get_bd_ports dcsOdd_cleanMaybeInvResp_payload_vc] [get_bd_pins system_ila_0/probe24]
  connect_bd_net -net dcsOdd_cleanMaybeInvResp_ready_1 [get_bd_ports dcsOdd_cleanMaybeInvResp_ready] [get_bd_pins system_ila_0/probe21]
  connect_bd_net -net dcsOdd_cleanMaybeInvResp_valid_1 [get_bd_ports dcsOdd_cleanMaybeInvResp_valid] [get_bd_pins system_ila_0/probe20]
  connect_bd_net -net dcsOdd_unlockResp_payload_data_1 [get_bd_ports dcsOdd_unlockResp_payload_data] [get_bd_pins system_ila_0/probe27]
  connect_bd_net -net dcsOdd_unlockResp_payload_size_1 [get_bd_ports dcsOdd_unlockResp_payload_size] [get_bd_pins system_ila_0/probe28]
  connect_bd_net -net dcsOdd_unlockResp_payload_vc_1 [get_bd_ports dcsOdd_unlockResp_payload_vc] [get_bd_pins system_ila_0/probe29]
  connect_bd_net -net dcsOdd_unlockResp_ready_1 [get_bd_ports dcsOdd_unlockResp_ready] [get_bd_pins system_ila_0/probe26]
  connect_bd_net -net dcsOdd_unlockResp_valid_1 [get_bd_ports dcsOdd_unlockResp_valid] [get_bd_pins system_ila_0/probe25]
  connect_bd_net -net reset_sys_1 [get_bd_ports reset] [get_bd_pins app_clk_reset/ext_reset_in] [get_bd_pins cmac_init_clk_reset/ext_reset_in]
  connect_bd_net -net rxCurrClIdx_0_1 [get_bd_ports rxCurrClIdx_0] [get_bd_pins system_ila_0/probe39]
  connect_bd_net -net rxCurrClIdx_1_1 [get_bd_ports rxCurrClIdx_1] [get_bd_pins system_ila_0/probe41]
  connect_bd_net -net rxCurrClIdx_2_1 [get_bd_ports rxCurrClIdx_2] [get_bd_pins system_ila_0/probe43]
  connect_bd_net -net rxCurrClIdx_3_1 [get_bd_ports rxCurrClIdx_3] [get_bd_pins system_ila_0/probe45]
  connect_bd_net -net rxFsm_0_stateReg_1 [get_bd_ports rxFsm_0_stateReg] [get_bd_pins system_ila_0/probe30]
  connect_bd_net -net rxFsm_1_stateReg_1 [get_bd_ports rxFsm_1_stateReg] [get_bd_pins system_ila_0/probe32]
  connect_bd_net -net rxFsm_2_stateReg_1 [get_bd_ports rxFsm_2_stateReg] [get_bd_pins system_ila_0/probe34]
  connect_bd_net -net rxFsm_3_stateReg_1 [get_bd_ports rxFsm_3_stateReg] [get_bd_pins system_ila_0/probe36]
  connect_bd_net -net txCurrClIdx_0_1 [get_bd_ports txCurrClIdx_0] [get_bd_pins system_ila_0/probe40]
  connect_bd_net -net txCurrClIdx_1_1 [get_bd_ports txCurrClIdx_1] [get_bd_pins system_ila_0/probe42]
  connect_bd_net -net txCurrClIdx_2_1 [get_bd_ports txCurrClIdx_2] [get_bd_pins system_ila_0/probe44]
  connect_bd_net -net txCurrClIdx_3_1 [get_bd_ports txCurrClIdx_3] [get_bd_pins system_ila_0/probe46]
  connect_bd_net -net txFsm_0_stateReg_1 [get_bd_ports txFsm_0_stateReg] [get_bd_pins system_ila_0/probe31]
  connect_bd_net -net txFsm_1_stateReg_1 [get_bd_ports txFsm_1_stateReg] [get_bd_pins system_ila_0/probe33]
  connect_bd_net -net txFsm_2_stateReg_1 [get_bd_ports txFsm_2_stateReg] [get_bd_pins system_ila_0/probe35]
  connect_bd_net -net txFsm_3_stateReg_1 [get_bd_ports txFsm_3_stateReg] [get_bd_pins system_ila_0/probe37]
  connect_bd_net -net xlconstant_0_dout [get_bd_pins xlconstant_0/dout] [get_bd_pins cmac_usplus_0/gt_rxpolarity] [get_bd_pins cmac_usplus_0/gt_txpolarity]

  # Create address segments
  assign_bd_address -offset 0x00000000 -range 0x00010000 -target_address_space [get_bd_addr_spaces cmac_regs_axil] [get_bd_addr_segs cmac_usplus_0/s_axi/Reg] -force


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


