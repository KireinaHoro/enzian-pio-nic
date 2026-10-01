// Test-only model of the exact common-clock, 1-cycle, write-first XPM RAM
// configuration used by ram_tdp. Not vendor primitive verification.
module xpm_memory_tdpram #(
 parameter ADDR_WIDTH_A=10, ADDR_WIDTH_B=10, AUTO_SLEEP_TIME=0,
 BYTE_WRITE_WIDTH_A=9, BYTE_WRITE_WIDTH_B=9,
 parameter CLOCKING_MODE="common_clock", ECC_MODE="no_ecc",
 MEMORY_INIT_FILE="none", MEMORY_INIT_PARAM="0", MEMORY_OPTIMIZATION="true",
 MEMORY_PRIMITIVE="block", parameter MEMORY_SIZE=36864, MESSAGE_CONTROL=0,
 READ_DATA_WIDTH_A=36, READ_DATA_WIDTH_B=36, READ_LATENCY_A=1, READ_LATENCY_B=1,
 parameter READ_RESET_VALUE_A="0", READ_RESET_VALUE_B="0",
 parameter USE_EMBEDDED_CONSTRAINT=0, USE_MEM_INIT=1,
 parameter WAKEUP_TIME="disable_sleep", parameter WRITE_DATA_WIDTH_A=36,
 WRITE_DATA_WIDTH_B=36, parameter WRITE_MODE_A="write_first", WRITE_MODE_B="write_first"
)(input clka,clkb,rsta,rstb,ena,enb,regcea,regceb,sleep,
 input [ADDR_WIDTH_A-1:0] addra, input [ADDR_WIDTH_B-1:0] addrb,
 input [35:0] dina,dinb, input [3:0] wea,web,
 input injectdbiterra,injectdbiterrb,injectsbiterra,injectsbiterrb,
 output logic [35:0] douta,doutb,
 output wire dbiterra,dbiterrb,sbiterra,sbiterrb);
 logic [35:0] mem[0:1023];
 assign {dbiterra,dbiterrb,sbiterra,sbiterrb}=0;
 initial begin
   assert(ADDR_WIDTH_A==10 && ADDR_WIDTH_B==10 && MEMORY_SIZE==36864 &&
     READ_LATENCY_A==1 && READ_LATENCY_B==1 && READ_DATA_WIDTH_A==36 &&
     READ_DATA_WIDTH_B==36 && WRITE_DATA_WIDTH_A==36 && WRITE_DATA_WIDTH_B==36 &&
     BYTE_WRITE_WIDTH_A==9 && BYTE_WRITE_WIDTH_B==9 &&
     CLOCKING_MODE=="common_clock" && WRITE_MODE_A=="write_first" &&
     WRITE_MODE_B=="write_first" && ECC_MODE=="no_ecc") else $fatal(1,"unsupported XPM model configuration");
 end
 always @(posedge clka) begin
   assert(clkb && !sleep && regcea && regceb) else $fatal(1,"unsupported XPM controls");
   assert(!(ena && enb && addra==addrb && (|wea || |web))) else $fatal(1,"XPM cross-port collision");
   if(ena) begin
     for(int i=0;i<4;i++) if(wea[i]) mem[addra][i*9+:9]=dina[i*9+:9];
     douta <= mem[addra];
   end
   if(enb) begin
     for(int i=0;i<4;i++) if(web[i]) mem[addrb][i*9+:9]=dinb[i*9+:9];
     doutb <= mem[addrb];
   end
   if(rsta) douta<=0;
   if(rstb) doutb<=0;
 end
endmodule
