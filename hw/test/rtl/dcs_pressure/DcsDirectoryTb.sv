`timescale 1ns/1ps
import eci_cc_defs::*;
import eci_dirc_defs::*;
module DcsDirectoryTb;
 logic clk=0, reset=1;
 always #5 clk=~clk;
 logic valid=0, ready, stall, error, rsp_valid, rsp_ready=1;
 logic rd,wr,busy,hit,full,nxm,sel_inv,en,full_stall;
 logic rd_rsp=0, rd_rsp_ready, rd_context, wr_context, launch_read;
 logic [17:0] tag=0, state;
 logic [6:0] set_id=0;
 logic [3:0] way;
 cc_req_t request=R12, active;
 cc_state_t next_state;
 cc_action_t action;
 int writes=0, lcias=0;
 // This is the dcu.sv TSU/ROM context wiring. The test services memory reads
 // explicitly with rd_rsp/RDDA; no tag/state writes or full flags are forced.
 dcu_tsu #(.NUM_SETS_PER_DCU(128),.TSR_OP_REGISTER(1)) tsu(
   .clk,.reset,.wr_tag_i(tag),.wr_stt_i(18'(next_state)),
   .wr_set_i(set_id),.wr_way_i(way),.wr_en_i(wr),
   .rd_tag_set_i(set_id),.rd_tag_to_cmp_i(tag),.rd_tag_en_i(rd),
   .rd_tag_stt_o(state),.rd_tag_way_o(way),.rd_tag_hit_o(hit),
   .rd_tag_set_full_o(full),.rd_tag_nxm_o(nxm),.tsu_busy_o(busy));
 eci_cc_table rom(.clk,.reset,.en,
   .curr_state_i(sel_inv ? s1__1 : cc_state_t'(state)),
   .curr_req_i(rd_context ? RDDA : request),
   .next_state_o(next_state),.next_action_o(action),.curr_active_req_o(active));
 dcu_controller controller(.clk,.reset,
   .eci_req_valid_i(valid),.eci_req_ready_o(ready),.eci_req_stall_o(stall),
   .eci_evt_crts_rtg_ntry_i(cc_req_creates_new_rtg_entry(request)),
   .eci_rsp_2_prv_snt_req_i(cc_evt_rsp2req(request)),
   .eci_rsp_valid_o(rsp_valid),.eci_rsp_ready_i(rsp_ready),.dec_req_invalid_i(1'b0),
   .rtg_busy_i(busy),.rtg_rd_en_o(rd),.rtg_wr_en_o(wr),
   .rtg_hit_i(hit),.rtg_full_i(full),.rtg_nxm_i(nxm),
   .cc_sel_inv_ps_o(sel_inv),.cc_rom_en_o(en),
   .cc_next_action_from_table_i(action),.cc_curr_req_i(active),
   .tr_rd_req_valid_o(launch_read),.tr_rd_req_ready_i(1'b1),
   .tr_rd_rsp_valid_i(rd_rsp),.tr_rd_rsp_ready_o(rd_rsp_ready),
   .tr_eci_full_i(1'b0),.tr_wr_req_ready_i(1'b1),.tr_wr_rsp_valid_i(1'b0),
   .ctxt_rdda_o(rd_context),.ctxt_wdda_o(wr_context),
   .stall_rtg_full_o(full_stall),.error_o(error));
 always @(posedge clk) if(!reset) begin
   assert(!error && !wr_context) else $fatal(1,"controller error/unsupported write context");
   if(wr) begin
     writes++;
     $display("EVENT directory_write set=%0d tag=%0d request=%s action=%s next=%s hit=%0d full=%0d",set_id,tag,active.name(),action.name(),next_state.name(),hit,full);
   end
   if(rsp_valid && rsp_ready && action==SEND_LCIA) lcias++;
 end
 task automatic event_req(input cc_req_t req, input int t, input int s=0);
   bit done;
   @(negedge clk); request=req;tag=18'(t);set_id=7'(s);valid=1;done=0;
   for(int i=0;i<200;i++) begin
     @(negedge clk);
     if(ready) begin done=1;break;end
   end
   assert(done) else $fatal(1,"event %s tag %0d timeout",req.name(),t);
   @(posedge clk); #1; valid=0;
   repeat(3) @(negedge clk);
 endtask
 task automatic finish_read(input int t, input int s=0);
   bit done;
   @(negedge clk); tag=18'(t);set_id=7'(s);rd_rsp=1;done=0;
   for(int i=0;i<200;i++) begin
     @(negedge clk);
     if(rd_rsp_ready) begin done=1;break;end
   end
   assert(done) else $fatal(1,"RDDA timeout");
   @(posedge clk); #1; rd_rsp=0;
   repeat(3) @(negedge clk);
 endtask
 task automatic acquire(input int t);
   event_req(R12,t);finish_read(t);
 endtask
 task automatic blocked_absent;
   int before_writes,before_lcias,retries;
   @(negedge clk); tag=0;set_id=0;request=LCI;valid=1;
   before_writes=writes;before_lcias=lcias;retries=0;
   repeat(512) begin
     @(negedge clk);
     assert(!ready && writes==before_writes && lcias==before_lcias) else $fatal(1,"absent full-set LCI advanced");
     if(full_stall && stall) begin
       assert(full && !hit) else $fatal(1,"not a real full miss");
       retries++;
     end
   end
   assert(retries>8) else $fatal(1,"insufficient full-set retries");
   // Abstract input arbitration can select a release after a reported stall.
   while(!stall) @(negedge clk);
   @(posedge clk); #1;valid=0;
   repeat(3) @(negedge clk);
   $display("PASS actual directory absent-LCI blocked without writes/LCIA for 512 cycles (%0d retries)",retries);
 endtask
 initial begin
   int lc;
   repeat(4) @(negedge clk);reset=0;
   wait(!busy);repeat(3) @(negedge clk);
   // Reach occupancy through the protocol: target was resident, then evicted
   // before a 17th CPU allocation. CPU occupancy never exceeds sixteen.
   acquire(0);
   for(int t=1;t<16;t++) acquire(t);
   event_req(V21,0);
   acquire(16);
   $display("PASS filled all sixteen ways via R12/RDDA after clean target eviction");
   // Other-set demand can remain pending without causing a target-set victim.
   event_req(R12,200,1);
   blocked_absent();
   // Full HIT: no allocation needed; LCI/A21 retains its existing entry as lock.
   event_req(LCI,16);event_req(A21,16);
   assert(next_state==s1pUL__1) else $fatal(1,"resident invalidation did not lock");
   $display("PASS full-set hit invalidation completes and retains existing lock entry");
   blocked_absent();
   // UL genuinely frees a way. Retry can now allocate the absent local lock.
   event_req(UL,16);
   lc=lcias;event_req(LCI,0);
   assert(next_state==s1pUL__1 && lcias==lc+1) else $fatal(1,"absent LCI failed after UL");
   $display("PASS releasing resident lock permits absent LCI allocation and LCIA");
   event_req(UL,0);
   // Read each survivor through a valid hit event and check it wasn't lost.
   for(int t=1;t<16;t++) begin
     event_req(LCI,t);
     assert(next_state==s1pCI__1_A21) else $fatal(1,"resident tag %0d lost",t);
     event_req(A21,t);event_req(UL,t);
   end
   finish_read(200,1);
   assert(next_state==s1__2) else $fatal(1,"other-set pending read lost");
   $display("PASS all fifteen unrelated resident entries and other-set pending read preserved");
   $finish;
 end
 initial begin #500000; $fatal(1,"timeout"); end
endmodule
