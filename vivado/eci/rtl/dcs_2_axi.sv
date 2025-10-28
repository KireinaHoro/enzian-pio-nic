/*
 * Systems Group, D-INFK, ETH Zurich.
 *
 * Author  : A.Ramdas
 * Date    : 2022-09-27
 * Project : Enzian
 *
 * Copyright (c) 2022, ETH Zurich.  All rights reserved.
 *
 */


`ifndef DCS_2_AXI_SV
 `define DCS_2_AXI_SV

import eci_cmd_defs::*;
import eci_dcs_defs::*;

// Instantiate dcs and modules that
// convert the rd, wr desc to AXI signals.
// Upstream ECI VC is 4 bits downstream is 5 bits.
// this conversion is done here. 

module dcs_2_axi #
  (
   parameter AXI_ID_WIDTH = MAX_DCU_ID_WIDTH, //7
   parameter AXI_ADDR_WIDTH = DS_ADDR_WIDTH,  //38
   parameter AXI_DATA_WIDTH = 512,
   parameter AXI_STRB_WIDTH = (AXI_DATA_WIDTH/8),
   parameter PERF_REGS_WIDTH = 32,
   parameter SYNTH_PERF_REGS = 1 //0,1
   ) 
   (
    input logic 					   clk,
    input logic 					   reset,
    // Input ECI header only events.
    // Note: VC width upstream is 4 bits instead of 5,
    // needs to be accommodated here.

    // Request without data: VC 6 or 7.
    input logic [ECI_WORD_WIDTH-1:0] 			   req_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   req_wod_pkt_size_i,
    input logic [3:0] 					   req_wod_pkt_vc_i,
    input logic 					   req_wod_pkt_valid_i,
    output logic 					   req_wod_pkt_ready_o,
    
    // Response without data: VC 10 or 11.
    input logic [ECI_WORD_WIDTH-1:0] 			   rsp_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   rsp_wod_pkt_size_i,
    input logic [3:0] 					   rsp_wod_pkt_vc_i,
    input logic 					   rsp_wod_pkt_valid_i,
    output logic 					   rsp_wod_pkt_ready_o,

    // Input ECI header + data events.
    // Response with data: VC 4 or 5.
    input logic [ECI_PACKET_SIZE-1:0][ECI_WORD_WIDTH-1:0]  rsp_wd_pkt_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   rsp_wd_pkt_size_i,
    input logic [3:0] 					   rsp_wd_pkt_vc_i,
    input logic 					   rsp_wd_pkt_valid_i,
    output logic 					   rsp_wd_pkt_ready_o,

    // LCL packet for clean, clean invalidates. (VC 16 or 17) (only header).
    input logic [ECI_WORD_WIDTH-1:0] 			   lcl_fwd_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   lcl_fwd_wod_pkt_size_i,
    input logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0] 	   lcl_fwd_wod_pkt_vc_i, //5
    input logic 					   lcl_fwd_wod_pkt_valid_i,
    output logic 					   lcl_fwd_wod_pkt_ready_o,

    // LCL packet for response without data (unlock) (VC 18 or 19) (only header).
    input logic [ECI_WORD_WIDTH-1:0] 			   lcl_rsp_wod_hdr_i,
    input logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   lcl_rsp_wod_pkt_size_i,
    input logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0] 	   lcl_rsp_wod_pkt_vc_i,
    input logic 					   lcl_rsp_wod_pkt_valid_i,
    output logic 					   lcl_rsp_wod_pkt_ready_o,


    // Output ECI header only events.
    // Response without data: VC 10 or 11.
    output logic [ECI_WORD_WIDTH-1:0] 			   rsp_wod_hdr_o,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   rsp_wod_pkt_size_o,
    output logic [3:0] 					   rsp_wod_pkt_vc_o,
    output logic 					   rsp_wod_pkt_valid_o,
    input logic 					   rsp_wod_pkt_ready_i,

    // Output ECI header + data events.
    // Response with data: VC 4 or 5.
    output logic [ECI_PACKET_SIZE-1:0][ECI_WORD_WIDTH-1:0] rsp_wd_pkt_o,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   rsp_wd_pkt_size_o,
    output logic [3:0] 					   rsp_wd_pkt_vc_o,
    output logic 					   rsp_wd_pkt_valid_o,
    input logic 					   rsp_wd_pkt_ready_i,

    // Output ECI Fwd without data headers: VC 8 or 9.
    output logic [ECI_WORD_WIDTH-1:0] 			   fwd_wod_hdr_o,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   fwd_wod_pkt_size_o,
    output logic [3:0] 					   fwd_wod_pkt_vc_o,
    output logic 					   fwd_wod_pkt_valid_o,
    input logic 					   fwd_wod_pkt_ready_i,

    // Output lcl rsp without data headers: VC 18 or 19.
    output logic [ECI_WORD_WIDTH-1:0] 			   lcl_rsp_wod_hdr_o,
    output logic [ECI_PACKET_SIZE_WIDTH-1:0] 		   lcl_rsp_wod_pkt_size_o,
    output logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0] 	   lcl_rsp_wod_pkt_vc_o,//5
    output logic 					   lcl_rsp_wod_pkt_valid_o,
    input logic 					   lcl_rsp_wod_pkt_ready_i,

    // AXI primary I/F.- Read channel.(term primary is used instead of master).
    output logic [AXI_ID_WIDTH-1:0] 			   p_axi_arid,
    output logic [AXI_ADDR_WIDTH-1:0] 			   p_axi_araddr,
    output logic [7:0] 					   p_axi_arlen, 
    output logic [2:0] 					   p_axi_arsize, 
    output logic [1:0] 					   p_axi_arburst, 
    output logic 					   p_axi_arlock, 
    output logic [3:0] 					   p_axi_arcache, 
    output logic [2:0] 					   p_axi_arprot, 
    output logic 					   p_axi_arvalid,
    input logic 					   p_axi_arready,
    input logic [AXI_ID_WIDTH-1:0] 			   p_axi_rid,
    input logic [AXI_DATA_WIDTH-1:0] 			   p_axi_rdata,
    input logic [1:0] 					   p_axi_rresp,
    input logic 					   p_axi_rlast,
    input logic 					   p_axi_rvalid,
    output logic 					   p_axi_rready,
    // AXI primary I/F - Write channel.
    output logic [AXI_ID_WIDTH-1:0] 			   p_axi_awid,
    output logic [AXI_ADDR_WIDTH-1:0] 			   p_axi_awaddr,
    output logic [7:0] 					   p_axi_awlen,
    output logic [2:0] 					   p_axi_awsize,
    output logic [1:0] 					   p_axi_awburst,
    output logic 					   p_axi_awlock,
    output logic [3:0] 					   p_axi_awcache,
    output logic [2:0] 					   p_axi_awprot,
    output logic 					   p_axi_awvalid,
    input logic 					   p_axi_awready,
    output logic [AXI_DATA_WIDTH-1:0] 			   p_axi_wdata,
    output logic [AXI_STRB_WIDTH-1:0] 			   p_axi_wstrb,
    output logic 					   p_axi_wlast,
    output logic 					   p_axi_wvalid,
    input logic 					   p_axi_wready,
    input logic [AXI_ID_WIDTH-1:0] 			   p_axi_bid,
    input logic [1:0] 					   p_axi_bresp,
    input logic 					   p_axi_bvalid,
    output logic 					   p_axi_bready
    );

   // VC width is 5 bits here, upstream ECI is only 4 bits.
   typedef struct packed {
      logic [ECI_WORD_WIDTH-1:0]            hdr;
      logic [ECI_PACKET_SIZE_WIDTH-1:0]     size;
      logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0] vc;
      logic 				    valid;
      logic 				    ready;
   } eci_hdr_if_t;

   typedef struct packed {
      logic [ECI_PACKET_SIZE-1:0][ECI_WORD_WIDTH-1:0] pkt;
      logic [ECI_PACKET_SIZE_WIDTH-1:0] 	      size;
      logic [ECI_LCL_TOT_NUM_VCS_WIDTH-1:0] 	      vc;
      logic					      valid;
      logic					      ready;
   } eci_pkt_if_t;

   typedef struct packed {
      logic [MAX_DCU_ID_WIDTH-1:0] id;
      logic [DS_ADDR_WIDTH-1:0]    addr;
      logic 			   valid;
      logic 			   ready;
   } rd_req_ctrl_if_t;

   typedef struct packed {
      logic [MAX_DCU_ID_WIDTH-1:0] id;
      logic 			   valid;
      logic 			   ready;
   } rd_rsp_ctrl_if_t;

   typedef logic [ECI_CL_WIDTH-1:0] rd_rsp_data_if_t;

   typedef struct packed {
      logic [MAX_DCU_ID_WIDTH-1:0]  id;
      logic [DS_ADDR_WIDTH-1:0]     addr;
      logic [ECI_CL_SIZE_BYTES-1:0] strb; 
      logic 			    valid;
      logic 			    ready;
   } wr_req_ctrl_if_t;

   typedef logic [ECI_CL_WIDTH-1:0] wr_req_data_if_t;

   typedef struct packed {
      logic [MAX_DCU_ID_WIDTH-1:0]  id;
      logic [1:0] 		    bresp;
      logic 			    valid;
      logic 			    ready;
   } wr_rsp_ctrl_if_t;

   typedef struct packed {
      // AXI primary I/F.- Read channel.(term primary is used instead of master).
      logic [AXI_ID_WIDTH-1:0]   arid;
      logic [AXI_ADDR_WIDTH-1:0] araddr;
      logic [7:0] 		 arlen; 
      logic [2:0] 		 arsize; 
      logic [1:0] 		 arburst; // always incr
      logic 			 arlock; // not used
      logic [3:0] 		 arcache; // not used
      logic [2:0] 		 arprot; // not used
      logic 			 arvalid;
      logic 			 arready;
      logic [AXI_ID_WIDTH-1:0] 	 rid;
      logic [AXI_DATA_WIDTH-1:0] rdata;
      logic [1:0] 		 rresp; // ignored.
      logic 			 rlast;
      logic 			 rvalid;
      logic 			 rready;
   } axi_rd_if_t;

   typedef struct packed {
      // AXI primary I/F - Write channel.
      logic [AXI_ID_WIDTH-1:0] 	 awid;
      logic [AXI_ADDR_WIDTH-1:0] awaddr;
      logic [7:0] 		 awlen;
      logic [2:0] 		 awsize;
      logic [1:0] 		 awburst;
      logic 			 awlock;
      logic [3:0] 		 awcache;
      logic [2:0] 		 awprot;
      logic 			 awvalid;
      logic 			 awready;
      logic [AXI_DATA_WIDTH-1:0] wdata;
      logic [AXI_STRB_WIDTH-1:0] wstrb;
      logic 			 wlast;
      logic 			 wvalid;
      logic 			 wready;
      logic [AXI_ID_WIDTH-1:0] 	 bid;
      logic [1:0] 		 bresp;
      logic 			 bvalid;
      logic 			 bready;
   } axi_wr_if_t;

   // DCS specific signals.
   eci_hdr_if_t dcs_req_wod_i, dcs_rsp_wod_i, dcs_rsp_wod_o, dcs_lcl_fwd_wod_i, dcs_lcl_rsp_wod_i;
   eci_hdr_if_t dcs_fwd_wod_o, dcs_lcl_rsp_wod_o;
   eci_pkt_if_t dcs_rsp_wd_i, dcs_rsp_wd_o;
   rd_req_ctrl_if_t dcs_rd_req_o;
   rd_rsp_ctrl_if_t dcs_rd_rsp_ctrl_i;
   rd_rsp_data_if_t dcs_rd_rsp_data_i;
   wr_req_ctrl_if_t dcs_wr_req_ctrl_o;
   wr_req_data_if_t dcs_wr_req_data_o;
   wr_rsp_ctrl_if_t dcs_wr_rsp_ctrl_i;
   // Desc to AXI read signals.
   rd_req_ctrl_if_t d2a_rd_req_ctrl_i;
   rd_rsp_ctrl_if_t d2a_rd_rsp_ctrl_o;
   rd_rsp_data_if_t d2a_rd_rsp_data_o;
   axi_rd_if_t d2a_p_axi_rd;
   // Desc to AXI write signals.
   wr_req_ctrl_if_t d2a_wr_req_ctrl_i;
   wr_rsp_ctrl_if_t d2a_wr_rsp_ctrl_o;
   wr_req_data_if_t d2a_wr_req_data_i;
   axi_wr_if_t d2a_p_axi_wr;
   
   always_comb begin : OUT_ASSIGN
      // Request without data: VC 6 or 7.
      req_wod_pkt_ready_o	= dcs_req_wod_i.ready;
      // Response without data: VC 10 or 11.
      rsp_wod_pkt_ready_o	= dcs_rsp_wod_i.ready;
      // Response with data: VC 4 or 5.
      rsp_wd_pkt_ready_o	= dcs_rsp_wd_i.ready;
      // lcl clean, inv req: VC 16 or 17.
      lcl_fwd_wod_pkt_ready_o   = dcs_lcl_fwd_wod_i.ready;
      // lcl rsp_wod unlock: VC 18 or 19
      lcl_rsp_wod_pkt_ready_o   = dcs_lcl_rsp_wod_i.ready;
      // Output ECI header only events.
      // Response without data: VC 10 or 11.
      rsp_wod_hdr_o		= dcs_rsp_wod_o.hdr;
      rsp_wod_pkt_size_o	= dcs_rsp_wod_o.size;
      rsp_wod_pkt_vc_o		= dcs_rsp_wod_o.vc[3:0];
      rsp_wod_pkt_valid_o	= dcs_rsp_wod_o.valid;

      // Output ECI header + data events.
      // Response with data: VC 4 or 5.
      rsp_wd_pkt_o		= dcs_rsp_wd_o.pkt;
      rsp_wd_pkt_size_o		= dcs_rsp_wd_o.size;
      rsp_wd_pkt_vc_o		= dcs_rsp_wd_o.vc[3:0];
      rsp_wd_pkt_valid_o	= dcs_rsp_wd_o.valid;

      // Output ECI header.
      // Fwd without data: VC 8 or 9
      fwd_wod_hdr_o		= dcs_fwd_wod_o.hdr;
      fwd_wod_pkt_size_o	= dcs_fwd_wod_o.size;
      fwd_wod_pkt_vc_o		= dcs_fwd_wod_o.vc[3:0];
      fwd_wod_pkt_valid_o	= dcs_fwd_wod_o.valid;

      // Output lcl rsp without data: VC 18 or 19.
      // lcl clean, clean invs.
      lcl_rsp_wod_hdr_o		= dcs_lcl_rsp_wod_o.hdr;
      lcl_rsp_wod_pkt_size_o	= dcs_lcl_rsp_wod_o.size;
      lcl_rsp_wod_pkt_vc_o	= dcs_lcl_rsp_wod_o.vc;
      lcl_rsp_wod_pkt_valid_o	= dcs_lcl_rsp_wod_o.valid;
      
      // AXI primary I/F.- Read channel.(term primary is used instead of master).
      p_axi_arid		= d2a_p_axi_rd.arid;
      p_axi_araddr		= d2a_p_axi_rd.araddr;
      p_axi_arlen		= d2a_p_axi_rd.arlen; 
      p_axi_arsize		= d2a_p_axi_rd.arsize; 
      p_axi_arburst		= d2a_p_axi_rd.arburst; 
      p_axi_arlock		= d2a_p_axi_rd.arlock; 
      p_axi_arcache		= d2a_p_axi_rd.arcache; 
      p_axi_arprot		= d2a_p_axi_rd.arprot; 
      p_axi_arvalid		= d2a_p_axi_rd.arvalid;
      p_axi_rready		= d2a_p_axi_rd.rready;
      // AXI primary I/F - Write channel.
      p_axi_awid		= d2a_p_axi_wr.awid;
      p_axi_awaddr		= d2a_p_axi_wr.awaddr;
      p_axi_awlen		= d2a_p_axi_wr.awlen;
      p_axi_awsize		= d2a_p_axi_wr.awsize;
      p_axi_awburst		= d2a_p_axi_wr.awburst;
      p_axi_awlock		= d2a_p_axi_wr.awlock;
      p_axi_awcache		= d2a_p_axi_wr.awcache;
      p_axi_awprot		= d2a_p_axi_wr.awprot;
      p_axi_awvalid		= d2a_p_axi_wr.awvalid;
      p_axi_wdata		= d2a_p_axi_wr.wdata;
      p_axi_wstrb		= d2a_p_axi_wr.wstrb;
      p_axi_wlast		= d2a_p_axi_wr.wlast;
      p_axi_wvalid		= d2a_p_axi_wr.wvalid;
      p_axi_bready		= d2a_p_axi_wr.bready;
   end : OUT_ASSIGN

   // DCS instance.
   always_comb begin : DCS_IP_ASSIGN
      // Input ECI header only events.
      dcs_req_wod_i.hdr		= req_wod_hdr_i;
      dcs_req_wod_i.size	= req_wod_pkt_size_i;
      dcs_req_wod_i.vc		= {1'b0,req_wod_pkt_vc_i};
      dcs_req_wod_i.valid	= req_wod_pkt_valid_i; 
      dcs_rsp_wod_i.hdr		= rsp_wod_hdr_i;
      dcs_rsp_wod_i.size	= rsp_wod_pkt_size_i;
      dcs_rsp_wod_i.vc		= {1'b0,rsp_wod_pkt_vc_i};
      dcs_rsp_wod_i.valid	= rsp_wod_pkt_valid_i;
      dcs_lcl_fwd_wod_i.hdr     = lcl_fwd_wod_hdr_i;
      dcs_lcl_fwd_wod_i.size    = lcl_fwd_wod_pkt_size_i;
      dcs_lcl_fwd_wod_i.vc      = lcl_fwd_wod_pkt_vc_i;
      dcs_lcl_fwd_wod_i.valid   = lcl_fwd_wod_pkt_valid_i;
      dcs_lcl_rsp_wod_i.hdr     = lcl_rsp_wod_hdr_i;
      dcs_lcl_rsp_wod_i.size    = lcl_rsp_wod_pkt_size_i;
      dcs_lcl_rsp_wod_i.vc      = lcl_rsp_wod_pkt_vc_i;
      dcs_lcl_rsp_wod_i.valid   = lcl_rsp_wod_pkt_valid_i;
      // Input ECI header + data events.
      dcs_rsp_wd_i.pkt		= rsp_wd_pkt_i;
      dcs_rsp_wd_i.size		= rsp_wd_pkt_size_i;
      dcs_rsp_wd_i.vc		= {1'b0,rsp_wd_pkt_vc_i};
      dcs_rsp_wd_i.valid	= rsp_wd_pkt_valid_i; 
      // Output ECI header only events.
      dcs_rsp_wod_o.ready	= rsp_wod_pkt_ready_i;
      // Output ECI header + data events.
      dcs_rsp_wd_o.ready	= rsp_wd_pkt_ready_i;
      // Output FWD vc 8 or 9
      dcs_fwd_wod_o.ready       = fwd_wod_pkt_ready_i;
      // Output lcl rsp vc 18 or 19
      dcs_lcl_rsp_wod_o.ready   = lcl_rsp_wod_pkt_ready_i;
      // Output Read descriptors
      // Read descriptors: Request and response.
      dcs_rd_req_o.ready	= d2a_rd_req_ctrl_i.ready;
      dcs_rd_rsp_ctrl_i.id	= d2a_rd_rsp_ctrl_o.id;
      dcs_rd_rsp_data_i		= d2a_rd_rsp_data_o;
      dcs_rd_rsp_ctrl_i.valid	= d2a_rd_rsp_ctrl_o.valid; 
      // Write descriptors: Request and response.
      dcs_wr_req_ctrl_o.ready	= d2a_wr_req_ctrl_i.ready;
      dcs_wr_rsp_ctrl_i.id	= d2a_wr_rsp_ctrl_o.id;
      dcs_wr_rsp_ctrl_i.bresp	= d2a_wr_rsp_ctrl_o.bresp;
      dcs_wr_rsp_ctrl_i.valid	= d2a_wr_rsp_ctrl_o.valid; 
   end : DCS_IP_ASSIGN
   dcs #
     (
      .PERF_REGS_WIDTH(PERF_REGS_WIDTH),
      .SYNTH_PERF_REGS(SYNTH_PERF_REGS)
      )
   dcs_inst
     (
      .clk(clk),
      .reset(reset),
      // Input ECI header only events.
      // Request without data: VC 6 or 7.
      .req_wod_hdr_i		(dcs_req_wod_i.hdr),
      .req_wod_pkt_size_i	(dcs_req_wod_i.size),
      .req_wod_pkt_vc_i		(dcs_req_wod_i.vc),
      .req_wod_pkt_valid_i	(dcs_req_wod_i.valid),
      .req_wod_pkt_ready_o	(dcs_req_wod_i.ready),
      // Response without data: VC 10 or 11.
      .rsp_wod_hdr_i		(dcs_rsp_wod_i.hdr),
      .rsp_wod_pkt_size_i	(dcs_rsp_wod_i.size),
      .rsp_wod_pkt_vc_i		(dcs_rsp_wod_i.vc),
      .rsp_wod_pkt_valid_i	(dcs_rsp_wod_i.valid),
      .rsp_wod_pkt_ready_o	(dcs_rsp_wod_i.ready),
      // Input ECI header + data events.
      // Response with data: VC 4 or 5.
      .rsp_wd_pkt_i		(dcs_rsp_wd_i.pkt),
      .rsp_wd_pkt_size_i	(dcs_rsp_wd_i.size),
      .rsp_wd_pkt_vc_i		(dcs_rsp_wd_i.vc),
      .rsp_wd_pkt_valid_i	(dcs_rsp_wd_i.valid),
      .rsp_wd_pkt_ready_o	(dcs_rsp_wd_i.ready),
      // Input clean, clean inv VC 16 or 17.
      .lcl_fwd_wod_hdr_i	(dcs_lcl_fwd_wod_i.hdr),
      .lcl_fwd_wod_pkt_size_i	(dcs_lcl_fwd_wod_i.size),
      .lcl_fwd_wod_pkt_vc_i	(dcs_lcl_fwd_wod_i.vc),
      .lcl_fwd_wod_pkt_valid_i	(dcs_lcl_fwd_wod_i.valid),
      .lcl_fwd_wod_pkt_ready_o	(dcs_lcl_fwd_wod_i.ready),
      // Input rsp_wod unlock VC 18 or 19.
      .lcl_rsp_wod_hdr_i	(dcs_lcl_rsp_wod_i.hdr),
      .lcl_rsp_wod_pkt_size_i	(dcs_lcl_rsp_wod_i.size),
      .lcl_rsp_wod_pkt_vc_i	(dcs_lcl_rsp_wod_i.vc),
      .lcl_rsp_wod_pkt_valid_i	(dcs_lcl_rsp_wod_i.valid),
      .lcl_rsp_wod_pkt_ready_o	(dcs_lcl_rsp_wod_i.ready),
      // Output ECI header only events.
      // Response without data: VC 10 or 11.
      .rsp_wod_hdr_o		(dcs_rsp_wod_o.hdr),
      .rsp_wod_pkt_size_o	(dcs_rsp_wod_o.size),
      .rsp_wod_pkt_vc_o		(dcs_rsp_wod_o.vc),
      .rsp_wod_pkt_valid_o	(dcs_rsp_wod_o.valid),
      .rsp_wod_pkt_ready_i	(dcs_rsp_wod_o.ready),
      // Output ECI header + data events.
      // Response with data: VC 4 or 5.
      .rsp_wd_pkt_o		(dcs_rsp_wd_o.pkt),
      .rsp_wd_pkt_size_o	(dcs_rsp_wd_o.size),
      .rsp_wd_pkt_vc_o		(dcs_rsp_wd_o.vc),
      .rsp_wd_pkt_valid_o	(dcs_rsp_wd_o.valid),
      .rsp_wd_pkt_ready_i	(dcs_rsp_wd_o.ready),
      // Output ECI header
      // Forwards without data: VC 8 or 9.
      .fwd_wod_hdr_o		(dcs_fwd_wod_o.hdr),
      .fwd_wod_pkt_size_o	(dcs_fwd_wod_o.size),
      .fwd_wod_pkt_vc_o		(dcs_fwd_wod_o.vc),
      .fwd_wod_pkt_valid_o	(dcs_fwd_wod_o.valid),
      .fwd_wod_pkt_ready_i	(dcs_fwd_wod_o.ready),
      // Output lcl rsp header.
      // lcl rsp without data: VC 18 or 19.
      .lcl_rsp_wod_hdr_o	(dcs_lcl_rsp_wod_o.hdr),
      .lcl_rsp_wod_pkt_size_o	(dcs_lcl_rsp_wod_o.size),
      .lcl_rsp_wod_pkt_vc_o	(dcs_lcl_rsp_wod_o.vc),
      .lcl_rsp_wod_pkt_valid_o	(dcs_lcl_rsp_wod_o.valid),
      .lcl_rsp_wod_pkt_ready_i	(dcs_lcl_rsp_wod_o.ready),
      // Output Read descriptors
      // Read descriptors: Request and response.
      .rd_req_id_o	(dcs_rd_req_o.id),
      .rd_req_addr_o	(dcs_rd_req_o.addr),
      .rd_req_valid_o	(dcs_rd_req_o.valid),
      .rd_req_ready_i	(dcs_rd_req_o.ready),
      .rd_rsp_id_i	(dcs_rd_rsp_ctrl_i.id),
      .rd_rsp_data_i	(dcs_rd_rsp_data_i),
      .rd_rsp_valid_i	(dcs_rd_rsp_ctrl_i.valid),
      .rd_rsp_ready_o	(dcs_rd_rsp_ctrl_i.ready),
      // Write descriptors: Request and response.
      .wr_req_id_o	(dcs_wr_req_ctrl_o.id),
      .wr_req_addr_o	(dcs_wr_req_ctrl_o.addr),
      .wr_req_data_o	(dcs_wr_req_data_o),
      .wr_req_strb_o	(dcs_wr_req_ctrl_o.strb),
      .wr_req_valid_o	(dcs_wr_req_ctrl_o.valid),
      .wr_req_ready_i	(dcs_wr_req_ctrl_o.ready),
      .wr_rsp_id_i	(dcs_wr_rsp_ctrl_i.id),
      .wr_rsp_bresp_i	(dcs_wr_rsp_ctrl_i.bresp),
      .wr_rsp_valid_i	(dcs_wr_rsp_ctrl_i.valid),
      .wr_rsp_ready_o	(dcs_wr_rsp_ctrl_i.ready)
      );

   // DCS to AXI RD signals.
   always_comb begin : DC2AXIRD_IP_ASSIGN
      // Input rd desc.
      d2a_rd_req_ctrl_i.id	= dcs_rd_req_o.id;
      d2a_rd_req_ctrl_i.addr	= dcs_rd_req_o.addr;
      d2a_rd_req_ctrl_i.valid	= dcs_rd_req_o.valid; 
      // Output rd desc.
      d2a_rd_rsp_ctrl_o.ready	= dcs_rd_rsp_ctrl_i.ready;
      // AXI Rd primary signals.
      d2a_p_axi_rd.arready	= p_axi_arready;
      d2a_p_axi_rd.rid		= p_axi_rid;
      d2a_p_axi_rd.rdata	= p_axi_rdata;
      d2a_p_axi_rd.rresp	= p_axi_rresp;
      d2a_p_axi_rd.rlast	= p_axi_rlast;
      d2a_p_axi_rd.rvalid	= p_axi_rvalid;      
   end : DC2AXIRD_IP_ASSIGN
   axi_rd_cl #
     (
      .DESC_ID_WIDTH(MAX_DCU_ID_WIDTH),
      .DESC_ADDR_WIDTH(DS_ADDR_WIDTH),
      .CL_WIDTH(ECI_CL_WIDTH),
      .AXI_ID_WIDTH(AXI_ID_WIDTH),
      .AXI_ADDR_WIDTH(AXI_ADDR_WIDTH),
      .AXI_DATA_WIDTH(AXI_DATA_WIDTH)
       )
   dc_2_axi_rd_i1
     (
      .clk		(clk),
      .reset		(reset),
      // Input rd desc.
      .rd_req_id_i	(d2a_rd_req_ctrl_i.id),
      .rd_req_addr_i	(d2a_rd_req_ctrl_i.addr),
      .rd_req_valid_i	(d2a_rd_req_ctrl_i.valid),
      .rd_req_ready_o	(d2a_rd_req_ctrl_i.ready),
      // Output rd desc.
      .rd_rsp_id_o	(d2a_rd_rsp_ctrl_o.id),
      .rd_rsp_data_o	(d2a_rd_rsp_data_o),
      .rd_rsp_valid_o	(d2a_rd_rsp_ctrl_o.valid),
      .rd_rsp_ready_i	(d2a_rd_rsp_ctrl_o.ready),
      // AXI Rd primary signals. 
      .p_axi_arid	(d2a_p_axi_rd.arid),
      .p_axi_araddr	(d2a_p_axi_rd.araddr),
      .p_axi_arlen	(d2a_p_axi_rd.arlen),
      .p_axi_arsize	(d2a_p_axi_rd.arsize),
      .p_axi_arburst	(d2a_p_axi_rd.arburst), // always incr.
      .p_axi_arlock	(d2a_p_axi_rd.arlock),  // not used. 
      .p_axi_arcache	(d2a_p_axi_rd.arcache),	// not used. 
      .p_axi_arprot	(d2a_p_axi_rd.arprot),	// not used. 
      .p_axi_arvalid	(d2a_p_axi_rd.arvalid),
      .p_axi_arready	(d2a_p_axi_rd.arready),
      .p_axi_rid	(d2a_p_axi_rd.rid),
      .p_axi_rdata	(d2a_p_axi_rd.rdata),
      .p_axi_rresp	(d2a_p_axi_rd.rresp),   // ignored.
      .p_axi_rlast	(d2a_p_axi_rd.rlast),
      .p_axi_rvalid	(d2a_p_axi_rd.rvalid),
      .p_axi_rready	(d2a_p_axi_rd.rready)
      );

   // DCS to AXI write signals.
   always_comb begin : AXIWRCL_IP_ASSIGN
      // Input wr desc.
      d2a_wr_req_ctrl_i.id	= dcs_wr_req_ctrl_o.id;
      d2a_wr_req_ctrl_i.addr	= dcs_wr_req_ctrl_o.addr;
      d2a_wr_req_data_i		= dcs_wr_req_data_o;
      d2a_wr_req_ctrl_i.strb	= dcs_wr_req_ctrl_o.strb;
      d2a_wr_req_ctrl_i.valid	= dcs_wr_req_ctrl_o.valid;
      // Output wr desc.
      d2a_wr_rsp_ctrl_o.ready	= dcs_wr_rsp_ctrl_i.ready;
      // Axi Wr primary signals.
      d2a_p_axi_wr.awready	= p_axi_awready;
      d2a_p_axi_wr.wready	= p_axi_wready;
      d2a_p_axi_wr.bid		= p_axi_bid;
      d2a_p_axi_wr.bresp	= p_axi_bresp;
      d2a_p_axi_wr.bvalid	= p_axi_bvalid; 
   end : AXIWRCL_IP_ASSIGN
   axi_wr_cl #
     (
      .DESC_ID_WIDTH(MAX_DCU_ID_WIDTH),
      .DESC_ADDR_WIDTH(DS_ADDR_WIDTH),
      .DESC_DATA_WIDTH(ECI_CL_WIDTH),
      .AXI_ID_WIDTH(AXI_ID_WIDTH),
      .AXI_ADDR_WIDTH(AXI_ADDR_WIDTH),
      .AXI_DATA_WIDTH(AXI_DATA_WIDTH)
      )
   dc_2_axi_wr_i1
     (
      .clk		(clk),
      .reset		(reset),
      // Input wr desc.
      .wr_req_id_i	(d2a_wr_req_ctrl_i.id),
      .wr_req_addr_i	(d2a_wr_req_ctrl_i.addr),
      .wr_req_data_i	(d2a_wr_req_data_i),
      .wr_req_strb_i	(d2a_wr_req_ctrl_i.strb),
      .wr_req_valid_i	(d2a_wr_req_ctrl_i.valid),
      .wr_req_ready_o	(d2a_wr_req_ctrl_i.ready),
      // Output wr desc.
      .wr_rsp_id_o	(d2a_wr_rsp_ctrl_o.id),
      .wr_rsp_bresp_o	(d2a_wr_rsp_ctrl_o.bresp),
      .wr_rsp_valid_o	(d2a_wr_rsp_ctrl_o.valid),
      .wr_rsp_ready_i	(d2a_wr_rsp_ctrl_o.ready),
      // AXI Wr primary signals.
      .p_axi_awid	(d2a_p_axi_wr.awid),
      .p_axi_awaddr	(d2a_p_axi_wr.awaddr),
      .p_axi_awlen	(d2a_p_axi_wr.awlen),
      .p_axi_awsize	(d2a_p_axi_wr.awsize),
      .p_axi_awburst	(d2a_p_axi_wr.awburst),
      .p_axi_awlock	(d2a_p_axi_wr.awlock),
      .p_axi_awcache	(d2a_p_axi_wr.awcache),
      .p_axi_awprot	(d2a_p_axi_wr.awprot),
      .p_axi_awvalid	(d2a_p_axi_wr.awvalid),
      .p_axi_awready	(d2a_p_axi_wr.awready),
      .p_axi_wdata	(d2a_p_axi_wr.wdata),
      .p_axi_wstrb	(d2a_p_axi_wr.wstrb),
      .p_axi_wlast	(d2a_p_axi_wr.wlast),
      .p_axi_wvalid	(d2a_p_axi_wr.wvalid),
      .p_axi_wready	(d2a_p_axi_wr.wready),
      .p_axi_bid	(d2a_p_axi_wr.bid),
      .p_axi_bresp	(d2a_p_axi_wr.bresp),
      .p_axi_bvalid	(d2a_p_axi_wr.bvalid),
      .p_axi_bready	(d2a_p_axi_wr.bready)
      );

   
endmodule //dcs_2_axi
`endif
 
