`resetall
`timescale 1ns / 1ps
`default_nettype none

module sync_reset_bufg #
(
    // depth of synchronizer
    parameter N = 2
)
(
    input  wire clk,
    input  wire rst,
    output wire out
);

wire rst_unbuf;

sync_reset #(
    .N
) sync (
    .clk,
    .rst,
    .out(rst_unbuf)
);

BUFG rst_buf (
    .I(rst_unbuf),
    .O(out)
);

endmodule

`resetall
