`timescale 1ns/1ps
import eci_cmd_defs::*;
import eci_cc_defs::*;
import eci_dirc_defs::*;
module DcsLauberhornTb;
 logic clk=0,reset=1;
 always #5 clk=~clk;
 eci_word_t req='0, rsp;
 logic [4:0] vc=VC_LCL_FWD_WO_DATA_E;
 logic valid=0,ready,stall,dbg_valid,dbg_error,full_stall;
 logic rsp_ready=1,rsp_valid,rd_valid=0,rd_ready,wr_valid=0,wr_ready;
 cc_req_t trace_req;
 cc_state_t trace_state;
 cc_action_t trace_action;
 dirc_err_t code;
 int writes=0,lcias=0,ra3s=0,forwards=0;
 dcu dut(.clk,.reset,.eci_req_hdr_i(req),.eci_req_vc_i(vc),.eci_req_size_i(5'd1),
   .eci_req_valid_i(valid),.eci_req_ready_o(ready),.eci_req_stall_o(stall),
   .eci_rsp_hdr_o(rsp),.eci_rsp_valid_o(rsp_valid),.eci_rsp_ready_i(rsp_ready),
   .rd_req_ready_i(1'b1),.rd_rsp_id_i('0),.rd_rsp_valid_i(rd_valid),.rd_rsp_ready_o(rd_ready),
   .wr_req_ready_i(1'b1),.wr_rsp_id_i('0),.wr_rsp_bresp_i('0),.wr_rsp_valid_i(wr_valid),.wr_rsp_ready_o(wr_ready),
   .dbg_cc_active_req_o(trace_req),.dbg_cc_next_state_o(trace_state),
   .dbg_cc_next_action_o(trace_action),.dbg_err_code_o(code),
   .dbg_error_o(dbg_error),.dbg_valid_o(dbg_valid),.dbg_stall_rtg_full_o(full_stall));
 always @(posedge clk) if(!reset) begin
   if(dut.tsu_wr_en_i) writes++;
   if(dbg_valid && trace_action==SEND_LCIA) lcias++;
   if(dbg_valid && trace_action==SEND_RA3) ra3s++;
   if(dbg_valid && trace_action==SEND_F31) forwards++;
   assert(!(dut.tsu_wr_en_i && !dut.tsu_rd_tag_hit_o && dut.tsu_rd_tag_set_full_o))
     else $fatal(1,"full-miss directory overwrite");
 end
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
 task automatic ack(input logic[39:0] a,input bit dirty);
   eci_word_t h;
   h='0;h.mrsp_3to8.opcode=ECI_CMD_MRSP_HAKD;
   h.mrsp_3to8.address=a;h.mrsp_3to8.hreq_id='0;h.mrsp_3to8.dmask=dirty ? 4'hf : 4'h0;
   send(h,dirty ? VC_RESP_W_DATA_E : VC_RESP_WO_DATA_E);
 endtask
 task automatic reset_dc;
   @(negedge clk);reset=1;valid=0;rd_valid=0;wr_valid=0;rsp_ready=1;
   repeat(4) @(negedge clk);reset=0;repeat(540) @(negedge clk);
 endtask
 initial begin
   int before_writes,before_lcias;
   eci_word_t h;
   reset_dc();
   before_writes=writes;
   for(int t=1;t<=32;t++) send(local_cmd(LCL_CMD_MFWD_CLEAN_INV,40'(t)<<20));
   assert(writes==before_writes) else $fatal(1,"absent invalidation allocated/wrote");
   $display("PASS repeated absent LCI completes without allocation or UL");
   for(int t=1;t<=16;t++) fill(40'(t)<<20);
   assert(ra3s==16) else $fatal(1,"wrong RA3 completion count");
   $display("PASS sixteen R12 fills use RA3 and reach exclusive state");
   before_writes=writes;
   rsp_ready=0;
   fork
     send(local_cmd(LCL_CMD_MFWD_CLEAN_INV,40'h1100000));
     begin
       wait(rsp_valid);repeat(40) @(negedge clk);
       assert(writes==before_writes && !dbg_error) else $fatal(1,"backpressured full miss changed directory");
       rsp_ready=1;
     end
   join
   assert(writes==before_writes) else $fatal(1,"full miss wrote arbitrary way");
   $display("PASS full-set absent LCI survives output backpressure without a directory write");
   // Every resident must still require a forward. Clean HAKD is classified from F31 context.
   for(int t=1;t<=16;t++) begin
     before_lcias=lcias;
     send(local_cmd(LCL_CMD_MFWD_CLEAN_INV,40'(t)<<20));
     assert(trace_action==SEND_F31 && lcias==before_lcias) else $fatal(1,"resident entry %0d lost",t);
     ack(40'(t)<<20,0);
     assert(trace_state==s1__1 && lcias==before_lcias+1) else $fatal(1,"clean HAKD did not complete LCI");
   end
   $display("PASS all sixteen resident tags preserved; clean F31/HAKD releases each without UL");
   fill(40'h2000000);send(local_cmd(LCL_CMD_MFWD_CLEAN_INV,40'h2000000));
   before_lcias=lcias;ack(40'h2000000,1);
   assert(lcias==before_lcias) else $fatal(1,"LCIA before dirty write completion");
   repeat(40) @(negedge clk);
   assert(!dbg_error && lcias==before_lcias) else $fatal(1,"dirty wait failed");
   wr_valid=1;do @(negedge clk);while(!wr_ready);
   @(posedge clk);#1;wr_valid=0;repeat(3) @(negedge clk);
   assert(trace_state==s1__1 && lcias==before_lcias+1) else $fatal(1,"dirty completion missing");
   $display("PASS dirty F31/HAKD waits for WDDA before LCIA and directory release");
   // Unsupported local operations fail decoding; they cannot enter removed ROM paths.
   for(int op=0;op<=2;op+=2) begin
     reset_dc();req=local_cmd(op,40'h100000);vc=op==0 ? VC_LCL_FWD_WO_DATA_E : VC_LCL_RESP_WO_DATA_E;valid=1;
     repeat(40) @(negedge clk);
     assert(dbg_error && code==ERR_ECI_EVT_NOT_DECODED) else $fatal(1,"unsupported local command accepted");
   end
   $display("PASS LC and UL are rejected at the wire decoder");
   $finish;
 end
 initial begin #500000; $fatal(1,"Lauberhorn DC test timeout"); end
endmodule
