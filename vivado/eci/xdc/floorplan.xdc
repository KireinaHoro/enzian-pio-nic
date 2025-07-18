# create pblocks for top and bottom SLRs
create_pblock pblock_slr2
resize_pblock pblock_slr2 -add SLR2:SLR2
create_pblock pblock_slr0
resize_pblock pblock_slr0 -add SLR0:SLR0

# assign two DCS to top and bottom SLR slices
# do not fix the CDC FIFOs
add_cells_to_pblock pblock_slr2 [get_cells i_app/dcs_even/i_dcs]
add_cells_to_pblock pblock_slr0 [get_cells i_app/dcs_odd/i_dcs]

# assign DMA, aligner, mem to SLR2
add_cells_to_pblock pblock_slr2 [get_cells [list \
  i_app/NicEngine_inst/PacketBuffer_logic_aligner \
  i_app/NicEngine_inst/PacketBuffer_logic_axiDma \
  i_app/NicEngine_inst/PacketBuffer_logic_axiMem \
]]

# assign decoder pipeline to SLR0 (closer to CMAC)
add_cells_to_pblock pblock_slr0 [get_cells [list \
  i_app/NicEngine_inst/EthernetDecoder_logic_decoder \
  i_app/NicEngine_inst/IpDecoder_logic_decoder \
  i_app/NicEngine_inst/UdpDecoder_logic_decoder \
  i_app/NicEngine_inst/OncRpcCallDecoder_logic_decoder \
]]
