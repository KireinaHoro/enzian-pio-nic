// Platform DCS elastic stage. Keep the DCS interface while using the standard
// registered-output AXIS skid slice: downstream ready no longer feeds an
// expansion-data enable through the DCS destination-selection cone.
module axis_pipeline_stage #(parameter DATA_WIDTH = 32) (
    input wire clk, reset,
    input wire [DATA_WIDTH-1:0] us_data,
    input wire us_valid,
    output wire us_ready,
    output wire [DATA_WIDTH-1:0] ds_data,
    output wire ds_valid,
    input wire ds_ready
);
    axis_register #(.DATA_WIDTH(DATA_WIDTH), .KEEP_ENABLE(0), .KEEP_WIDTH(1),
        .LAST_ENABLE(0), .ID_ENABLE(0), .DEST_ENABLE(0), .USER_ENABLE(0),
        .REG_TYPE(2)) slice (
        .clk(clk), .rst(reset),
        .s_axis_tdata(us_data), .s_axis_tvalid(us_valid), .s_axis_tready(us_ready),
        .s_axis_tkeep(1'b1), .s_axis_tlast(1'b0), .s_axis_tid(8'b0),
        .s_axis_tdest(8'b0), .s_axis_tuser(1'b0),
        .m_axis_tdata(ds_data), .m_axis_tvalid(ds_valid), .m_axis_tready(ds_ready),
        .m_axis_tkeep(), .m_axis_tlast(), .m_axis_tid(), .m_axis_tdest(), .m_axis_tuser()
    );
endmodule
