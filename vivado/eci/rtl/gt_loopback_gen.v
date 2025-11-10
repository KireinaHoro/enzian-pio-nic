module gt_loopback_gen #(
    parameter NUM_CHANS = 4
) (
    input                        loopback,
    output reg [3*NUM_CHANS-1:0] gt_loopback_in
);

always @* begin
    if (loopback) begin
        // Near-End PMA Loopback
        gt_loopback_in = {NUM_CHANS{3'b010}};
    end else begin
        gt_loopback_in = {NUM_CHANS{3'b000}};
    end
end

endmodule
