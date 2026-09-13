`timescale 1ns/1ps
// Exercise the trace TX FIFO's real width/depth with both output-stage settings.
module tb;
  parameter OUTPUT_FIFO_ENABLE = 1;
  reg s_clk = 0, m_clk = 0;
  always #5 s_clk = ~s_clk;
  always #3.1 m_clk = ~m_clk;
  reg s_rst = 1, m_rst = 1;
  reg [511:0] s_data = 0;
  reg [63:0] s_keep = 0;
  reg s_valid = 0, s_last = 0, m_ready = 0;
  wire s_ready, m_valid, m_last;
  wire [511:0] m_data;
  wire [63:0] m_keep;
  integer expected = 0, received = 0, expected_count = 0;
  integer ready_cycle = 0, blocked = 0;
  reg drain = 0, checking = 0;

  axis_async_fifo #(
    .DEPTH(64*32), .DATA_WIDTH(512), .KEEP_ENABLE(1), .KEEP_WIDTH(64),
    .LAST_ENABLE(1), .ID_ENABLE(0), .DEST_ENABLE(0), .USER_ENABLE(0),
    .RAM_PIPELINE(1), .OUTPUT_FIFO_ENABLE(OUTPUT_FIFO_ENABLE),
    .FRAME_FIFO(1), .DROP_OVERSIZE_FRAME(1)
  ) dut (
    .s_clk(s_clk), .s_rst(s_rst), .m_clk(m_clk), .m_rst(m_rst),
    .s_axis_tdata(s_data), .s_axis_tkeep(s_keep), .s_axis_tvalid(s_valid),
    .s_axis_tready(s_ready), .s_axis_tlast(s_last),
    .s_axis_tid(8'b0), .s_axis_tdest(8'b0), .s_axis_tuser(1'b0),
    .m_axis_tdata(m_data), .m_axis_tkeep(m_keep), .m_axis_tvalid(m_valid),
    .m_axis_tready(m_ready), .m_axis_tlast(m_last),
    .s_pause_req(1'b0), .m_pause_req(1'b0)
  );

  always @(negedge m_clk) begin
    ready_cycle = ready_cycle + 1;
    m_ready = drain && !m_rst && (ready_cycle % 19 >= 11);
  end
  always @(posedge s_clk)
    if (!s_rst && s_valid && !s_ready) blocked = blocked + 1;
  always @(posedge m_clk) begin
    if (!m_rst && m_valid && m_ready && checking) begin
      if (received >= expected_count) $fatal(1, "unexpected extra beat");
      if (m_data !== {16{expected[31:0]}}) $fatal(1, "data/order mismatch: expected %d", expected);
      if (m_last !== (expected % 7 == 6)) $fatal(1, "TLAST mismatch");
      if (m_keep !== ((expected % 7 == 6) ? 64'h1f : 64'hffffffffffffffff)) $fatal(1, "TKEEP mismatch");
      expected = expected + 1;
      received = received + 1;
    end
  end

  task send(input integer first, input integer count);
    integer i;
    begin
      for (i = first; i < first + count; i = i + 1) begin
        @(negedge s_clk);
        s_valid = 1;
        s_data = {16{i[31:0]}};
        s_last = i % 7 == 6;
        s_keep = s_last ? 64'h1f : 64'hffffffffffffffff;
        @(posedge s_clk);
        while (!s_ready) @(posedge s_clk);
      end
      @(negedge s_clk); s_valid = 0;
    end
  endtask
  task reset_fifo;
    begin
      drain = 0;
      @(negedge s_clk); s_valid = 0; s_rst = 1;
      @(negedge m_clk); m_rst = 1;
      repeat (12) @(negedge s_clk);
      s_rst = 0;
      repeat (4) @(negedge m_clk);
      m_rst = 0;
      repeat (30) @(negedge s_clk);
    end
  endtask
  initial begin
    #1000000; $fatal(1, "timeout");
  end
  initial begin
    reset_fifo;
    checking = 1; expected = 0; expected_count = 280;
    fork
      send(0, 280);
      begin repeat (160) @(negedge m_clk); drain = 1; end
    join
    wait (received == expected_count);
    if (blocked == 0) $fatal(1, "test did not exercise backpressure");
    // Leave committed frames and an incomplete frame queued, then reset both
    // domains. None of these old beats may emerge after reset.
    drain = 0; checking = 0;
    send(280, 17);
    reset_fifo;
    received = 0; expected = 700; expected_count = 140; checking = 1; drain = 1;
    send(700, 140);
    wait (received == expected_count);
    repeat (100) @(negedge m_clk);
    $display("PASS output_fifo=%0d stalls=%0d: ordering, TKEEP/TLAST, reset flush", OUTPUT_FIFO_ENABLE, blocked);
    $finish;
  end
endmodule
