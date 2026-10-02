`timescale 1ns/1ps
import eci_cmd_defs::*;
import eci_dcs_defs::*;
// Actual slice control array/routers/arbiters. RAM and passive trace outputs
// use test models; payload datapath and CPU are not instantiated.
module DcsSliceRoutingTb;
 logic clk=0,reset=1;
 always #5 clk=~clk;
 eci_word_t req='0, local_rsp, cpu_rsp, fwd;
 logic [1:0] channel=0;
 logic valid=0,ready;
 logic req_ready,ack_ready,lci_ready;
 logic rd_valid,wr_valid,rd_done=0,wr_done=0,rd_done_ready,wr_done_ready;
 logic [6:0] rd_id,wr_id,done_id;
 logic [37:0] rd_addr,wr_addr;
 logic [127:0] wr_strb;
 logic local_valid,cpu_valid,fwd_valid;
 logic local_ready=1;
 logic [39:0] expected_by_id[0:63];
 logic [39:0] addresses[0:4095];
 integer count,fd,status,reads=0,writes=0,acks=0,forwards=0,replies=0;
 logic [5:0] fwd_id;
 string vectors;
 assign ready=channel==0 ? req_ready : channel==1 ? lci_ready : ack_ready;
 dcs_dcus dut(
   .clk,.reset,
   .req_wod_hdr_i(req),.req_wod_pkt_size_i(5'd1),.req_wod_pkt_vc_i(5'(VC_REQ_WO_DATA_E)),
   .req_wod_pkt_valid_i(valid && channel==0),.req_wod_pkt_ready_o(req_ready),
   .rsp_wod_hdr_i('0),.rsp_wod_pkt_size_i('0),.rsp_wod_pkt_vc_i('0),.rsp_wod_pkt_valid_i(1'b0),
   .rsp_wd_hdr_i(req),.rsp_wd_pkt_size_i(5'd17),.rsp_wd_pkt_vc_i(5'(VC_RESP_W_DATA_E)),
   .rsp_wd_pkt_valid_i(valid && channel==2),.rsp_wd_pkt_ready_o(ack_ready),
   .lcl_fwd_wod_hdr_i(req),.lcl_fwd_wod_pkt_size_i(5'd1),.lcl_fwd_wod_pkt_vc_i(5'(VC_LCL_FWD_WO_DATA_E)),
   .lcl_fwd_wod_pkt_valid_i(valid && channel==1),.lcl_fwd_wod_pkt_ready_o(lci_ready),
   .lcl_rsp_wod_hdr_i('0),.lcl_rsp_wod_pkt_size_i('0),.lcl_rsp_wod_pkt_vc_i('0),.lcl_rsp_wod_pkt_valid_i(1'b0),
   .rsp_wod_pkt_ready_i(1'b1),.rsp_wd_hdr_o(cpu_rsp),.rsp_wd_pkt_valid_o(cpu_valid),.rsp_wd_pkt_ready_i(1'b1),
   .fwd_wod_hdr_o(fwd),.fwd_wod_pkt_valid_o(fwd_valid),.fwd_wod_pkt_ready_i(1'b1),
   .lcl_rsp_wod_hdr_o(local_rsp),.lcl_rsp_wod_pkt_valid_o(local_valid),.lcl_rsp_wod_pkt_ready_i(local_ready),
   .rd_req_id_o(rd_id),.rd_req_addr_o(rd_addr),.rd_req_valid_o(rd_valid),.rd_req_ready_i(1'b1),
   .rd_rsp_id_i(done_id),.rd_rsp_valid_i(rd_done),.rd_rsp_ready_o(rd_done_ready),
   .wr_req_id_o(wr_id),.wr_req_addr_o(wr_addr),.wr_req_strb_o(wr_strb),.wr_req_valid_o(wr_valid),.wr_req_ready_i(1'b1),
   .wr_rsp_id_i(done_id),.wr_rsp_bresp_i(2'b00),.wr_rsp_valid_i(wr_done),.wr_rsp_ready_o(wr_done_ready));
 always @(posedge clk) if(!reset) begin
   for(int d=0;d<DS_NUM_DCU_IDX;d++) begin
     assert(!dut.dc_dbg_o[d].error) else $fatal(1,"DCU %0d error",d);
     if(dut.dc_req_wod_i[d].valid)
       assert(d==dut.dc_req_wod_i[d].hdr[12:8]) else $fatal(1,"wrong request DCU");
     if(dut.dc_lcl_fwd_wod_i[d].valid)
       assert(d==dut.dc_lcl_fwd_wod_i[d].hdr[12:8]) else $fatal(1,"wrong LCI DCU");
     if(dut.dc_rsp_wd_hdr_i[d].valid)
       assert(d==dut.dc_rsp_wd_hdr_i[d].hdr[12:8]) else $fatal(1,"wrong dirty ACK DCU");
   end
   if(rd_valid) begin
     assert(rd_addr==expected_by_id[rd_id[5:0]][37:0] && rd_id=={1'b0,rd_addr[12:7]}) else $fatal(1,"read address/id changed");
     reads++;
   end
   if(wr_valid) begin
     assert(wr_addr==expected_by_id[wr_id[5:0]][37:0] && wr_id=={1'b0,wr_addr[12:7]} && &wr_strb) else $fatal(1,"write address/id/strb changed");
     writes++;
   end
   if(cpu_valid) begin
     assert(cpu_rsp[39:0]==expected_by_id[cpu_rsp[12:7]]) else $fatal(1,"CPU response address changed");
     replies++;
   end
   if(fwd_valid) begin
     assert(fwd[39:0]==expected_by_id[fwd[12:7]]) else $fatal(1,"forward address changed");
     fwd_id=fwd.mfwd_generic.hreq_id;
     forwards++;
   end
   if(local_valid && local_ready) begin
     assert(local_rsp[39:0]==expected_by_id[local_rsp[12:7]]) else $fatal(1,"LCIA address changed");
     acks++;
   end
 end
 task automatic send(input eci_word_t h,input logic[1:0] ch);
   @(negedge clk); req=h;channel=ch;valid=1;
   do @(posedge clk); while(!ready);
   @(negedge clk);valid=0;
 endtask
 task automatic await_count(input int kind,input int target);
   bit done;
   done=0;
   repeat(300) begin
     @(negedge clk);
     if((kind==0 && reads==target)||(kind==1 && replies==target)||(kind==2 && forwards==target)||(kind==3 && writes==target)||(kind==4 && acks==target)) begin done=1;break;end
   end
   assert(done) else $fatal(1,"completion timeout kind %0d target %0d",kind,target);
 endtask
 task automatic start_read(input logic[39:0] address);
   eci_word_t h;
   int target;
   target=reads+1;expected_by_id[address[12:7]]=address;
   h='0;h.mreq_load.opcode=ECI_CMD_MREQ_RLDD;h.mreq_load.address=address;h.mreq_load.dmask='1;
   send(h,0);await_count(0,target);
 endtask
 task automatic finish_read(input logic[39:0] address);
   int target;
   target=replies+1;
   done_id={1'b0,address[12:7]};rd_done=1;
   do @(posedge clk);while(!rd_done_ready);
   @(negedge clk);rd_done=0;await_count(1,target);
 endtask
 task automatic retire(input logic[39:0] address);
   eci_word_t h;
   int forward_target,write_target,ack_target;
   forward_target=forwards+1;write_target=writes+1;ack_target=acks+1;
   h='0;h.lcl_mfwd_generic.opcode=LCL_CMD_MFWD_CLEAN_INV;h.lcl_mfwd_generic.address=address;
   h.lcl_mfwd_generic.hreq_id=3;h.lcl_mfwd_generic.ns=1;h.lcl_mfwd_generic.dmask='1;
   send(h,1);await_count(2,forward_target);
   h='0;h.mrsp_3to8.opcode=ECI_CMD_MRSP_HAKD;h.mrsp_3to8.address=address;h.mrsp_3to8.hreq_id=fwd_id;h.mrsp_3to8.dmask='1;
   send(h,2);await_count(3,write_target);
   repeat(40) @(negedge clk);
   assert(acks==ack_target-1 && !local_valid) else $fatal(1,"LCIA preceded delayed write completion");
   local_ready=0;done_id={1'b0,address[12:7]};wr_done=1;
   do @(posedge clk);while(!wr_done_ready);
   @(negedge clk);wr_done=0;
   wait(local_valid);repeat(8) @(negedge clk);
   assert(local_valid && local_rsp[39:0]==address) else $fatal(1,"LCIA lost under backpressure");
   local_ready=1;await_count(4,ack_target);repeat(4) @(negedge clk);
 endtask
 initial begin
   int held_replies;
   logic[39:0] held[0:2];
   logic[39:0] peer,bypass_rx,bypass_tx;
   assert($value$plusargs("vectors=%s",vectors)) else $fatal(1,"missing vectors");
   fd=$fopen(vectors,"r");count=0;
   while(!$feof(fd)) begin status=$fscanf(fd,"%h\n",addresses[count]);if(status==1) count++;end
   $fclose(fd); assert(count==812) else $fatal(1,"unexpected selected address matrix");
   repeat(4) @(negedge clk);reset=0;repeat(540) @(negedge clk);
   for(int n=0;n<count;n++) begin
     start_read(addresses[n]);finish_read(addresses[n]);retire(addresses[n]);
   end
   assert(reads==count && replies==count && writes==count && acks==count);
   $display("PASS %0d addresses through production slice control routing, read response, dirty write completion and backpressured LCIA",count);
   // Input matrix: four domain visits x 28 lines per worker, then bypass.
   for(int i=0;i<3;i++) held[i]=addresses[i*112];
   peer=addresses[3*112];bypass_rx=addresses[7*112];bypass_tx=addresses[7*112+14];
   for(int i=0;i<3;i++) start_read(held[i]);
   start_read(bypass_rx);held_replies=replies;
   // Three worker reads and bypass RX have no response. An independent worker
   // and bypass TX repeatedly complete reads and dirty invalidation/writeback.
   for(int i=0;i<3;i++) begin
     start_read(peer);finish_read(peer);retire(peer);
     start_read(bypass_tx);finish_read(bypass_tx);retire(bypass_tx);
     assert(replies==held_replies+2*(i+1)) else $fatal(1,"held read unexpectedly completed");
   end
   $display("PASS three held worker reads plus held bypass RX permit independent worker and bypass TX completion");
   finish_read(bypass_rx);retire(bypass_rx);
   for(int i=2;i>=0;i--) begin finish_read(held[i]);retire(held[i]);end
   assert(reads==replies && writes==acks) else $fatal(1,"outstanding resource accounting mismatch");
   $display("PASS delayed held-read responses route by retained ID; final writeback and LCIA release all transactions");
   $finish;
 end
 initial begin #100000000;$fatal(1,"slice routing test timeout");end
endmodule

// Passive trace-only ports. They cannot affect protocol readiness; production
// trace mux/buffer implementation is covered by the separate toolkit tests.
module eci_channel_buffer(input clk,input [583:0] i,output ready,output [583:0] o,input out_ready);
 assign ready=1'b1;assign o='0;
endmodule
module eci_channel_muxer #(parameter N=16,A=1,B=1)(input clk,input [N*584-1:0] i,output [N-1:0] ready,output [583:0] o,input out_ready);
 assign ready='1;assign o='0;
endmodule
