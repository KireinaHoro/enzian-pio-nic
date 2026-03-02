import eci_cmd_defs::*;
import eci_dcs_defs::*;

module dcs_trace_eci (
    input logic                                   reset,
    input logic                                   clk,

    // ECI channels
    input logic [ECI_WORD_WIDTH-1:0]              req_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       req_wod_pkt_size_i,
    input logic [3:0]                             req_wod_pkt_vc_i,
    input logic                                   req_wod_pkt_valid_i,
    input logic                                  req_wod_pkt_ready_o,

    input logic [ECI_WORD_WIDTH-1:0]              rsp_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       rsp_wod_pkt_size_i,
    input logic [3:0]                             rsp_wod_pkt_vc_i,
    input logic                                   rsp_wod_pkt_valid_i,
    input logic                                  rsp_wod_pkt_ready_o,

    input logic [ECI_WORD_WIDTH-1:0] rsp_wd_pkt_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]       rsp_wd_pkt_size_i,
    input logic [3:0]                             rsp_wd_pkt_vc_i,
    input logic                                   rsp_wd_pkt_valid_i,
    input logic                                  rsp_wd_pkt_ready_o,

    input logic [ECI_WORD_WIDTH-1:0]             rsp_wod_hdr_o,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]      rsp_wod_pkt_size_o,
    input logic [3:0]                            rsp_wod_pkt_vc_o,
    input logic                                  rsp_wod_pkt_valid_o,
    input logic                                   rsp_wod_pkt_ready_i,

    input logic [ECI_WORD_WIDTH-1:0] rsp_wd_pkt_o,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]      rsp_wd_pkt_size_o,
    input logic [3:0]                            rsp_wd_pkt_vc_o,
    input logic                                  rsp_wd_pkt_valid_o,
    input logic                                   rsp_wd_pkt_ready_i,

    input logic [ECI_WORD_WIDTH-1:0]             fwd_wod_hdr_o,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0]      fwd_wod_pkt_size_o,
    input logic [3:0]                            fwd_wod_pkt_vc_o,
    input logic                                  fwd_wod_pkt_valid_o,
    input logic                                   fwd_wod_pkt_ready_i
);

logic dump;
logic sampleLost;

logic [63:0] traceOut_event_data;
logic [4:0]  traceOut_event_size;
logic [4:0]  traceOut_event_vc;
logic [2:0]  traceOut_src;
logic [63:0] traceOut_ts;

vio_dcs_eci i_vio (
    .clk,
    .probe_out0(dump)
);

ila_dcs_eci i_ila (
    .clk,
    .probe0(traceOut_event_data),
    .probe1(traceOut_event_size),
    .probe2(traceOut_event_vc),
    .probe3(traceOut_src),
    .probe4(traceOut_ts),
    .probe5(dump),
    .probe6(sampleLost)
);

dcs_eci_buf i_buf (
    .clk,
    .reset,

    .traceOut_event_data,
    .traceOut_event_size,
    .traceOut_event_vc,
    .traceOut_src,
    .traceOut_ts,
    .dump,
    .sampleLost,

    .traceIn_0_valid        (req_wod_pkt_valid_i && req_wod_pkt_ready_o),
    .traceIn_0_payload_data (req_wod_hdr_i),
    .traceIn_0_payload_size (req_wod_pkt_size_i),
    .traceIn_0_payload_vc   (req_wod_pkt_vc_i),

    .traceIn_1_valid        (rsp_wod_pkt_valid_i && rsp_wod_pkt_ready_o),
    .traceIn_1_payload_data (rsp_wod_hdr_i),
    .traceIn_1_payload_size (rsp_wod_pkt_size_i),
    .traceIn_1_payload_vc   (rsp_wod_pkt_vc_i),

    .traceIn_2_valid        (rsp_wd_pkt_valid_i && rsp_wd_pkt_ready_o),
    .traceIn_2_payload_data (rsp_wd_pkt_i),
    .traceIn_2_payload_size (rsp_wd_pkt_size_i),
    .traceIn_2_payload_vc   (rsp_wd_pkt_vc_i),

    .traceIn_3_valid        (rsp_wod_pkt_valid_o && rsp_wod_pkt_ready_i),
    .traceIn_3_payload_data (rsp_wod_hdr_o),
    .traceIn_3_payload_size (rsp_wod_pkt_size_o),
    .traceIn_3_payload_vc   (rsp_wod_pkt_vc_o),

    .traceIn_4_valid        (rsp_wd_pkt_valid_o && rsp_wd_pkt_ready_i),
    .traceIn_4_payload_data (rsp_wd_pkt_o),
    .traceIn_4_payload_size (rsp_wd_pkt_size_o),
    .traceIn_4_payload_vc   (rsp_wd_pkt_vc_o),

    .traceIn_5_valid        (fwd_wod_pkt_valid_o && fwd_wod_pkt_ready_i),
    .traceIn_5_payload_data (fwd_wod_hdr_o),
    .traceIn_5_payload_size (fwd_wod_pkt_size_o),
    .traceIn_5_payload_vc   (fwd_wod_pkt_vc_o)
);

endmodule
