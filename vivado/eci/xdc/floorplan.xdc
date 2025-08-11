# create pblocks for SLRs in the dynamic region
# region defs adapted from static shell definition
create_pblock pblock_slr2
resize_pblock [get_pblocks pblock_slr2] -add {CLOCKREGION_X0Y14:CLOCKREGION_X5Y14 CLOCKREGION_X0Y11:CLOCKREGION_X4Y13 CLOCKREGION_X0Y10:CLOCKREGION_X3Y10}
resize_pblock [get_pblocks pblock_slr2] -add {SLICE_X142Y720:SLICE_X168Y839 SLICE_X112Y630:SLICE_X168Y659 SLICE_X155Y600:SLICE_X168Y629}
resize_pblock [get_pblocks pblock_slr2] -add {CFGIO_SITE_X0Y2:CFGIO_SITE_X0Y2}
resize_pblock [get_pblocks pblock_slr2] -add {DSP48E2_X17Y288:DSP48E2_X18Y335 DSP48E2_X18Y240:DSP48E2_X18Y263 DSP48E2_X14Y252:DSP48E2_X17Y263}
resize_pblock [get_pblocks pblock_slr2] -add {GTYE4_CHANNEL_X1Y40:GTYE4_CHANNEL_X1Y43}
resize_pblock [get_pblocks pblock_slr2] -add {GTYE4_COMMON_X1Y10:GTYE4_COMMON_X1Y10}
resize_pblock [get_pblocks pblock_slr2] -add {ILKNE4_X1Y7:ILKNE4_X1Y7}
resize_pblock [get_pblocks pblock_slr2] -add {IOB_X1Y546:IOB_X1Y571}
resize_pblock [get_pblocks pblock_slr2] -add {LAGUNA_X22Y480:LAGUNA_X23Y599 LAGUNA_X16Y540:LAGUNA_X21Y599}
resize_pblock [get_pblocks pblock_slr2] -add {PCIE40E4_X1Y4:PCIE40E4_X1Y4}
resize_pblock [get_pblocks pblock_slr2] -add {RAMB18_X10Y288:RAMB18_X11Y335 RAMB18_X11Y240:RAMB18_X11Y263 RAMB18_X8Y252:RAMB18_X10Y263}
resize_pblock [get_pblocks pblock_slr2] -add {RAMB36_X10Y144:RAMB36_X11Y167 RAMB36_X11Y120:RAMB36_X11Y131 RAMB36_X8Y126:RAMB36_X10Y131}
resize_pblock [get_pblocks pblock_slr2] -add {SYSMONE4_X0Y2:SYSMONE4_X0Y2}
resize_pblock [get_pblocks pblock_slr2] -add {URAM288_X3Y168:URAM288_X3Y175}

create_pblock pblock_slr1
resize_pblock [get_pblocks pblock_slr1] -add {CLOCKREGION_X0Y9:CLOCKREGION_X5Y9 CLOCKREGION_X0Y6:CLOCKREGION_X4Y8 CLOCKREGION_X0Y5:CLOCKREGION_X5Y5}
create_pblock pblock_dcs_odd
resize_pblock [get_pblocks pblock_dcs_odd] -add {CLOCKREGION_X0Y5:CLOCKREGION_X3Y9}
create_pblock pblock_eci_gateway
resize_pblock [get_pblocks pblock_dcs_odd] -add {CLOCKREGION_X2Y5:CLOCKREGION_X5Y5 CLOCKREGION_X2Y6:CLOCKREGION_X4Y8 CLOCKREGION_X2Y9:CLOCKREGION_X5Y9}

create_pblock pblock_slr0
resize_pblock [get_pblocks pblock_slr0] -add {CLOCKREGION_X0Y0:CLOCKREGION_X5Y4}

set nic_engine_plock   [get_pblocks pblock_slr0]
set dcs_even_pblock    [get_pblocks pblock_slr2]
set dcs_odd_pblock     [get_pblocks pblock_dcs_odd]
set eci_gateway_pblock [get_pblocks pblock_eci_gateway]

# assign two DCS to top and bottom SLR slices
add_cells_to_pblock $dcs_even_pblock [get_cells [list \
  i_app/dcs_even/i_dcs \
  i_app/dcs_even/i_chan_cdc_req_wod_slave \
  i_app/dcs_even/i_chan_cdc_rsp_wod_slave \
  i_app/dcs_even/i_chan_cdc_rsp_wd_slave \
  i_app/dcs_even/i_chan_cdc_rsp_wod_master \
  i_app/dcs_even/i_chan_cdc_rsp_wd_master \
  i_app/dcs_even/i_chan_cdc_fwd_wod_master \
  i_app/dcs_even/i_app_rst_sync \
  i_app/dcs_even/i_eci_rst_sync \
]]

add_cells_to_pblock $dcs_odd_pblock [get_cells [list \
  i_app/dcs_odd/i_dcs \
  i_app/dcs_odd/i_chan_cdc_req_wod_slave \
  i_app/dcs_odd/i_chan_cdc_rsp_wod_slave \
  i_app/dcs_odd/i_chan_cdc_rsp_wd_slave \
  i_app/dcs_odd/i_chan_cdc_rsp_wod_master \
  i_app/dcs_odd/i_chan_cdc_rsp_wd_master \
  i_app/dcs_odd/i_chan_cdc_fwd_wod_master \
  i_app/dcs_odd/i_app_rst_sync \
  i_app/dcs_odd/i_eci_rst_sync \
]]

# SI/MI constraints for pipelining reg slices between DCS and ECI gateway
add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_eci_*_slave/*slr_auto_dest*}]
add_cells_to_pblock $eci_gateway_pblock [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_eci_*_slave/*slr_auto_src*}]
add_cells_to_pblock $eci_gateway_pblock [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_eci_*_master/*slr_auto_dest*}]
add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_eci_*_master/*slr_auto_src*}]

add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_eci_*_slave/*slr_auto_dest*}]
add_cells_to_pblock $eci_gateway_pblock [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_eci_*_slave/*slr_auto_src*}]
add_cells_to_pblock $eci_gateway_pblock [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_eci_*_master/*slr_auto_dest*}]
add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_eci_*_master/*slr_auto_src*}]

# SI/MI constraints for pipelining reg slices between DCS and NicEngine
add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_slave/*slr_auto_dest*}]
add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_slave/*slr_auto_src*}]
add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_master/*slr_auto_dest*}]
add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_master/*slr_auto_src*}]

add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_slave/*slr_auto_dest*}]
add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_slave/*slr_auto_src*}]
add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_master/*slr_auto_dest*}]
add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_master/*slr_auto_src*}]

# SI/MI constraints for pipelining AXI between DCS and NicEngine
# !!! need to differentiate between channel directions
set is_fwd  "(NAME=~*ar16.ar_auto* || NAME=~*aw16.aw_auto* || NAME=~*w16.w_auto*)"
set is_resp "(NAME=~*r16.r_auto*   || NAME=~*b16.b_auto*)"

add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter "NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_src*  && $is_fwd"]
add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter "NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_dest* && $is_resp"]

add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter "NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_src*   && $is_fwd"]
add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter "NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_dest*  && $is_resp"]

add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter "NAME=~i_app/dcs_*/i_axi_pipe*slr_auto_dest*    && $is_fwd"]
add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter "NAME=~i_app/dcs_*/i_axi_pipe*slr_auto_src*     && $is_resp"]

# NicEngine in a specific SLR
# do not place the entire NicEngine, only limit important big components
# as pipelining logic should be allowed to stretch to other SLRs
add_cells_to_pblock $nic_engine_plock [get_cells [list \
  i_app/NicEngine_inst/PacketBuffer_logic_aligner \
  i_app/NicEngine_inst/PacketBuffer_logic_axiDma \
  i_app/NicEngine_inst/PacketBuffer_logic_axiMem \
  i_app/nic_engine_rst_sync \
]]

