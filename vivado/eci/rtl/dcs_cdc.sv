`ifndef DCS_CDC_SV
`define DCS_CDC_SV

import eci_cmd_defs::*;
import eci_dcs_defs::*;

module cross_chan #(
    parameter FIRST_CDC = 0,
    parameter WITH_DATA = 0,
    localparam PKT_WIDTH = WITH_DATA ?
        ECI_PACKET_SIZE * ECI_WORD_WIDTH :
        ECI_WORD_WIDTH
) (
    input logic                                   in_reset,
    input logic                                   in_clk,

    input logic [PKT_WIDTH-1:0]                   in,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       in_size,
    input logic [3:0]                             in_vc,
    input logic                                   in_valid,
    output logic                                  in_ready,

    input logic                                   out_reset,
    input logic                                   out_clk,

    output logic [PKT_WIDTH-1:0]                  out,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0]      out_size,
    output logic [3:0]                            out_vc,
    output logic                                  out_valid,
    input logic                                   out_ready
);

localparam FRAG_WIDTH = WITH_DATA ? 69 * 8 : 10 * 8;

logic [FRAG_WIDTH-1:0]    in_frag;
logic                     in_frag_valid;
logic                     in_frag_ready;

logic [FRAG_WIDTH-1:0]    cdc_frag;
logic                     cdc_frag_valid;
logic                     cdc_frag_ready;

logic [FRAG_WIDTH-1:0]    pipe_frag;
logic                     pipe_frag_valid;
logic                     pipe_frag_ready;

logic [FRAG_WIDTH-1:0]    out_frag;
logic                     out_frag_valid;
logic                     out_frag_ready;

generate
if (WITH_DATA == 0) begin

assign in_frag = {7'b0, in, in_size, in_vc};
assign in_frag_valid = in_valid;
assign in_ready = in_frag_ready;

assign {out, out_size, out_vc} = out_frag;
assign out_valid = out_frag_valid;
assign out_frag_ready = out_ready;

if (FIRST_CDC == 0) begin
// pipeline first then CDC -- pipeline regs in in clock domain
axis_reg_eci_wod i_pipe (
  .aclk(in_clk),
  .aresetn(!in_reset),
  .s_axis_tdata(in_frag),
  .s_axis_tvalid(in_frag_valid),
  .s_axis_tready(in_frag_ready),
  .m_axis_tdata(pipe_frag),
  .m_axis_tvalid(pipe_frag_valid),
  .m_axis_tready(pipe_frag_ready)
);
axis_cdc_eci_wod i_cdc (
  .s_axis_aclk(in_clk),
  .s_axis_aresetn(!in_reset),
  .s_axis_tdata(pipe_frag),
  .s_axis_tvalid(pipe_frag_valid),
  .s_axis_tready(pipe_frag_ready),
  .m_axis_aclk(out_clk),
  .m_axis_aresetn(!out_reset),
  .m_axis_tdata(out_frag),
  .m_axis_tvalid(out_frag_valid),
  .m_axis_tready(out_frag_ready)
);
end else begin
// CDC first then pipeline -- pipeline regs in out clock domain
axis_cdc_eci_wod i_cdc (
  .s_axis_aclk(in_clk),
  .s_axis_aresetn(!in_reset),
  .s_axis_tdata(in_frag),
  .s_axis_tvalid(in_frag_valid),
  .s_axis_tready(in_frag_ready),
  .m_axis_aclk(out_clk),
  .m_axis_aresetn(!out_reset),
  .m_axis_tdata(cdc_frag),
  .m_axis_tvalid(cdc_frag_valid),
  .m_axis_tready(cdc_frag_ready)
);
axis_reg_eci_wod i_pipe (
  .aclk(out_clk),
  .aresetn(!out_reset),
  .s_axis_tdata(cdc_frag),
  .s_axis_tvalid(cdc_frag_valid),
  .s_axis_tready(cdc_frag_ready),
  .m_axis_tdata(out_frag),
  .m_axis_tvalid(out_frag_valid),
  .m_axis_tready(out_frag_ready)
);
end // FIRST_CDC

end else begin

// fragment input ECI bus
axis_downsize_eci_wd i_downsize (
  .aclk(in_clk),
  .aresetn(!in_reset),
  .s_axis_tdata({7'b0, in, in_size, in_vc}),
  .s_axis_tvalid(in_valid),
  .s_axis_tready(in_ready),
  .m_axis_tdata(in_frag),
  .m_axis_tvalid(in_frag_valid),
  .m_axis_tready(in_frag_ready)
);
// reassemble ECI bus
axis_upsize_eci_wd i_upsize (
  .aclk(out_clk),
  .aresetn(!out_reset),
  .s_axis_tdata(out_frag),
  .s_axis_tvalid(out_frag_valid),
  .s_axis_tready(out_frag_ready),
  .m_axis_tdata({7'b0, out, out_size, out_vc}),
  .m_axis_tvalid(out_valid),
  .m_axis_tready(out_ready)
);

if (FIRST_CDC == 0) begin
// pipeline first then CDC -- pipeline regs in in clock domain
axis_reg_eci_wd i_pipe (
  .aclk(in_clk),
  .aresetn(!in_reset),
  .s_axis_tdata(in_frag),
  .s_axis_tvalid(in_frag_valid),
  .s_axis_tready(in_frag_ready),
  .m_axis_tdata(pipe_frag),
  .m_axis_tvalid(pipe_frag_valid),
  .m_axis_tready(pipe_frag_ready)
);
axis_cdc_eci_wd i_cdc (
  .s_axis_aclk(in_clk),
  .s_axis_aresetn(!in_reset),
  .s_axis_tdata(pipe_frag),
  .s_axis_tvalid(pipe_frag_valid),
  .s_axis_tready(pipe_frag_ready),
  .m_axis_aclk(out_clk),
  .m_axis_aresetn(!out_reset),
  .m_axis_tdata(out_frag),
  .m_axis_tvalid(out_frag_valid),
  .m_axis_tready(out_frag_ready)
);
end else begin
// CDC first then pipeline -- pipeline regs in out clock domain
axis_cdc_eci_wd i_cdc (
  .s_axis_aclk(in_clk),
  .s_axis_aresetn(!in_reset),
  .s_axis_tdata(in_frag),
  .s_axis_tvalid(in_frag_valid),
  .s_axis_tready(in_frag_ready),
  .m_axis_aclk(out_clk),
  .m_axis_aresetn(!out_reset),
  .m_axis_tdata(cdc_frag),
  .m_axis_tvalid(cdc_frag_valid),
  .m_axis_tready(cdc_frag_ready)
);
axis_reg_eci_wd i_pipe (
  .aclk(out_clk),
  .aresetn(!out_reset),
  .s_axis_tdata(cdc_frag),
  .s_axis_tvalid(cdc_frag_valid),
  .s_axis_tready(cdc_frag_ready),
  .m_axis_tdata(out_frag),
  .m_axis_tvalid(out_frag_valid),
  .m_axis_tready(out_frag_ready)
);
end // FIRST_CDC

end // WITH_DATA

endgenerate

endmodule

module dcs_cdc #(
   parameter AXI_ID_WIDTH = MAX_DCU_ID_WIDTH, //7
   parameter AXI_ADDR_WIDTH = DS_ADDR_WIDTH,  //38
   parameter AXI_DATA_WIDTH = 512,
   localparam AXI_STRB_WIDTH = (AXI_DATA_WIDTH/8),
   parameter PERF_REGS_WIDTH = 32,
   parameter SYNTH_PERF_REGS = 1 //0,1
) (
    // ===== interfaces toward ECI gateway are clocked with eci_clk =====
    input logic                                   eci_reset,
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
    output logic                                  m_axi_bready,

    // DCS event trace output.
    output logic        trace_dcs_event_valid[2],
    output logic        trace_dcs_event_error[2],
    output logic [39:0] trace_dcs_event_cli[2],
    output logic [6:0]  trace_dcs_event_state[2],
    output logic [3:0]  trace_dcs_event_action[2],
    output logic [4:0]  trace_dcs_event_request[2],

    // Packed ECI frame trace payloads for the global trace DMA.
    output logic [5:0]   trace_eci_app_valid,
    output logic [127:0] trace_eci_app_payload[6],
    output logic [5:0]   trace_eci_sys_valid,
    output logic [127:0] trace_eci_sys_payload[6]
);

// Use 1024b interface with the DC since the ECI to AXI converters
// do not support interleaved R channel (it blindly assembles a
// 1024-bit response).

logic app_reset;

logic [AXI_ID_WIDTH-1:0]                p_axi_arid;
logic [AXI_ADDR_WIDTH-1:0]              p_axi_araddr;
logic [7:0]                             p_axi_arlen;
logic [2:0]                             p_axi_arsize;
logic [1:0]                             p_axi_arburst;
logic                                   p_axi_arlock;
logic [3:0]                             p_axi_arcache;
logic [2:0]                             p_axi_arprot;
logic                                   p_axi_arvalid;
logic                                   p_axi_arready;
logic [AXI_ID_WIDTH-1:0]                p_axi_rid;
logic [AXI_DATA_WIDTH-1:0]              p_axi_rdata;
logic [1:0]                             p_axi_rresp;
logic                                   p_axi_rlast;
logic                                   p_axi_rvalid;
logic                                   p_axi_rready;

logic [AXI_ID_WIDTH-1:0]                p_axi_awid;
logic [AXI_ADDR_WIDTH-1:0]              p_axi_awaddr;
logic [7:0]                             p_axi_awlen;
logic [2:0]                             p_axi_awsize;
logic [1:0]                             p_axi_awburst;
logic                                   p_axi_awlock;
logic [3:0]                             p_axi_awcache;
logic [2:0]                             p_axi_awprot;
logic                                   p_axi_awvalid;
logic                                   p_axi_awready;
logic [AXI_DATA_WIDTH-1:0]              p_axi_wdata;
logic [AXI_STRB_WIDTH-1:0]              p_axi_wstrb;
logic                                   p_axi_wlast;
logic                                   p_axi_wvalid;
logic                                   p_axi_wready;
logic [AXI_ID_WIDTH-1:0]                p_axi_bid;
logic [1:0]                             p_axi_bresp;
logic                                   p_axi_bvalid;
logic                                   p_axi_bready;

localparam logic [3:0] TRACE_VERSION = 4'd0;
localparam logic [3:0] TRACE_KIND_ECI = 4'd1;

function automatic logic [127:0] pack_eci_trace(
    input logic [7:0]                       local_source,
    input logic [ECI_WORD_WIDTH-1:0]        data,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0] size,
    input logic [3:0]                       vc
);
    pack_eci_trace = {
        TRACE_VERSION,
        TRACE_KIND_ECI,
        local_source,
        38'b0,
        1'b0, vc,
        size,
        data
    };
endfunction

// Pipeline FIFO and CDC for all ECI channels.
// We first pipeline into SLR0/2, then CDC, to relieve congestion in SLR1.

xpm_cdc_sync_rst i_app_rst_sync (
    .dest_clk(app_clk),
    .dest_rst(app_reset),
    .src_rst(eci_reset)
);

logic [ECI_WORD_WIDTH-1:0]        xslr_req_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] xslr_req_wod_pkt_size_i;
logic [3:0]                       xslr_req_wod_pkt_vc_i;
logic                             xslr_req_wod_pkt_valid_i;
logic                             xslr_req_wod_pkt_ready_o;
cross_chan #(
    .WITH_DATA(0),
    .FIRST_CDC(0)
) i_cross_req_wod_slave (
    .in_reset               (eci_reset),
    .in_clk                 (eci_clk),
    .in                     (req_wod_hdr_i),
    .in_size                (req_wod_pkt_size_i),
    .in_vc                  (req_wod_pkt_vc_i),
    .in_valid               (req_wod_pkt_valid_i),
    .in_ready               (req_wod_pkt_ready_o),

    .out_reset              (app_reset),
    .out_clk                (app_clk),
    .out                    (xslr_req_wod_hdr_i),
    .out_size               (xslr_req_wod_pkt_size_i),
    .out_vc                 (xslr_req_wod_pkt_vc_i),
    .out_valid              (xslr_req_wod_pkt_valid_i),
    .out_ready              (xslr_req_wod_pkt_ready_o)
);

logic [ECI_WORD_WIDTH-1:0]        xslr_rsp_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] xslr_rsp_wod_pkt_size_i;
logic [3:0]                       xslr_rsp_wod_pkt_vc_i;
logic                             xslr_rsp_wod_pkt_valid_i;
logic                             xslr_rsp_wod_pkt_ready_o;
cross_chan #(
    .WITH_DATA(0),
    .FIRST_CDC(0)
) i_cross_rsp_wod_slave (
    .in_reset               (eci_reset),
    .in_clk                 (eci_clk),
    .in                     (rsp_wod_hdr_i),
    .in_size                (rsp_wod_pkt_size_i),
    .in_vc                  (rsp_wod_pkt_vc_i),
    .in_valid               (rsp_wod_pkt_valid_i),
    .in_ready               (rsp_wod_pkt_ready_o),

    .out_reset              (app_reset),
    .out_clk                (app_clk),
    .out                    (xslr_rsp_wod_hdr_i),
    .out_size               (xslr_rsp_wod_pkt_size_i),
    .out_vc                 (xslr_rsp_wod_pkt_vc_i),
    .out_valid              (xslr_rsp_wod_pkt_valid_i),
    .out_ready              (xslr_rsp_wod_pkt_ready_o)
);

logic [(ECI_WORD_WIDTH * ECI_PACKET_SIZE)-1:0]          xslr_rsp_wd_pkt_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0] xslr_rsp_wd_pkt_size_i;
logic [3:0]                       xslr_rsp_wd_pkt_vc_i;
logic                             xslr_rsp_wd_pkt_valid_i;
logic                             xslr_rsp_wd_pkt_ready_o;
cross_chan #(
    .WITH_DATA(1),
    .FIRST_CDC(0)
) i_cross_rsp_wd_slave (
    .in_reset               (eci_reset),
    .in_clk                 (eci_clk),
    .in                     (rsp_wd_pkt_i),
    .in_size                (rsp_wd_pkt_size_i),
    .in_vc                  (rsp_wd_pkt_vc_i),
    .in_valid               (rsp_wd_pkt_valid_i),
    .in_ready               (rsp_wd_pkt_ready_o),

    .out_reset              (app_reset),
    .out_clk                (app_clk),
    .out                    (xslr_rsp_wd_pkt_i),
    .out_size               (xslr_rsp_wd_pkt_size_i),
    .out_vc                 (xslr_rsp_wd_pkt_vc_i),
    .out_valid              (xslr_rsp_wd_pkt_valid_i),
    .out_ready              (xslr_rsp_wd_pkt_ready_o)
);

logic [(ECI_WORD_WIDTH)-1:0]      xslr_rsp_wod_hdr_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] xslr_rsp_wod_pkt_size_o;
logic [3:0]                       xslr_rsp_wod_pkt_vc_o;
logic                             xslr_rsp_wod_pkt_valid_o;
logic                             xslr_rsp_wod_pkt_ready_i;
cross_chan #(
    .WITH_DATA(0),
    .FIRST_CDC(1)
) i_cross_rsp_wod_master (
    .in_reset               (app_reset),
    .in_clk                 (app_clk),
    .in                     (xslr_rsp_wod_hdr_o),
    .in_size                (xslr_rsp_wod_pkt_size_o),
    .in_vc                  (xslr_rsp_wod_pkt_vc_o),
    .in_valid               (xslr_rsp_wod_pkt_valid_o),
    .in_ready               (xslr_rsp_wod_pkt_ready_i),

    .out_reset              (eci_reset),
    .out_clk                (eci_clk),
    .out                    (rsp_wod_hdr_o),
    .out_size               (rsp_wod_pkt_size_o),
    .out_vc                 (rsp_wod_pkt_vc_o),
    .out_valid              (rsp_wod_pkt_valid_o),
    .out_ready              (rsp_wod_pkt_ready_i)
);

logic [(ECI_WORD_WIDTH * ECI_PACKET_SIZE)-1:0]          xslr_rsp_wd_pkt_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] xslr_rsp_wd_pkt_size_o;
logic [3:0]                       xslr_rsp_wd_pkt_vc_o;
logic                             xslr_rsp_wd_pkt_valid_o;
logic                             xslr_rsp_wd_pkt_ready_i;
cross_chan #(
    .WITH_DATA(1),
    .FIRST_CDC(1)
) i_cross_rsp_wd_master (
    .in_reset               (app_reset),
    .in_clk                 (app_clk),
    .in                     (xslr_rsp_wd_pkt_o),
    .in_size                (xslr_rsp_wd_pkt_size_o),
    .in_vc                  (xslr_rsp_wd_pkt_vc_o),
    .in_valid               (xslr_rsp_wd_pkt_valid_o),
    .in_ready               (xslr_rsp_wd_pkt_ready_i),

    .out_reset              (eci_reset),
    .out_clk                (eci_clk),
    .out                    (rsp_wd_pkt_o),
    .out_size               (rsp_wd_pkt_size_o),
    .out_vc                 (rsp_wd_pkt_vc_o),
    .out_valid              (rsp_wd_pkt_valid_o),
    .out_ready              (rsp_wd_pkt_ready_i)
);

logic [(ECI_WORD_WIDTH)-1:0]          xslr_fwd_wod_hdr_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0] xslr_fwd_wod_pkt_size_o;
logic [3:0]                       xslr_fwd_wod_pkt_vc_o;
logic                             xslr_fwd_wod_pkt_valid_o;
logic                             xslr_fwd_wod_pkt_ready_i;
cross_chan #(
    .WITH_DATA(0),
    .FIRST_CDC(1)
) i_cross_fwd_wod_master (
    .in_reset               (app_reset),
    .in_clk                 (app_clk),
    .in                     (xslr_fwd_wod_hdr_o),
    .in_size                (xslr_fwd_wod_pkt_size_o),
    .in_vc                  (xslr_fwd_wod_pkt_vc_o),
    .in_valid               (xslr_fwd_wod_pkt_valid_o),
    .in_ready               (xslr_fwd_wod_pkt_ready_i),

    .out_reset              (eci_reset),
    .out_clk                (eci_clk),
    .out                    (fwd_wod_hdr_o),
    .out_size               (fwd_wod_pkt_size_o),
    .out_vc                 (fwd_wod_pkt_vc_o),
    .out_valid              (fwd_wod_pkt_valid_o),
    .out_ready              (fwd_wod_pkt_ready_i)
);

logic [ECI_WORD_WIDTH-1:0]            pipe_lcl_fwd_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0]     pipe_lcl_fwd_wod_pkt_size_i;
logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0] pipe_lcl_fwd_wod_pkt_vc_i;
logic                                 pipe_lcl_fwd_wod_pkt_valid_i;
logic                                 pipe_lcl_fwd_wod_pkt_ready_o;
axis_reg_eci_wod i_chan_pipe_lcl_fwd_wod_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({6'b0, lcl_fwd_wod_hdr_i, lcl_fwd_wod_pkt_size_i, lcl_fwd_wod_pkt_vc_i}),
  .s_axis_tvalid(lcl_fwd_wod_pkt_valid_i),
  .s_axis_tready(lcl_fwd_wod_pkt_ready_o),
  .m_axis_tdata({6'b0, pipe_lcl_fwd_wod_hdr_i, pipe_lcl_fwd_wod_pkt_size_i, pipe_lcl_fwd_wod_pkt_vc_i}),
  .m_axis_tvalid(pipe_lcl_fwd_wod_pkt_valid_i),
  .m_axis_tready(pipe_lcl_fwd_wod_pkt_ready_o)
);

logic [ECI_WORD_WIDTH-1:0]            pipe_lcl_rsp_wod_hdr_i;
logic [ECI_PACKET_SIZE_WIDTH-1:0]     pipe_lcl_rsp_wod_pkt_size_i;
logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0] pipe_lcl_rsp_wod_pkt_vc_i;
logic                                 pipe_lcl_rsp_wod_pkt_valid_i;
logic                                 pipe_lcl_rsp_wod_pkt_ready_o;
axis_reg_eci_wod i_chan_pipe_lcl_rsp_wod_slave (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .s_axis_tdata({6'b0, lcl_rsp_wod_hdr_i, lcl_rsp_wod_pkt_size_i, lcl_rsp_wod_pkt_vc_i}),
  .s_axis_tvalid(lcl_rsp_wod_pkt_valid_i),
  .s_axis_tready(lcl_rsp_wod_pkt_ready_o),
  .m_axis_tdata({6'b0, pipe_lcl_rsp_wod_hdr_i, pipe_lcl_rsp_wod_pkt_size_i, pipe_lcl_rsp_wod_pkt_vc_i}),
  .m_axis_tvalid(pipe_lcl_rsp_wod_pkt_valid_i),
  .m_axis_tready(pipe_lcl_rsp_wod_pkt_ready_o)
);

logic [ECI_WORD_WIDTH-1:0]            pipe_lcl_rsp_wod_hdr_o;
logic [ECI_PACKET_SIZE_WIDTH-1:0]     pipe_lcl_rsp_wod_pkt_size_o;
logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0] pipe_lcl_rsp_wod_pkt_vc_o;
logic                                 pipe_lcl_rsp_wod_pkt_valid_o;
logic                                 pipe_lcl_rsp_wod_pkt_ready_i;
axis_reg_eci_wod i_chan_pipe_lcl_rsp_wod_master (
  .aclk(app_clk),
  .aresetn(!app_reset),
  .m_axis_tdata({6'b0, lcl_rsp_wod_hdr_o, lcl_rsp_wod_pkt_size_o, lcl_rsp_wod_pkt_vc_o}),
  .m_axis_tvalid(lcl_rsp_wod_pkt_valid_o),
  .m_axis_tready(lcl_rsp_wod_pkt_ready_i),
  .s_axis_tdata({6'b0, pipe_lcl_rsp_wod_hdr_o, pipe_lcl_rsp_wod_pkt_size_o, pipe_lcl_rsp_wod_pkt_vc_o}),
  .s_axis_tvalid(pipe_lcl_rsp_wod_pkt_valid_o),
  .s_axis_tready(pipe_lcl_rsp_wod_pkt_ready_i)
);

axi_reg_dcs i_axi_pipe (
  .aclk(app_clk),                 // input wire aclk
  .aresetn(!app_reset),           // input wire aresetn

  .s_axi_awid(p_axi_awid),        // output wire [6 : 0] m_axi_awid
  .s_axi_awaddr(p_axi_awaddr),    // output wire [37 : 0] m_axi_awaddr
  .s_axi_awlen(p_axi_awlen),      // output wire [7 : 0] m_axi_awlen
  .s_axi_awsize(p_axi_awsize),    // output wire [2 : 0] m_axi_awsize
  .s_axi_awburst(p_axi_awburst),  // output wire [1 : 0] m_axi_awburst
  .s_axi_awlock(p_axi_awlock),    // output wire [0 : 0] m_axi_awlock
  .s_axi_awcache(p_axi_awcache),  // output wire [3 : 0] m_axi_awcache
  .s_axi_awprot(p_axi_awprot),    // output wire [2 : 0] m_axi_awprot
  .s_axi_awvalid(p_axi_awvalid),  // output wire m_axi_awvalid
  .s_axi_awready(p_axi_awready),  // input wire m_axi_awready
  .s_axi_wdata(p_axi_wdata),      // output wire [511 : 0] m_axi_wdata
  .s_axi_wstrb(p_axi_wstrb),      // output wire [63 : 0] m_axi_wstrb
  .s_axi_wlast(p_axi_wlast),      // output wire m_axi_wlast
  .s_axi_wvalid(p_axi_wvalid),    // output wire m_axi_wvalid
  .s_axi_wready(p_axi_wready),    // input wire m_axi_wready
  .s_axi_bid(p_axi_bid),          // input wire [6 : 0] m_axi_bid
  .s_axi_bresp(p_axi_bresp),      // input wire [1 : 0] m_axi_bresp
  .s_axi_bvalid(p_axi_bvalid),    // input wire m_axi_bvalid
  .s_axi_bready(p_axi_bready),    // output wire m_axi_bready
  .s_axi_arid(p_axi_arid),        // output wire [6 : 0] m_axi_arid
  .s_axi_araddr(p_axi_araddr),    // output wire [37 : 0] m_axi_araddr
  .s_axi_arlen(p_axi_arlen),      // output wire [7 : 0] m_axi_arlen
  .s_axi_arsize(p_axi_arsize),    // output wire [2 : 0] m_axi_arsize
  .s_axi_arburst(p_axi_arburst),  // output wire [1 : 0] m_axi_arburst
  .s_axi_arlock(p_axi_arlock),    // output wire [0 : 0] m_axi_arlock
  .s_axi_arcache(p_axi_arcache),  // output wire [3 : 0] m_axi_arcache
  .s_axi_arprot(p_axi_arprot),    // output wire [2 : 0] m_axi_arprot
  .s_axi_arvalid(p_axi_arvalid),  // output wire m_axi_arvalid
  .s_axi_arready(p_axi_arready),  // input wire m_axi_arready
  .s_axi_rid(p_axi_rid),          // input wire [6 : 0] m_axi_rid
  .s_axi_rdata(p_axi_rdata),      // input wire [511 : 0] m_axi_rdata
  .s_axi_rresp(p_axi_rresp),      // input wire [1 : 0] m_axi_rresp
  .s_axi_rlast(p_axi_rlast),      // input wire m_axi_rlast
  .s_axi_rvalid(p_axi_rvalid),    // input wire m_axi_rvalid
  .s_axi_rready(p_axi_rready),    // output wire m_axi_rready

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

  .req_wod_hdr_i(xslr_req_wod_hdr_i),
  .req_wod_pkt_size_i(xslr_req_wod_pkt_size_i),
  .req_wod_pkt_vc_i(xslr_req_wod_pkt_vc_i),
  .req_wod_pkt_valid_i(xslr_req_wod_pkt_valid_i),
  .req_wod_pkt_ready_o(xslr_req_wod_pkt_ready_o),

  .rsp_wod_hdr_i(xslr_rsp_wod_hdr_i),
  .rsp_wod_pkt_size_i(xslr_rsp_wod_pkt_size_i),
  .rsp_wod_pkt_vc_i(xslr_rsp_wod_pkt_vc_i),
  .rsp_wod_pkt_valid_i(xslr_rsp_wod_pkt_valid_i),
  .rsp_wod_pkt_ready_o(xslr_rsp_wod_pkt_ready_o),

  .rsp_wd_pkt_i(xslr_rsp_wd_pkt_i),
  .rsp_wd_pkt_size_i(xslr_rsp_wd_pkt_size_i),
  .rsp_wd_pkt_vc_i(xslr_rsp_wd_pkt_vc_i),
  .rsp_wd_pkt_valid_i(xslr_rsp_wd_pkt_valid_i),
  .rsp_wd_pkt_ready_o(xslr_rsp_wd_pkt_ready_o),

  .rsp_wod_hdr_o(xslr_rsp_wod_hdr_o),
  .rsp_wod_pkt_size_o(xslr_rsp_wod_pkt_size_o),
  .rsp_wod_pkt_vc_o(xslr_rsp_wod_pkt_vc_o),
  .rsp_wod_pkt_valid_o(xslr_rsp_wod_pkt_valid_o),
  .rsp_wod_pkt_ready_i(xslr_rsp_wod_pkt_ready_i),

  .rsp_wd_pkt_o(xslr_rsp_wd_pkt_o),
  .rsp_wd_pkt_size_o(xslr_rsp_wd_pkt_size_o),
  .rsp_wd_pkt_vc_o(xslr_rsp_wd_pkt_vc_o),
  .rsp_wd_pkt_valid_o(xslr_rsp_wd_pkt_valid_o),
  .rsp_wd_pkt_ready_i(xslr_rsp_wd_pkt_ready_i),

  .fwd_wod_hdr_o(xslr_fwd_wod_hdr_o),
  .fwd_wod_pkt_size_o(xslr_fwd_wod_pkt_size_o),
  .fwd_wod_pkt_vc_o(xslr_fwd_wod_pkt_vc_o),
  .fwd_wod_pkt_valid_o(xslr_fwd_wod_pkt_valid_o),
  .fwd_wod_pkt_ready_i(xslr_fwd_wod_pkt_ready_i),

  .lcl_fwd_wod_hdr_i(pipe_lcl_fwd_wod_hdr_i),
  .lcl_fwd_wod_pkt_size_i(pipe_lcl_fwd_wod_pkt_size_i),
  .lcl_fwd_wod_pkt_vc_i(pipe_lcl_fwd_wod_pkt_vc_i), // 5 bits!
  .lcl_fwd_wod_pkt_valid_i(pipe_lcl_fwd_wod_pkt_valid_i),
  .lcl_fwd_wod_pkt_ready_o(pipe_lcl_fwd_wod_pkt_ready_o),

  .lcl_rsp_wod_hdr_i(pipe_lcl_rsp_wod_hdr_i),
  .lcl_rsp_wod_pkt_size_i(pipe_lcl_rsp_wod_pkt_size_i),
  .lcl_rsp_wod_pkt_vc_i(pipe_lcl_rsp_wod_pkt_vc_i), // 5 bits!
  .lcl_rsp_wod_pkt_valid_i(pipe_lcl_rsp_wod_pkt_valid_i),
  .lcl_rsp_wod_pkt_ready_o(pipe_lcl_rsp_wod_pkt_ready_o),

  .lcl_rsp_wod_hdr_o(pipe_lcl_rsp_wod_hdr_o),
  .lcl_rsp_wod_pkt_size_o(pipe_lcl_rsp_wod_pkt_size_o),
  .lcl_rsp_wod_pkt_vc_o(pipe_lcl_rsp_wod_pkt_vc_o), // 5 bits!
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
  .p_axi_bready,

  // tracing interfaces
  .trace_dcs_event_valid,
  .trace_dcs_event_error,
  .trace_dcs_event_cli,
  .trace_dcs_event_state,
  .trace_dcs_event_action,
  .trace_dcs_event_request
);

// also trace ECI messages into the DC (before and after cross)
assign trace_eci_app_valid[0] = xslr_req_wod_pkt_valid_i && xslr_req_wod_pkt_ready_o;
assign trace_eci_app_valid[1] = xslr_rsp_wod_pkt_valid_i && xslr_rsp_wod_pkt_ready_o;
assign trace_eci_app_valid[2] = xslr_rsp_wd_pkt_valid_i && xslr_rsp_wd_pkt_ready_o;
assign trace_eci_app_valid[3] = xslr_rsp_wod_pkt_valid_o && xslr_rsp_wod_pkt_ready_i;
assign trace_eci_app_valid[4] = xslr_rsp_wd_pkt_valid_o && xslr_rsp_wd_pkt_ready_i;
assign trace_eci_app_valid[5] = xslr_fwd_wod_pkt_valid_o && xslr_fwd_wod_pkt_ready_i;

assign trace_eci_app_payload[0] = pack_eci_trace(8'd0, xslr_req_wod_hdr_i, xslr_req_wod_pkt_size_i, xslr_req_wod_pkt_vc_i);
assign trace_eci_app_payload[1] = pack_eci_trace(8'd1, xslr_rsp_wod_hdr_i, xslr_rsp_wod_pkt_size_i, xslr_rsp_wod_pkt_vc_i);
assign trace_eci_app_payload[2] = pack_eci_trace(8'd2, xslr_rsp_wd_pkt_i[ECI_WORD_WIDTH-1:0], xslr_rsp_wd_pkt_size_i, xslr_rsp_wd_pkt_vc_i);
assign trace_eci_app_payload[3] = pack_eci_trace(8'd3, xslr_rsp_wod_hdr_o, xslr_rsp_wod_pkt_size_o, xslr_rsp_wod_pkt_vc_o);
assign trace_eci_app_payload[4] = pack_eci_trace(8'd4, xslr_rsp_wd_pkt_o[ECI_WORD_WIDTH-1:0], xslr_rsp_wd_pkt_size_o, xslr_rsp_wd_pkt_vc_o);
assign trace_eci_app_payload[5] = pack_eci_trace(8'd5, xslr_fwd_wod_hdr_o, xslr_fwd_wod_pkt_size_o, xslr_fwd_wod_pkt_vc_o);

assign trace_eci_sys_valid[0] = req_wod_pkt_valid_i && req_wod_pkt_ready_o;
assign trace_eci_sys_valid[1] = rsp_wod_pkt_valid_i && rsp_wod_pkt_ready_o;
assign trace_eci_sys_valid[2] = rsp_wd_pkt_valid_i && rsp_wd_pkt_ready_o;
assign trace_eci_sys_valid[3] = rsp_wod_pkt_valid_o && rsp_wod_pkt_ready_i;
assign trace_eci_sys_valid[4] = rsp_wd_pkt_valid_o && rsp_wd_pkt_ready_i;
assign trace_eci_sys_valid[5] = fwd_wod_pkt_valid_o && fwd_wod_pkt_ready_i;

assign trace_eci_sys_payload[0] = pack_eci_trace(8'd0, req_wod_hdr_i, req_wod_pkt_size_i, req_wod_pkt_vc_i);
assign trace_eci_sys_payload[1] = pack_eci_trace(8'd1, rsp_wod_hdr_i, rsp_wod_pkt_size_i, rsp_wod_pkt_vc_i);
assign trace_eci_sys_payload[2] = pack_eci_trace(8'd2, rsp_wd_pkt_i[0], rsp_wd_pkt_size_i, rsp_wd_pkt_vc_i);
assign trace_eci_sys_payload[3] = pack_eci_trace(8'd3, rsp_wod_hdr_o, rsp_wod_pkt_size_o, rsp_wod_pkt_vc_o);
assign trace_eci_sys_payload[4] = pack_eci_trace(8'd4, rsp_wd_pkt_o[0], rsp_wd_pkt_size_o, rsp_wd_pkt_vc_o);
assign trace_eci_sys_payload[5] = pack_eci_trace(8'd5, fwd_wod_hdr_o, fwd_wod_pkt_size_o, fwd_wod_pkt_vc_o);

endmodule

`endif
