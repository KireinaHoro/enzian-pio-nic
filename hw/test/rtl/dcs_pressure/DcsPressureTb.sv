`timescale 1ns/1ps
import eci_cmd_defs::*;
import eci_cc_defs::*;
import eci_dirc_defs::*;
import eci_dcs_defs::*;
module DcsPressureTb;
  logic clk = 0, reset = 1;
  always #5 clk = ~clk;
  logic valid = 0, full = 1, ready, stall, wr, en, error;
  logic full_stall;
  cc_req_t request = LCI, active;
  cc_state_t state = s1__1, next_state;
  cc_action_t action;
  always @(posedge clk) begin
    if (reset) state <= s1__1;
    else if (wr) state <= next_state;
  end
  eci_cc_table rom(.clk, .reset, .en, .curr_state_i(state), .curr_req_i(request),
    .next_state_o(next_state), .next_action_o(action), .curr_active_req_o(active));
  // The directory lookup is a controlled environment: absent/full, then one
  // released way. The actual controller and selected auto_lock table are DUTs.
  dcu_controller dut(.clk, .reset, .eci_req_valid_i(valid),
    .eci_req_ready_o(ready), .eci_req_stall_o(stall),
    .eci_evt_crts_rtg_ntry_i(cc_req_creates_new_rtg_entry(request)),
    .eci_rsp_2_prv_snt_req_i(1'b0), .eci_rsp_ready_i(1'b1), .dec_req_invalid_i(1'b0),
    .rtg_busy_i(1'b0), .rtg_hit_i(cc_is_state_valid(state)), .rtg_full_i(full),
    .rtg_nxm_i(1'b0), .rtg_wr_en_o(wr), .cc_rom_en_o(en),
    .cc_next_action_from_table_i(action), .cc_curr_req_i(active),
    .tr_rd_req_ready_i(1'b1), .tr_rd_rsp_valid_i(1'b0), .tr_eci_full_i(1'b0),
    .tr_wr_req_ready_i(1'b1), .tr_wr_rsp_valid_i(1'b0),
    .stall_rtg_full_o(full_stall), .error_o(error));

  logic [4:0] av = 0, ar, ast;
  logic [63:0] ah;
  logic avalid, aready, astall;
  int counts[5];
  logic arb_test = 0;
  // Port 1 models a permanently unserviceable input head. Other classes remain
  // serviceable; this tests skip-on-stall, not a complete DCS or ECI endpoint.
  assign aready = arb_test && avalid && ah != 1;
  assign astall = arb_test && avalid && ah == 1;
  arb_4_ecih arb(.clk, .reset,
    .eci_hdr1_i(64'd1), .eci_pkt1_size_i(5'd1), .eci_pkt1_vc_i(5'd6), .eci_pkt1_valid_i(av[0]), .eci_pkt1_ready_o(ar[0]), .eci_pkt1_stall_o(ast[0]),
    .eci_hdr2_i(64'd2), .eci_pkt2_size_i(5'd1), .eci_pkt2_vc_i(5'd4), .eci_pkt2_valid_i(av[1]), .eci_pkt2_ready_o(ar[1]), .eci_pkt2_stall_o(ast[1]),
    .eci_hdr3_i(64'd3), .eci_pkt3_size_i(5'd1), .eci_pkt3_vc_i(5'd10), .eci_pkt3_valid_i(av[2]), .eci_pkt3_ready_o(ar[2]), .eci_pkt3_stall_o(ast[2]),
    .eci_hdr4_i(64'd4), .eci_pkt4_size_i(5'd1), .eci_pkt4_vc_i(5'd16), .eci_pkt4_valid_i(av[3]), .eci_pkt4_ready_o(ar[3]), .eci_pkt4_stall_o(ast[3]),
    .eci_hdr5_i(64'd5), .eci_pkt5_size_i(5'd1), .eci_pkt5_vc_i(5'd18), .eci_pkt5_valid_i(av[4]), .eci_pkt5_ready_o(ar[4]), .eci_pkt5_stall_o(ast[4]),
    .eci_hdr_o(ah), .eci_pkt_valid_o(avalid), .eci_pkt_ready_i(aready), .eci_pkt_stall_i(astall));
  always @(posedge clk) if (arb_test) begin
    assert (!ar[0]) else $fatal(1, "stalled input was consumed");
    for (int i=1; i<5; i++) if (ar[i] && av[i]) counts[i]++;
  end
  logic [DS_DCU_ID_WIDTH-1:0] gate_id=0;
  logic gate_valid=0, gate_ready, gate_hvalid, gate_dvalid, clear_valid=0;
  dp_gate #(.GEN_MAP_ECID_TO_WRD(0)) gate_dut(.clk, .reset,
    .hd_dcu_id_i(gate_id), .hd_dmask_i('1), .hd_hdr_i(64'd0), .hd_data_i('0),
    .hd_valid_i(gate_valid), .hd_ready_o(gate_ready),
    .h_valid_o(gate_hvalid), .h_ready_i(1'b1), .d_valid_o(gate_dvalid), .d_ready_i(1'b1),
    .c_dcu_idx_i('0), .c_dcu_idx_valid_i(clear_valid));
  task automatic gate_accept;
    bit accepted;
    accepted=0;
    for(int i=0;i<16;i++) begin
      @(negedge clk);
      if(gate_ready) begin accepted=1; break; end
    end
    assert(accepted) else $fatal(1,"data gate failed to accept eligible DCU");
    @(posedge clk); #1; gate_valid=0;
  endtask
  task automatic wait_accept;
    bit accepted;
    accepted = 0;
    for (int i=0; i<32; i++) begin
      @(negedge clk);
      assert (!error) else $fatal(1,"controller error");
      if (ready) begin accepted=1; break; end
    end
    assert (accepted) else $fatal(1,"request failed to complete after space became available");
    @(posedge clk); #1; valid=0;
  endtask
  initial begin
    int stalls;
    stalls=0;
    for(int i=0;i<5;i++) counts[i]=0;
    repeat(4) @(negedge clk);
    reset=0;
`ifdef NO_AUTO_LOCK
    assert (!cc_req_creates_new_rtg_entry(LCI)) else $fatal(1,"LCI allocates");
    valid=1;
    wait_accept();
    assert (state == s1__1 && !cc_is_state_valid(state)) else $fatal(1,"absent LCI retained charge");
    $display("PASS nonlocking absent LCI completes with full directory and no charge");
`else
    assert (cc_req_creates_new_rtg_entry(LCI)) else $fatal(1,"wrong protocol variant");
    valid=1;
    repeat(64) begin
      @(negedge clk);
      assert (!ready && !wr && !en && !error) else $fatal(1,"full-set LCI advanced unexpectedly");
      if (stall && full_stall) stalls++;
    end
    assert (stalls > 1) else $fatal(1,"controller did not retry full-set LCI");
    $display("PASS absent LCI stalls/retries on full directory (%0d retries)",stalls);
    full=0;
    wait_accept();
    assert (state == s1pUL__1 && cc_is_state_valid(state)) else $fatal(1,"LCI did not retain directory charge");
    $display("PASS absent LCI allocates a charged lock entry after a way is released");
    @(negedge clk); full=1; request=UL; valid=1;
    wait_accept();
    assert (state == s1__1 && !cc_is_state_valid(state)) else $fatal(1,"UL did not free the entry");
    $display("PASS UL releases charge even with full flag asserted");
`endif
    @(negedge clk); av='1; arb_test=1;
    repeat(128) @(negedge clk);
    arb_test=0; av=0;
    for(int i=1;i<5;i++) assert(counts[i]>0) else $fatal(1,"starved arbiter port %0d",i+1);
    $display("PASS stalled request head does not block other arbiter classes: %0d %0d %0d %0d",counts[1],counts[2],counts[3],counts[4]);
    @(negedge clk); gate_id=0; gate_valid=1;
    gate_accept();
    @(negedge clk); gate_valid=1;
    repeat(64) begin
      @(negedge clk);
      assert(!gate_ready && !gate_hvalid && !gate_dvalid) else $fatal(1,"busy data slot reused");
    end
    $display("PASS busy same-DCU data head backpressures the shared ingress for 64 cycles");
    clear_valid=1;
    @(negedge clk); clear_valid=0;
    gate_accept();
    @(negedge clk); gate_id=2; gate_valid=1;
    gate_accept();
    $display("PASS clearing the head permits progress; another DCU can use its own slot");
    $finish;
  end
  initial begin #10000; $fatal(1,"testbench timeout"); end
endmodule
