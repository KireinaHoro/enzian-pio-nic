# create pblocks for top and bottom SLRs
create_pblock pblock_slr2
resize_pblock pblock_slr2 -add SLR2:SLR2
create_pblock pblock_slr1
resize_pblock pblock_slr1 -add SLR1:SLR1
create_pblock pblock_slr0
resize_pblock pblock_slr0 -add SLR0:SLR0

set nic_engine_plock   [get_pblocks pblock_slr0]
set dcs_even_pblock    [get_pblocks pblock_slr2]
set dcs_odd_pblock     [get_pblocks pblock_slr0]
set eci_gateway_pblock [get_pblocks pblock_slr1]

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
# disabled due to need to cross SLR0->2
#add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_slave/*slr_auto_dest*}]
#add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_slave/*slr_auto_src*}]
#add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_master/*slr_auto_dest*}]
#add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter {NAME =~ i_app/dcs_even/i_chan_pipe_lcl_*_master/*slr_auto_src*}]

add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_slave/*slr_auto_dest*}]
add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_slave/*slr_auto_src*}]
add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_master/*slr_auto_dest*}]
add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter {NAME =~ i_app/dcs_odd/i_chan_pipe_lcl_*_master/*slr_auto_src*}]

# SI/MI constraints for pipelining AXI between DCS and NicEngine
# !!! need to differentiate between channel directions
set is_fwd  "(NAME~=*ar16.ar_auto* || NAME~=*aw16.aw_auto* || NAME~=*w16.w_auto*)"
set is_resp "(NAME~=*r16.r_auto*   || NAME~=*b16.b_auto*)"

add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter "NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_src*  && $is_fwd"]
add_cells_to_pblock $dcs_even_pblock    [get_cells -hierarchical -filter "NAME=~i_app/dcs_even/i_axi_pipe*slr_auto_dest* && $is_resp"]

add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter "NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_src*   && $is_fwd"]
add_cells_to_pblock $dcs_odd_pblock     [get_cells -hierarchical -filter "NAME=~i_app/dcs_odd/i_axi_pipe*slr_auto_dest*  && $is_resp"]

add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter "NAME=~i_app/dcs_*/i_axi_pipe*slr_auto_dest*    && $is_fwd"]
add_cells_to_pblock $nic_engine_plock   [get_cells -hierarchical -filter "NAME=~i_app/dcs_*/i_axi_pipe*slr_auto_src*     && $is_resp"]

# NicEngine in a specific SLR
# The entire engine is in SLR0, so we can't fix SLR0->2 FIFOs for dcs_even
# to allow effective auto-pipelining
add_cells_to_pblock $nic_engine_plock [get_cells [list \
  i_app/NicEngine_inst \
  i_app/nic_engine_rst_sync \
]]

