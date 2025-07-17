# assign two DCS to top and bottom SLR slices
create_pblock pblock_slr2
resize_pblock pblock_slr2 -add SLR2:SLR2
add_cells_to_pblock pblock_slr2 [get_cells [list i_app/dcs_even]]

create_pblock pblock_slr0
resize_pblock pblock_slr0 -add SLR0:SLR0
add_cells_to_pblock pblock_slr0 [get_cells [list i_app/dcs_odd]]
