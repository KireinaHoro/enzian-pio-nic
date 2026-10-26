// Platform composition of the DCS read converter and standard AXIS skid slice.
module lauberhorn_axi_rd_cl #
  (
   parameter DESC_ID_WIDTH   = 6,
   parameter DESC_ADDR_WIDTH = 40,
   parameter CL_WIDTH        = 1024,
   parameter AXI_ID_WIDTH    = DESC_ID_WIDTH,
   parameter AXI_ADDR_WIDTH  = DESC_ADDR_WIDTH,
   // can be varied between 64 and 1024 (powers of 2).
   parameter AXI_DATA_WIDTH = 512
   )
   (
    input logic 		      clk,
    input logic 		      reset,
   
    // Read desc input.
    input logic [DESC_ID_WIDTH-1:0]   rd_req_id_i,
    input logic [DESC_ADDR_WIDTH-1:0] rd_req_addr_i,
    input logic 		      rd_req_valid_i,
    output logic 		      rd_req_ready_o,

    // Read resp desc output.
    output logic [DESC_ID_WIDTH-1:0]  rd_rsp_id_o,
    output logic [CL_WIDTH-1:0]       rd_rsp_data_o,
    output logic 		      rd_rsp_valid_o,
    input logic 		      rd_rsp_ready_i,

    // AXI output primary I/F.
    output logic [AXI_ID_WIDTH-1:0]   p_axi_arid,
    output logic [AXI_ADDR_WIDTH-1:0] p_axi_araddr,
    output logic [7:0] 		      p_axi_arlen, 
    output logic [2:0] 		      p_axi_arsize, 
    output logic [1:0] 		      p_axi_arburst, // always incr
    output logic 		      p_axi_arlock,  // not used
    output logic [3:0] 		      p_axi_arcache, // not used
    output logic [2:0] 		      p_axi_arprot,  // not used
    output logic 		      p_axi_arvalid,
    input logic 		      p_axi_arready,
    input logic [AXI_ID_WIDTH-1:0]    p_axi_rid,
    input logic [AXI_DATA_WIDTH-1:0]  p_axi_rdata,
    input logic [1:0] 		      p_axi_rresp, // ignored.
    input logic 		      p_axi_rlast,
    input logic 		      p_axi_rvalid,
    output logic 		      p_axi_rready
   );

  // Register response data and backward ready together. DCS recipient-ID
  // decoding can no longer control the AXI beat bank's enables in one cycle.
  logic [DESC_ID_WIDTH-1:0] raw_id;
  logic [CL_WIDTH-1:0] raw_data;
  logic raw_valid, raw_ready;
  axi_rd_cl #(.DESC_ID_WIDTH(DESC_ID_WIDTH), .DESC_ADDR_WIDTH(DESC_ADDR_WIDTH),
    .CL_WIDTH(CL_WIDTH), .AXI_ID_WIDTH(AXI_ID_WIDTH),
    .AXI_ADDR_WIDTH(AXI_ADDR_WIDTH), .AXI_DATA_WIDTH(AXI_DATA_WIDTH)) read_cl (
    .rd_rsp_id_o(raw_id), .rd_rsp_data_o(raw_data),
    .rd_rsp_valid_o(raw_valid), .rd_rsp_ready_i(raw_ready), .*);

  axis_register #(.DATA_WIDTH(CL_WIDTH + DESC_ID_WIDTH), .KEEP_ENABLE(0),
    .KEEP_WIDTH(1), .LAST_ENABLE(0), .ID_ENABLE(0), .DEST_ENABLE(0),
    .USER_ENABLE(0), .REG_TYPE(2)) response_pipe (
    .clk(clk), .rst(reset),
    .s_axis_tdata({raw_id, raw_data}), .s_axis_tvalid(raw_valid),
    .s_axis_tready(raw_ready), .s_axis_tkeep(1'b1), .s_axis_tlast(1'b0),
    .s_axis_tid(8'b0), .s_axis_tdest(8'b0), .s_axis_tuser(1'b0),
    .m_axis_tdata({rd_rsp_id_o, rd_rsp_data_o}),
    .m_axis_tvalid(rd_rsp_valid_o), .m_axis_tready(rd_rsp_ready_i),
    .m_axis_tkeep(), .m_axis_tlast(), .m_axis_tid(), .m_axis_tdest(), .m_axis_tuser());
endmodule
