------------------------------------------------------------------------------
-- Copyright (c) 2024 ETH Zurich.
-- All rights reserved.
--
-- This file is distributed under the terms in the attached LICENSE file.
-- If you do not find this file, copies can be found by writing to:
-- ETH Zurich D-INFK, Stampfenbachstrasse 114, CH-8092 Zurich. Attn: Systems Group
-------------------------------------------------------------------------------

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

library UNISIM;
use UNISIM.vcomponents.all;

library xpm;
use xpm.vcomponents.all;

use work.eci_defs.all;

entity lauberhorn_eci is
port (
-- 322.265625 MHz
    clk_sys                 : in std_logic;
-- programmed to 100MHz
    prgc0_clk_p             : in std_logic;
    prgc0_clk_n             : in std_logic;
-- programmed to 300MHz
    prgc1_clk_p             : in std_logic;
    prgc1_clk_n             : in std_logic;
-- power on reset
    reset_sys               : in std_logic;
-- ECI link status
    link1_up                : in std_logic;
    link2_up                : in std_logic;
-- ECI links
    link1_in_data           : in std_logic_vector(447 downto 0);
    link1_in_vc_no          : in std_logic_vector(27 downto 0);
    link1_in_we2            : in std_logic_vector(6 downto 0);
    link1_in_we3            : in std_logic_vector(6 downto 0);
    link1_in_we4            : in std_logic_vector(6 downto 0);
    link1_in_we5            : in std_logic_vector(6 downto 0);
    link1_in_valid          : in std_logic;
    link1_in_credit_return  : out std_logic_vector(12 downto 2);

    link1_out_hi_data       : out std_logic_vector(575 downto 0);
    link1_out_hi_vc_no      : out std_logic_vector(3 downto 0);
    link1_out_hi_size       : out std_logic_vector(2 downto 0);
    link1_out_hi_valid      : out std_logic;
    link1_out_hi_ready      : in std_logic;

    link1_out_lo_data       : out std_logic_vector(63 downto 0);
    link1_out_lo_vc_no      : out std_logic_vector(3 downto 0);
    link1_out_lo_valid      : out std_logic;
    link1_out_lo_ready      : in std_logic;
    link1_out_credit_return : in std_logic_vector(12 downto 2);

    link2_in_data           : in std_logic_vector(447 downto 0);
    link2_in_vc_no          : in std_logic_vector(27 downto 0);
    link2_in_we2            : in std_logic_vector(6 downto 0);
    link2_in_we3            : in std_logic_vector(6 downto 0);
    link2_in_we4            : in std_logic_vector(6 downto 0);
    link2_in_we5            : in std_logic_vector(6 downto 0);
    link2_in_valid          : in std_logic;
    link2_in_credit_return  : out std_logic_vector(12 downto 2);

    link2_out_hi_data       : out std_logic_vector(575 downto 0);
    link2_out_hi_vc_no      : out std_logic_vector(3 downto 0);
    link2_out_hi_size       : out std_logic_vector(2 downto 0);
    link2_out_hi_valid      : out std_logic;
    link2_out_hi_ready      : in std_logic;

    link2_out_lo_data       : out std_logic_vector(63 downto 0);
    link2_out_lo_vc_no      : out std_logic_vector(3 downto 0);
    link2_out_lo_valid      : out std_logic;
    link2_out_lo_ready      : in std_logic;
    link2_out_credit_return : in std_logic_vector(12 downto 2);

    disable_2nd_link        : out std_logic;
-- AXI Lite FPGA -> CPU
    m_io_axil_awaddr        : out std_logic_vector(43 downto 0);
    m_io_axil_awvalid       : out std_logic;
    m_io_axil_awready       : in std_logic;
    m_io_axil_wdata         : out std_logic_vector(63 downto 0);
    m_io_axil_wstrb         : out std_logic_vector(7 downto 0);
    m_io_axil_wvalid        : out std_logic;
    m_io_axil_wready        : in std_logic;
    m_io_axil_bresp         : in std_logic_vector(1 downto 0);
    m_io_axil_bvalid        : in std_logic;
    m_io_axil_bready        : out std_logic;
    m_io_axil_araddr        : out std_logic_vector(43 downto 0);
    m_io_axil_arvalid       : out std_logic;
    m_io_axil_arready       : in  std_logic;
    m_io_axil_rdata         : in std_logic_vector(63 downto 0);
    m_io_axil_rresp         : in std_logic_vector(1 downto 0);
    m_io_axil_rvalid        : in std_logic;
    m_io_axil_rready        : out std_logic;
-- AXI Lite CPU -> FPGA
    s_io_axil_awaddr        : in std_logic_vector(43 downto 0);
    s_io_axil_awvalid       : in std_logic;
    s_io_axil_awready       : out std_logic;
    s_io_axil_wdata         : in std_logic_vector(63 downto 0);
    s_io_axil_wstrb         : in std_logic_vector(7 downto 0);
    s_io_axil_wvalid        : in std_logic;
    s_io_axil_wready        : out std_logic;
    s_io_axil_bresp         : out std_logic_vector(1 downto 0);
    s_io_axil_bvalid        : out std_logic;
    s_io_axil_bready        : in std_logic;
    s_io_axil_araddr        : in std_logic_vector(43 downto 0);
    s_io_axil_arvalid       : in std_logic;
    s_io_axil_arready       : out  std_logic;
    s_io_axil_rdata         : out std_logic_vector(63 downto 0);
    s_io_axil_rresp         : out std_logic_vector(1 downto 0);
    s_io_axil_rvalid        : out std_logic;
    s_io_axil_rready        : in std_logic;
-- BSCAN slave port for ILAs, VIOs, MIGs, MDMs etc.
    s_bscan_bscanid_en      : in std_logic;
    s_bscan_capture         : in std_logic;
    s_bscan_drck            : in std_logic;
    s_bscan_reset           : in std_logic;
    s_bscan_runtest         : in std_logic;
    s_bscan_sel             : in std_logic;
    s_bscan_shift           : in std_logic;
    s_bscan_tck             : in std_logic;
    s_bscan_tdi             : in std_logic;
    s_bscan_tdo             : out std_logic;
    s_bscan_tms             : in std_logic;
    s_bscan_update          : in std_logic;
-- Microblaze Debug Module port
    mdm_SYS_Rst             : in std_logic;
    mdm_Clk                 : in std_logic;
    mdm_TDI                 : in std_logic;
    mdm_TDO                 : out std_logic;
    mdm_Reg_En              : in std_logic_vector(0 to 7);
    mdm_Capture             : in std_logic;
    mdm_Shift               : in std_logic;
    mdm_Update              : in std_logic;
    mdm_Rst                 : in std_logic;
    mdm_Disable             : in std_logic;
-- general purpose registers, accessible through the I/O space
    gpo_reg0            : in std_logic_vector(63 downto 0);
    gpo_reg1            : in std_logic_vector(63 downto 0);
    gpo_reg2            : in std_logic_vector(63 downto 0);
    gpo_reg3            : in std_logic_vector(63 downto 0);
    gpo_reg4            : in std_logic_vector(63 downto 0);
    gpo_reg5            : in std_logic_vector(63 downto 0);
    gpo_reg6            : in std_logic_vector(63 downto 0);
    gpo_reg7            : in std_logic_vector(63 downto 0);
    gpo_reg8            : in std_logic_vector(63 downto 0);
    gpo_reg9            : in std_logic_vector(63 downto 0);
    gpo_reg10           : in std_logic_vector(63 downto 0);
    gpo_reg11           : in std_logic_vector(63 downto 0);
    gpo_reg12           : in std_logic_vector(63 downto 0);
    gpo_reg13           : in std_logic_vector(63 downto 0);
    gpo_reg14           : in std_logic_vector(63 downto 0);
    gpo_reg15           : in std_logic_vector(63 downto 0);
    gpi_reg0            : out std_logic_vector(63 downto 0);
    gpi_reg1            : out std_logic_vector(63 downto 0);
    gpi_reg2            : out std_logic_vector(63 downto 0);
    gpi_reg3            : out std_logic_vector(63 downto 0);
    gpi_reg4            : out std_logic_vector(63 downto 0);
    gpi_reg5            : out std_logic_vector(63 downto 0);
    gpi_reg6            : out std_logic_vector(63 downto 0);
    gpi_reg7            : out std_logic_vector(63 downto 0);
    gpi_reg8            : out std_logic_vector(63 downto 0);
    gpi_reg9            : out std_logic_vector(63 downto 0);
    gpi_reg10           : out std_logic_vector(63 downto 0);
    gpi_reg11           : out std_logic_vector(63 downto 0);
    gpi_reg12           : out std_logic_vector(63 downto 0);
    gpi_reg13           : out std_logic_vector(63 downto 0);
    gpi_reg14           : out std_logic_vector(63 downto 0);
    gpi_reg15           : out std_logic_vector(63 downto 0);
-- DDR4
    F_D1_ACT_N : out std_logic;
    F_D1_A : out std_logic_vector ( 17 downto 0 );
    F_D1_BA : out std_logic_vector ( 1 downto 0 );
    F_D1_BG : out std_logic_vector ( 1 downto 0 );
    F_D1_CK_N : out std_logic_vector ( 1 downto 0 );
    F_D1_CK_P : out std_logic_vector ( 1 downto 0 );
    F_D1_CKE : out std_logic_vector ( 1 downto 0 );
    F_D1_CS_N : out std_logic_vector ( 3 downto 0 );
    F_D1_DQ : inout std_logic_vector ( 71 downto 0 );
    F_D1_DQS_N : inout std_logic_vector ( 17 downto 0 );
    F_D1_DQS_P : inout std_logic_vector ( 17 downto 0 );
    F_D1_ODT : out std_logic_vector ( 1 downto 0 );
    F_D1_PARITY_N : out std_logic;
    F_D1_RESET_N : out std_logic;
    F_D1C_CLK_N : in std_logic;
    F_D1C_CLK_P : in std_logic;

    F_D2_ACT_N : out std_logic;
    F_D2_A : out std_logic_vector ( 17 downto 0 );
    F_D2_BA : out std_logic_vector ( 1 downto 0 );
    F_D2_BG : out std_logic_vector ( 1 downto 0 );
    F_D2_CK_N : out std_logic_vector ( 1 downto 0 );
    F_D2_CK_P : out std_logic_vector ( 1 downto 0 );
    F_D2_CKE : out std_logic_vector ( 1 downto 0 );
    F_D2_CS_N : out std_logic_vector ( 3 downto 0 );
    F_D2_DQ : inout std_logic_vector ( 71 downto 0 );
    F_D2_DQS_N : inout std_logic_vector ( 17 downto 0 );
    F_D2_DQS_P : inout std_logic_vector ( 17 downto 0 );
    F_D2_ODT : out std_logic_vector ( 1 downto 0 );
    F_D2_PARITY_N : out std_logic;
    F_D2_RESET_N : out std_logic;
    F_D2C_CLK_N : in std_logic;
    F_D2C_CLK_P : in std_logic;

    F_D3_ACT_N : out std_logic;
    F_D3_A : out std_logic_vector ( 17 downto 0 );
    F_D3_BA : out std_logic_vector ( 1 downto 0 );
    F_D3_BG : out std_logic_vector ( 1 downto 0 );
    F_D3_CK_N : out std_logic_vector ( 1 downto 0 );
    F_D3_CK_P : out std_logic_vector ( 1 downto 0 );
    F_D3_CKE : out std_logic_vector ( 1 downto 0 );
    F_D3_CS_N : out std_logic_vector ( 3 downto 0 );
    F_D3_DQ : inout std_logic_vector ( 71 downto 0 );
    F_D3_DQS_N : inout std_logic_vector ( 17 downto 0 );
    F_D3_DQS_P : inout std_logic_vector ( 17 downto 0 );
    F_D3_ODT : out std_logic_vector ( 1 downto 0 );
    F_D3_PARITY_N : out std_logic;
    F_D3_RESET_N : out std_logic;
    F_D3C_CLK_N : in std_logic;
    F_D3C_CLK_P : in std_logic;

    F_D4_ACT_N : out std_logic;
    F_D4_A : out std_logic_vector ( 17 downto 0 );
    F_D4_BA : out std_logic_vector ( 1 downto 0 );
    F_D4_BG : out std_logic_vector ( 1 downto 0 );
    F_D4_CK_N : out std_logic_vector ( 1 downto 0 );
    F_D4_CK_P : out std_logic_vector ( 1 downto 0 );
    F_D4_CKE : out std_logic_vector ( 1 downto 0 );
    F_D4_CS_N : out std_logic_vector ( 3 downto 0 );
    F_D4_DQ : inout std_logic_vector ( 71 downto 0 );
    F_D4_DQS_N : inout std_logic_vector ( 17 downto 0 );
    F_D4_DQS_P : inout std_logic_vector ( 17 downto 0 );
    F_D4_ODT : out std_logic_vector ( 1 downto 0 );
    F_D4_PARITY_N : out std_logic;
    F_D4_RESET_N : out std_logic;
    F_D4C_CLK_N : in std_logic;
    F_D4C_CLK_P : in std_logic;
-- CMAC
    F_MAC0C_CLK_P   : in std_logic;
    F_MAC0C_CLK_N   : in std_logic;
    F_MAC0_TX_P : out std_logic_vector(3 downto 0);
    F_MAC0_TX_N : out std_logic_vector(3 downto 0);
    F_MAC0_RX_P : in std_logic_vector(3 downto 0);
    F_MAC0_RX_N : in std_logic_vector(3 downto 0);

    F_MAC1C_CLK_P   : in std_logic;
    F_MAC1C_CLK_N   : in std_logic;
    F_MAC1_TX_P : out std_logic_vector(3 downto 0);
    F_MAC1_TX_N : out std_logic_vector(3 downto 0);
    F_MAC1_RX_P : in std_logic_vector(3 downto 0);
    F_MAC1_RX_N : in std_logic_vector(3 downto 0);

    F_MAC2C_CLK_P   : in std_logic;
    F_MAC2C_CLK_N   : in std_logic;
    F_MAC2_TX_P : out std_logic_vector(3 downto 0);
    F_MAC2_TX_N : out std_logic_vector(3 downto 0);
    F_MAC2_RX_P : in std_logic_vector(3 downto 0);
    F_MAC2_RX_N : in std_logic_vector(3 downto 0);

    F_MAC3C_CLK_P   : in std_logic;
    F_MAC3C_CLK_N   : in std_logic;
    F_MAC3_TX_P : out std_logic_vector(3 downto 0);
    F_MAC3_TX_N : out std_logic_vector(3 downto 0);
    F_MAC3_RX_P : in std_logic_vector(3 downto 0);
    F_MAC3_RX_N : in std_logic_vector(3 downto 0);
-- PCIE x16
    F_PCIE16C_CLK_P   : in std_logic;
    F_PCIE16C_CLK_N   : in std_logic;
    F_PCIE16_TX_P : out std_logic_vector(15 downto 0);
    F_PCIE16_TX_N : out std_logic_vector(15 downto 0);
    F_PCIE16_RX_P : in std_logic_vector(15 downto 0);
    F_PCIE16_RX_N : in std_logic_vector(15 downto 0);
-- NVMe
    F_NVMEC_CLK_P   : in std_logic;
    F_NVMEC_CLK_N   : in std_logic;
    F_NVME_TX_P : out std_logic_vector(3 downto 0);
    F_NVME_TX_N : out std_logic_vector(3 downto 0);
    F_NVME_RX_P : in std_logic_vector(3 downto 0);
    F_NVME_RX_N : in std_logic_vector(3 downto 0);
-- C2C
    B_C2CC_CLK_P    : in std_logic;
    B_C2CC_CLK_N    : in std_logic;
    B_C2C_TX_P      : in std_logic_vector(0 downto 0);
    B_C2C_TX_N      : in std_logic_vector(0 downto 0);
    B_C2C_RX_P      : out std_logic_vector(0 downto 0);
    B_C2C_RX_N      : out std_logic_vector(0 downto 0);
    B_C2C_NMI       : in std_logic;
-- I2C
    F_I2C0_SDA      : inout std_logic;
    F_I2C0_SCL      : inout std_logic;

    F_I2C1_SDA      : inout std_logic;
    F_I2C1_SCL      : inout std_logic;

    F_I2C2_SDA      : inout std_logic;
    F_I2C2_SCL      : inout std_logic;

    F_I2C3_SDA      : inout std_logic;
    F_I2C3_SCL      : inout std_logic;

    F_I2C4_SDA      : inout std_logic;
    F_I2C4_SCL      : inout std_logic;

    F_I2C5_SDA      : inout std_logic;
    F_I2C5_SCL      : inout std_logic;
    F_I2C5_RESET_N  : out std_logic;
    F_I2C5_INT_N    : in std_logic;
-- FUART
    B_FUART_TXD     : in std_logic;
    B_FUART_RXD     : out std_logic;
    B_FUART_RTS     : in std_logic;
    B_FUART_CTS     : out std_logic;
-- IRQ
    F_IRQ_IRQ0      : out std_logic;
    F_IRQ_IRQ1      : out std_logic;
    F_IRQ_IRQ2      : out std_logic;
    F_IRQ_IRQ3      : out std_logic
);
end lauberhorn_eci;

architecture Behavioral of lauberhorn_eci is

-- Must use component declarations since direct entity instantiation
-- does not work with the unpacked arrays
type trace_dcs_event_cli_array is array (integer range <>) of std_logic_vector(39 downto 0);
type trace_dcs_event_state_array is array (integer range <>) of std_logic_vector(6 downto 0);
type trace_dcs_event_action_array is array (integer range <>) of std_logic_vector(3 downto 0);
type trace_dcs_event_request_array is array (integer range <>) of std_logic_vector(4 downto 0);
subtype trace_payload_t is std_logic_vector(74 downto 0);
type trace_payload_array is array (integer range <>) of trace_payload_t;

component dcs_cdc is
generic (
  TRACE_ECI_STALL_COUNTER_SHIFT : integer := 16
);
port (
  eci_clk, eci_reset, app_clk : in std_logic;

  -- Input ECI events.
  -- ECI packet for request without data. (VC 6 or 7) (only header).
  req_wod_hdr_i       : in std_logic_vector(63 downto 0);
  req_wod_pkt_size_i  : in std_logic_vector( 4 downto 0);
  req_wod_pkt_vc_i    : in std_logic_vector( 3 downto 0);
  req_wod_pkt_valid_i : in std_logic;
  req_wod_pkt_ready_o : out std_logic;

  -- ECI packet for response without data.(VC 10 or 11). (only header).
  rsp_wod_hdr_i       : in std_logic_vector(63 downto 0);
  rsp_wod_pkt_size_i  : in std_logic_vector( 4 downto 0);
  rsp_wod_pkt_vc_i    : in std_logic_vector( 3 downto 0);
  rsp_wod_pkt_valid_i : in std_logic;
  rsp_wod_pkt_ready_o : out std_logic;

  -- ECI packet for response with data. (VC 4 or 5). (header + data).
  rsp_wd_pkt_i        : in std_logic_vector(17*64-1 downto 0);
  rsp_wd_pkt_size_i   : in std_logic_vector( 4 downto 0);
  rsp_wd_pkt_vc_i     : in std_logic_vector( 3 downto 0);
  rsp_wd_pkt_valid_i  : in std_logic;
  rsp_wd_pkt_ready_o  : out std_logic;

  -- ECI packet for local forward without data. (VC 16 or 17).
  -- lcl clean, lcl clean inv requests.
  lcl_fwd_wod_hdr_i       : in std_logic_vector(63 downto 0);
  lcl_fwd_wod_pkt_size_i  : in std_logic_vector( 4 downto 0);
  lcl_fwd_wod_pkt_vc_i    : in std_logic_vector( 4 downto 0); --5 bits not 4.
  lcl_fwd_wod_pkt_valid_i : in std_logic;
  lcl_fwd_wod_pkt_ready_o : out std_logic;

  -- ECI packet for local rsp without data. (VC 18 or 19).
  -- lcl unlock response message.
  lcl_rsp_wod_hdr_i       : in std_logic_vector(63 downto 0);
  lcl_rsp_wod_pkt_size_i  : in std_logic_vector( 4 downto 0);
  lcl_rsp_wod_pkt_vc_i    : in std_logic_vector( 4 downto 0); --5 bits not 4.
  lcl_rsp_wod_pkt_valid_i : in std_logic;
  lcl_rsp_wod_pkt_ready_o : out std_logic;

  -- Output ECI events. (rsp without data, rsp with data).
  -- VC 10,11
  rsp_wod_hdr_o       : out std_logic_vector(63 downto 0);
  rsp_wod_pkt_size_o  : out std_logic_vector( 4 downto 0);
  rsp_wod_pkt_vc_o    : out std_logic_vector( 3 downto 0);
  rsp_wod_pkt_valid_o : out std_logic;
  rsp_wod_pkt_ready_i : in std_logic;

  -- Responses with data (VC 5 or 4)
  -- header+payload
  rsp_wd_pkt_o       : out std_logic_vector(17*64-1 downto 0);
  rsp_wd_pkt_size_o  : out std_logic_vector( 4 downto 0);
  rsp_wd_pkt_vc_o    : out std_logic_vector( 3 downto 0);
  rsp_wd_pkt_valid_o : out std_logic;
  rsp_wd_pkt_ready_i : in std_logic;

  -- forwards without data (VC 8 or 9).
  fwd_wod_hdr_o       : out std_logic_vector(63 downto 0);
  fwd_wod_pkt_size_o  : out std_logic_vector( 4 downto 0);
  fwd_wod_pkt_vc_o    : out std_logic_vector( 3 downto 0);
  fwd_wod_pkt_valid_o : out std_logic;
  fwd_wod_pkt_ready_i : in std_logic;

  -- lcl responses without data (VC 18 or 19)
  lcl_rsp_wod_hdr_o       : out std_logic_vector(63 downto 0);
  lcl_rsp_wod_pkt_size_o  : out std_logic_vector( 4 downto 0);
  lcl_rsp_wod_pkt_vc_o    : out std_logic_vector( 4 downto 0); --5 bits not 4.
  lcl_rsp_wod_pkt_valid_o : out std_logic;
  lcl_rsp_wod_pkt_ready_i : in std_logic;

  -- Primary AXI rd/wr i/f.
  m_axi_arid    : out std_logic_vector( 6 downto 0);
  m_axi_araddr  : out std_logic_vector(37 downto 0);
  m_axi_arlen   : out std_logic_vector( 7 downto 0);
  m_axi_arsize  : out std_logic_vector( 2 downto 0);
  m_axi_arburst : out std_logic_vector( 1 downto 0);
  m_axi_arlock  : out std_logic;
  m_axi_arcache : out std_logic_vector( 3 downto 0);
  m_axi_arprot  : out std_logic_vector( 2 downto 0);
  m_axi_arvalid : out std_logic;
  m_axi_arready : in std_logic;
  m_axi_rid     : in std_logic_vector( 6 downto 0);
  m_axi_rdata   : in std_logic_vector(511 downto 0);
  m_axi_rresp   : in std_logic_vector( 1 downto 0);
  m_axi_rlast   : in std_logic;
  m_axi_rvalid  : in std_logic;
  m_axi_rready  : out std_logic;

  m_axi_awid    : out std_logic_vector ( 6 downto 0);
  m_axi_awaddr  : out std_logic_vector (37 downto 0);
  m_axi_awlen   : out std_logic_vector ( 7 downto 0);
  m_axi_awsize  : out std_logic_vector ( 2 downto 0);
  m_axi_awburst : out std_logic_vector ( 1 downto 0);
  m_axi_awlock  : out std_logic;
  m_axi_awcache : out std_logic_vector ( 3 downto 0);
  m_axi_awprot  : out std_logic_vector ( 2 downto 0);
  m_axi_awvalid : out std_logic;
  m_axi_awready : in std_logic ;
  m_axi_wdata   : out std_logic_vector (511 downto 0);
  m_axi_wstrb   : out std_logic_vector (63 downto 0);
  m_axi_wlast   : out std_logic;
  m_axi_wvalid  : out std_logic;
  m_axi_wready  : in std_logic;
  m_axi_bid     : in std_logic_vector( 6 downto 0);
  m_axi_bresp   : in std_logic_vector( 1 downto 0);
  m_axi_bvalid  : in std_logic;
  m_axi_bready  : out std_logic;

  -- Tracing
  trace_dcs_event_valid   : out std_logic_vector(1 downto 0);
  trace_dcs_event_error   : out std_logic_vector(1 downto 0);
  trace_dcs_event_cli     : out trace_dcs_event_cli_array(1 downto 0);
  trace_dcs_event_state   : out trace_dcs_event_state_array(1 downto 0);
  trace_dcs_event_action  : out trace_dcs_event_action_array(1 downto 0);
  trace_dcs_event_request : out trace_dcs_event_request_array(1 downto 0);

  trace_eci_stall_threshold : in std_logic_vector(5 downto 0);
  trace_eci_app_valid   : out std_logic_vector(5 downto 0);
  trace_eci_app_payload : out trace_payload_array(5 downto 0);
  trace_eci_sys_valid   : out std_logic_vector(5 downto 0);
  trace_eci_sys_payload : out trace_payload_array(5 downto 0)
);
end component;

type ECI_PACKET_RX is record
    c6_gsync            : ECI_CHANNEL;
    c6_gsync_ready      : std_logic;
    c7_gsync            : ECI_CHANNEL;
    c7_gsync_ready      : std_logic;
    ginv                : ECI_CHANNEL;
    -- RX req_wod VC 6,7
    dcs_c6              : ECI_CHANNEL;
    dcs_c6_ready        : std_logic;
    dcs_c7              : ECI_CHANNEL;
    dcs_c7_ready        : std_logic;
    -- RX rsp_wod VC 10,11
    dcs_c10             : ECI_CHANNEL;
    dcs_c10_ready       : std_logic;
    dcs_c11             : ECI_CHANNEL;
    dcs_c11_ready       : std_logic;
    -- RX rsp_wd VC 4,5
    dcs_c4              : ECI_CHANNEL;
    dcs_c4_ready        : std_logic;
    dcs_c5              : ECI_CHANNEL;
    dcs_c5_ready        : std_logic;
    -- RX rsp_wd VC 4,5 ECI packet.
    dcs_c4_wd_pkt       : WORDS(16 downto 0);
    dcs_c4_wd_pkt_size  : std_logic_vector(4 downto 0);
    dcs_c4_wd_pkt_vc    : std_logic_vector(3 downto 0);
    dcs_c4_wd_pkt_valid : std_logic;
    dcs_c4_wd_pkt_ready : std_logic;
    dcs_c5_wd_pkt       : WORDS(16 downto 0);
    dcs_c5_wd_pkt_size  : std_logic_vector(4 downto 0);
    dcs_c5_wd_pkt_vc    : std_logic_vector(3 downto 0);
    dcs_c5_wd_pkt_valid : std_logic;
    dcs_c5_wd_pkt_ready : std_logic;
    -- RX VC 12
    ipi_c12             : ECI_CHANNEL;
    ipi_c12_ready       : std_logic;
end record ECI_PACKET_RX;

type ECI_PACKET_TX is record
    c10_gsync           : ECI_CHANNEL;
    c10_gsync_ready     : std_logic;
    c11_gsync           : ECI_CHANNEL;
    c11_gsync_ready     : std_logic;
    -- TX rsp_wod VC 10,11
    dcs_c10             : ECI_CHANNEL;
    dcs_c10_ready       : std_logic;
    dcs_c11             : ECI_CHANNEL;
    dcs_c11_ready       : std_logic;
    -- TX rsp_wd VC 4,5
    dcs_c4              : ECI_CHANNEL;
    dcs_c4_ready        : std_logic;
    dcs_c5              : ECI_CHANNEL;
    dcs_c5_ready        : std_logic;
    -- TX rsp_wd VC 4,5 ECI packet.
    dcs_c4_wd_pkt       : std_logic_vector(17*64-1 downto 0);
    dcs_c4_wd_pkt_size  : std_logic_vector(4 downto 0);
    dcs_c4_wd_pkt_vc    : std_logic_vector(3 downto 0);
    dcs_c4_wd_pkt_valid : std_logic;
    dcs_c4_wd_pkt_ready : std_logic;
    dcs_c5_wd_pkt       : std_logic_vector(17*64-1 downto 0);
    dcs_c5_wd_pkt_size  : std_logic_vector(4 downto 0);
    dcs_c5_wd_pkt_vc    : std_logic_vector(3 downto 0);
    dcs_c5_wd_pkt_valid : std_logic;
    dcs_c5_wd_pkt_ready : std_logic;
    -- TX fwd_wod VC 8.9
    dcs_c8              : ECI_CHANNEL;
    dcs_c8_ready        : std_logic;
    dcs_c9              : ECI_CHANNEL;
    dcs_c9_ready        : std_logic;
    -- TX VC 12
    ipi_c12             : ECI_CHANNEL;
    ipi_c12_ready       : std_logic;
end record ECI_PACKET_TX;

type DCS_AXI is record
    arid    : std_logic_vector( 6 downto 0);
    araddr  : std_logic_vector(37 downto 0);
    arlen   : std_logic_vector( 7 downto 0);
    arsize  : std_logic_vector( 2 downto 0);
    arburst : std_logic_vector( 1 downto 0);
    arlock  : std_logic;
    arcache : std_logic_vector( 3 downto 0);
    arprot  : std_logic_vector( 2 downto 0);
    arvalid : std_logic;
    arready : std_logic;
    rid     : std_logic_vector( 6 downto 0);
    rdata   : std_logic_vector(511 downto 0);
    rresp   : std_logic_vector( 1 downto 0);
    rlast   : std_logic;
    rvalid  : std_logic;
    rready  : std_logic;

    awid    : std_logic_vector ( 6 downto 0);
    awaddr  : std_logic_vector (37 downto 0);
    awlen   : std_logic_vector ( 7 downto 0);
    awsize  : std_logic_vector ( 2 downto 0);
    awburst : std_logic_vector ( 1 downto 0);
    awlock  : std_logic;
    awcache : std_logic_vector ( 3 downto 0);
    awprot  : std_logic_vector ( 2 downto 0);
    awvalid : std_logic;
    awready : std_logic ;
    wdata   : std_logic_vector (511 downto 0);
    wstrb   : std_logic_vector (63 downto 0);
    wlast   : std_logic;
    wvalid  : std_logic;
    wready  : std_logic;
    bid     : std_logic_vector( 6 downto 0);
    bresp   : std_logic_vector( 1 downto 0);
    bvalid  : std_logic;
    bready  : std_logic;
end record DCS_AXI;

type TRACE_AXI is record
    arid    : std_logic_vector( 6 downto 0);
    araddr  : std_logic_vector(34 downto 0);
    arlen   : std_logic_vector( 7 downto 0);
    arsize  : std_logic_vector( 2 downto 0);
    arburst : std_logic_vector( 1 downto 0);
    arlock  : std_logic_vector( 0 downto 0);
    arcache : std_logic_vector( 3 downto 0);
    arprot  : std_logic_vector( 2 downto 0);
    arvalid : std_logic;
    arready : std_logic;
    rid     : std_logic_vector( 6 downto 0);
    rdata   : std_logic_vector(511 downto 0);
    rresp   : std_logic_vector( 1 downto 0);
    rlast   : std_logic;
    rvalid  : std_logic;
    rready  : std_logic;

    awid    : std_logic_vector ( 6 downto 0);
    awaddr  : std_logic_vector (34 downto 0);
    awlen   : std_logic_vector ( 7 downto 0);
    awsize  : std_logic_vector ( 2 downto 0);
    awburst : std_logic_vector ( 1 downto 0);
    awlock  : std_logic_vector ( 0 downto 0);
    awcache : std_logic_vector ( 3 downto 0);
    awprot  : std_logic_vector ( 2 downto 0);
    awvalid : std_logic;
    awready : std_logic ;
    wdata   : std_logic_vector (511 downto 0);
    wstrb   : std_logic_vector (63 downto 0);
    wlast   : std_logic;
    wvalid  : std_logic;
    wready  : std_logic;
    bid     : std_logic_vector( 6 downto 0);
    bresp   : std_logic_vector( 1 downto 0);
    bvalid  : std_logic;
    bready  : std_logic;
end record TRACE_AXI;

type BSCAN is record
    bscanid_en     : std_logic;
    capture        : std_logic;
    drck           : std_logic;
    reset          : std_logic;
    runtest        : std_logic;
    sel            : std_logic;
    shift          : std_logic;
    tck            : std_logic;
    tdi            : std_logic;
    tdo            : std_logic;
    tms            : std_logic;
    update         : std_logic;
end record BSCAN;

type LCL_CHANNEL is record
  data  : std_logic_vector(63 downto 0);
  size  : std_logic_vector(4 downto 0);
  vc_no : std_logic_vector(4 downto 0);
  valid : std_logic;
  ready : std_logic;
end record LCL_CHANNEL;

type CMAC_AXIS is record
    tvalid : std_logic;
    tready : std_logic;
    tdata : std_logic_vector(511 downto 0);
    tkeep : std_logic_vector(63 downto 0);
    tlast : std_logic;
end record CMAC_AXIS;

type REGS_AXIL is record
    araddr  : std_logic_vector(43 downto 0);
    arprot  : std_logic_vector( 2 downto 0);
    arvalid : std_logic;
    arready : std_logic;
    rdata   : std_logic_vector(63 downto 0);
    rresp   : std_logic_vector( 1 downto 0);
    rvalid  : std_logic;
    rready  : std_logic;

    awaddr  : std_logic_vector (43 downto 0);
    awprot  : std_logic_vector ( 2 downto 0);
    awvalid : std_logic;
    awready : std_logic ;
    wdata   : std_logic_vector (63 downto 0);
    wstrb   : std_logic_vector (7 downto 0);
    wvalid  : std_logic;
    wready  : std_logic;
    bresp   : std_logic_vector( 1 downto 0);
    bvalid  : std_logic;
    bready  : std_logic;
end record REGS_AXIL;

type REGS_AXIL_NARROW is record
    araddr  : std_logic_vector(31 downto 0);
    arprot  : std_logic_vector( 2 downto 0);
    arvalid : std_logic;
    arready : std_logic;
    rdata   : std_logic_vector(31 downto 0);
    rresp   : std_logic_vector( 1 downto 0);
    rvalid  : std_logic;
    rready  : std_logic;

    awaddr  : std_logic_vector (31 downto 0);
    awprot  : std_logic_vector ( 2 downto 0);
    awvalid : std_logic;
    awready : std_logic ;
    wdata   : std_logic_vector (31 downto 0);
    wstrb   : std_logic_vector (3 downto 0);
    wvalid  : std_logic;
    wready  : std_logic;
    bresp   : std_logic_vector( 1 downto 0);
    bvalid  : std_logic;
    bready  : std_logic;
end record REGS_AXIL_NARROW;

constant TRACE_ZERO_18 : std_logic_vector(17 downto 0) := (others => '0');

function pack_dcs_trace(
    error     : std_logic;
    cli       : std_logic_vector(39 downto 0);
    state     : std_logic_vector(6 downto 0);
    action    : std_logic_vector(3 downto 0);
    request   : std_logic_vector(4 downto 0)
) return trace_payload_t is
begin
    return TRACE_ZERO_18 & request & action & state & cli & error;
end function;

signal cmac_rx_axis, cmac_tx_axis : CMAC_AXIS;
signal io_reg_axil_cdc : REGS_AXIL;

-- Interrupt Controller Command from NicEngine (in app_clk)
signal intc_cmd : std_logic_vector(31 downto 0);
signal intc_cmd_ready, intc_cmd_valid : std_logic;

-- CDC-ed into clk_sys
signal intc_cmd_cdc : std_logic_vector(31 downto 0);
signal intc_cmd_cdc_ready, intc_cmd_cdc_valid : std_logic;

signal m0_bscan : BSCAN;

signal link_eci_packet_rx : ECI_PACKET_RX;
signal link_eci_packet_tx : ECI_PACKET_TX;

signal clk, clk_io : std_logic;
signal reset : std_logic;
signal reset_n : std_logic;

signal txclk, rxclk : std_logic;
signal txclk_reset, rxclk_reset : std_logic;

signal app_clk, app_clk_reset : std_logic;

signal dcs_even_axi, dcs_odd_axi : DCS_AXI;

signal core0_states, core1_states, core2_states, core3_states, core4_states : std_logic_vector(17 downto 0);

-- Trace DMA wires
signal trace_dma_axi : TRACE_AXI;
signal trace_sample_lost, trace_dma_error : std_logic;
signal trace_write_slot : std_logic_vector(27 downto 0);
signal trace_eci_stall_threshold : std_logic_vector(5 downto 0);

signal dcs_even_trace_eci_app_valid : std_logic_vector(5 downto 0);
signal dcs_even_trace_eci_app_payload : trace_payload_array(5 downto 0);
signal dcs_even_trace_eci_sys_valid : std_logic_vector(5 downto 0);
signal dcs_even_trace_eci_sys_payload : trace_payload_array(5 downto 0);

signal dcs_odd_trace_eci_app_valid : std_logic_vector(5 downto 0);
signal dcs_odd_trace_eci_app_payload : trace_payload_array(5 downto 0);
signal dcs_odd_trace_eci_sys_valid : std_logic_vector(5 downto 0);
signal dcs_odd_trace_eci_sys_payload : trace_payload_array(5 downto 0);

signal dcs_odd_trace_dcs_event_valid   : std_logic_vector(1 downto 0);
signal dcs_odd_trace_dcs_event_error   : std_logic_vector(1 downto 0);
signal dcs_odd_trace_dcs_event_cli     : trace_dcs_event_cli_array(1 downto 0);
signal dcs_odd_trace_dcs_event_state   : trace_dcs_event_state_array(1 downto 0);
signal dcs_odd_trace_dcs_event_action  : trace_dcs_event_action_array(1 downto 0);
signal dcs_odd_trace_dcs_event_request : trace_dcs_event_request_array(1 downto 0);

signal dcs_even_trace_dcs_event_valid   : std_logic_vector(1 downto 0);
signal dcs_even_trace_dcs_event_error   : std_logic_vector(1 downto 0);
signal dcs_even_trace_dcs_event_cli     : trace_dcs_event_cli_array(1 downto 0);
signal dcs_even_trace_dcs_event_state   : trace_dcs_event_state_array(1 downto 0);
signal dcs_even_trace_dcs_event_action  : trace_dcs_event_action_array(1 downto 0);
signal dcs_even_trace_dcs_event_request : trace_dcs_event_request_array(1 downto 0);

signal alloc_resp, alloc_free : std_logic_vector(41 downto 0);
signal alloc_req : std_logic_vector(17 downto 0);

signal dma_rxFsm_state : std_logic_vector(2 downto 0);
signal dma_write_desc : std_logic_vector(34 downto 0);
signal dma_write_desc_status : std_logic_vector(16 downto 0);

-- LCL channel signals between DC and the NIC engine
signal dcs_c16_i               : LCL_CHANNEL; -- LCL FWD WOD
signal dcs_c17_i               : LCL_CHANNEL; -- LCL FWD WOD
signal dcs_c18_o               : LCL_CHANNEL; -- LCL RSP WOD
signal dcs_c19_o               : LCL_CHANNEL; -- LCL RSP WOD
signal dcs_c18_i               : LCL_CHANNEL; -- LCL RSP WOD
signal dcs_c19_i               : LCL_CHANNEL; -- LCL RSP WOD

begin

clk <= clk_sys;

i_eci_gateway : entity work.eci_gateway
  generic map (
    TX_NO_CHANNELS      => 9,
    RX_NO_CHANNELS      => 10,
    RX_FILTER_VC        => ("00000100000", "00000010000", "00000110000", "00000100000", "00000010000", "01000000000", "00100000000", "00000001000", "00000000100", "10000000000"),
    RX_FILTER_TYPE_MASK => ("11111", "11111", "11111", "00000", "00000", "00000", "00000", "00000", "00000", "00000"),
    RX_FILTER_TYPE      => ("11000", "11000", "10100", "00000", "00000", "00000", "00000", "00000", "00000", "00000"),
    RX_FILTER_CLI_MASK  => ((others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0')),
    RX_FILTER_CLI       => ((others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'))
)
port map (
    clk_sys                 => clk,
    clk_io_out              => clk_io,

    prgc0_clk_p             => prgc0_clk_p,
    prgc0_clk_n             => prgc0_clk_n,
    prgc1_clk_p             => prgc1_clk_p,
    prgc1_clk_n             => prgc1_clk_n,

    reset_sys               => reset_sys,
    reset_out               => reset,
    reset_n_out             => reset_n,
    link1_up                => link1_up,
    link2_up                => link2_up,

    link1_in_data           => link1_in_data,
    link1_in_vc_no          => link1_in_vc_no,
    link1_in_we2            => link1_in_we2,
    link1_in_we3            => link1_in_we3,
    link1_in_we4            => link1_in_we4,
    link1_in_we5            => link1_in_we5,
    link1_in_valid          => link1_in_valid,
    link1_in_credit_return  => link1_in_credit_return,

    link1_out_hi_data       => link1_out_hi_data,
    link1_out_hi_vc_no      => link1_out_hi_vc_no,
    link1_out_hi_size       => link1_out_hi_size,
    link1_out_hi_valid      => link1_out_hi_valid,
    link1_out_hi_ready      => link1_out_hi_ready,

    link1_out_lo_data       => link1_out_lo_data,
    link1_out_lo_vc_no      => link1_out_lo_vc_no,
    link1_out_lo_valid      => link1_out_lo_valid,
    link1_out_lo_ready      => link1_out_lo_ready,
    link1_out_credit_return => link1_out_credit_return,

    link2_in_data           => link2_in_data,
    link2_in_vc_no          => link2_in_vc_no,
    link2_in_we2            => link2_in_we2,
    link2_in_we3            => link2_in_we3,
    link2_in_we4            => link2_in_we4,
    link2_in_we5            => link2_in_we5,
    link2_in_valid          => link2_in_valid,
    link2_in_credit_return  => link2_in_credit_return,

    link2_out_hi_data       => link2_out_hi_data,
    link2_out_hi_vc_no      => link2_out_hi_vc_no,
    link2_out_hi_size       => link2_out_hi_size,
    link2_out_hi_valid      => link2_out_hi_valid,
    link2_out_hi_ready      => link2_out_hi_ready,

    link2_out_lo_data       => link2_out_lo_data,
    link2_out_lo_vc_no      => link2_out_lo_vc_no,
    link2_out_lo_valid      => link2_out_lo_valid,
    link2_out_lo_ready      => link2_out_lo_ready,
    link2_out_credit_return => link2_out_credit_return,

    s_bscan_bscanid_en      => s_bscan_bscanid_en,
    s_bscan_capture         => s_bscan_capture,
    s_bscan_drck            => s_bscan_drck,
    s_bscan_reset           => s_bscan_reset,
    s_bscan_runtest         => s_bscan_runtest,
    s_bscan_sel             => s_bscan_sel,
    s_bscan_shift           => s_bscan_shift,
    s_bscan_tck             => s_bscan_tck,
    s_bscan_tdi             => s_bscan_tdi,
    s_bscan_tdo             => s_bscan_tdo,
    s_bscan_tms             => s_bscan_tms,
    s_bscan_update          => s_bscan_update,

    m0_bscan_bscanid_en     => m0_bscan.bscanid_en,
    m0_bscan_capture        => m0_bscan.capture,
    m0_bscan_drck           => m0_bscan.drck,
    m0_bscan_reset          => m0_bscan.reset,
    m0_bscan_runtest        => m0_bscan.runtest,
    m0_bscan_sel            => m0_bscan.sel,
    m0_bscan_shift          => m0_bscan.shift,
    m0_bscan_tck            => m0_bscan.tck,
    m0_bscan_tdi            => m0_bscan.tdi,
    m0_bscan_tdo            => m0_bscan.tdo,
    m0_bscan_tms            => m0_bscan.tms,
    m0_bscan_update         => m0_bscan.update,

    rx_eci_channels(0)      => link_eci_packet_rx.c7_gsync,
    rx_eci_channels(1)      => link_eci_packet_rx.c6_gsync,
    rx_eci_channels(2)      => link_eci_packet_rx.ginv,
    rx_eci_channels(3)      => link_eci_packet_rx.dcs_c7,
    rx_eci_channels(4)      => link_eci_packet_rx.dcs_c6,
    rx_eci_channels(5)      => link_eci_packet_rx.dcs_c11,
    rx_eci_channels(6)      => link_eci_packet_rx.dcs_c10,
    rx_eci_channels(7)      => link_eci_packet_rx.dcs_c5,
    rx_eci_channels(8)      => link_eci_packet_rx.dcs_c4,
    rx_eci_channels(9)      => link_eci_packet_rx.ipi_c12,

    rx_eci_channels_ready(0)   => link_eci_packet_rx.c7_gsync_ready,
    rx_eci_channels_ready(1)   => link_eci_packet_rx.c6_gsync_ready,
    rx_eci_channels_ready(2)   => '1',
    rx_eci_channels_ready(3)   => link_eci_packet_rx.dcs_c7_ready,
    rx_eci_channels_ready(4)   => link_eci_packet_rx.dcs_c6_ready,
    rx_eci_channels_ready(5)   => link_eci_packet_rx.dcs_c11_ready,
    rx_eci_channels_ready(6)   => link_eci_packet_rx.dcs_c10_ready,
    rx_eci_channels_ready(7)   => link_eci_packet_rx.dcs_c5_ready,
    rx_eci_channels_ready(8)   => link_eci_packet_rx.dcs_c4_ready,
    rx_eci_channels_ready(9)   => link_eci_packet_rx.ipi_c12_ready,

    tx_eci_channels(0)      => link_eci_packet_tx.c11_gsync,
    tx_eci_channels(1)      => link_eci_packet_tx.c10_gsync,
    tx_eci_channels(2)      => link_eci_packet_tx.dcs_c11,
    tx_eci_channels(3)      => link_eci_packet_tx.dcs_c5,
    tx_eci_channels(4)      => link_eci_packet_tx.dcs_c10,
    tx_eci_channels(5)      => link_eci_packet_tx.dcs_c4,
    tx_eci_channels(6)      => link_eci_packet_tx.dcs_c8,
    tx_eci_channels(7)      => link_eci_packet_tx.dcs_c9,
    tx_eci_channels(8)      => link_eci_packet_tx.ipi_c12,

    tx_eci_channels_ready(0)   => link_eci_packet_tx.c11_gsync_ready,
    tx_eci_channels_ready(1)   => link_eci_packet_tx.c10_gsync_ready,
    tx_eci_channels_ready(2)   => link_eci_packet_tx.dcs_c11_ready,
    tx_eci_channels_ready(3)   => link_eci_packet_tx.dcs_c5_ready,
    tx_eci_channels_ready(4)   => link_eci_packet_tx.dcs_c10_ready,
    tx_eci_channels_ready(5)   => link_eci_packet_tx.dcs_c4_ready,
    tx_eci_channels_ready(6)   => link_eci_packet_tx.dcs_c8_ready,
    tx_eci_channels_ready(7)   => link_eci_packet_tx.dcs_c9_ready,
    tx_eci_channels_ready(8)   => link_eci_packet_tx.ipi_c12_ready
);

-- IPI controller on VC12.
vc12_ipi_ctrl : entity work.eci_interrupt_controller
port map (
    clk                        => clk,
    resetn                     => reset_n,
    eci_req                    => link_eci_packet_rx.ipi_c12,
    eci_req_ready              => link_eci_packet_rx.ipi_c12_ready,
    eci_rsp                    => link_eci_packet_tx.ipi_c12,
    eci_rsp_ready              => link_eci_packet_tx.ipi_c12_ready,

    input_tdata                => intc_cmd_cdc,
    input_tlast                => '1',
    input_tvalid               => intc_cmd_cdc_valid,
    input_tready               => intc_cmd_cdc_ready,
    -- ignore interrupts from the CPU
    output_tdata               => open,
    output_tlast               => open,
    output_tvalid              => open,
    output_tready              => '1'
);

ipi_ctrl_ila : entity work.ila_eci_intc
port map (
    clk                        => clk,
    probe0                     => link_eci_packet_tx.ipi_c12.valid & link_eci_packet_tx.ipi_c12_ready,
    probe1                     => intc_cmd_cdc & intc_cmd_cdc_valid & intc_cmd_cdc_ready
);

-- CDC FIFO for IPI commands
ipi_cmd_cdc : entity work.axis_async_fifo
generic map (
    DEPTH       => 32,
    DATA_WIDTH  => 32,
    KEEP_ENABLE => 0,
    USER_ENABLE => 0,
    LAST_ENABLE => 0
)
port map (
    s_clk           => app_clk,
    s_rst           => app_clk_reset,
    s_axis_tdata    => intc_cmd,
    s_axis_tvalid   => intc_cmd_valid,
    s_axis_tready   => intc_cmd_ready,

    m_clk           => clk,
    m_rst           => reset,
    m_axis_tdata    => intc_cmd_cdc,
    m_axis_tvalid   => intc_cmd_cdc_valid,
    m_axis_tready   => intc_cmd_cdc_ready
);

-- GSYNC response handler, sends GSDN.
-- Odd VCs, GSYNC arrives in VC7 and GSDN sent in VC11.
vc7_vc11_gsync_loopback : entity work.loopback_vc_resp_nodata
generic map (
   WORD_WIDTH => 64,
   GSDN_GSYNC_FN => 1
)
port map (
    clk   => clk,
    reset => reset,

    vc_req_i       => link_eci_packet_rx.c7_gsync.data(0),
    vc_req_valid_i => link_eci_packet_rx.c7_gsync.valid,
    vc_req_ready_o => link_eci_packet_rx.c7_gsync_ready,

    vc_resp_o       => link_eci_packet_tx.c11_gsync.data(0),
    vc_resp_valid_o => link_eci_packet_tx.c11_gsync.valid,
    vc_resp_ready_i => link_eci_packet_tx.c11_gsync_ready
);

link_eci_packet_tx.c11_gsync.vc_no <= "1011";
link_eci_packet_tx.c11_gsync.size <= "000";

-- GSYNC response handler, sends GSDN.
-- Even VCs, GSYNC arrives in VC6 and GSDN sent in VC10.
vc6_vc10_gsync_loopback : entity work.loopback_vc_resp_nodata
generic map (
   WORD_WIDTH => 64,
   GSDN_GSYNC_FN => 1
)
port map (
    clk   => clk,
    reset => reset,

    vc_req_i       => link_eci_packet_rx.c6_gsync.data(0),
    vc_req_valid_i => link_eci_packet_rx.c6_gsync.valid,
    vc_req_ready_o => link_eci_packet_rx.c6_gsync_ready,

    vc_resp_o       => link_eci_packet_tx.c10_gsync.data(0),
    vc_resp_valid_o => link_eci_packet_tx.c10_gsync.valid,
    vc_resp_ready_i => link_eci_packet_tx.c10_gsync_ready
);

link_eci_packet_tx.c10_gsync.vc_no <= "1010";
link_eci_packet_tx.c10_gsync.size <= "000";

-- RX packetizer.
-- Packetize data from eci_gateway into ECI packet.
-- RX rsp_wd VC5.
i_dcs_c5_eci_channel_to_bus : entity work.eci_channel_bus_converter
port map (
    clk             => clk,
    -- Input eci_gateway packet.
    in_channel      => link_eci_packet_rx.dcs_c5,
    in_ready        => link_eci_packet_rx.dcs_c5_ready,
    -- Output ECI packet.
    out_data        => link_eci_packet_rx.dcs_c5_wd_pkt,
    out_vc_no       => link_eci_packet_rx.dcs_c5_wd_pkt_vc,
    out_size        => link_eci_packet_rx.dcs_c5_wd_pkt_size,
    out_valid       => link_eci_packet_rx.dcs_c5_wd_pkt_valid,
    out_ready       => link_eci_packet_rx.dcs_c5_wd_pkt_ready
);

-- TX serializer.
-- Serialize ECI packet into eci_gateway.
-- TX rsp_wd VC5.
i_dcs_c5_bus_to_eci_channel : entity work.eci_bus_channel_converter
port map (
    clk             => clk,
    -- Input ECI packet.
    in_data         => vector_to_words(link_eci_packet_tx.dcs_c5_wd_pkt),
    in_vc_no        => link_eci_packet_tx.dcs_c5_wd_pkt_vc,
    in_size         => link_eci_packet_tx.dcs_c5_wd_pkt_size,
    in_valid        => link_eci_packet_tx.dcs_c5_wd_pkt_valid,
    in_ready        => link_eci_packet_tx.dcs_c5_wd_pkt_ready,
    -- output eci_gateway packet.
    out_channel     => link_eci_packet_tx.dcs_c5,
    out_ready       => link_eci_packet_tx.dcs_c5_ready
);

-- RX packetizer.
-- Packetize data from eci_gateway into ECI packet.
-- RX rsp_wd VC4.
i_dcs_c4_eci_channel_to_bus : entity work.eci_channel_bus_converter
port map (
    clk             => clk,
    -- Input eci_gateway packet.
    in_channel      => link_eci_packet_rx.dcs_c4,
    in_ready        => link_eci_packet_rx.dcs_c4_ready,
    -- output ECI packet.
    out_data        => link_eci_packet_rx.dcs_c4_wd_pkt,
    out_vc_no       => link_eci_packet_rx.dcs_c4_wd_pkt_vc,
    out_size        => link_eci_packet_rx.dcs_c4_wd_pkt_size,
    out_valid       => link_eci_packet_rx.dcs_c4_wd_pkt_valid,
    out_ready       => link_eci_packet_rx.dcs_c4_wd_pkt_ready
);

-- TX serializer.
-- Serialize ECI packet into eci_gateway.
-- TX rsp_wd VC4.
i_dcs_c4_bus_to_eci_channel : entity work.eci_bus_channel_converter
port map (
    clk             => clk,
    -- Input ECI packet.
    in_data         => vector_to_words(link_eci_packet_tx.dcs_c4_wd_pkt),
    in_vc_no        => link_eci_packet_tx.dcs_c4_wd_pkt_vc,
    in_size         => link_eci_packet_tx.dcs_c4_wd_pkt_size,
    in_valid        => link_eci_packet_tx.dcs_c4_wd_pkt_valid,
    in_ready        => link_eci_packet_tx.dcs_c4_wd_pkt_ready,
    -- output eci_gateway packet.
    out_channel     => link_eci_packet_tx.dcs_c4,
    out_ready       => link_eci_packet_tx.dcs_c4_ready
);

-- DC Slices: One DCS for odd and another for even VCs.
-- DCS for even VCs ie odd CL indices.
dcs_even : dcs_cdc
port map (
  eci_reset   => reset,
  eci_clk     => clk,
  app_clk     => app_clk,

  -- Input ECI events.
  -- ECI packet for request without data. (VC 6 or 7) (only header).
  req_wod_hdr_i       => link_eci_packet_rx.dcs_c6.data(0),
  req_wod_pkt_size_i  => "00001",
  req_wod_pkt_vc_i    => link_eci_packet_rx.dcs_c6.vc_no,
  req_wod_pkt_valid_i => link_eci_packet_rx.dcs_c6.valid,
  req_wod_pkt_ready_o => link_eci_packet_rx.dcs_c6_ready,

  -- ECI packet for response without data.(VC 10 or 11). (only header).
  rsp_wod_hdr_i       => link_eci_packet_rx.dcs_c10.data(0),
  rsp_wod_pkt_size_i  => "00001",
  rsp_wod_pkt_vc_i    => link_eci_packet_rx.dcs_c10.vc_no,
  rsp_wod_pkt_valid_i => link_eci_packet_rx.dcs_c10.valid,
  rsp_wod_pkt_ready_o => link_eci_packet_rx.dcs_c10_ready,

  -- ECI packet for response with data. (VC 4 or 5). (header + data).
  rsp_wd_pkt_i        => words_to_vector(link_eci_packet_rx.dcs_c4_wd_pkt),
  rsp_wd_pkt_size_i   => link_eci_packet_rx.dcs_c4_wd_pkt_size,
  rsp_wd_pkt_vc_i     => link_eci_packet_rx.dcs_c4_wd_pkt_vc,
  rsp_wd_pkt_valid_i  => link_eci_packet_rx.dcs_c4_wd_pkt_valid,
  rsp_wd_pkt_ready_o  => link_eci_packet_rx.dcs_c4_wd_pkt_ready,

  -- ECI packet for lcl fwd without data (VC 16 or 17).
  lcl_fwd_wod_hdr_i       => dcs_c16_i.data,
  lcl_fwd_wod_pkt_size_i  => dcs_c16_i.size,
  lcl_fwd_wod_pkt_vc_i    => dcs_c16_i.vc_no, --5 bits not 4.
  lcl_fwd_wod_pkt_valid_i => dcs_c16_i.valid,
  lcl_fwd_wod_pkt_ready_o => dcs_c16_i.ready,

  -- ECI packet for lcl rsp without data (VC 18 or 19).
  lcl_rsp_wod_hdr_i       => dcs_c18_i.data,
  lcl_rsp_wod_pkt_size_i  => dcs_c18_i.size,
  lcl_rsp_wod_pkt_vc_i    => dcs_c18_i.vc_no, --5 bits not 4.
  lcl_rsp_wod_pkt_valid_i => dcs_c18_i.valid,
  lcl_rsp_wod_pkt_ready_o => dcs_c18_i.ready,

  -- Output ECI events. (rsp without data, rsp with data).
  -- VC 10,11
  rsp_wod_hdr_o                  => link_eci_packet_tx.dcs_c10.data(0),
  rsp_wod_pkt_size_o             => open,
  rsp_wod_pkt_vc_o               => link_eci_packet_tx.dcs_c10.vc_no,
  rsp_wod_pkt_valid_o            => link_eci_packet_tx.dcs_c10.valid,
  rsp_wod_pkt_ready_i            => link_eci_packet_tx.dcs_c10_ready,

  -- Responses with data (VC 5 or 4)
  -- header+payload
  rsp_wd_pkt_o                   => link_eci_packet_tx.dcs_c4_wd_pkt,
  rsp_wd_pkt_size_o              => link_eci_packet_tx.dcs_c4_wd_pkt_size,
  rsp_wd_pkt_vc_o                => link_eci_packet_tx.dcs_c4_wd_pkt_vc,
  rsp_wd_pkt_valid_o             => link_eci_packet_tx.dcs_c4_wd_pkt_valid,
  rsp_wd_pkt_ready_i             => link_eci_packet_tx.dcs_c4_wd_pkt_ready,

  -- ECI fwd without data (VC 8 or 9)
  fwd_wod_hdr_o       => link_eci_packet_tx.dcs_c8.data(0),
  fwd_wod_pkt_size_o  => open,
  fwd_wod_pkt_vc_o    => link_eci_packet_tx.dcs_c8.vc_no,
  fwd_wod_pkt_valid_o => link_eci_packet_tx.dcs_c8.valid,
  fwd_wod_pkt_ready_i => link_eci_packet_tx.dcs_c8_ready,

  -- Lcl rsp without data (VC 18 or 19)
  lcl_rsp_wod_hdr_o       => dcs_c18_o.data,
  lcl_rsp_wod_pkt_size_o  => dcs_c18_o.size,
  lcl_rsp_wod_pkt_vc_o    => dcs_c18_o.vc_no, --5 bits not 4.
  lcl_rsp_wod_pkt_valid_o => dcs_c18_o.valid,
  lcl_rsp_wod_pkt_ready_i => dcs_c18_o.ready,

  -- Master AXI rd/wr i/f.
  m_axi_arid    => dcs_even_axi.arid,
  m_axi_araddr  => dcs_even_axi.araddr,
  m_axi_arlen   => dcs_even_axi.arlen,
  m_axi_arsize  => dcs_even_axi.arsize,
  m_axi_arburst => dcs_even_axi.arburst,
  m_axi_arlock  => dcs_even_axi.arlock,
  m_axi_arcache => dcs_even_axi.arcache,
  m_axi_arprot  => dcs_even_axi.arprot,
  m_axi_arvalid => dcs_even_axi.arvalid,
  m_axi_arready => dcs_even_axi.arready,
  m_axi_rid     => dcs_even_axi.rid,
  m_axi_rdata   => dcs_even_axi.rdata,
  m_axi_rresp   => dcs_even_axi.rresp,
  m_axi_rlast   => dcs_even_axi.rlast,
  m_axi_rvalid  => dcs_even_axi.rvalid,
  m_axi_rready  => dcs_even_axi.rready,

  m_axi_awid    => dcs_even_axi.awid,
  m_axi_awaddr  => dcs_even_axi.awaddr,
  m_axi_awlen   => dcs_even_axi.awlen,
  m_axi_awsize  => dcs_even_axi.awsize,
  m_axi_awburst => dcs_even_axi.awburst,
  m_axi_awlock  => dcs_even_axi.awlock,
  m_axi_awcache => dcs_even_axi.awcache,
  m_axi_awprot  => dcs_even_axi.awprot,
  m_axi_awvalid => dcs_even_axi.awvalid,
  m_axi_awready => dcs_even_axi.awready,
  m_axi_wdata   => dcs_even_axi.wdata,
  m_axi_wstrb   => dcs_even_axi.wstrb,
  m_axi_wlast   => dcs_even_axi.wlast,
  m_axi_wvalid  => dcs_even_axi.wvalid,
  m_axi_wready  => dcs_even_axi.wready,
  m_axi_bid     => dcs_even_axi.bid,
  m_axi_bresp   => dcs_even_axi.bresp,
  m_axi_bvalid  => dcs_even_axi.bvalid,
  m_axi_bready  => dcs_even_axi.bready,

  -- Tracing interface
  trace_dcs_event_valid   => dcs_even_trace_dcs_event_valid   ,
  trace_dcs_event_error   => dcs_even_trace_dcs_event_error   ,
  trace_dcs_event_state   => dcs_even_trace_dcs_event_state   ,
  trace_dcs_event_action  => dcs_even_trace_dcs_event_action  ,
  trace_dcs_event_request => dcs_even_trace_dcs_event_request ,
  trace_dcs_event_cli     => dcs_even_trace_dcs_event_cli,

  trace_eci_stall_threshold => trace_eci_stall_threshold,
  trace_eci_app_valid   => dcs_even_trace_eci_app_valid,
  trace_eci_app_payload => dcs_even_trace_eci_app_payload,
  trace_eci_sys_valid   => dcs_even_trace_eci_sys_valid,
  trace_eci_sys_payload => dcs_even_trace_eci_sys_payload
);

-- DCS for odd VCs ie even CL indices.
dcs_odd : dcs_cdc
port map (
  eci_reset   => reset,
  eci_clk     => clk,
  app_clk     => app_clk,

  -- Input ECI events.
  -- ECI packet for request without data. (VC 6 or 7) (only header).
  req_wod_hdr_i       => link_eci_packet_rx.dcs_c7.data(0),
  req_wod_pkt_size_i  => "00001",
  req_wod_pkt_vc_i    => link_eci_packet_rx.dcs_c7.vc_no,
  req_wod_pkt_valid_i => link_eci_packet_rx.dcs_c7.valid,
  req_wod_pkt_ready_o => link_eci_packet_rx.dcs_c7_ready,

  -- ECI packet for response without data.(VC 10 or 11). (only header).
  rsp_wod_hdr_i       => link_eci_packet_rx.dcs_c11.data(0),
  rsp_wod_pkt_size_i  => "00001",
  rsp_wod_pkt_vc_i    => link_eci_packet_rx.dcs_c11.vc_no,
  rsp_wod_pkt_valid_i => link_eci_packet_rx.dcs_c11.valid,
  rsp_wod_pkt_ready_o => link_eci_packet_rx.dcs_c11_ready,

  -- ECI packet for response with data. (VC 4 or 5). (header + data).
  rsp_wd_pkt_i        => words_to_vector(link_eci_packet_rx.dcs_c5_wd_pkt),
  rsp_wd_pkt_size_i   => link_eci_packet_rx.dcs_c5_wd_pkt_size,
  rsp_wd_pkt_vc_i     => link_eci_packet_rx.dcs_c5_wd_pkt_vc,
  rsp_wd_pkt_valid_i  => link_eci_packet_rx.dcs_c5_wd_pkt_valid,
  rsp_wd_pkt_ready_o  => link_eci_packet_rx.dcs_c5_wd_pkt_ready,

  -- ECI packet for lcl fwd without data (VC 16 or 17).
  lcl_fwd_wod_hdr_i       => dcs_c17_i.data,
  lcl_fwd_wod_pkt_size_i  => dcs_c17_i.size,
  lcl_fwd_wod_pkt_vc_i    => dcs_c17_i.vc_no, --5 bits not 4.
  lcl_fwd_wod_pkt_valid_i => dcs_c17_i.valid,
  lcl_fwd_wod_pkt_ready_o => dcs_c17_i.ready,

  -- ECI packet for lcl rsp without data (VC 18 or 19).
  lcl_rsp_wod_hdr_i       => dcs_c19_i.data,
  lcl_rsp_wod_pkt_size_i  => dcs_c19_i.size,
  lcl_rsp_wod_pkt_vc_i    => dcs_c19_i.vc_no,
  lcl_rsp_wod_pkt_valid_i => dcs_c19_i.valid,
  lcl_rsp_wod_pkt_ready_o => dcs_c19_i.ready,

  -- Output ECI events. (rsp without data, rsp with data).
  -- VC 10,11
  rsp_wod_hdr_o                  => link_eci_packet_tx.dcs_c11.data(0),
  rsp_wod_pkt_size_o             => open,
  rsp_wod_pkt_vc_o               => link_eci_packet_tx.dcs_c11.vc_no,
  rsp_wod_pkt_valid_o            => link_eci_packet_tx.dcs_c11.valid,
  rsp_wod_pkt_ready_i            => link_eci_packet_tx.dcs_c11_ready,

  -- Responses with data (VC 5 or 4)
  -- header+payload
  rsp_wd_pkt_o                   => link_eci_packet_tx.dcs_c5_wd_pkt,
  rsp_wd_pkt_size_o              => link_eci_packet_tx.dcs_c5_wd_pkt_size,
  rsp_wd_pkt_vc_o                => link_eci_packet_tx.dcs_c5_wd_pkt_vc,
  rsp_wd_pkt_valid_o             => link_eci_packet_tx.dcs_c5_wd_pkt_valid,
  rsp_wd_pkt_ready_i             => link_eci_packet_tx.dcs_c5_wd_pkt_ready,

  -- ECI fwd without data (VC 8 or 9)
  fwd_wod_hdr_o       => link_eci_packet_tx.dcs_c9.data(0),
  fwd_wod_pkt_size_o  => open,
  fwd_wod_pkt_vc_o    => link_eci_packet_tx.dcs_c9.vc_no,
  fwd_wod_pkt_valid_o => link_eci_packet_tx.dcs_c9.valid,
  fwd_wod_pkt_ready_i => link_eci_packet_tx.dcs_c9_ready,

  -- Lcl rsp without data (VC 18 or 19)
  lcl_rsp_wod_hdr_o       => dcs_c19_o.data,
  lcl_rsp_wod_pkt_size_o  => dcs_c19_o.size,
  lcl_rsp_wod_pkt_vc_o    => dcs_c19_o.vc_no, --5 bits not 4.
  lcl_rsp_wod_pkt_valid_o => dcs_c19_o.valid,
  lcl_rsp_wod_pkt_ready_i => dcs_c19_o.ready,

  -- Master AXI rd/wr i/f.
  m_axi_arid    => dcs_odd_axi.arid,
  m_axi_araddr  => dcs_odd_axi.araddr,
  m_axi_arlen   => dcs_odd_axi.arlen,
  m_axi_arsize  => dcs_odd_axi.arsize,
  m_axi_arburst => dcs_odd_axi.arburst,
  m_axi_arlock  => dcs_odd_axi.arlock,
  m_axi_arcache => dcs_odd_axi.arcache,
  m_axi_arprot  => dcs_odd_axi.arprot,
  m_axi_arvalid => dcs_odd_axi.arvalid,
  m_axi_arready => dcs_odd_axi.arready,
  m_axi_rid     => dcs_odd_axi.rid,
  m_axi_rdata   => dcs_odd_axi.rdata,
  m_axi_rresp   => dcs_odd_axi.rresp,
  m_axi_rlast   => dcs_odd_axi.rlast,
  m_axi_rvalid  => dcs_odd_axi.rvalid,
  m_axi_rready  => dcs_odd_axi.rready,

  m_axi_awid    => dcs_odd_axi.awid,
  m_axi_awaddr  => dcs_odd_axi.awaddr,
  m_axi_awlen   => dcs_odd_axi.awlen,
  m_axi_awsize  => dcs_odd_axi.awsize,
  m_axi_awburst => dcs_odd_axi.awburst,
  m_axi_awlock  => dcs_odd_axi.awlock,
  m_axi_awcache => dcs_odd_axi.awcache,
  m_axi_awprot  => dcs_odd_axi.awprot,
  m_axi_awvalid => dcs_odd_axi.awvalid,
  m_axi_awready => dcs_odd_axi.awready,
  m_axi_wdata   => dcs_odd_axi.wdata,
  m_axi_wstrb   => dcs_odd_axi.wstrb,
  m_axi_wlast   => dcs_odd_axi.wlast,
  m_axi_wvalid  => dcs_odd_axi.wvalid,
  m_axi_wready  => dcs_odd_axi.wready,
  m_axi_bid     => dcs_odd_axi.bid,
  m_axi_bresp   => dcs_odd_axi.bresp,
  m_axi_bvalid  => dcs_odd_axi.bvalid,
  m_axi_bready  => dcs_odd_axi.bready,

  -- Tracing interface
  trace_dcs_event_valid   => dcs_odd_trace_dcs_event_valid   ,
  trace_dcs_event_error   => dcs_odd_trace_dcs_event_error   ,
  trace_dcs_event_state   => dcs_odd_trace_dcs_event_state   ,
  trace_dcs_event_action  => dcs_odd_trace_dcs_event_action  ,
  trace_dcs_event_request => dcs_odd_trace_dcs_event_request ,
  trace_dcs_event_cli     => dcs_odd_trace_dcs_event_cli,

  trace_eci_stall_threshold => trace_eci_stall_threshold,
  trace_eci_app_valid   => dcs_odd_trace_eci_app_valid,
  trace_eci_app_payload => dcs_odd_trace_eci_app_payload,
  trace_eci_sys_valid   => dcs_odd_trace_eci_sys_valid,
  trace_eci_sys_payload => dcs_odd_trace_eci_sys_payload
);

-- reset synchronizers for RX and TX clocks
rx_rst_sync : xpm_cdc_sync_rst
port map (
    dest_clk => rxclk,
    src_rst => reset,
    dest_rst => rxclk_reset
);

tx_rst_sync : xpm_cdc_sync_rst
port map (
    dest_clk => txclk,
    src_rst => reset,
    dest_rst => txclk_reset
);

axil_cdc_inst : entity work.axil_cdc
  generic map (
    DATA_WIDTH => 64,
    ADDR_WIDTH => 44
  )
  port map (
    s_clk => clk,
    s_rst => reset,

    s_axil_awaddr => s_io_axil_awaddr,
    s_axil_awprot => (others => '0'),
    s_axil_awvalid => s_io_axil_awvalid,
    s_axil_awready => s_io_axil_awready,
    s_axil_wdata => s_io_axil_wdata,
    s_axil_wstrb => s_io_axil_wstrb,
    s_axil_wvalid => s_io_axil_wvalid,
    s_axil_wready => s_io_axil_wready,
    s_axil_bresp => s_io_axil_bresp,
    s_axil_bvalid => s_io_axil_bvalid,
    s_axil_bready => s_io_axil_bready,
    s_axil_araddr => s_io_axil_araddr,
    s_axil_arprot => (others => '0'),
    s_axil_arvalid => s_io_axil_arvalid,
    s_axil_arready => s_io_axil_arready,
    s_axil_rdata => s_io_axil_rdata,
    s_axil_rresp => s_io_axil_rresp,
    s_axil_rvalid => s_io_axil_rvalid,
    s_axil_rready => s_io_axil_rready,

    m_clk => app_clk,
    m_rst => app_clk_reset,

    m_axil_awaddr => io_reg_axil_cdc.awaddr,
    m_axil_awprot => io_reg_axil_cdc.awprot,
    m_axil_awvalid => io_reg_axil_cdc.awvalid,
    m_axil_awready => io_reg_axil_cdc.awready,
    m_axil_wdata => io_reg_axil_cdc.wdata,
    m_axil_wstrb => io_reg_axil_cdc.wstrb,
    m_axil_wvalid => io_reg_axil_cdc.wvalid,
    m_axil_wready => io_reg_axil_cdc.wready,
    m_axil_bresp => io_reg_axil_cdc.bresp,
    m_axil_bvalid => io_reg_axil_cdc.bvalid,
    m_axil_bready => io_reg_axil_cdc.bready,
    m_axil_araddr => io_reg_axil_cdc.araddr,
    m_axil_arprot => io_reg_axil_cdc.arprot,
    m_axil_arvalid => io_reg_axil_cdc.arvalid,
    m_axil_arready => io_reg_axil_cdc.arready,
    m_axil_rdata => io_reg_axil_cdc.rdata,
    m_axil_rresp => io_reg_axil_cdc.rresp,
    m_axil_rvalid => io_reg_axil_cdc.rvalid,
    m_axil_rready => io_reg_axil_cdc.rready
  );

i_trace_dma : entity work.lauberhorn_trace_dma
  port map (
    clk => app_clk,
    reset => app_clk_reset,
    sys_clk => clk,
    sys_reset => reset,

    appTraceIn_0_valid => dcs_even_trace_dcs_event_valid(0),
    appTraceIn_0_payload => pack_dcs_trace(dcs_even_trace_dcs_event_error(0), dcs_even_trace_dcs_event_cli(0), dcs_even_trace_dcs_event_state(0), dcs_even_trace_dcs_event_action(0), dcs_even_trace_dcs_event_request(0)),
    appTraceIn_1_valid => dcs_even_trace_dcs_event_valid(1),
    appTraceIn_1_payload => pack_dcs_trace(dcs_even_trace_dcs_event_error(1), dcs_even_trace_dcs_event_cli(1), dcs_even_trace_dcs_event_state(1), dcs_even_trace_dcs_event_action(1), dcs_even_trace_dcs_event_request(1)),
    appTraceIn_2_valid => dcs_odd_trace_dcs_event_valid(0),
    appTraceIn_2_payload => pack_dcs_trace(dcs_odd_trace_dcs_event_error(0), dcs_odd_trace_dcs_event_cli(0), dcs_odd_trace_dcs_event_state(0), dcs_odd_trace_dcs_event_action(0), dcs_odd_trace_dcs_event_request(0)),
    appTraceIn_3_valid => dcs_odd_trace_dcs_event_valid(1),
    appTraceIn_3_payload => pack_dcs_trace(dcs_odd_trace_dcs_event_error(1), dcs_odd_trace_dcs_event_cli(1), dcs_odd_trace_dcs_event_state(1), dcs_odd_trace_dcs_event_action(1), dcs_odd_trace_dcs_event_request(1)),

    appTraceIn_4_valid => dcs_even_trace_eci_app_valid(0),
    appTraceIn_4_payload => dcs_even_trace_eci_app_payload(0),
    appTraceIn_5_valid => dcs_even_trace_eci_app_valid(1),
    appTraceIn_5_payload => dcs_even_trace_eci_app_payload(1),
    appTraceIn_6_valid => dcs_even_trace_eci_app_valid(2),
    appTraceIn_6_payload => dcs_even_trace_eci_app_payload(2),
    appTraceIn_7_valid => dcs_even_trace_eci_app_valid(3),
    appTraceIn_7_payload => dcs_even_trace_eci_app_payload(3),
    appTraceIn_8_valid => dcs_even_trace_eci_app_valid(4),
    appTraceIn_8_payload => dcs_even_trace_eci_app_payload(4),
    appTraceIn_9_valid => dcs_even_trace_eci_app_valid(5),
    appTraceIn_9_payload => dcs_even_trace_eci_app_payload(5),

    appTraceIn_10_valid => dcs_odd_trace_eci_app_valid(0),
    appTraceIn_10_payload => dcs_odd_trace_eci_app_payload(0),
    appTraceIn_11_valid => dcs_odd_trace_eci_app_valid(1),
    appTraceIn_11_payload => dcs_odd_trace_eci_app_payload(1),
    appTraceIn_12_valid => dcs_odd_trace_eci_app_valid(2),
    appTraceIn_12_payload => dcs_odd_trace_eci_app_payload(2),
    appTraceIn_13_valid => dcs_odd_trace_eci_app_valid(3),
    appTraceIn_13_payload => dcs_odd_trace_eci_app_payload(3),
    appTraceIn_14_valid => dcs_odd_trace_eci_app_valid(4),
    appTraceIn_14_payload => dcs_odd_trace_eci_app_payload(4),
    appTraceIn_15_valid => dcs_odd_trace_eci_app_valid(5),
    appTraceIn_15_payload => dcs_odd_trace_eci_app_payload(5),

    sysTraceIn_0_valid => dcs_even_trace_eci_sys_valid(0),
    sysTraceIn_0_payload => dcs_even_trace_eci_sys_payload(0),
    sysTraceIn_1_valid => dcs_even_trace_eci_sys_valid(1),
    sysTraceIn_1_payload => dcs_even_trace_eci_sys_payload(1),
    sysTraceIn_2_valid => dcs_even_trace_eci_sys_valid(2),
    sysTraceIn_2_payload => dcs_even_trace_eci_sys_payload(2),
    sysTraceIn_3_valid => dcs_even_trace_eci_sys_valid(3),
    sysTraceIn_3_payload => dcs_even_trace_eci_sys_payload(3),
    sysTraceIn_4_valid => dcs_even_trace_eci_sys_valid(4),
    sysTraceIn_4_payload => dcs_even_trace_eci_sys_payload(4),
    sysTraceIn_5_valid => dcs_even_trace_eci_sys_valid(5),
    sysTraceIn_5_payload => dcs_even_trace_eci_sys_payload(5),

    sysTraceIn_6_valid => dcs_odd_trace_eci_sys_valid(0),
    sysTraceIn_6_payload => dcs_odd_trace_eci_sys_payload(0),
    sysTraceIn_7_valid => dcs_odd_trace_eci_sys_valid(1),
    sysTraceIn_7_payload => dcs_odd_trace_eci_sys_payload(1),
    sysTraceIn_8_valid => dcs_odd_trace_eci_sys_valid(2),
    sysTraceIn_8_payload => dcs_odd_trace_eci_sys_payload(2),
    sysTraceIn_9_valid => dcs_odd_trace_eci_sys_valid(3),
    sysTraceIn_9_payload => dcs_odd_trace_eci_sys_payload(3),
    sysTraceIn_10_valid => dcs_odd_trace_eci_sys_valid(4),
    sysTraceIn_10_payload => dcs_odd_trace_eci_sys_payload(4),
    sysTraceIn_11_valid => dcs_odd_trace_eci_sys_valid(5),
    sysTraceIn_11_payload => dcs_odd_trace_eci_sys_payload(5),

    axi_aw_valid => trace_dma_axi.awvalid,
    axi_aw_ready => trace_dma_axi.awready,
    axi_aw_payload_addr => trace_dma_axi.awaddr,
    axi_aw_payload_id => trace_dma_axi.awid,
    axi_aw_payload_len => trace_dma_axi.awlen,
    axi_aw_payload_size => trace_dma_axi.awsize,
    axi_aw_payload_burst => trace_dma_axi.awburst,
    axi_aw_payload_lock => trace_dma_axi.awlock,
    axi_aw_payload_cache => trace_dma_axi.awcache,
    axi_aw_payload_prot => trace_dma_axi.awprot,
    axi_w_valid => trace_dma_axi.wvalid,
    axi_w_ready => trace_dma_axi.wready,
    axi_w_payload_data => trace_dma_axi.wdata,
    axi_w_payload_strb => trace_dma_axi.wstrb,
    axi_w_payload_last => trace_dma_axi.wlast,
    axi_b_valid => trace_dma_axi.bvalid,
    axi_b_ready => trace_dma_axi.bready,
    axi_b_payload_id => trace_dma_axi.bid,
    axi_b_payload_resp => trace_dma_axi.bresp,
    axi_ar_valid => trace_dma_axi.arvalid,
    axi_ar_ready => trace_dma_axi.arready,
    axi_ar_payload_addr => trace_dma_axi.araddr,
    axi_ar_payload_id => trace_dma_axi.arid,
    axi_ar_payload_len => trace_dma_axi.arlen,
    axi_ar_payload_size => trace_dma_axi.arsize,
    axi_ar_payload_burst => trace_dma_axi.arburst,
    axi_ar_payload_lock => trace_dma_axi.arlock,
    axi_ar_payload_cache => trace_dma_axi.arcache,
    axi_ar_payload_prot => trace_dma_axi.arprot,
    axi_r_valid => trace_dma_axi.rvalid,
    axi_r_ready => trace_dma_axi.rready,
    axi_r_payload_data => trace_dma_axi.rdata,
    axi_r_payload_id => trace_dma_axi.rid,
    axi_r_payload_resp => trace_dma_axi.rresp,
    axi_r_payload_last => trace_dma_axi.rlast,

    sampleLost => trace_sample_lost,
    dmaError => trace_dma_error,
    writeSlot => trace_write_slot
  );

  design_1_i: entity work.design_1
  PORT MAP (
    -- GT connections
    gt_grx_n => F_MAC0_RX_N,
    gt_grx_p => F_MAC0_RX_P,
    gt_gtx_n => F_MAC0_TX_N,
    gt_gtx_p => F_MAC0_TX_P,
    gt_ref_clk_clk_n => F_MAC0C_CLK_N,
    gt_ref_clk_clk_p => F_MAC0C_CLK_P,

    -- clocking & reset
    clk_io => clk_io,
    app_clk => app_clk,
    rxclk => rxclk,
    txclk => txclk,
    reset => reset,
    app_clk_reset => app_clk_reset,
    trace_stall_threshold => trace_eci_stall_threshold,

    -- TX interface
    tx_axis_tready => cmac_tx_axis.tready,
    tx_axis_tvalid => cmac_tx_axis.tvalid,
    tx_axis_tdata => cmac_tx_axis.tdata,
    tx_axis_tlast => cmac_tx_axis.tlast,
    tx_axis_tkeep => cmac_tx_axis.tkeep,
    tx_axis_tuser => '0',

    -- RX interface (no tready!)
    rx_axis_tvalid => cmac_rx_axis.tvalid,
    rx_axis_tdata => cmac_rx_axis.tdata,
    rx_axis_tlast => cmac_rx_axis.tlast,
    rx_axis_tkeep => cmac_rx_axis.tkeep,
    rx_axis_tuser => open,

    -- Debug DCS odd and even interfaces
    dcs_even_mon_arid => dcs_even_axi.arid,
    dcs_even_mon_araddr => dcs_even_axi.araddr,
    dcs_even_mon_arburst => dcs_even_axi.arburst,
    dcs_even_mon_arcache => dcs_even_axi.arcache,
    dcs_even_mon_arlen => dcs_even_axi.arlen,
    dcs_even_mon_arlock(0) => dcs_even_axi.arlock,
    dcs_even_mon_arprot => dcs_even_axi.arprot,
    dcs_even_mon_arready => dcs_even_axi.arready,
    dcs_even_mon_arsize => dcs_even_axi.arsize,
    dcs_even_mon_arvalid => dcs_even_axi.arvalid,
    dcs_even_mon_awid => dcs_even_axi.awid,
    dcs_even_mon_awaddr => dcs_even_axi.awaddr,
    dcs_even_mon_awburst => dcs_even_axi.awburst,
    dcs_even_mon_awcache => dcs_even_axi.awcache,
    dcs_even_mon_awlen => dcs_even_axi.awlen,
    dcs_even_mon_awlock(0) => dcs_even_axi.awlock,
    dcs_even_mon_awprot => dcs_even_axi.awprot,
    dcs_even_mon_awready => dcs_even_axi.awready,
    dcs_even_mon_awsize => dcs_even_axi.awsize,
    dcs_even_mon_awvalid => dcs_even_axi.awvalid,
    dcs_even_mon_bid => dcs_even_axi.bid,
    dcs_even_mon_bready => dcs_even_axi.bready,
    dcs_even_mon_bresp => dcs_even_axi.bresp,
    dcs_even_mon_bvalid => dcs_even_axi.bvalid,
    dcs_even_mon_rid => dcs_even_axi.rid,
    dcs_even_mon_rdata => dcs_even_axi.rdata,
    dcs_even_mon_rlast => dcs_even_axi.rlast,
    dcs_even_mon_rready => dcs_even_axi.rready,
    dcs_even_mon_rresp => dcs_even_axi.rresp,
    dcs_even_mon_rvalid => dcs_even_axi.rvalid,
    dcs_even_mon_wdata => dcs_even_axi.wdata,
    dcs_even_mon_wlast => dcs_even_axi.wlast,
    dcs_even_mon_wready => dcs_even_axi.wready,
    dcs_even_mon_wstrb => dcs_even_axi.wstrb,
    dcs_even_mon_wvalid => dcs_even_axi.wvalid,
    dcs_even_mon_arqos => (others => '0'),
    dcs_even_mon_arregion => (others => '0'),
    dcs_even_mon_awqos => (others => '0'),
    dcs_even_mon_awregion => (others => '0'),
    dcs_odd_mon_arid => dcs_odd_axi.arid,
    dcs_odd_mon_araddr => dcs_odd_axi.araddr,
    dcs_odd_mon_arburst => dcs_odd_axi.arburst,
    dcs_odd_mon_arcache => dcs_odd_axi.arcache,
    dcs_odd_mon_arlen => dcs_odd_axi.arlen,
    dcs_odd_mon_arlock(0) => dcs_odd_axi.arlock,
    dcs_odd_mon_arprot => dcs_odd_axi.arprot,
    dcs_odd_mon_arready => dcs_odd_axi.arready,
    dcs_odd_mon_arsize => dcs_odd_axi.arsize,
    dcs_odd_mon_arvalid => dcs_odd_axi.arvalid,
    dcs_odd_mon_awid => dcs_odd_axi.awid,
    dcs_odd_mon_awaddr => dcs_odd_axi.awaddr,
    dcs_odd_mon_awburst => dcs_odd_axi.awburst,
    dcs_odd_mon_awcache => dcs_odd_axi.awcache,
    dcs_odd_mon_awlen => dcs_odd_axi.awlen,
    dcs_odd_mon_awlock(0) => dcs_odd_axi.awlock,
    dcs_odd_mon_awprot => dcs_odd_axi.awprot,
    dcs_odd_mon_awready => dcs_odd_axi.awready,
    dcs_odd_mon_awsize => dcs_odd_axi.awsize,
    dcs_odd_mon_awvalid => dcs_odd_axi.awvalid,
    dcs_odd_mon_bid => dcs_odd_axi.bid,
    dcs_odd_mon_bready => dcs_odd_axi.bready,
    dcs_odd_mon_bresp => dcs_odd_axi.bresp,
    dcs_odd_mon_bvalid => dcs_odd_axi.bvalid,
    dcs_odd_mon_rid => dcs_odd_axi.rid,
    dcs_odd_mon_rdata => dcs_odd_axi.rdata,
    dcs_odd_mon_rlast => dcs_odd_axi.rlast,
    dcs_odd_mon_rready => dcs_odd_axi.rready,
    dcs_odd_mon_rresp => dcs_odd_axi.rresp,
    dcs_odd_mon_rvalid => dcs_odd_axi.rvalid,
    dcs_odd_mon_wdata => dcs_odd_axi.wdata,
    dcs_odd_mon_wlast => dcs_odd_axi.wlast,
    dcs_odd_mon_wready => dcs_odd_axi.wready,
    dcs_odd_mon_wstrb => dcs_odd_axi.wstrb,
    dcs_odd_mon_wvalid => dcs_odd_axi.wvalid,
    dcs_odd_mon_arqos => (others => '0'),
    dcs_odd_mon_arregion => (others => '0'),
    dcs_odd_mon_awqos => (others => '0'),
    dcs_odd_mon_awregion => (others => '0'),

    -- DCS commands
    lci_even  => dcs_c16_i.valid & dcs_c16_i.ready & dcs_c16_i.vc_no & dcs_c16_i.size & dcs_c16_i.data,
    lcia_even => dcs_c18_o.valid & dcs_c18_o.ready & dcs_c18_o.vc_no & dcs_c18_o.size & dcs_c18_o.data,
    ul_even   => dcs_c18_i.valid & dcs_c18_i.ready & dcs_c18_i.vc_no & dcs_c18_i.size & dcs_c18_i.data,

    lci_odd   => dcs_c17_i.valid & dcs_c17_i.ready & dcs_c17_i.vc_no & dcs_c17_i.size & dcs_c17_i.data,
    lcia_odd  => dcs_c19_o.valid & dcs_c19_o.ready & dcs_c19_o.vc_no & dcs_c19_o.size & dcs_c19_o.data,
    ul_odd    => dcs_c19_i.valid & dcs_c19_i.ready & dcs_c19_i.vc_no & dcs_c19_i.size & dcs_c19_i.data,

    -- 2F2F protocol states
    core0_states => core0_states,
    core1_states => core1_states,
    core2_states => core2_states,
    core3_states => core3_states,
    core4_states => core4_states,

    -- RX allocator
    alloc_free => alloc_free,
    alloc_resp => alloc_resp,
    alloc_req => alloc_req,

    -- Trace buffer AXI ports
    trace_ddr_axi_awid => trace_dma_axi.awid,
    trace_ddr_axi_awaddr => trace_dma_axi.awaddr,
    trace_ddr_axi_awlen => trace_dma_axi.awlen,
    trace_ddr_axi_awsize => trace_dma_axi.awsize,
    trace_ddr_axi_awburst => trace_dma_axi.awburst,
    trace_ddr_axi_awlock => trace_dma_axi.awlock,
    trace_ddr_axi_awcache => trace_dma_axi.awcache,
    trace_ddr_axi_awprot => trace_dma_axi.awprot,
    trace_ddr_axi_awqos => (others => '0'),
    trace_ddr_axi_awvalid => trace_dma_axi.awvalid,
    trace_ddr_axi_awready => trace_dma_axi.awready,
    trace_ddr_axi_wdata => trace_dma_axi.wdata,
    trace_ddr_axi_wstrb => trace_dma_axi.wstrb,
    trace_ddr_axi_wlast => trace_dma_axi.wlast,
    trace_ddr_axi_wvalid => trace_dma_axi.wvalid,
    trace_ddr_axi_wready => trace_dma_axi.wready,
    trace_ddr_axi_bid => trace_dma_axi.bid,
    trace_ddr_axi_bresp => trace_dma_axi.bresp,
    trace_ddr_axi_bvalid => trace_dma_axi.bvalid,
    trace_ddr_axi_bready => trace_dma_axi.bready,
    trace_ddr_axi_arid => trace_dma_axi.arid,
    trace_ddr_axi_araddr => trace_dma_axi.araddr,
    trace_ddr_axi_arlen => trace_dma_axi.arlen,
    trace_ddr_axi_arsize => trace_dma_axi.arsize,
    trace_ddr_axi_arburst => trace_dma_axi.arburst,
    trace_ddr_axi_arlock => trace_dma_axi.arlock,
    trace_ddr_axi_arcache => trace_dma_axi.arcache,
    trace_ddr_axi_arprot => trace_dma_axi.arprot,
    trace_ddr_axi_arqos => (others => '0'),
    trace_ddr_axi_arvalid => trace_dma_axi.arvalid,
    trace_ddr_axi_arready => trace_dma_axi.arready,
    trace_ddr_axi_rid => trace_dma_axi.rid,
    trace_ddr_axi_rdata => trace_dma_axi.rdata,
    trace_ddr_axi_rresp => trace_dma_axi.rresp,
    trace_ddr_axi_rlast => trace_dma_axi.rlast,
    trace_ddr_axi_rvalid => trace_dma_axi.rvalid,
    trace_ddr_axi_rready => trace_dma_axi.rready,

    -- Trace DDR AXI-Lite control interface is unused by Lauberhorn.
    trace_ddr_axi_ctrl_awaddr => (others => '0'),
    trace_ddr_axi_ctrl_awvalid => '0',
    trace_ddr_axi_ctrl_awready => open,
    trace_ddr_axi_ctrl_wdata => (others => '0'),
    trace_ddr_axi_ctrl_wvalid => '0',
    trace_ddr_axi_ctrl_wready => open,
    trace_ddr_axi_ctrl_bresp => open,
    trace_ddr_axi_ctrl_bvalid => open,
    trace_ddr_axi_ctrl_bready => '1',
    trace_ddr_axi_ctrl_araddr => (others => '0'),
    trace_ddr_axi_ctrl_arvalid => '0',
    trace_ddr_axi_ctrl_arready => open,
    trace_ddr_axi_ctrl_rdata => open,
    trace_ddr_axi_ctrl_rresp => open,
    trace_ddr_axi_ctrl_rvalid => open,
    trace_ddr_axi_ctrl_rready => '1',

    -- Trace buffer DDR
    trace_ddr_act_n => F_D4_ACT_N,
    trace_ddr_adr => F_D4_A,
    trace_ddr_ba => F_D4_BA,
    trace_ddr_bg => F_D4_BG,
    trace_ddr_ck_c(0) => F_D4_CK_N(0),
    trace_ddr_ck_t(0) => F_D4_CK_P(0),
    trace_ddr_cke(0) => F_D4_CKE(0),
    trace_ddr_cs_n(0) => F_D4_CS_N(0),
    trace_ddr_dq => F_D4_DQ,
    trace_ddr_dqs_c => F_D4_DQS_N,
    trace_ddr_dqs_t => F_D4_DQS_P,
    trace_ddr_odt(0) => F_D4_ODT(0),
    trace_ddr_par => F_D4_PARITY_N,
    trace_ddr_reset_n => F_D4_RESET_N,
    trace_ddr_clk_clk_n => F_D4C_CLK_N,
    trace_ddr_clk_clk_p => F_D4C_CLK_P,

    -- DMA states
    dma_rxFsm_state => dma_rxFsm_state,
    dma_write_desc => dma_write_desc,
    dma_write_desc_status => dma_write_desc_status
);

NicEngine_inst : entity work.NicEngine
  port map (
    clk => app_clk,
    reset => app_clk_reset,

    -- CMAC clocks
    cmacRxClock_clk => rxclk,
    cmacRxClock_reset => rxclk_reset,
    cmacTxClock_clk => txclk,
    cmacTxClock_reset => txclk_reset,

    -- CMAC interface
    s_axis_rx_tvalid => cmac_rx_axis.tvalid,
    s_axis_rx_tready => cmac_rx_axis.tready,
    s_axis_rx_tdata => cmac_rx_axis.tdata,
    s_axis_rx_tlast => cmac_rx_axis.tlast,
    s_axis_rx_tkeep => cmac_rx_axis.tkeep,

    m_axis_tx_tvalid => cmac_tx_axis.tvalid,
    m_axis_tx_tready => cmac_tx_axis.tready,
    m_axis_tx_tdata => cmac_tx_axis.tdata,
    m_axis_tx_tlast => cmac_tx_axis.tlast,
    m_axis_tx_tkeep => cmac_tx_axis.tkeep,

    -- ECI interrupt controller
    ipiToIntc_valid => intc_cmd_valid,
    ipiToIntc_ready => intc_cmd_ready,
    ipiToIntc_payload_cmd => intc_cmd(7 downto 0),
    ipiToIntc_payload_intId => intc_cmd(11 downto 8),
    ipiToIntc_payload_affLvl0 => intc_cmd(27 downto 12),
    ipiToIntc_payload_affLvl1 => intc_cmd(31 downto 28),

    -- DCS odd interface
    s_axi_dcs_odd_awvalid => dcs_odd_axi.awvalid,
    s_axi_dcs_odd_awready => dcs_odd_axi.awready,
    s_axi_dcs_odd_awaddr => dcs_odd_axi.awaddr,
    s_axi_dcs_odd_awid => dcs_odd_axi.awid,
    s_axi_dcs_odd_awlen => dcs_odd_axi.awlen,
    s_axi_dcs_odd_awsize => dcs_odd_axi.awsize,
    s_axi_dcs_odd_awburst => dcs_odd_axi.awburst,
    s_axi_dcs_odd_awlock => dcs_odd_axi.awlock,
    s_axi_dcs_odd_awcache => dcs_odd_axi.awcache,
    s_axi_dcs_odd_awprot => dcs_odd_axi.awprot,
    s_axi_dcs_odd_wvalid => dcs_odd_axi.wvalid,
    s_axi_dcs_odd_wready => dcs_odd_axi.wready,
    s_axi_dcs_odd_wdata => dcs_odd_axi.wdata,
    s_axi_dcs_odd_wstrb => dcs_odd_axi.wstrb,
    s_axi_dcs_odd_wlast => dcs_odd_axi.wlast,
    s_axi_dcs_odd_bvalid => dcs_odd_axi.bvalid,
    s_axi_dcs_odd_bready => dcs_odd_axi.bready,
    s_axi_dcs_odd_bid => dcs_odd_axi.bid,
    s_axi_dcs_odd_bresp => dcs_odd_axi.bresp,
    s_axi_dcs_odd_arvalid => dcs_odd_axi.arvalid,
    s_axi_dcs_odd_arready => dcs_odd_axi.arready,
    s_axi_dcs_odd_araddr => dcs_odd_axi.araddr,
    s_axi_dcs_odd_arid => dcs_odd_axi.arid,
    s_axi_dcs_odd_arlen => dcs_odd_axi.arlen,
    s_axi_dcs_odd_arsize => dcs_odd_axi.arsize,
    s_axi_dcs_odd_arburst => dcs_odd_axi.arburst,
    s_axi_dcs_odd_arlock => dcs_odd_axi.arlock,
    s_axi_dcs_odd_arcache => dcs_odd_axi.arcache,
    s_axi_dcs_odd_arprot => dcs_odd_axi.arprot,
    s_axi_dcs_odd_rvalid => dcs_odd_axi.rvalid,
    s_axi_dcs_odd_rready => dcs_odd_axi.rready,
    s_axi_dcs_odd_rdata => dcs_odd_axi.rdata,
    s_axi_dcs_odd_rid => dcs_odd_axi.rid,
    s_axi_dcs_odd_rresp => dcs_odd_axi.rresp,
    s_axi_dcs_odd_rlast => dcs_odd_axi.rlast,

    dcsOdd_cleanMaybeInvReq_valid => dcs_c17_i.valid,
    dcsOdd_cleanMaybeInvReq_ready => dcs_c17_i.ready,
    dcsOdd_cleanMaybeInvReq_payload_data => dcs_c17_i.data,
    dcsOdd_cleanMaybeInvReq_payload_size => dcs_c17_i.size,
    dcsOdd_cleanMaybeInvReq_payload_vc => dcs_c17_i.vc_no,
    dcsOdd_cleanMaybeInvResp_valid => dcs_c19_o.valid,
    dcsOdd_cleanMaybeInvResp_ready => dcs_c19_o.ready,
    dcsOdd_cleanMaybeInvResp_payload_data => dcs_c19_o.data,
    dcsOdd_cleanMaybeInvResp_payload_size => dcs_c19_o.size,
    dcsOdd_cleanMaybeInvResp_payload_vc => dcs_c19_o.vc_no,
    dcsOdd_unlockResp_valid => dcs_c19_i.valid,
    dcsOdd_unlockResp_ready => dcs_c19_i.ready,
    dcsOdd_unlockResp_payload_data => dcs_c19_i.data,
    dcsOdd_unlockResp_payload_size => dcs_c19_i.size,
    dcsOdd_unlockResp_payload_vc => dcs_c19_i.vc_no,

    -- DCS even interface
    s_axi_dcs_even_awvalid => dcs_even_axi.awvalid,
    s_axi_dcs_even_awready => dcs_even_axi.awready,
    s_axi_dcs_even_awaddr => dcs_even_axi.awaddr,
    s_axi_dcs_even_awid => dcs_even_axi.awid,
    s_axi_dcs_even_awlen => dcs_even_axi.awlen,
    s_axi_dcs_even_awsize => dcs_even_axi.awsize,
    s_axi_dcs_even_awburst => dcs_even_axi.awburst,
    s_axi_dcs_even_awlock => dcs_even_axi.awlock,
    s_axi_dcs_even_awcache => dcs_even_axi.awcache,
    s_axi_dcs_even_awprot => dcs_even_axi.awprot,
    s_axi_dcs_even_wvalid => dcs_even_axi.wvalid,
    s_axi_dcs_even_wready => dcs_even_axi.wready,
    s_axi_dcs_even_wdata => dcs_even_axi.wdata,
    s_axi_dcs_even_wstrb => dcs_even_axi.wstrb,
    s_axi_dcs_even_wlast => dcs_even_axi.wlast,
    s_axi_dcs_even_bvalid => dcs_even_axi.bvalid,
    s_axi_dcs_even_bready => dcs_even_axi.bready,
    s_axi_dcs_even_bid => dcs_even_axi.bid,
    s_axi_dcs_even_bresp => dcs_even_axi.bresp,
    s_axi_dcs_even_arvalid => dcs_even_axi.arvalid,
    s_axi_dcs_even_arready => dcs_even_axi.arready,
    s_axi_dcs_even_araddr => dcs_even_axi.araddr,
    s_axi_dcs_even_arid => dcs_even_axi.arid,
    s_axi_dcs_even_arlen => dcs_even_axi.arlen,
    s_axi_dcs_even_arsize => dcs_even_axi.arsize,
    s_axi_dcs_even_arburst => dcs_even_axi.arburst,
    s_axi_dcs_even_arlock => dcs_even_axi.arlock,
    s_axi_dcs_even_arcache => dcs_even_axi.arcache,
    s_axi_dcs_even_arprot => dcs_even_axi.arprot,
    s_axi_dcs_even_rvalid => dcs_even_axi.rvalid,
    s_axi_dcs_even_rready => dcs_even_axi.rready,
    s_axi_dcs_even_rdata => dcs_even_axi.rdata,
    s_axi_dcs_even_rid => dcs_even_axi.rid,
    s_axi_dcs_even_rresp => dcs_even_axi.rresp,
    s_axi_dcs_even_rlast => dcs_even_axi.rlast,

    dcsEven_cleanMaybeInvReq_valid => dcs_c16_i.valid,
    dcsEven_cleanMaybeInvReq_ready => dcs_c16_i.ready,
    dcsEven_cleanMaybeInvReq_payload_data => dcs_c16_i.data,
    dcsEven_cleanMaybeInvReq_payload_size => dcs_c16_i.size,
    dcsEven_cleanMaybeInvReq_payload_vc => dcs_c16_i.vc_no,
    dcsEven_cleanMaybeInvResp_valid => dcs_c18_o.valid,
    dcsEven_cleanMaybeInvResp_ready => dcs_c18_o.ready,
    dcsEven_cleanMaybeInvResp_payload_data => dcs_c18_o.data,
    dcsEven_cleanMaybeInvResp_payload_size => dcs_c18_o.size,
    dcsEven_cleanMaybeInvResp_payload_vc => dcs_c18_o.vc_no,
    dcsEven_unlockResp_valid => dcs_c18_i.valid,
    dcsEven_unlockResp_ready => dcs_c18_i.ready,
    dcsEven_unlockResp_payload_data => dcs_c18_i.data,
    dcsEven_unlockResp_payload_size => dcs_c18_i.size,
    dcsEven_unlockResp_payload_vc => dcs_c18_i.vc_no,

    -- regs
    s_axil_ctrl_awvalid => io_reg_axil_cdc.awvalid,
    s_axil_ctrl_awready => io_reg_axil_cdc.awready,
    s_axil_ctrl_awaddr => io_reg_axil_cdc.awaddr,
    s_axil_ctrl_awprot => io_reg_axil_cdc.awprot,
    s_axil_ctrl_wvalid => io_reg_axil_cdc.wvalid,
    s_axil_ctrl_wready => io_reg_axil_cdc.wready,
    s_axil_ctrl_wdata => io_reg_axil_cdc.wdata,
    s_axil_ctrl_wstrb => io_reg_axil_cdc.wstrb,
    s_axil_ctrl_bvalid => io_reg_axil_cdc.bvalid,
    s_axil_ctrl_bready => io_reg_axil_cdc.bready,
    s_axil_ctrl_bresp => io_reg_axil_cdc.bresp,
    s_axil_ctrl_arvalid => io_reg_axil_cdc.arvalid,
    s_axil_ctrl_arready => io_reg_axil_cdc.arready,
    s_axil_ctrl_araddr => io_reg_axil_cdc.araddr,
    s_axil_ctrl_arprot => io_reg_axil_cdc.arprot,
    s_axil_ctrl_rvalid => io_reg_axil_cdc.rvalid,
    s_axil_ctrl_rready => io_reg_axil_cdc.rready,
    s_axil_ctrl_rdata => io_reg_axil_cdc.rdata,
    s_axil_ctrl_rresp => io_reg_axil_cdc.rresp,

    -- Debug interfaces
    core0_rxRouter_state => core0_states(3 downto 0),
    core0_txRouter_read_state => core0_states(6 downto 4),
    core0_txRouter_write_state => core0_states(9 downto 7),
    core0_rxFsm_state => core0_states(12 downto 10),
    core0_txFsm_state => core0_states(15 downto 13),
    core0_rxClIdx => core0_states(16),
    core0_txClIdx => core0_states(17),

    core1_rxRouter_state => core1_states(3 downto 0),
    core1_txRouter_read_state => core1_states(6 downto 4),
    core1_txRouter_write_state => core1_states(9 downto 7),
    core1_rxFsm_state => core1_states(12 downto 10),
    core1_txFsm_state => core1_states(15 downto 13),
    core1_rxClIdx => core1_states(16),
    core1_txClIdx => core1_states(17),

    core2_rxRouter_state => core2_states(3 downto 0),
    core2_txRouter_read_state => core2_states(6 downto 4),
    core2_txRouter_write_state => core2_states(9 downto 7),
    core2_rxFsm_state => core2_states(12 downto 10),
    core2_txFsm_state => core2_states(15 downto 13),
    core2_rxClIdx => core2_states(16),
    core2_txClIdx => core2_states(17),

    core3_rxRouter_state => core3_states(3 downto 0),
    core3_txRouter_read_state => core3_states(6 downto 4),
    core3_txRouter_write_state => core3_states(9 downto 7),
    core3_rxFsm_state => core3_states(12 downto 10),
    core3_txFsm_state => core3_states(15 downto 13),
    core3_rxClIdx => core3_states(16),
    core3_txClIdx => core3_states(17),

    core4_rxRouter_state => core4_states(3 downto 0),
    core4_txRouter_read_state => core4_states(6 downto 4),
    core4_txRouter_write_state => core4_states(9 downto 7),
    core4_rxFsm_state => core4_states(12 downto 10),
    core4_txFsm_state => core4_states(15 downto 13),
    core4_rxClIdx => core4_states(16),
    core4_txClIdx => core4_states(17),

    alloc_free_valid => alloc_free(41),
    alloc_free_ready => alloc_free(40),
    alloc_free_payload_addr_bits => alloc_free(39 downto 16),
    alloc_free_payload_size_bits => alloc_free(15 downto 0),

    alloc_resp_valid => alloc_resp(41),
    alloc_resp_ready => alloc_resp(40),
    alloc_resp_payload_addr_bits => alloc_resp(39 downto 16),
    alloc_resp_payload_size_bits => alloc_resp(15 downto 0),

    alloc_req_valid => alloc_req(17),
    alloc_req_ready => alloc_req(16),
    alloc_req_payload_bits => alloc_req(15 downto 0),

    dma_write_desc_valid => dma_write_desc(34),
    dma_write_desc_ready => dma_write_desc(33),
    dma_write_desc_payload_addr => dma_write_desc(32 downto 16),
    dma_write_desc_payload_len => dma_write_desc(15 downto 0),

    dma_write_desc_status_valid => dma_write_desc_status(16),
    dma_write_desc_status_payload_len => dma_write_desc_status(15 downto 0),

    dma_rxFsm_state => dma_rxFsm_state
  );

end Behavioral;
