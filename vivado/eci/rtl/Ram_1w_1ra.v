module Ram_1w_1ra #(
        parameter integer wordCount = 0,
        parameter integer wordWidth = 0,
        parameter technology = "auto",
        parameter readUnderWrite = "dontCare",
        parameter integer wrAddressWidth = 0,
        parameter integer wrDataWidth = 0,
        parameter integer wrMaskWidth = 0,
        parameter wrMaskEnable = 1'b0,
        parameter integer rdAddressWidth = 0,
        parameter integer rdDataWidth  = 0
    )(
        input clk,
        input wr_en,
        input [wrMaskWidth-1:0] wr_mask,
        input [wrAddressWidth-1:0] wr_addr,
        input [wrDataWidth-1:0] wr_data,
        input [rdAddressWidth-1:0] rd_addr,
        output [rdDataWidth-1:0] rd_data
    );

    Ram_1w_1rs #(
        .wordCount(wordCount),
        .wordWidth(wordWidth),
        .technology(technology),
        .readUnderWrite(readUnderWrite),
        .wrAddressWidth(wrAddressWidth),
        .wrDataWidth(wrDataWidth),
        .wrMaskWidth(wrMaskWidth),
        .wrMaskEnable(wrMaskEnable),
        .rdAddressWidth(rdAddressWidth),
        .rdDataWidth(rdDataWidth)
    ) i_ram (
        .wr_clk(clk),
        .wr_en(wr_en),
        .wr_mask(wr_mask),
        .wr_addr(wr_addr),
        .wr_data(wr_data),
        .rd_clk(clk),
        .rd_en(1'b1),
        .rd_addr(rd_addr),
        .rd_data(rd_data)
    );
endmodule
