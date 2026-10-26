module dcs_elastic_pipeline_tb;
    logic clk=0, reset=1;
    always #5 clk=~clk;
    logic [1090:0] us_data, ds_data;
    logic us_valid=0, us_ready, ds_valid, ds_ready=0;
    axis_pipeline_stage #(.DATA_WIDTH(1091)) dut (.*);
    integer sent=0, received=0, cycle=0;
    logic held=0;
    logic [1090:0] held_data;
    function automatic [1090:0] pattern(input integer n);
        pattern={3'(n), {34{32'(n ^ 'h39ac05ed)}}};
    endfunction
    always @(posedge clk) if (!reset) begin
        if (held) assert(ds_valid && ds_data == held_data)
            else $fatal(1,"stalled output changed");
        held <= ds_valid && !ds_ready;
        held_data <= ds_data;
        if (us_valid && us_ready) sent <= sent+1;
        if (ds_valid && ds_ready) begin
            assert(received < sent && ds_data == pattern(received))
                else $fatal(1,"loss/duplication/order mismatch %0d",received);
            received <= received+1;
        end
        if (cycle > 10000) $fatal(1,"timeout");
    end
    initial begin
        us_data=0;
        repeat(5) @(negedge clk);
        reset=0;
        while (received < 1000) begin
            // Hold an offered beat until accepted; sent changes only on fire.
            us_valid=(sent<1000);
            us_data=pattern(sent);
            ds_ready=(cycle>50 && cycle%31>=15);
            @(negedge clk);
            cycle=cycle+1;
        end
        $display("DCS_ELASTIC_PIPELINE_PASSED: 1000 wide beats under sustained stalls");
        $finish;
    end
endmodule
