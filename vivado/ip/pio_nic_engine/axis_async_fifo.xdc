# AXI stream asynchronous FIFO timing constraints
# XXX: We only have ASYNC_REG constraints here.  The actual timing constraints are at the top level

set fifo_inst [get_cells -hier -filter {(ORIG_REF_NAME == axis_async_fifo || REF_NAME == axis_async_fifo) && NAME =~ */rxFifo}]

set reset_ffs [get_cells -quiet -hier -regexp ".*/s_rst_sync\[23\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $reset_ffs

set reset_ffs [get_cells -quiet -hier -regexp ".*/m_rst_sync\[23\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $reset_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/rd_ptr_gray_sync\[12\]_reg_reg\\\[\\d+\\\]" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/wr_ptr_gray_sync\[12\]_reg_reg\\\[\\d+\\\]" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/wr_ptr_commit_sync_reg_reg\\\[\\d+\\\]" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

set update_ffs [get_cells -quiet -hier -regexp ".*/wr_ptr_update(_ack)?_sync\[123\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $update_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/pause.s_pause_req_sync\[123\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/pause.s_pause_ack_sync\[123\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

# =========================

set fifo_inst [get_cells -hier -filter {(ORIG_REF_NAME == axis_async_fifo || REF_NAME == axis_async_fifo) && NAME =~ */txFifo}]

set reset_ffs [get_cells -quiet -hier -regexp ".*/s_rst_sync\[23\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $reset_ffs

set reset_ffs [get_cells -quiet -hier -regexp ".*/m_rst_sync\[23\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $reset_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/rd_ptr_gray_sync\[12\]_reg_reg\\\[\\d+\\\]" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/wr_ptr_gray_sync\[12\]_reg_reg\\\[\\d+\\\]" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/wr_ptr_commit_sync_reg_reg\\\[\\d+\\\]" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

set update_ffs [get_cells -quiet -hier -regexp ".*/wr_ptr_update(_ack)?_sync\[123\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $update_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/pause.s_pause_req_sync\[123\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

set sync_ffs [get_cells -quiet -hier -regexp ".*/pause.s_pause_ack_sync\[123\]_reg_reg" -filter "PARENT == $fifo_inst"]
set_property -quiet ASYNC_REG TRUE $sync_ffs

