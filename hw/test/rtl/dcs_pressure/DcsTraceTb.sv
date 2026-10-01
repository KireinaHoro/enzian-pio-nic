`timescale 1ns/1ps
import eci_cmd_defs::*;
import eci_cc_defs::*;
import eci_dirc_defs::*;
module DcsTraceTb;
 logic clk=0,reset=1;
 always #5 clk=~clk;
 eci_word_t req='0;
 logic [4:0] vc=VC_LCL_FWD_WO_DATA_E;
 logic valid=0,ready,stall,dbg_valid,dbg_error,full_stall,dr,ds,rdda,wdda;
 logic [63:0] dh,rh;
 cc_req_t trace_req;
 cc_state_t trace_state;
 cc_action_t trace_action;
 dirc_err_t code;
 logic rd_valid=0,rd_ready;
 dcu dut(.clk,.reset,.eci_req_hdr_i(req),.eci_req_vc_i(vc),.eci_req_size_i(5'd1),
   .eci_req_valid_i(valid),.eci_req_ready_o(ready),.eci_req_stall_o(stall),
   .eci_rsp_ready_i(1'b1),.rd_req_ready_i(1'b1),.rd_rsp_id_i('0),
   .rd_rsp_valid_i(rd_valid),.rd_rsp_ready_o(rd_ready),
   .wr_req_ready_i(1'b1),.wr_rsp_id_i('0),.wr_rsp_bresp_i('0),.wr_rsp_valid_i(1'b0),
   .dbg_eci_req_hdr_o(dh),.dbg_eci_rsp_hdr_o(rh),.dbg_eci_req_ready_o(dr),
   .dbg_eci_req_stalled_o(ds),.dbg_rdda_o(rdda),.dbg_wdda_o(wdda),
   .dbg_cc_active_req_o(trace_req),.dbg_cc_next_state_o(trace_state),
   .dbg_cc_next_action_o(trace_action),.dbg_err_code_o(code),
   .dbg_error_o(dbg_error),.dbg_valid_o(dbg_valid),.dbg_stall_rtg_full_o(full_stall));
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
     assert(!dbg_error) else $fatal(1,"DC error %0d",code);
     if(ready) begin done=1;break;end
   end
   assert(done) else $fatal(1,"accept timeout");
   @(posedge clk);#1;valid=0;repeat(3) @(negedge clk);
 endtask
 task automatic full_trace(input int op);
   int reports,retries;
   logic [39:0] target;
   reports=0;retries=0;target=40'h2001100000;
   @(negedge clk);req=local_cmd(op,target);vc=VC_LCL_FWD_WO_DATA_E;valid=1;
   repeat(600) begin
     @(negedge clk);
     assert(!ready && !dbg_error) else $fatal(1,"unexpected completion/error");
     if(full_stall && stall) retries++;
     if(dbg_valid && full_stall) begin
       reports++;
       assert(code==ERR_RTG_ALLOC_FULL && trace_action==STALL && trace_state==s1__1)
         else $fatal(1,"allocation failure trace mislabeled");
       assert(trace_req==(op==LCL_CMD_MFWD_CLEAN ? LC : LCI)) else $fatal(1,"stale ROM request leaked");
       assert(dc_trace_address(dr,ds,rdda,wdda,dh,rh)==target) else $fatal(1,"wrong blocked address");
     end
   end
   assert(reports==16 && retries>16) else $fatal(1,"retry reports not bounded: %0d/%0d",reports,retries);
   while(!stall) @(negedge clk);
   @(posedge clk);#1;valid=0;repeat(3) @(negedge clk);
   $display("PASS %s full-miss trace: current address/request, nonfatal marker, 16 reports across %0d retries",op==LCL_CMD_MFWD_CLEAN ? "LC" : "LCI",retries);
 endtask
 task automatic reset_dc;
   @(negedge clk);reset=1;valid=0;rd_valid=0;
   repeat(4) @(negedge clk);reset=0;repeat(540) @(negedge clk);
 endtask
 initial begin
   eci_word_t h;
   reset_dc();
   for(int t=1;t<=16;t++) send(local_cmd(t%2 ? LCL_CMD_MFWD_CLEAN : LCL_CMD_MFWD_CLEAN_INV,40'(t)<<20));
   $display("PASS populated full set with sixteen LC/LCI locks pending UL");
   full_trace(LCL_CMD_MFWD_CLEAN);full_trace(LCL_CMD_MFWD_CLEAN_INV);
   h='0;h.lcl_unlock.opcode=LCL_CMD_MRSP_UNLOCK;h.lcl_unlock.address=40'h100000;
   send(h,VC_LCL_RESP_WO_DATA_E);
   send(local_cmd(LCL_CMD_MFWD_CLEAN_INV,40'h2001100000));
   assert(trace_state==s1pUL__1) else $fatal(1,"UL did not restore allocation");
   $display("PASS UL frees entry and waiting LCI completes");
   reset_dc();
   for(int t=1;t<=16;t++) begin
     h='0;h.mreq_load.opcode=ECI_CMD_MREQ_RLDD;
     h.mreq_load.address=40'(t)<<20;h.mreq_load.dmask='1;
     send(h,VC_REQ_WO_DATA_E);
     rd_valid=1;
     do @(negedge clk); while(!rd_ready);
     @(posedge clk);#1;rd_valid=0;repeat(3) @(negedge clk);
   end
   $display("PASS populated full set with sixteen CPU-resident shared lines");
   full_trace(LCL_CMD_MFWD_CLEAN_INV);
   $finish;
 end
 initial begin #200000; $fatal(1,"trace test timeout"); end
endmodule
