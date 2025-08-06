`ifndef DCS_CDC_SV
`define DCS_CDC_SV

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
    // asynchronous reset -- synchronized locally
    input logic                                   reset_async,

    // ===== interfaces toward ECI gateway are clocked with eci_clk =====
    input logic                                   eci_clk,

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

    // ===== interfaces toward application are clocked with app_clk =====
    input logic                                   app_clk,

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
    output logic [AXI_ID_WIDTH-1:0]               m_axi_arid,
    output logic [AXI_ADDR_WIDTH-1:0]             m_axi_araddr,
    output logic [7:0]                            m_axi_arlen,
    output logic [2:0]                            m_axi_arsize,
    output logic [1:0]                            m_axi_arburst,
    output logic                                  m_axi_arlock,
    output logic [3:0]                            m_axi_arcache,
    output logic [2:0]                            m_axi_arprot,
    output logic                                  m_axi_arvalid,
    input logic                                   m_axi_arready,
    input logic [AXI_ID_WIDTH-1:0]                m_axi_rid,
    input logic [AXI_DATA_WIDTH-1:0]              m_axi_rdata,
    input logic [1:0]                             m_axi_rresp,
    input logic                                   m_axi_rlast,
    input logic                                   m_axi_rvalid,
    output logic                                  m_axi_rready,

    output logic [AXI_ID_WIDTH-1:0]               m_axi_awid,
    output logic [AXI_ADDR_WIDTH-1:0]             m_axi_awaddr,
    output logic [7:0]                            m_axi_awlen,
    output logic [2:0]                            m_axi_awsize,
    output logic [1:0]                            m_axi_awburst,
    output logic                                  m_axi_awlock,
    output logic [3:0]                            m_axi_awcache,
    output logic [2:0]                            m_axi_awprot,
    output logic                                  m_axi_awvalid,
    input logic                                   m_axi_awready,
    output logic [AXI_DATA_WIDTH-1:0]             m_axi_wdata,
    output logic [AXI_STRB_WIDTH-1:0]             m_axi_wstrb,
    output logic                                  m_axi_wlast,
    output logic                                  m_axi_wvalid,
    input logic                                   m_axi_wready,
    input logic [AXI_ID_WIDTH-1:0]                m_axi_bid,
    input logic [1:0]                             m_axi_bresp,
    input logic                                   m_axi_bvalid,
    output logic                                  m_axi_bready
);

logic eci_reset, app_reset;
xpm_cdc_sync_rst i_app_rst_sync (
    .dest_clk(app_clk),
    .dest_rst(app_reset),
    .src_rst(reset_async)
);
xpm_cdc_sync_rst i_eci_rst_sync (
    .dest_clk(eci_clk),
    .dest_rst(eci_reset),
    .src_rst(reset_async)
);

logic [ECI_WORD_WIDTH-1:0]        cdc_req_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] cdc_req_wod_pkt_size_i;
logic [3:0]                       cdc_req_wod_pkt_vc_i;
logic                             cdc_req_wod_pkt_valid_i;
logic                             cdc_req_wod_pkt_ready_o;

logic [ECI_WORD_WIDTH-1:0]        pipe_req_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_req_wod_pkt_size_i;
logic [3:0]                       pipe_req_wod_pkt_vc_i;
logic                             pipe_req_wod_pkt_valid_i;
logic                             pipe_req_wod_pkt_ready_o;
axis_cdc_eci_wod i_chan_cdc_req_wod_slave (
  .s_axis_aclk(eci_clk),
  .s_axis_aresetn(!eci_reset),
  .s_axis_tdata({7'b0, req_wod_hdr_i, req_wod_pkt_size_i, req_wod_pkt_vc_i}),
  .s_axis_tvalid(req_wod_pkt_valid_i),
  .s_axis_tready(req_wod_pkt_ready_o),
  .m_axis_aclk(app_clk),
  .m_axis_aresetn(!app_reset),
  .m_axis_tdata({7'b0, cdc_req_wod_hdr_i, cdc_req_wod_pkt_size_i, cdc_req_wod_pkt_vc_i}),
  .m_axis_tvalid(cdc_req_wod_pkt_valid_i),
  .m_axis_tready(cdc_req_wod_pkt_ready_o)
);
axis_reg_eci_wod i_chan_pipe_eci_req_wod_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({7'b0, cdc_req_wod_hdr_i, cdc_req_wod_pkt_size_i, cdc_req_wod_pkt_vc_i}),
  .s_axis_tvalid(cdc_req_wod_pkt_valid_i),
  .s_axis_tready(cdc_req_wod_pkt_ready_o),
  .m_axis_tdata({7'b0, pipe_req_wod_hdr_i, pipe_req_wod_pkt_size_i, pipe_req_wod_pkt_vc_i}),
  .m_axis_tvalid(pipe_req_wod_pkt_valid_i),
  .m_axis_tready(pipe_req_wod_pkt_ready_o)
);


logic [ECI_WORD_WIDTH-1:0]        cdc_rsp_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] cdc_rsp_wod_pkt_size_i;
logic [3:0]                       cdc_rsp_wod_pkt_vc_i;
logic                             cdc_rsp_wod_pkt_valid_i;
logic                             cdc_rsp_wod_pkt_ready_o;

logic [ECI_WORD_WIDTH-1:0]        pipe_rsp_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_rsp_wod_pkt_size_i;
logic [3:0]                       pipe_rsp_wod_pkt_vc_i;
logic                             pipe_rsp_wod_pkt_valid_i;
logic                             pipe_rsp_wod_pkt_ready_o;
axis_cdc_eci_wod i_chan_cdc_rsp_wod_slave (
  .s_axis_aclk(eci_clk),
  .s_axis_aresetn(!eci_reset),
  .s_axis_tdata({7'b0, rsp_wod_hdr_i, rsp_wod_pkt_size_i, rsp_wod_pkt_vc_i}),
  .s_axis_tvalid(rsp_wod_pkt_valid_i),
  .s_axis_tready(rsp_wod_pkt_ready_o),
  .m_axis_aclk(app_clk),
  .m_axis_aresetn(!app_reset),
  .m_axis_tdata({7'b0, cdc_rsp_wod_hdr_i, cdc_rsp_wod_pkt_size_i, cdc_rsp_wod_pkt_vc_i}),
  .m_axis_tvalid(cdc_rsp_wod_pkt_valid_i),
  .m_axis_tready(cdc_rsp_wod_pkt_ready_o)
);
axis_reg_eci_wod i_chan_pipe_eci_rsp_wod_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({7'b0, cdc_rsp_wod_hdr_i, cdc_rsp_wod_pkt_size_i, cdc_rsp_wod_pkt_vc_i}),
  .s_axis_tvalid(cdc_rsp_wod_pkt_valid_i),
  .s_axis_tready(cdc_rsp_wod_pkt_ready_o),
  .m_axis_tdata({7'b0, pipe_rsp_wod_hdr_i, pipe_rsp_wod_pkt_size_i, pipe_rsp_wod_pkt_vc_i}),
  .m_axis_tvalid(pipe_rsp_wod_pkt_valid_i),
  .m_axis_tready(pipe_rsp_wod_pkt_ready_o)
);


logic [(ECI_WORD_WIDTH * ECI_PACKET_SIZE)-1:0]          cdc_rsp_wd_pkt_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] cdc_rsp_wd_pkt_size_i;
logic [3:0]                       cdc_rsp_wd_pkt_vc_i;
logic                             cdc_rsp_wd_pkt_valid_i;
logic                             cdc_rsp_wd_pkt_ready_o;

logic [(ECI_WORD_WIDTH * ECI_PACKET_SIZE)-1:0]          pipe_rsp_wd_pkt_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_rsp_wd_pkt_size_i;
logic [3:0]                       pipe_rsp_wd_pkt_vc_i;
logic                             pipe_rsp_wd_pkt_valid_i;
logic                             pipe_rsp_wd_pkt_ready_o;
axis_cdc_eci_wd i_chan_cdc_rsp_wd_slave (
  .s_axis_aclk(eci_clk),
  .s_axis_aresetn(!eci_reset),
  .s_axis_tdata({7'b0, rsp_wd_pkt_i, rsp_wd_pkt_size_i, rsp_wd_pkt_vc_i}),
  .s_axis_tvalid(rsp_wd_pkt_valid_i),
  .s_axis_tready(rsp_wd_pkt_ready_o),
  .m_axis_aclk(app_clk),
  .m_axis_aresetn(!app_reset),
  .m_axis_tdata({7'b0, cdc_rsp_wd_pkt_i, cdc_rsp_wd_pkt_size_i, cdc_rsp_wd_pkt_vc_i}),
  .m_axis_tvalid(cdc_rsp_wd_pkt_valid_i),
  .m_axis_tready(cdc_rsp_wd_pkt_ready_o)
);
axis_reg_eci_wd i_chan_pipe_eci_rsp_wd_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({7'b0, cdc_rsp_wd_pkt_i, cdc_rsp_wd_pkt_size_i, cdc_rsp_wd_pkt_vc_i}),
  .s_axis_tvalid(cdc_rsp_wd_pkt_valid_i),
  .s_axis_tready(cdc_rsp_wd_pkt_ready_o),
  .m_axis_tdata({7'b0, pipe_rsp_wd_pkt_i, pipe_rsp_wd_pkt_size_i, pipe_rsp_wd_pkt_vc_i}),
  .m_axis_tvalid(pipe_rsp_wd_pkt_valid_i),
  .m_axis_tready(pipe_rsp_wd_pkt_ready_o)
);


logic [(ECI_WORD_WIDTH)-1:0]          cdc_rsp_wod_hdr_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] cdc_rsp_wod_pkt_size_o;
logic [3:0]                       cdc_rsp_wod_pkt_vc_o;
logic                             cdc_rsp_wod_pkt_valid_o;
logic                             cdc_rsp_wod_pkt_ready_i;

logic [(ECI_WORD_WIDTH)-1:0]          pipe_rsp_wod_hdr_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_rsp_wod_pkt_size_o;
logic [3:0]                       pipe_rsp_wod_pkt_vc_o;
logic                             pipe_rsp_wod_pkt_valid_o;
logic                             pipe_rsp_wod_pkt_ready_i;
axis_cdc_eci_wod i_chan_cdc_rsp_wod_master (
  .s_axis_aclk(app_clk),
  .s_axis_aresetn(!app_reset),
  .s_axis_tdata({7'b0, cdc_rsp_wod_hdr_o, cdc_rsp_wod_pkt_size_o, cdc_rsp_wod_pkt_vc_o}),
  .s_axis_tvalid(cdc_rsp_wod_pkt_valid_o),
  .s_axis_tready(cdc_rsp_wod_pkt_ready_i),
  .m_axis_aclk(eci_clk),
  .m_axis_aresetn(!eci_reset),
  .m_axis_tdata({7'b0, rsp_wod_hdr_o, rsp_wod_pkt_size_o, rsp_wod_pkt_vc_o}),
  .m_axis_tvalid(rsp_wod_pkt_valid_o),
  .m_axis_tready(rsp_wod_pkt_ready_i)
);
axis_reg_eci_wod i_chan_pipe_eci_rsp_wod_master (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({7'b0, pipe_rsp_wod_hdr_o, pipe_rsp_wod_pkt_size_o, pipe_rsp_wod_pkt_vc_o}),
  .s_axis_tvalid(pipe_rsp_wod_pkt_valid_o),
  .s_axis_tready(pipe_rsp_wod_pkt_ready_i),
  .m_axis_tdata({7'b0, cdc_rsp_wod_hdr_o, cdc_rsp_wod_pkt_size_o, cdc_rsp_wod_pkt_vc_o}),
  .m_axis_tvalid(cdc_rsp_wod_pkt_valid_o),
  .m_axis_tready(cdc_rsp_wod_pkt_ready_i)
);


logic [(ECI_WORD_WIDTH * ECI_PACKET_SIZE)-1:0]          cdc_rsp_wd_pkt_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] cdc_rsp_wd_pkt_size_o;
logic [3:0]                       cdc_rsp_wd_pkt_vc_o;
logic                             cdc_rsp_wd_pkt_valid_o;
logic                             cdc_rsp_wd_pkt_ready_i;

logic [(ECI_WORD_WIDTH * ECI_PACKET_SIZE)-1:0]          pipe_rsp_wd_pkt_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_rsp_wd_pkt_size_o;
logic [3:0]                       pipe_rsp_wd_pkt_vc_o;
logic                             pipe_rsp_wd_pkt_valid_o;
logic                             pipe_rsp_wd_pkt_ready_i;
axis_cdc_eci_wd i_chan_cdc_rsp_wd_master (
  .s_axis_aclk(app_clk),
  .s_axis_aresetn(!app_reset),
  .s_axis_tdata({7'b0, cdc_rsp_wd_pkt_o, cdc_rsp_wd_pkt_size_o, cdc_rsp_wd_pkt_vc_o}),
  .s_axis_tvalid(cdc_rsp_wd_pkt_valid_o),
  .s_axis_tready(cdc_rsp_wd_pkt_ready_i),
  .m_axis_aclk(eci_clk),
  .m_axis_aresetn(!eci_reset),
  .m_axis_tdata({7'b0, rsp_wd_pkt_o, rsp_wd_pkt_size_o, rsp_wd_pkt_vc_o}),
  .m_axis_tvalid(rsp_wd_pkt_valid_o),
  .m_axis_tready(rsp_wd_pkt_ready_i)
);
axis_reg_eci_wd i_chan_pipe_eci_rsp_wd_master (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({7'b0, pipe_rsp_wd_pkt_o, pipe_rsp_wd_pkt_size_o, pipe_rsp_wd_pkt_vc_o}),
  .s_axis_tvalid(pipe_rsp_wd_pkt_valid_o),
  .s_axis_tready(pipe_rsp_wd_pkt_ready_i),
  .m_axis_tdata({7'b0, cdc_rsp_wd_pkt_o, cdc_rsp_wd_pkt_size_o, cdc_rsp_wd_pkt_vc_o}),
  .m_axis_tvalid(cdc_rsp_wd_pkt_valid_o),
  .m_axis_tready(cdc_rsp_wd_pkt_ready_i)
);


logic [(ECI_WORD_WIDTH)-1:0]          cdc_fwd_wod_hdr_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] cdc_fwd_wod_pkt_size_o;
logic [3:0]                       cdc_fwd_wod_pkt_vc_o;
logic                             cdc_fwd_wod_pkt_valid_o;
logic                             cdc_fwd_wod_pkt_ready_i;

logic [(ECI_WORD_WIDTH)-1:0]          pipe_fwd_wod_hdr_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_fwd_wod_pkt_size_o;
logic [3:0]                       pipe_fwd_wod_pkt_vc_o;
logic                             pipe_fwd_wod_pkt_valid_o;
logic                             pipe_fwd_wod_pkt_ready_i;
axis_cdc_eci_wod i_chan_cdc_fwd_wod_master (
  .s_axis_aclk(app_clk),
  .s_axis_aresetn(!app_reset),
  .s_axis_tdata({7'b0, cdc_fwd_wod_hdr_o, cdc_fwd_wod_pkt_size_o, cdc_fwd_wod_pkt_vc_o}),
  .s_axis_tvalid(cdc_fwd_wod_pkt_valid_o),
  .s_axis_tready(cdc_fwd_wod_pkt_ready_i),
  .m_axis_aclk(eci_clk),
  .m_axis_aresetn(!eci_reset),
  .m_axis_tdata({7'b0, fwd_wod_hdr_o, fwd_wod_pkt_size_o, fwd_wod_pkt_vc_o}),
  .m_axis_tvalid(fwd_wod_pkt_valid_o),
  .m_axis_tready(fwd_wod_pkt_ready_i)
);
axis_reg_eci_wod i_chan_pipe_eci_fwd_wod_master (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({7'b0, pipe_fwd_wod_hdr_o, pipe_fwd_wod_pkt_size_o, pipe_fwd_wod_pkt_vc_o}),
  .s_axis_tvalid(pipe_fwd_wod_pkt_valid_o),
  .s_axis_tready(pipe_fwd_wod_pkt_ready_i),
  .m_axis_tdata({7'b0, cdc_fwd_wod_hdr_o, cdc_fwd_wod_pkt_size_o, cdc_fwd_wod_pkt_vc_o}),
  .m_axis_tvalid(cdc_fwd_wod_pkt_valid_o),
  .m_axis_tready(cdc_fwd_wod_pkt_ready_i)
);

logic [ECI_WORD_WIDTH-1:0]        pipe_lcl_fwd_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_lcl_fwd_wod_pkt_size_i;
logic [3:0]                       pipe_lcl_fwd_wod_pkt_vc_i;
logic                             pipe_lcl_fwd_wod_pkt_valid_i;
logic                             pipe_lcl_fwd_wod_pkt_ready_o;
axis_reg_eci_wod i_chan_pipe_lcl_fwd_wod_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({7'b0, lcl_fwd_wod_hdr_i, lcl_fwd_wod_pkt_size_i, lcl_fwd_wod_pkt_vc_i}),
  .s_axis_tvalid(lcl_fwd_wod_pkt_valid_i),
  .s_axis_tready(lcl_fwd_wod_pkt_ready_o),
  .m_axis_tdata({7'b0, pipe_lcl_fwd_wod_hdr_i, pipe_lcl_fwd_wod_pkt_size_i, pipe_lcl_fwd_wod_pkt_vc_i}),
  .m_axis_tvalid(pipe_lcl_fwd_wod_pkt_valid_i),
  .m_axis_tready(pipe_lcl_fwd_wod_pkt_ready_o)
);

logic [ECI_WORD_WIDTH-1:0]        pipe_lcl_rsp_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_lcl_rsp_wod_pkt_size_i;
logic [3:0]                       pipe_lcl_rsp_wod_pkt_vc_i;
logic                             pipe_lcl_rsp_wod_pkt_valid_i;
logic                             pipe_lcl_rsp_wod_pkt_ready_o;
axis_reg_eci_wod i_chan_pipe_lcl_rsp_wod_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({7'b0, lcl_rsp_wod_hdr_i, lcl_rsp_wod_pkt_size_i, lcl_rsp_wod_pkt_vc_i}),
  .s_axis_tvalid(lcl_rsp_wod_pkt_valid_i),
  .s_axis_tready(lcl_rsp_wod_pkt_ready_o),
  .m_axis_tdata({7'b0, pipe_lcl_rsp_wod_hdr_i, pipe_lcl_rsp_wod_pkt_size_i, pipe_lcl_rsp_wod_pkt_vc_i}),
  .m_axis_tvalid(pipe_lcl_rsp_wod_pkt_valid_i),
  .m_axis_tready(pipe_lcl_rsp_wod_pkt_ready_o)
);

logic [ECI_WORD_WIDTH-1:0]        pipe_lcl_rsp_wod_hdr_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] pipe_lcl_rsp_wod_pkt_size_o;
logic [3:0]                       pipe_lcl_rsp_wod_pkt_vc_o;
logic                             pipe_lcl_rsp_wod_pkt_valid_o;
logic                             pipe_lcl_rsp_wod_pkt_ready_i;
axis_reg_eci_wod i_chan_pipe_lcl_rsp_wod_master (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .m_axis_tdata({7'b0, lcl_rsp_wod_hdr_o, lcl_rsp_wod_pkt_size_o, lcl_rsp_wod_pkt_vc_o}),
  .m_axis_tvalid(lcl_rsp_wod_pkt_valid_o),
  .m_axis_tready(lcl_rsp_wod_pkt_ready_i),
  .s_axis_tdata({7'b0, pipe_lcl_rsp_wod_hdr_o, pipe_lcl_rsp_wod_pkt_size_o, pipe_lcl_rsp_wod_pkt_vc_o}),
  .s_axis_tvalid(pipe_lcl_rsp_wod_pkt_valid_o),
  .s_axis_tready(pipe_lcl_rsp_wod_pkt_ready_i)
);

logic [AXI_ID_WIDTH-1:0]               p_axi_arid;
logic [AXI_ADDR_WIDTH-1:0]             p_axi_araddr;
logic [7:0]                            p_axi_arlen;
logic [2:0]                            p_axi_arsize;
logic [1:0]                            p_axi_arburst;
logic                                  p_axi_arlock;
logic [3:0]                            p_axi_arcache;
logic [2:0]                            p_axi_arprot;
logic                                  p_axi_arvalid;
logic                                  p_axi_arready;
logic [AXI_ID_WIDTH-1:0]               p_axi_rid;
logic [AXI_DATA_WIDTH-1:0]             p_axi_rdata;
logic [1:0]                            p_axi_rresp;
logic                                  p_axi_rlast;
logic                                  p_axi_rvalid;
logic                                  p_axi_rready;

logic [AXI_ID_WIDTH-1:0]               p_axi_awid;
logic [AXI_ADDR_WIDTH-1:0]             p_axi_awaddr;
logic [7:0]                            p_axi_awlen;
logic [2:0]                            p_axi_awsize;
logic [1:0]                            p_axi_awburst;
logic                                  p_axi_awlock;
logic [3:0]                            p_axi_awcache;
logic [2:0]                            p_axi_awprot;
logic                                  p_axi_awvalid;
logic                                  p_axi_awready;
logic [AXI_DATA_WIDTH-1:0]             p_axi_wdata;
logic [AXI_STRB_WIDTH-1:0]             p_axi_wstrb;
logic                                  p_axi_wlast;
logic                                  p_axi_wvalid;
logic                                  p_axi_wready;
logic [AXI_ID_WIDTH-1:0]               p_axi_bid;
logic [1:0]                            p_axi_bresp;
logic                                  p_axi_bvalid;
logic                                  p_axi_bready;

axi_reg_dcs i_axi_pipe (
  .aclk(app_clk),                 // input wire aclk
  .aresetn(!app_reset),           // input wire aresetn
  .s_axi_awid(p_axi_awid),        // input wire [6 : 0] s_axi_awid
  .s_axi_awaddr(p_axi_awaddr),    // input wire [37 : 0] s_axi_awaddr
  .s_axi_awlen(p_axi_awlen),      // input wire [7 : 0] s_axi_awlen
  .s_axi_awsize(p_axi_awsize),    // input wire [2 : 0] s_axi_awsize
  .s_axi_awburst(p_axi_awburst),  // input wire [1 : 0] s_axi_awburst
  .s_axi_awlock(p_axi_awlock),    // input wire [0 : 0] s_axi_awlock
  .s_axi_awcache(p_axi_awcache),  // input wire [3 : 0] s_axi_awcache
  .s_axi_awprot(p_axi_awprot),    // input wire [2 : 0] s_axi_awprot
  .s_axi_awvalid(p_axi_awvalid),  // input wire s_axi_awvalid
  .s_axi_awready(p_axi_awready),  // output wire s_axi_awready
  .s_axi_wdata(p_axi_wdata),      // input wire [511 : 0] s_axi_wdata
  .s_axi_wstrb(p_axi_wstrb),      // input wire [63 : 0] s_axi_wstrb
  .s_axi_wlast(p_axi_wlast),      // input wire s_axi_wlast
  .s_axi_wvalid(p_axi_wvalid),    // input wire s_axi_wvalid
  .s_axi_wready(p_axi_wready),    // output wire s_axi_wready
  .s_axi_bid(p_axi_bid),          // output wire [6 : 0] s_axi_bid
  .s_axi_bresp(p_axi_bresp),      // output wire [1 : 0] s_axi_bresp
  .s_axi_bvalid(p_axi_bvalid),    // output wire s_axi_bvalid
  .s_axi_bready(p_axi_bready),    // input wire s_axi_bready
  .s_axi_arid(p_axi_arid),        // input wire [6 : 0] s_axi_arid
  .s_axi_araddr(p_axi_araddr),    // input wire [37 : 0] s_axi_araddr
  .s_axi_arlen(p_axi_arlen),      // input wire [7 : 0] s_axi_arlen
  .s_axi_arsize(p_axi_arsize),    // input wire [2 : 0] s_axi_arsize
  .s_axi_arburst(p_axi_arburst),  // input wire [1 : 0] s_axi_arburst
  .s_axi_arlock(p_axi_arlock),    // input wire [0 : 0] s_axi_arlock
  .s_axi_arcache(p_axi_arcache),  // input wire [3 : 0] s_axi_arcache
  .s_axi_arprot(p_axi_arprot),    // input wire [2 : 0] s_axi_arprot
  .s_axi_arvalid(p_axi_arvalid),  // input wire s_axi_arvalid
  .s_axi_arready(p_axi_arready),  // output wire s_axi_arready
  .s_axi_rid(p_axi_rid),          // output wire [6 : 0] s_axi_rid
  .s_axi_rdata(p_axi_rdata),      // output wire [511 : 0] s_axi_rdata
  .s_axi_rresp(p_axi_rresp),      // output wire [1 : 0] s_axi_rresp
  .s_axi_rlast(p_axi_rlast),      // output wire s_axi_rlast
  .s_axi_rvalid(p_axi_rvalid),    // output wire s_axi_rvalid
  .s_axi_rready(p_axi_rready),    // input wire s_axi_rready

  .m_axi_awid(m_axi_awid),        // output wire [6 : 0] m_axi_awid
  .m_axi_awaddr(m_axi_awaddr),    // output wire [37 : 0] m_axi_awaddr
  .m_axi_awlen(m_axi_awlen),      // output wire [7 : 0] m_axi_awlen
  .m_axi_awsize(m_axi_awsize),    // output wire [2 : 0] m_axi_awsize
  .m_axi_awburst(m_axi_awburst),  // output wire [1 : 0] m_axi_awburst
  .m_axi_awlock(m_axi_awlock),    // output wire [0 : 0] m_axi_awlock
  .m_axi_awcache(m_axi_awcache),  // output wire [3 : 0] m_axi_awcache
  .m_axi_awprot(m_axi_awprot),    // output wire [2 : 0] m_axi_awprot
  .m_axi_awvalid(m_axi_awvalid),  // output wire m_axi_awvalid
  .m_axi_awready(m_axi_awready),  // input wire m_axi_awready
  .m_axi_wdata(m_axi_wdata),      // output wire [511 : 0] m_axi_wdata
  .m_axi_wstrb(m_axi_wstrb),      // output wire [63 : 0] m_axi_wstrb
  .m_axi_wlast(m_axi_wlast),      // output wire m_axi_wlast
  .m_axi_wvalid(m_axi_wvalid),    // output wire m_axi_wvalid
  .m_axi_wready(m_axi_wready),    // input wire m_axi_wready
  .m_axi_bid(m_axi_bid),          // input wire [6 : 0] m_axi_bid
  .m_axi_bresp(m_axi_bresp),      // input wire [1 : 0] m_axi_bresp
  .m_axi_bvalid(m_axi_bvalid),    // input wire m_axi_bvalid
  .m_axi_bready(m_axi_bready),    // output wire m_axi_bready
  .m_axi_arid(m_axi_arid),        // output wire [6 : 0] m_axi_arid
  .m_axi_araddr(m_axi_araddr),    // output wire [37 : 0] m_axi_araddr
  .m_axi_arlen(m_axi_arlen),      // output wire [7 : 0] m_axi_arlen
  .m_axi_arsize(m_axi_arsize),    // output wire [2 : 0] m_axi_arsize
  .m_axi_arburst(m_axi_arburst),  // output wire [1 : 0] m_axi_arburst
  .m_axi_arlock(m_axi_arlock),    // output wire [0 : 0] m_axi_arlock
  .m_axi_arcache(m_axi_arcache),  // output wire [3 : 0] m_axi_arcache
  .m_axi_arprot(m_axi_arprot),    // output wire [2 : 0] m_axi_arprot
  .m_axi_arvalid(m_axi_arvalid),  // output wire m_axi_arvalid
  .m_axi_arready(m_axi_arready),  // input wire m_axi_arready
  .m_axi_rid(m_axi_rid),          // input wire [6 : 0] m_axi_rid
  .m_axi_rdata(m_axi_rdata),      // input wire [511 : 0] m_axi_rdata
  .m_axi_rresp(m_axi_rresp),      // input wire [1 : 0] m_axi_rresp
  .m_axi_rlast(m_axi_rlast),      // input wire m_axi_rlast
  .m_axi_rvalid(m_axi_rvalid),    // input wire m_axi_rvalid
  .m_axi_rready(m_axi_rready)    // output wire m_axi_rready
);

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


  .req_wod_hdr_i(pipe_req_wod_hdr_i),
  .req_wod_pkt_size_i(pipe_req_wod_pkt_size_i),
  .req_wod_pkt_vc_i(pipe_req_wod_pkt_vc_i),
  .req_wod_pkt_valid_i(pipe_req_wod_pkt_valid_i),
  .req_wod_pkt_ready_o(pipe_req_wod_pkt_ready_o),

  .rsp_wod_hdr_i(pipe_rsp_wod_hdr_i),
  .rsp_wod_pkt_size_i(pipe_rsp_wod_pkt_size_i),
  .rsp_wod_pkt_vc_i(pipe_rsp_wod_pkt_vc_i),
  .rsp_wod_pkt_valid_i(pipe_rsp_wod_pkt_valid_i),
  .rsp_wod_pkt_ready_o(pipe_rsp_wod_pkt_ready_o),

  .rsp_wd_pkt_i(pipe_rsp_wd_pkt_i),
  .rsp_wd_pkt_size_i(pipe_rsp_wd_pkt_size_i),
  .rsp_wd_pkt_vc_i(pipe_rsp_wd_pkt_vc_i),
  .rsp_wd_pkt_valid_i(pipe_rsp_wd_pkt_valid_i),
  .rsp_wd_pkt_ready_o(pipe_rsp_wd_pkt_ready_o),

  .rsp_wod_hdr_o(pipe_rsp_wod_hdr_o),
  .rsp_wod_pkt_size_o(pipe_rsp_wod_pkt_size_o),
  .rsp_wod_pkt_vc_o(pipe_rsp_wod_pkt_vc_o),
  .rsp_wod_pkt_valid_o(pipe_rsp_wod_pkt_valid_o),
  .rsp_wod_pkt_ready_i(pipe_rsp_wod_pkt_ready_i),

  .rsp_wd_pkt_o(pipe_rsp_wd_pkt_o),
  .rsp_wd_pkt_size_o(pipe_rsp_wd_pkt_size_o),
  .rsp_wd_pkt_vc_o(pipe_rsp_wd_pkt_vc_o),
  .rsp_wd_pkt_valid_o(pipe_rsp_wd_pkt_valid_o),
  .rsp_wd_pkt_ready_i(pipe_rsp_wd_pkt_ready_i),

  .fwd_wod_hdr_o(pipe_fwd_wod_hdr_o),
  .fwd_wod_pkt_size_o(pipe_fwd_wod_pkt_size_o),
  .fwd_wod_pkt_vc_o(pipe_fwd_wod_pkt_vc_o),
  .fwd_wod_pkt_valid_o(pipe_fwd_wod_pkt_valid_o),
  .fwd_wod_pkt_ready_i(pipe_fwd_wod_pkt_ready_i),

  .lcl_fwd_wod_hdr_i(pipe_lcl_fwd_wod_hdr_i),
  .lcl_fwd_wod_pkt_size_i(pipe_lcl_fwd_wod_pkt_size_i),
  .lcl_fwd_wod_pkt_vc_i(pipe_lcl_fwd_wod_pkt_vc_i), //5
  .lcl_fwd_wod_pkt_valid_i(pipe_lcl_fwd_wod_pkt_valid_i),
  .lcl_fwd_wod_pkt_ready_o(pipe_lcl_fwd_wod_pkt_ready_o),

  .lcl_rsp_wod_hdr_i(pipe_lcl_rsp_wod_hdr_i),
  .lcl_rsp_wod_pkt_size_i(pipe_lcl_rsp_wod_pkt_size_i),
  .lcl_rsp_wod_pkt_vc_i(pipe_lcl_rsp_wod_pkt_vc_i),
  .lcl_rsp_wod_pkt_valid_i(pipe_lcl_rsp_wod_pkt_valid_i),
  .lcl_rsp_wod_pkt_ready_o(pipe_lcl_rsp_wod_pkt_ready_o),

  .lcl_rsp_wod_hdr_o(pipe_lcl_rsp_wod_hdr_o),
  .lcl_rsp_wod_pkt_size_o(pipe_lcl_rsp_wod_pkt_size_o),
  .lcl_rsp_wod_pkt_vc_o(pipe_lcl_rsp_wod_pkt_vc_o),//5
  .lcl_rsp_wod_pkt_valid_o(pipe_lcl_rsp_wod_pkt_valid_o),
  .lcl_rsp_wod_pkt_ready_i(pipe_lcl_rsp_wod_pkt_ready_i),

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
