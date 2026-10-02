`timescale 1ns/1ps
import eci_cmd_defs::*;
import eci_cc_defs::*;
import eci_dirc_defs::*;
// Replays the board's data-bearing HAKD, without a simulated software store.
// Real DCU + write adapter + generated actual RX router. Test-only RAM,
// response payload and read completion stand in for the rest of the slice.
module DcsRxWritebackTb;
 logic clk=0,reset=1;
 always #5 clk=~clk;
 eci_word_t req='0,rsp;
 logic [4:0] vc=VC_LCL_FWD_WO_DATA_E;
 logic valid=0,ready,stall,dbg_valid,dbg_error,full_stall;
 logic rsp_ready=1,rsp_valid,rd_valid=0,rd_ready,wr_valid,wr_ready;
 cc_req_t trace_req;
 cc_state_t trace_state;
 cc_action_t trace_action;
 dirc_err_t code;
 int lcias=0,ra3s=0,forwards=0,write_requests=0,write_responses=0;
 logic [6:0] wr_id,done_id,awid,bid,sink_id,rd_id;
 logic [37:0] wr_addr,awaddr;
 logic [127:0] wr_strb;
 logic wr_req,wr_accept;
 logic [1:0] done_resp,bresp;
 logic awvalid,awready,wvalid,wready,wlast,bvalid,bready;
 logic [511:0] wdata;
 logic [63:0] wstrb;
 logic [7:0] awlen;
 logic [2:0] awsize;
 logic [1:0] awburst;
 logic router_awready,router_wready,router_bvalid;
 logic [6:0] router_bid;
 logic [1:0] router_bresp;
 logic sink=0,release_b=0,sink_bvalid=0;
 int aw_count=0,w_count=0,read_beats=0;
 logic arvalid=0,arready,rvalid,rlast;
 logic [37:0] araddr=0;
 logic inv_done;
 assign inv_done=dbg_valid && trace_action==SEND_LCIA;
 dcu dut(.clk,.reset,.eci_req_hdr_i(req),.eci_req_vc_i(vc),.eci_req_size_i(vc==VC_RESP_W_DATA_E ? 5'(ECI_PACKET_SIZE) : 5'd1),
   .eci_req_valid_i(valid),.eci_req_ready_o(ready),.eci_req_stall_o(stall),
   .eci_rsp_hdr_o(rsp),.eci_rsp_valid_o(rsp_valid),.eci_rsp_ready_i(rsp_ready),
   .rd_req_ready_i(1'b1),.rd_req_id_o(rd_id),.rd_rsp_id_i(rd_id),.rd_rsp_valid_i(rd_valid),.rd_rsp_ready_o(rd_ready),
   .wr_req_id_o(wr_id),.wr_req_addr_o(wr_addr),.wr_req_strb_o(wr_strb),
   .wr_req_valid_o(wr_req),.wr_req_ready_i(wr_accept),
   .wr_rsp_id_i(done_id),.wr_rsp_bresp_i(done_resp),.wr_rsp_valid_i(wr_valid),.wr_rsp_ready_o(wr_ready),
   .dbg_cc_active_req_o(trace_req),.dbg_cc_next_state_o(trace_state),
   .dbg_cc_next_action_o(trace_action),.dbg_err_code_o(code),
   .dbg_error_o(dbg_error),.dbg_valid_o(dbg_valid),.dbg_stall_rtg_full_o(full_stall));
 axi_wr_cl #(.DESC_ID_WIDTH(7),.DESC_ADDR_WIDTH(38)) writer(
   .clk,.reset,.wr_req_id_i(wr_id),.wr_req_addr_i(wr_addr),
   .wr_req_strb_i(wr_strb),.wr_req_data_i({128{8'h5a}}),
   .wr_req_valid_i(wr_req),.wr_req_ready_o(wr_accept),
   .wr_rsp_id_o(done_id),.wr_rsp_bresp_o(done_resp),.wr_rsp_valid_o(wr_valid),.wr_rsp_ready_i(wr_ready),
   .p_axi_awid(awid),.p_axi_awaddr(awaddr),.p_axi_awlen(awlen),.p_axi_awsize(awsize),.p_axi_awburst(awburst),
   .p_axi_awvalid(awvalid),.p_axi_awready(awready),
   .p_axi_wdata(wdata),.p_axi_wstrb(wstrb),.p_axi_wlast(wlast),.p_axi_wvalid(wvalid),.p_axi_wready(wready),
   .p_axi_bid(bid),.p_axi_bresp(bresp),.p_axi_bvalid(bvalid),.p_axi_bready(bready));
 assign awready=sink ? (aw_count==0) : router_awready;
 assign wready=sink ? (w_count<2) : router_wready;
 assign bvalid=sink ? sink_bvalid : router_bvalid;
 assign bid=sink ? sink_id : router_bid;
 assign bresp=sink ? 2'b0 : router_bresp;
 RxWritebackFixture rx(.clk,.reset,.invDone(inv_done),
   .axi_aw_valid(awvalid && !sink),.axi_aw_ready(router_awready),.axi_aw_payload_addr(awaddr),
   .axi_aw_payload_id(awid),.axi_aw_payload_len(awlen),.axi_aw_payload_size(awsize),.axi_aw_payload_burst(awburst),
   .axi_aw_payload_region('0),.axi_aw_payload_lock('0),.axi_aw_payload_cache('0),.axi_aw_payload_qos('0),.axi_aw_payload_prot('0),
   .axi_w_valid(wvalid && !sink),.axi_w_ready(router_wready),.axi_w_payload_data(wdata),.axi_w_payload_strb(wstrb),.axi_w_payload_last(wlast),
   .axi_b_valid(router_bvalid),.axi_b_ready(bready && !sink),.axi_b_payload_id(router_bid),.axi_b_payload_resp(router_bresp),
   .axi_ar_valid(arvalid),.axi_ar_ready(arready),.axi_ar_payload_addr(araddr),.axi_ar_payload_id(7'd1),
   .axi_ar_payload_len(8'd1),.axi_ar_payload_size(3'd6),.axi_ar_payload_burst(2'd1),
   .axi_ar_payload_region('0),.axi_ar_payload_lock('0),.axi_ar_payload_cache('0),.axi_ar_payload_qos('0),.axi_ar_payload_prot('0),
   .axi_r_valid(rvalid),.axi_r_ready(1'b1),.axi_r_payload_last(rlast));
 always @(posedge clk) if(!reset) begin
   assert(!dbg_error) else $fatal(1,"DC error %0d",code);
   if(dbg_valid && trace_action==SEND_LCIA) lcias++;
   if(dbg_valid && trace_action==SEND_RA3) ra3s++;
   if(dbg_valid && trace_action==SEND_F31) forwards++;
   if(wr_req && wr_accept) begin
     write_requests++;
     $display("EVENT WDD address=%h strobes=%h",wr_addr,wr_strb);
   end
   if(wr_valid && wr_ready) begin write_responses++;$display("EVENT WDDA");end
   if(rvalid) read_beats++;
   if(awvalid && awready) begin
     sink_id=awid;
     assert(awaddr==38'h400 && awlen==1 && awsize==6 && awburst==1);
     aw_count++;
   end
   if(wvalid && wready) begin
     assert(wlast==(w_count==1) && wstrb=='1 && wdata=={64{8'h5a}});
     w_count++;
   end
   if(sink && release_b && aw_count==1 && w_count==2 && write_responses==0)
     sink_bvalid<=1;
   if(bvalid && bready) sink_bvalid<=0;
 end else begin
   aw_count=0;w_count=0;read_beats=0;lcias=0;ra3s=0;forwards=0;
   write_requests=0;write_responses=0;sink_bvalid<=0;
 end
 task automatic read_control(input logic[37:0] addr);
   @(negedge clk);araddr=addr;arvalid=1;
   do @(posedge clk); while(!arready);
   @(negedge clk);arvalid=0;
 endtask
 function automatic eci_word_t local_cmd(input int op,input logic[39:0] a);
   eci_word_t h;
   h='0;h.lcl_mfwd_generic.opcode=5'(op);h.lcl_mfwd_generic.address=a;
   h.lcl_mfwd_generic.hreq_id=6'd3;h.lcl_mfwd_generic.ns=1;h.lcl_mfwd_generic.dmask='1;
   return h;
 endfunction
 task automatic send(input eci_word_t h,input int channel=VC_LCL_FWD_WO_DATA_E);
   bit done;
   @(negedge clk);req=h;vc=5'(channel);valid=1;done=0;
   for(int i=0;i<200;i++) begin
     @(negedge clk);
     assert(!dbg_error) else $fatal(1,"DC error %0d req %0d",code,trace_req);
     if(ready) begin done=1;break;end
   end
   assert(done) else $fatal(1,"accept timeout");
   @(posedge clk);#1;valid=0;repeat(3) @(negedge clk);
 endtask
 task automatic fill(input logic[39:0] a);
   eci_word_t h;
   h='0;h.mreq_load.opcode=ECI_CMD_MREQ_RLDD;h.mreq_load.address=a;h.mreq_load.dmask='1;
   send(h,VC_REQ_WO_DATA_E);
   rd_valid=1;
   do @(negedge clk); while(!rd_ready);
   @(posedge clk);#1;rd_valid=0;repeat(3) @(negedge clk);
   assert(trace_state==s1__3 && trace_action==SEND_RA3) else $fatal(1,"R12 did not produce RA3");
 endtask
 task automatic ack(input logic[39:0] a,input bit with_data);
   eci_word_t h;
   h='0;h.mrsp_3to8.opcode=ECI_CMD_MRSP_HAKD;
   h.mrsp_3to8.address=a;h.mrsp_3to8.hreq_id='0;h.mrsp_3to8.dmask=with_data ? 4'hf : 4'h0;
   send(h,with_data ? VC_RESP_W_DATA_E : VC_RESP_WO_DATA_E);
 endtask
 task automatic reset_dc;
   @(negedge clk);reset=1;valid=0;rd_valid=0;rsp_ready=1;
   repeat(4) @(negedge clk);reset=0;repeat(540) @(negedge clk);
 endtask
 initial begin
   // Clean acknowledgement is the control: no writeback service required.
   reset_dc();fill(40'h400);send(local_cmd(LCL_CMD_MFWD_CLEAN_INV,40'h400));ack(40'h400,0);
   repeat(30) @(negedge clk);
   assert(lcias==1 && write_requests==0) else $fatal(1,"clean control did not finish");
   $display("PASS no-data HAKD completes LCI without AXI writeback");
   // Two runs differ only in the write destination; the read router is unchanged.
   for(int mode=0;mode<2;mode++) begin
     sink=1'(mode);release_b=0;reset_dc();
     read_control(38'h300);repeat(40) @(negedge clk);
     assert(read_beats==2) else $fatal(1,"initial RX read failed");
     fill(40'h400);read_control(38'h380);repeat(40) @(negedge clk);
     assert(read_beats==2) else $fatal(1,"next control completed before invalidation");
     send(local_cmd(LCL_CMD_MFWD_CLEAN_INV,40'h400));ack(40'h400,1);
     assert(trace_req==A31d && trace_action==WDD) else $fatal(1,"wrong HAKD decode/action");
     repeat(200) @(negedge clk);
     assert(aw_count==1 && w_count==2) else $fatal(1,"unexpected AXI acceptance");
     if(!sink) begin
       assert(write_requests==1 && write_responses==1 && lcias==1 && read_beats==4)
         else $fatal(1,"production RX writeback did not release LCI and next read");
       $display("PASS production RX drains returned data, completes WDDA/LCIA and releases control 0x380");
     end else begin
       assert(write_requests==1 && write_responses==0 && lcias==0 && read_beats==2)
         else $fatal(1,"expected delayed-B dependency not reproduced");
       $display("PASS independent test sink accepts full burst but delayed B still blocks WDDA/LCIA/read");
       release_b=1;repeat(80) @(negedge clk);
       assert(write_responses==1 && lcias==1 && read_beats==4)
         else $fatal(1,"serviced writeback did not release LCI and next RX read");
       $display("PASS B completion releases WDDA then LCIA and next control read");
     end
   end
   $finish;
 end
 initial begin #100000; $fatal(1,"RX writeback test timeout"); end
endmodule
