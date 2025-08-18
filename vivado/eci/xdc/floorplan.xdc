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
resize_pblock [get_pblocks pblock_slr1] -add {SLICE_X152Y540:SLICE_X168Y599 SLICE_X137Y540:SLICE_X149Y599 SLICE_X121Y540:SLICE_X134Y599 SLICE_X0Y540:SLICE_X118Y599}
resize_pblock [get_pblocks pblock_slr1] -add {BIAS_X0Y18:BIAS_X1Y19}
resize_pblock [get_pblocks pblock_slr1] -add {BITSLICE_CONTROL_X0Y72:BITSLICE_CONTROL_X1Y79}
resize_pblock [get_pblocks pblock_slr1] -add {BITSLICE_RX_TX_X0Y468:BITSLICE_RX_TX_X1Y519}
resize_pblock [get_pblocks pblock_slr1] -add {BITSLICE_TX_X0Y72:BITSLICE_TX_X1Y79}
resize_pblock [get_pblocks pblock_slr1] -add {BUFCE_LEAF_X768Y36:BUFCE_LEAF_X863Y39 BUFCE_LEAF_X696Y36:BUFCE_LEAF_X759Y39 BUFCE_LEAF_X624Y36:BUFCE_LEAF_X687Y39 BUFCE_LEAF_X0Y36:BUFCE_LEAF_X599Y39}
resize_pblock [get_pblocks pblock_slr1] -add {BUFCE_ROW_X0Y216:BUFCE_ROW_X1Y239}
resize_pblock [get_pblocks pblock_slr1] -add {BUFCE_ROW_FSR_X167Y9:BUFCE_ROW_FSR_X188Y9 BUFCE_ROW_FSR_X152Y9:BUFCE_ROW_FSR_X164Y9 BUFCE_ROW_FSR_X140Y9:BUFCE_ROW_FSR_X149Y9 BUFCE_ROW_FSR_X0Y9:BUFCE_ROW_FSR_X134Y9}
resize_pblock [get_pblocks pblock_slr1] -add {BUFGCE_X0Y216:BUFGCE_X1Y239}
resize_pblock [get_pblocks pblock_slr1] -add {BUFGCE_DIV_X0Y36:BUFGCE_DIV_X1Y39}
resize_pblock [get_pblocks pblock_slr1] -add {BUFGCTRL_X0Y72:BUFGCTRL_X1Y79}
resize_pblock [get_pblocks pblock_slr1] -add {BUFG_GT_X0Y216:BUFG_GT_X1Y239}
resize_pblock [get_pblocks pblock_slr1] -add {BUFG_GT_SYNC_X0Y135:BUFG_GT_SYNC_X1Y149}
resize_pblock [get_pblocks pblock_slr1] -add {CMACE4_X0Y5:CMACE4_X0Y5}
resize_pblock [get_pblocks pblock_slr1] -add {DSP48E2_X0Y216:DSP48E2_X18Y239}
resize_pblock [get_pblocks pblock_slr1] -add {GTYE4_CHANNEL_X0Y36:GTYE4_CHANNEL_X1Y39}
resize_pblock [get_pblocks pblock_slr1] -add {GTYE4_COMMON_X0Y9:GTYE4_COMMON_X1Y9}
resize_pblock [get_pblocks pblock_slr1] -add {HARD_SYNC_X0Y18:HARD_SYNC_X23Y19}
resize_pblock [get_pblocks pblock_slr1] -add {HPIOBDIFFINBUF_X0Y216:HPIOBDIFFINBUF_X1Y239}
resize_pblock [get_pblocks pblock_slr1] -add {HPIOBDIFFOUTBUF_X0Y216:HPIOBDIFFOUTBUF_X1Y239}
resize_pblock [get_pblocks pblock_slr1] -add {HPIO_VREF_SITE_X0Y18:HPIO_VREF_SITE_X1Y19}
resize_pblock [get_pblocks pblock_slr1] -add {HPIO_ZMATCH_BLK_HCLK_X0Y9:HPIO_ZMATCH_BLK_HCLK_X1Y9}
resize_pblock [get_pblocks pblock_slr1] -add {ILKNE4_X1Y5:ILKNE4_X1Y5}
resize_pblock [get_pblocks pblock_slr1] -add {IOB_X0Y468:IOB_X1Y519}
resize_pblock [get_pblocks pblock_slr1] -add {LAGUNA_X22Y360:LAGUNA_X23Y479 LAGUNA_X0Y360:LAGUNA_X15Y479}
resize_pblock [get_pblocks pblock_slr1] -add {MMCM_X0Y9:MMCM_X1Y9}
resize_pblock [get_pblocks pblock_slr1] -add {PLL_X0Y18:PLL_X1Y19}
resize_pblock [get_pblocks pblock_slr1] -add {RAMB18_X0Y216:RAMB18_X11Y239}
resize_pblock [get_pblocks pblock_slr1] -add {RAMB36_X0Y108:RAMB36_X11Y119}
resize_pblock [get_pblocks pblock_slr1] -add {RIU_OR_X0Y36:RIU_OR_X1Y39}
resize_pblock [get_pblocks pblock_slr1] -add {URAM288_X0Y144:URAM288_X3Y159}
resize_pblock [get_pblocks pblock_slr1] -add {CLOCKREGION_X0Y6:CLOCKREGION_X4Y8 CLOCKREGION_X0Y5:CLOCKREGION_X5Y5}

create_pblock pblock_slr0
resize_pblock [get_pblocks pblock_slr0] -add {CLOCKREGION_X0Y0:CLOCKREGION_X5Y4}

set nic_decoders_pblock [get_pblocks pblock_slr0]

create_pblock pblock_nic_hostif
set nic_hostif_pblock  [get_pblocks pblock_nic_hostif]
resize_pblock $nic_hostif_pblock -add {CLOCKREGION_X0Y5:CLOCKREGION_X2Y9}

set dcs_even_pblock    [get_pblocks pblock_slr2]
set dcs_odd_pblock     [get_pblocks pblock_slr0]
set eci_gateway_pblock [get_pblocks pblock_slr1]

# assign two DCS to top and bottom SLR slices
add_cells_to_pblock $dcs_even_pblock [get_cells [list \
  i_app/dcs_even/i_dcs \
  i_app/dcs_even/i_app_rst_sync \
]]
add_cells_to_pblock $dcs_even_pblock [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_cdc_*}]

add_cells_to_pblock $dcs_odd_pblock [get_cells [list \
  i_app/dcs_odd/i_dcs \
  i_app/dcs_odd/i_app_rst_sync \
]]
add_cells_to_pblock $dcs_odd_pblock [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_cdc_*}]

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
add_cells_to_pblock $nic_hostif_pblock  [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_slave/*slr_auto_src*}]
add_cells_to_pblock $nic_hostif_pblock  [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_master/*slr_auto_dest*}]
add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_master/*slr_auto_src*}]

add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_slave/*slr_auto_dest*}]
add_cells_to_pblock $nic_hostif_pblock  [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_slave/*slr_auto_src*}]
add_cells_to_pblock $nic_hostif_pblock  [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_master/*slr_auto_dest*}]
add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_master/*slr_auto_src*}]

# SI/MI constraints for pipelining AXI between DCS and NicEngine
# !!! need to differentiate between channel directions
set is_fwd  "(NAME=~*ar16.ar_auto* || NAME=~*aw16.aw_auto* || NAME=~*w16.w_auto*)"
set is_resp "(NAME=~*r16.r_auto*   || NAME=~*b16.b_auto*)"

add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter "NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_src*  && $is_fwd"]
add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter "NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_dest* && $is_resp"]

add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter "NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_src*   && $is_fwd"]
add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter "NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_dest*  && $is_resp"]

add_cells_to_pblock $nic_hostif_pblock  [get_cells -hierarchical -filter "NAME=~i_app/dcs_*/i_axi_pipe*slr_auto_dest*    && $is_fwd"]
add_cells_to_pblock $nic_hostif_pblock  [get_cells -hierarchical -filter "NAME=~i_app/dcs_*/i_axi_pipe*slr_auto_src*     && $is_resp"]

# NicEngine
add_cells_to_pblock $nic_decoders_pblock [get_cells -hierarchical -filter "NAME=~i_app/NicEngine_inst/*Decoder_logic_*"]
add_cells_to_pblock $nic_decoders_pblock [get_cells -hierarchical -filter "NAME=~i_app/NicEngine_inst/RxDecoderSink_logic_*"]

add_cells_to_pblock $nic_hostif_pblock [get_cells -hierarchical -filter "NAME=~i_app/NicEngine_inst/driveDcsBus_core*_*Router"]
add_cells_to_pblock $nic_hostif_pblock [get_cells -hierarchical -filter "NAME=~i_app/NicEngine_inst/Scheduler_logic_*"]
add_cells_to_pblock $nic_hostif_pblock [get_cells -hierarchical -filter "NAME=~i_app/NicEngine_inst/PacketBuffer_logic_*"]
add_cells_to_pblock $nic_hostif_pblock [get_cells -hierarchical -filter "NAME=~i_app/NicEngine_inst/EciInterfacePlugin_logic_*"]
