# create pblocks for top and bottom SLRs
create_pblock pblock_slr2
resize_pblock pblock_slr2 -add SLR2:SLR2
create_pblock pblock_slr0
resize_pblock pblock_slr0 -add SLR0:SLR0

# assign two DCS to top and bottom SLR slices
add_cells_to_pblock pblock_slr2 [get_cells [list \
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

add_cells_to_pblock pblock_slr0 [get_cells [list \
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
add_cells_to_pblock pblock_slr2 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_eci_*_slave/*slr_auto_dest*}]
add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_eci_*_slave/*slr_auto_src*}]
add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_eci_*_master/*slr_auto_dest*}]
add_cells_to_pblock pblock_slr2 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_eci_*_master/*slr_auto_src*}]

add_cells_to_pblock pblock_slr0 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_eci_*_slave/*slr_auto_dest*}]
add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_eci_*_slave/*slr_auto_src*}]
add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_eci_*_master/*slr_auto_dest*}]
add_cells_to_pblock pblock_slr0 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_eci_*_master/*slr_auto_src*}]

# SI/MI constraints for pipelining reg slices between DCS and NicEngine
# We do not constraint where the NicEngine is, so leave out some dest/src
add_cells_to_pblock pblock_slr2 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_slave/*slr_auto_dest*}]
#add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_slave/*slr_auto_src*}]
#add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_master/*slr_auto_dest*}]
add_cells_to_pblock pblock_slr2 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_master/*slr_auto_src*}]

add_cells_to_pblock pblock_slr0 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_slave/*slr_auto_dest*}]
#add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_slave/*slr_auto_src*}]
#add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_master/*slr_auto_dest*}]
add_cells_to_pblock pblock_slr0 [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_master/*slr_auto_src*}]

# SI/MI constraints for pipelining AXI between DCS and NicEngine
# We do not constraint where the NicEngine is, so leave out some dest/src
# !!! need to differentiate between channel directions
add_cells_to_pblock pblock_slr2 [get_cells -hierarchical -filter {NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_src* && (NAME~=*ar16.ar_auto* || NAME~=*aw16.aw_auto* || NAME~=*w16.w_auto*)}]
add_cells_to_pblock pblock_slr2 [get_cells -hierarchical -filter {NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_dest* && (NAME~=*r16.r_auto* || NAME~=*b16.b_auto*)}]

#add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_dest* && (NAME~=*ar16.ar_auto* || NAME~=*aw16.aw_auto* || NAME~=*w16.w_auto*)}]
#add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_src* && (NAME~=*r16.r_auto* || NAME~=*b16.b_auto*)}]

add_cells_to_pblock pblock_slr0 [get_cells -hierarchical -filter {NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_src* && (NAME~=*ar16.ar_auto* || NAME~=*aw16.aw_auto* || NAME~=*w16.w_auto*)}]
add_cells_to_pblock pblock_slr0 [get_cells -hierarchical -filter {NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_dest* && (NAME~=*r16.r_auto* || NAME~=*b16.b_auto*)}]

#add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_dest* && (NAME~=*ar16.ar_auto* || NAME~=*aw16.aw_auto* || NAME~=*w16.w_auto*)}]
#add_cells_to_pblock pblock_slr1 [get_cells -hierarchical -filter {NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_src* && (NAME~=*r16.r_auto* || NAME~=*b16.b_auto*)}]

# assign DMA, aligner, mem to SLR0 (closer to CMAC)
#add_cells_to_pblock pblock_slr0 [get_cells [list \
#  i_app/NicEngine_inst/PacketBuffer_logic_aligner \
#  i_app/NicEngine_inst/PacketBuffer_logic_axiDma \
#  i_app/NicEngine_inst/PacketBuffer_logic_axiMem \
#  i_app/nic_engine_rst_sync \
#]]
