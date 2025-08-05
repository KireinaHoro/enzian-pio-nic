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
  .s_axis_tdata({req_wod_hdr_i, req_wod_pkt_size_i, req_wod_pkt_vc_i}),
  .s_axis_tvalid(req_wod_pkt_valid_i),
  .s_axis_tready(req_wod_pkt_ready_o),
  .m_axis_aclk(app_clk),
  .m_axis_aresetn(!app_reset),
  .m_axis_tdata({cdc_req_wod_hdr_i, cdc_req_wod_pkt_size_i, cdc_req_wod_pkt_vc_i}),
  .m_axis_tvalid(cdc_req_wod_pkt_valid_i),
  .m_axis_tready(cdc_req_wod_pkt_ready_o)
);
axis_reg_eci_wod i_chan_pipe_req_wod_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({cdc_req_wod_hdr_i, cdc_req_wod_pkt_size_i, cdc_req_wod_pkt_vc_i}),
  .s_axis_tvalid(cdc_req_wod_pkt_valid_i),
  .s_axis_tready(cdc_req_wod_pkt_ready_o),
  .m_axis_tdata({pipe_req_wod_hdr_i, pipe_req_wod_pkt_size_i, pipe_req_wod_pkt_vc_i}),
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
  .s_axis_tdata({rsp_wod_hdr_i, rsp_wod_pkt_size_i, rsp_wod_pkt_vc_i}),
  .s_axis_tvalid(rsp_wod_pkt_valid_i),
  .s_axis_tready(rsp_wod_pkt_ready_o),
  .m_axis_aclk(app_clk),
  .m_axis_aresetn(!app_reset),
  .m_axis_tdata({cdc_rsp_wod_hdr_i, cdc_rsp_wod_pkt_size_i, cdc_rsp_wod_pkt_vc_i}),
  .m_axis_tvalid(cdc_rsp_wod_pkt_valid_i),
  .m_axis_tready(cdc_rsp_wod_pkt_ready_o)
);
axis_reg_eci_wod i_chan_pipe_rsp_wod_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({cdc_rsp_wod_hdr_i, cdc_rsp_wod_pkt_size_i, cdc_rsp_wod_pkt_vc_i}),
  .s_axis_tvalid(cdc_rsp_wod_pkt_valid_i),
  .s_axis_tready(cdc_rsp_wod_pkt_ready_o),
  .m_axis_tdata({pipe_rsp_wod_hdr_i, pipe_rsp_wod_pkt_size_i, pipe_rsp_wod_pkt_vc_i}),
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
  .s_axis_tdata({rsp_wd_pkt_i, rsp_wd_pkt_size_i, rsp_wd_pkt_vc_i}),
  .s_axis_tvalid(rsp_wd_pkt_valid_i),
  .s_axis_tready(rsp_wd_pkt_ready_o),
  .m_axis_aclk(app_clk),
  .m_axis_aresetn(!app_reset),
  .m_axis_tdata({cdc_rsp_wd_pkt_i, cdc_rsp_wd_pkt_size_i, cdc_rsp_wd_pkt_vc_i}),
  .m_axis_tvalid(cdc_rsp_wd_pkt_valid_i),
  .m_axis_tready(cdc_rsp_wd_pkt_ready_o)
);
axis_reg_eci_wd i_chan_pipe_rsp_wd_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({cdc_rsp_wd_pkt_i, cdc_rsp_wd_pkt_size_i, cdc_rsp_wd_pkt_vc_i}),
  .s_axis_tvalid(cdc_rsp_wd_pkt_valid_i),
  .s_axis_tready(cdc_rsp_wd_pkt_ready_o),
  .m_axis_tdata({pipe_rsp_wd_pkt_i, pipe_rsp_wd_pkt_size_i, pipe_rsp_wd_pkt_vc_i}),
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
  .s_axis_tdata({cdc_rsp_wod_hdr_o, cdc_rsp_wod_pkt_size_o, cdc_rsp_wod_pkt_vc_o}),
  .s_axis_tvalid(cdc_rsp_wod_pkt_valid_o),
  .s_axis_tready(cdc_rsp_wod_pkt_ready_i),
  .m_axis_aclk(eci_clk),
  .m_axis_aresetn(!eci_reset),
  .m_axis_tdata({rsp_wod_hdr_o, rsp_wod_pkt_size_o, rsp_wod_pkt_vc_o}),
  .m_axis_tvalid(rsp_wod_pkt_valid_o),
  .m_axis_tready(rsp_wod_pkt_ready_i)
);
axis_reg_eci_wod i_chan_pipe_rsp_wod_master (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({pipe_rsp_wod_hdr_o, pipe_rsp_wod_pkt_size_o, pipe_rsp_wod_pkt_vc_o}),
  .s_axis_tvalid(pipe_rsp_wod_pkt_valid_o),
  .s_axis_tready(pipe_rsp_wod_pkt_ready_i),
  .m_axis_tdata({cdc_rsp_wod_hdr_o, cdc_rsp_wod_pkt_size_o, cdc_rsp_wod_pkt_vc_o}),
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
  .s_axis_tdata({cdc_rsp_wd_pkt_o, cdc_rsp_wd_pkt_size_o, cdc_rsp_wd_pkt_vc_o}),
  .s_axis_tvalid(cdc_rsp_wd_pkt_valid_o),
  .s_axis_tready(cdc_rsp_wd_pkt_ready_i),
  .m_axis_aclk(eci_clk),
  .m_axis_aresetn(!eci_reset),
  .m_axis_tdata({rsp_wd_pkt_o, rsp_wd_pkt_size_o, rsp_wd_pkt_vc_o}),
  .m_axis_tvalid(rsp_wd_pkt_valid_o),
  .m_axis_tready(rsp_wd_pkt_ready_i)
);
axis_reg_eci_wd i_chan_pipe_rsp_wd_master (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({pipe_rsp_wd_pkt_o, pipe_rsp_wd_pkt_size_o, pipe_rsp_wd_pkt_vc_o}),
  .s_axis_tvalid(pipe_rsp_wd_pkt_valid_o),
  .s_axis_tready(pipe_rsp_wd_pkt_ready_i),
  .m_axis_tdata({cdc_rsp_wd_pkt_o, cdc_rsp_wd_pkt_size_o, cdc_rsp_wd_pkt_vc_o}),
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
  .s_axis_tdata({cdc_fwd_wod_hdr_o, cdc_fwd_wod_pkt_size_o, cdc_fwd_wod_pkt_vc_o}),
  .s_axis_tvalid(cdc_fwd_wod_pkt_valid_o),
  .s_axis_tready(cdc_fwd_wod_pkt_ready_i),
  .m_axis_aclk(eci_clk),
  .m_axis_aresetn(!eci_reset),
  .m_axis_tdata({fwd_wod_hdr_o, fwd_wod_pkt_size_o, fwd_wod_pkt_vc_o}),
  .m_axis_tvalid(fwd_wod_pkt_valid_o),
  .m_axis_tready(fwd_wod_pkt_ready_i)
);
axis_reg_eci_wd i_chan_pipe_fwd_wod_master (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({pipe_fwd_wod_hdr_o, pipe_fwd_wod_pkt_size_o, pipe_fwd_wod_pkt_vc_o}),
  .s_axis_tvalid(pipe_fwd_wod_pkt_valid_o),
  .s_axis_tready(pipe_fwd_wod_pkt_ready_i),
  .m_axis_tdata({cdc_fwd_wod_hdr_o, cdc_fwd_wod_pkt_size_o, cdc_fwd_wod_pkt_vc_o}),
  .m_axis_tvalid(cdc_fwd_wod_pkt_valid_o),
  .m_axis_tready(cdc_fwd_wod_pkt_ready_i)
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
