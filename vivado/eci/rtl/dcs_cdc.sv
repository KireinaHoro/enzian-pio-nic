`ifndef DCS_CDC_SV
`define DCS_CDC_SV

`define DECL_CHAN_SLAVE(prefix, bus_name, data_name, data_width) \
logic [(data_width)-1:0]          prefix``_``bus_name``_``data_name``_i; \
logic [ECI_PACKET_SIZE_WIDTH-1:0] prefix``_``bus_name``_pkt_size_i; \
logic [3:0]                       prefix``_``bus_name``_pkt_vc_i; \
logic                             prefix``_``bus_name``_pkt_valid_i; \
logic                             prefix``_``bus_name``_pkt_ready_o;

`define CROSS_CHAN_SLAVE(bus_name, data_name, data_width) \
`DECL_CHAN_SLAVE(cdc, bus_name, data_name, data_width) \
`DECL_CHAN_SLAVE(pipe, bus_name, data_name, data_width) \
axis_async_fifo #( \
  .DEPTH(2), \
  .DATA_WIDTH((data_width) + 4 + ECI_PACKET_SIZE_WIDTH), \
  .KEEP_ENABLE(0), \
  .LAST_ENABLE(0), \
  .USER_ENABLE(0) \
) i_chan_cdc_``bus_name``_slave ( \
  .s_clk(eci_clk), \
  .s_rst(eci_reset), \
  .s_axis_tdata({bus_name``_``data_name``_i, bus_name``_pkt_size_i, bus_name``_pkt_vc_i}), \
  .s_axis_tvalid(bus_name``_pkt_valid_i), \
  .s_axis_tready(bus_name``_pkt_ready_o), \
  .m_clk(app_clk), \
  .m_rst(app_reset), \
  .m_axis_tdata({cdc_``bus_name``_``data_name``_i, cdc_``bus_name``_pkt_size_i, cdc_``bus_name``_pkt_vc_i}), \
  .m_axis_tvalid(cdc_``bus_name``_pkt_valid_i), \
  .m_axis_tready(cdc_``bus_name``_pkt_ready_o) \
); \
axis_pipeline_register #( \
  .LENGTH(3) \
  .DATA_WIDTH((data_width) + 4 + ECI_PACKET_SIZE_WIDTH), \
  .KEEP_ENABLE(0), \
  .LAST_ENABLE(0), \
  .USER_ENABLE(0), \
) i_chan_pipe_``bus_name``_slave ( \
  clk(app_clk), \
  rst(app_reset), \
  .s_axis_tdata({cdc_``bus_name``_``data_name``_i, cdc_``bus_name``_pkt_size_i, cdc_``bus_name``_pkt_vc_i}), \
  .s_axis_tvalid(cdc_``bus_name``_pkt_valid_i), \
  .s_axis_tready(cdc_``bus_name``_pkt_ready_o), \
  .m_axis_tdata({pipe_``bus_name``_``data_name``_i, pipe_``bus_name``_pkt_size_i, pipe_``bus_name``_pkt_vc_i}), \
  .m_axis_tvalid(pipe_``bus_name``_pkt_valid_i), \
  .m_axis_tready(pipe_``bus_name``_pkt_ready_o) \
);

`define DECL_CHAN_MASTER(prefix, bus_name, data_name, data_width) \
logic [(data_width)-1:0]          prefix``_``bus_name``_``data_name``_o; \
logic [ECI_PACKET_SIZE_WIDTH-1:0] prefix``_``bus_name``_pkt_size_o; \
logic [3:0]                       prefix``_``bus_name``_pkt_vc_o; \
logic                             prefix``_``bus_name``_pkt_valid_o; \
logic                             prefix``_``bus_name``_pkt_ready_i;

`define CROSS_CHAN_MASTER(bus_name, data_name, data_width) \
DECL_CHAN_MASTER(cdc, bus_name, data_name, data_width) \
DECL_CHAN_MASTER(pipe, bus_name, data_name, data_width) \
axis_async_fifo #( \
  .DEPTH(2), \
  .DATA_WIDTH((data_width) + 4 + ECI_PACKET_SIZE_WIDTH), \
  .KEEP_ENABLE(0), \
  .LAST_ENABLE(0), \
  .USER_ENABLE(0) \
) i_chan_cdc_``bus_name``_master ( \
  .s_clk(app_clk), \
  .s_rst(app_reset), \
  .s_axis_tdata({cdc_``bus_name``_``data_name``_o, cdc_``bus_name``_pkt_size_o, cdc_``bus_name``_pkt_vc_o}), \
  .s_axis_tvalid(cdc_``bus_name``_pkt_valid_o), \
  .s_axis_tready(cdc_``bus_name``_pkt_ready_i), \
  .m_clk(eci_clk), \
  .m_rst(eci_reset), \
  .m_axis_tdata({bus_name``_``data_name``_o, bus_name``_pkt_size_o, bus_name``_pkt_vc_o}), \
  .m_axis_tvalid(bus_name``_pkt_valid_o), \
  .m_axis_tready(bus_name``_pkt_ready_i) \
); \
axis_pipeline_register #( \
  .LENGTH(3) \
  .DATA_WIDTH((data_width) + 4 + ECI_PACKET_SIZE_WIDTH), \
  .KEEP_ENABLE(0), \
  .LAST_ENABLE(0), \
  .USER_ENABLE(0), \
) i_chan_pipe_``bus_name``_slave ( \
  clk(app_clk), \
  rst(app_reset), \
  .s_axis_tdata({pipe_``bus_name``_``data_name``_o, pipe_``bus_name``_pkt_size_o, pipe_``bus_name``_pkt_vc_o}), \
  .s_axis_tvalid(pipe_``bus_name``_pkt_valid_o), \
  .s_axis_tready(pipe_``bus_name``_pkt_ready_i), \
  .m_axis_tdata({cdc_``bus_name``_``data_name``_o, cdc_``bus_name``_pkt_size_o, cdc_``bus_name``_pkt_vc_o}), \
  .m_axis_tvalid(cdc_``bus_name``_pkt_valid_o), \
  .m_axis_tready(cdc_``bus_name``_pkt_ready_i) \
);

`define CONNECT_CHAN_SLAVE(bus_name, data_name) \
  .bus_name``_``data_name``_i(pipe_``bus_name``_``data_name``_i), \
  .bus_name``_pkt_size_i(pipe_``bus_name``_pkt_size_i), \
  .bus_name``_pkt_vc_i(pipe_``bus_name``_pkt_vc_i), \
  .bus_name``_pkt_valid_i(pipe_``bus_name``_pkt_valid_i), \
  .bus_name``_pkt_ready_o(pipe_``bus_name``_pkt_ready_o),

`define CONNECT_CHAN_MASTER(bus_name, data_name) \
  .bus_name``_``data_name``_o(pipe_``bus_name``_``data_name``_o), \
  .bus_name``_pkt_size_o(pipe_``bus_name``_pkt_size_o), \
  .bus_name``_pkt_vc_o(pipe_``bus_name``_pkt_vc_o), \
  .bus_name``_pkt_valid_o(pipe_``bus_name``_pkt_valid_o), \
  .bus_name``_pkt_ready_i(pipe_``bus_name``_pkt_ready_i),

import eci_cmd_defs::*;
import eci_dcs_defs::*;

module dcs_cdc #(
   parameter AXI_ID_WIDTH = MAX_DCU_ID_WIDTH, //7
   parameter AXI_ADDR_WIDTH = DS_ADDR_WIDTH,  //38
   parameter AXI_DATA_WIDTH = 512,
   parameter AXI_STRB_WIDTH = (AXI_DATA_WIDTH/8),
   parameter PERF_REGS_WIDTH = 32,
   parameter SYNTH_PERF_REGS = 1 //0,1
) (
    input logic                                   eci_clk,
    input logic                                   eci_reset,

    // ECI channels
    input logic [ECI_WORD_WIDTH-1:0]              req_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       req_wod_pkt_size_i,
    input logic [3:0]                             req_wod_pkt_vc_i,
    input logic                                   req_wod_pkt_valid_i,
    output logic                                  req_wod_pkt_ready_o,

    input logic [ECI_WORD_WIDTH-1:0]              rsp_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       rsp_wod_pkt_size_i,
    input logic [3:0]                             rsp_wod_pkt_vc_i,
    input logic                                   rsp_wod_pkt_valid_i,
    output logic                                  rsp_wod_pkt_ready_o,

    input logic [ECI_PACKET_SIZE-1:0][ECI_WORD_WIDTH-1:0] rsp_wd_pkt_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       rsp_wd_pkt_size_i,
    input logic [3:0]                             rsp_wd_pkt_vc_i,
    input logic                                   rsp_wd_pkt_valid_i,
    output logic                                  rsp_wd_pkt_ready_o,

    output logic [ECI_WORD_WIDTH-1:0]             rsp_wod_hdr_o,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0]      rsp_wod_pkt_size_o,
    output logic [3:0]                            rsp_wod_pkt_vc_o,
    output logic                                  rsp_wod_pkt_valid_o,
    input logic                                   rsp_wod_pkt_ready_i,

    output logic [ECI_PACKET_SIZE-1:0][ECI_WORD_WIDTH-1:0] rsp_wd_pkt_o,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0]      rsp_wd_pkt_size_o,
    output logic [3:0]                            rsp_wd_pkt_vc_o,
    output logic                                  rsp_wd_pkt_valid_o,
    input logic                                   rsp_wd_pkt_ready_i,

    output logic [ECI_WORD_WIDTH-1:0]             fwd_wod_hdr_o,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0]      fwd_wod_pkt_size_o,
    output logic [3:0]                            fwd_wod_pkt_vc_o,
    output logic                                  fwd_wod_pkt_valid_o,
    input logic                                   fwd_wod_pkt_ready_i,

    // app clocks
    input logic                                   app_clk,
    input logic                                   app_reset,

    // LCL channels
    input logic [ECI_WORD_WIDTH-1:0]              lcl_fwd_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       lcl_fwd_wod_pkt_size_i,
    input logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0]   lcl_fwd_wod_pkt_vc_i, //5
    input logic                                   lcl_fwd_wod_pkt_valid_i,
    output logic                                  lcl_fwd_wod_pkt_ready_o,

    input logic [ECI_WORD_WIDTH-1:0]              lcl_rsp_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       lcl_rsp_wod_pkt_size_i,
    input logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0]   lcl_rsp_wod_pkt_vc_i,
    input logic                                   lcl_rsp_wod_pkt_valid_i,
    output logic                                  lcl_rsp_wod_pkt_ready_o,

    output logic [ECI_WORD_WIDTH-1:0]             lcl_rsp_wod_hdr_o,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0]      lcl_rsp_wod_pkt_size_o,
    output logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0]  lcl_rsp_wod_pkt_vc_o,//5
    output logic                                  lcl_rsp_wod_pkt_valid_o,
    input logic                                   lcl_rsp_wod_pkt_ready_i,

    // AXI
    output logic [AXI_ID_WIDTH-1:0]     p_axi_arid,
    output logic [AXI_ADDR_WIDTH-1:0]   p_axi_araddr,
    output logic [7:0]                  p_axi_arlen,
    output logic [2:0]                  p_axi_arsize,
    output logic [1:0]                  p_axi_arburst,
    output logic                        p_axi_arlock,
    output logic [3:0]                  p_axi_arcache,
    output logic [2:0]                  p_axi_arprot,
    output logic                        p_axi_arvalid,
    input logic                         p_axi_arready,
    input logic [AXI_ID_WIDTH-1:0]      p_axi_rid,
    input logic [AXI_DATA_WIDTH-1:0]    p_axi_rdata,
    input logic [1:0]                   p_axi_rresp,
    input logic                         p_axi_rlast,
    input logic                         p_axi_rvalid,
    output logic                        p_axi_rready,

    output logic [AXI_ID_WIDTH-1:0]     p_axi_awid,
    output logic [AXI_ADDR_WIDTH-1:0]   p_axi_awaddr,
    output logic [7:0]                  p_axi_awlen,
    output logic [2:0]                  p_axi_awsize,
    output logic [1:0]                  p_axi_awburst,
    output logic                        p_axi_awlock,
    output logic [3:0]                  p_axi_awcache,
    output logic [2:0]                  p_axi_awprot,
    output logic                        p_axi_awvalid,
    input logic                         p_axi_awready,
    output logic [AXI_DATA_WIDTH-1:0]   p_axi_wdata,
    output logic [AXI_STRB_WIDTH-1:0]   p_axi_wstrb,
    output logic                        p_axi_wlast,
    output logic                        p_axi_wvalid,
    input logic                         p_axi_wready,
    input logic [AXI_ID_WIDTH-1:0]      p_axi_bid,
    input logic [1:0]                   p_axi_bresp,
    input logic                         p_axi_bvalid,
    output logic                        p_axi_bready
);

`CROSS_CHAN_SLAVE(req_wod, hdr, ECI_WORD_WIDTH)
`CROSS_CHAN_SLAVE(rsp_wod, hdr, ECI_WORD_WIDTH)
`CROSS_CHAN_SLAVE(rsp_wd, pkt, ECI_WORD_WIDTH * ECI_PACKET_SIZE)
`CROSS_CHAN_MASTER(rsp_wod, hdr, ECI_WORD_WIDTH)
`CROSS_CHAN_MASTER(rsp_wd, pkt, ECI_WORD_WIDTH * ECI_PACKET_SIZE)
`CROSS_CHAN_MASTER(fwd_wod, hdr, ECI_WORD_WIDTH)

dcs_2_axi #(
  .AXI_ID_WIDTH(AXI_ID_WIDTH),
  .AXI_ADDR_WIDTH(AXI_ADDR_WIDTH),
  .AXI_DATA_WIDTH(AXI_DATA_WIDTH),
  .AXI_STRB_WIDTH(AXI_STRB_WIDTH),
  .PERF_REGS_WIDTH(PERF_REGS_WIDTH),
  .SYNTH_PERF_REGS(SYNTH_PERF_REGS)
) i_dcs (
  .clk(app_clk),
  .reset(app_reset),

  `CONNECT_CHAN_SLAVE(req_wod, hdr)
  `CONNECT_CHAN_SLAVE(rsp_wod, hdr)
  `CONNECT_CHAN_SLAVE(rsp_wd, pkt)
  `CONNECT_CHAN_MASTER(rsp_wod, hdr)
  `CONNECT_CHAN_MASTER(rsp_wd, pkt)
  `CONNECT_CHAN_MASTER(fwd_wod, hdr)

  .lcl_fwd_wod_hdr_i,
  .lcl_fwd_wod_pkt_size_i,
  .lcl_fwd_wod_pkt_vc_i, //5
  .lcl_fwd_wod_pkt_valid_i,
  .lcl_fwd_wod_pkt_ready_o,

  .lcl_rsp_wod_hdr_i,
  .lcl_rsp_wod_pkt_size_i,
  .lcl_rsp_wod_pkt_vc_i,
  .lcl_rsp_wod_pkt_valid_i,
  .lcl_rsp_wod_pkt_ready_o,

  .lcl_rsp_wod_hdr_o,
  .lcl_rsp_wod_pkt_size_o,
  .lcl_rsp_wod_pkt_vc_o,//5
  .lcl_rsp_wod_pkt_valid_o,
  .lcl_rsp_wod_pkt_ready_i,

  .p_axi_arid,
  .p_axi_araddr,
  .p_axi_arlen,
  .p_axi_arsize,
  .p_axi_arburst,
  .p_axi_arlock,
  .p_axi_arcache,
  .p_axi_arprot,
  .p_axi_arvalid,
  .p_axi_arready,
  .p_axi_rid,
  .p_axi_rdata,
  .p_axi_rresp,
  .p_axi_rlast,
  .p_axi_rvalid,
  .p_axi_rready,
  .p_axi_awid,
  .p_axi_awaddr,
  .p_axi_awlen,
  .p_axi_awsize,
  .p_axi_awburst,
  .p_axi_awlock,
  .p_axi_awcache,
  .p_axi_awprot,
  .p_axi_awvalid,
  .p_axi_awready,
  .p_axi_wdata,
  .p_axi_wstrb,
  .p_axi_wlast,
  .p_axi_wvalid,
  .p_axi_wready,
  .p_axi_bid,
  .p_axi_bresp,
  .p_axi_bvalid,
  .p_axi_bready
);

endmodule

`endif
