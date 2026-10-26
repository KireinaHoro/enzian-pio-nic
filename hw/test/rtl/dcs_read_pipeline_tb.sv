module dcs_read_pipeline_tb;
  localparam DESC_ID_WIDTH=7, DESC_ADDR_WIDTH=24, CL_WIDTH=1024;
  localparam AXI_ID_WIDTH=7, AXI_ADDR_WIDTH=24, AXI_DATA_WIDTH=512;
  logic  clk;
  logic  reset;
  logic [DESC_ID_WIDTH-1:0] rd_req_id_i;
  logic [DESC_ADDR_WIDTH-1:0] rd_req_addr_i;
  logic  rd_req_valid_i;
  logic  rd_req_ready_o;
  logic [DESC_ID_WIDTH-1:0] rd_rsp_id_o;
  logic [CL_WIDTH-1:0] rd_rsp_data_o;
  logic  rd_rsp_valid_o;
  logic  rd_rsp_ready_i;
  logic [AXI_ID_WIDTH-1:0] p_axi_arid;
  logic [AXI_ADDR_WIDTH-1:0] p_axi_araddr;
  logic [7:0] p_axi_arlen;
  logic [2:0] p_axi_arsize;
  logic [1:0] p_axi_arburst;
  logic  p_axi_arlock;
  logic [3:0] p_axi_arcache;
  logic [2:0] p_axi_arprot;
  logic  p_axi_arvalid;
  logic  p_axi_arready;
  logic [AXI_ID_WIDTH-1:0] p_axi_rid;
  logic [AXI_DATA_WIDTH-1:0] p_axi_rdata;
  logic [1:0] p_axi_rresp;
  logic  p_axi_rlast;
  logic  p_axi_rvalid;
  logic  p_axi_rready;
  lauberhorn_axi_rd_cl #(.DESC_ID_WIDTH(7), .DESC_ADDR_WIDTH(24),
    .AXI_ID_WIDTH(7), .AXI_ADDR_WIDTH(24)) dut (.*);
  initial begin clk=0; forever #5 clk=~clk; end
  integer cycles=0, received=0, addressed=0;
  logic held=0;
  logic [1030:0] held_response;
  always @(negedge clk) begin
    cycles <= cycles + 1;
    p_axi_arready <= cycles % 7 >= 2;
    rd_rsp_ready_i <= cycles > 60 && cycles % 17 >= 9;
  end
  always @(posedge clk) if (!reset) begin
    if (p_axi_arvalid && p_axi_arready) begin
      assert(p_axi_arid == (addressed % 128) && p_axi_araddr == addressed * 128 &&
             p_axi_arlen == 1 && p_axi_arsize == 6) else $fatal(1,"AR mismatch");
      addressed <= addressed + 1;
    end
    if (held) assert(rd_rsp_valid_o && {rd_rsp_id_o,rd_rsp_data_o} == held_response)
      else $fatal(1,"stalled response changed");
    held <= rd_rsp_valid_o && !rd_rsp_ready_i;
    held_response <= {rd_rsp_id_o,rd_rsp_data_o};
    if (rd_rsp_valid_o && rd_rsp_ready_i) begin
      assert(rd_rsp_id_o == received % 128 &&
             rd_rsp_data_o == {{16{32'(received*2+1)}},{16{32'(received*2)}}})
        else $fatal(1,"response ID/data/order mismatch at %0d", received);
      received <= received + 1;
    end
    if (cycles > 15000) $fatal(1,"timeout");
  end
  initial begin
    reset=1; rd_req_valid_i=0; p_axi_rvalid=0; p_axi_rresp=0;
    rd_req_id_i=0; rd_req_addr_i=0; p_axi_rid=0; p_axi_rdata=0; p_axi_rlast=0;
    p_axi_arready=0; rd_rsp_ready_i=0;
    repeat(5) @(negedge clk);
    reset=0;
    for(integer txn=0; txn<256; txn++) begin
      @(negedge clk);
      rd_req_id_i=7'(txn); rd_req_addr_i=24'(txn*128); rd_req_valid_i=1;
      do @(posedge clk); while (!rd_req_ready_o);
      @(negedge clk); rd_req_valid_i=0;
      wait(addressed > txn);
      for(integer beat=0; beat<2; beat++) begin
        @(negedge clk);
        p_axi_rid=7'(txn); p_axi_rdata={16{32'(txn*2+beat)}};
        p_axi_rlast=(beat==1); p_axi_rvalid=1;
        do @(posedge clk); while (!p_axi_rready);
        @(negedge clk); p_axi_rvalid=0;
      end
    end
    wait(received==256);
    $display("DCS_READ_PIPELINE_PASSED: 256 ordered cache lines with stalls and ID wrap");
    $finish;
  end
endmodule
