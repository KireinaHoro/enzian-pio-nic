-------------------------------------------------------------------------------
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

entity pio_nic_eci is
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
end pio_nic_eci;

architecture Behavioral of pio_nic_eci is

component sync_reset is
generic (
    N : integer := 2
);
port (
    clk, rst : in std_logic;
    \out\ : out std_logic
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

signal cmac_rx_axis, cmac_tx_axis : CMAC_AXIS;
signal cmac_reg_axil, nic_engine_axil, io_reg_axil_cdc : REGS_AXIL;
signal cmac_reg_axil_narrow : REGS_AXIL_NARROW;

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

signal a, b, c, d : std_logic;

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
    resetn                     => not reset,
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

-- CDC FIFO for IPI commands
ipi_cmd_cdc : entity work.axis_async_fifo
generic map (
    DEPTH       => 2,
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
dcs_even : entity work.dcs_cdc
port map (
  eci_clk   => clk,
  eci_reset => reset,

  app_clk   => app_clk,
  app_reset => app_clk_reset,

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

  -- Primary AXI rd/wr i/f.
  p_axi_arid    => dcs_even_axi.arid,
  p_axi_araddr  => dcs_even_axi.araddr,
  p_axi_arlen   => dcs_even_axi.arlen,
  p_axi_arsize  => dcs_even_axi.arsize,
  p_axi_arburst => dcs_even_axi.arburst,
  p_axi_arlock  => dcs_even_axi.arlock,
  p_axi_arcache => dcs_even_axi.arcache,
  p_axi_arprot  => dcs_even_axi.arprot,
  p_axi_arvalid => dcs_even_axi.arvalid,
  p_axi_arready => dcs_even_axi.arready,
  p_axi_rid     => dcs_even_axi.rid,
  p_axi_rdata   => dcs_even_axi.rdata,
  p_axi_rresp   => dcs_even_axi.rresp,
  p_axi_rlast   => dcs_even_axi.rlast,
  p_axi_rvalid  => dcs_even_axi.rvalid,
  p_axi_rready  => dcs_even_axi.rready,

  p_axi_awid    => dcs_even_axi.awid,
  p_axi_awaddr  => dcs_even_axi.awaddr,
  p_axi_awlen   => dcs_even_axi.awlen,
  p_axi_awsize  => dcs_even_axi.awsize,
  p_axi_awburst => dcs_even_axi.awburst,
  p_axi_awlock  => dcs_even_axi.awlock,
  p_axi_awcache => dcs_even_axi.awcache,
  p_axi_awprot  => dcs_even_axi.awprot,
  p_axi_awvalid => dcs_even_axi.awvalid,
  p_axi_awready => dcs_even_axi.awready,
  p_axi_wdata   => dcs_even_axi.wdata,
  p_axi_wstrb   => dcs_even_axi.wstrb,
  p_axi_wlast   => dcs_even_axi.wlast,
  p_axi_wvalid  => dcs_even_axi.wvalid,
  p_axi_wready  => dcs_even_axi.wready,
  p_axi_bid     => dcs_even_axi.bid,
  p_axi_bresp   => dcs_even_axi.bresp,
  p_axi_bvalid  => dcs_even_axi.bvalid,
  p_axi_bready  => dcs_even_axi.bready
  );

-- DCS for odd VCs ie even CL indices.
dcs_odd : entity work.dcs_cdc
port map (
  eci_clk   => clk,
  eci_reset => reset,

  app_clk   => app_clk,
  app_reset => app_clk_reset,

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

  -- Primary AXI rd/wr i/f.
  p_axi_arid    => dcs_odd_axi.arid,
  p_axi_araddr  => dcs_odd_axi.araddr,
  p_axi_arlen   => dcs_odd_axi.arlen,
  p_axi_arsize  => dcs_odd_axi.arsize,
  p_axi_arburst => dcs_odd_axi.arburst,
  p_axi_arlock  => dcs_odd_axi.arlock,
  p_axi_arcache => dcs_odd_axi.arcache,
  p_axi_arprot  => dcs_odd_axi.arprot,
  p_axi_arvalid => dcs_odd_axi.arvalid,
  p_axi_arready => dcs_odd_axi.arready,
  p_axi_rid     => dcs_odd_axi.rid,
  p_axi_rdata   => dcs_odd_axi.rdata,
  p_axi_rresp   => dcs_odd_axi.rresp,
  p_axi_rlast   => dcs_odd_axi.rlast,
  p_axi_rvalid  => dcs_odd_axi.rvalid,
  p_axi_rready  => dcs_odd_axi.rready,

  p_axi_awid    => dcs_odd_axi.awid,
  p_axi_awaddr  => dcs_odd_axi.awaddr,
  p_axi_awlen   => dcs_odd_axi.awlen,
  p_axi_awsize  => dcs_odd_axi.awsize,
  p_axi_awburst => dcs_odd_axi.awburst,
  p_axi_awlock  => dcs_odd_axi.awlock,
  p_axi_awcache => dcs_odd_axi.awcache,
  p_axi_awprot  => dcs_odd_axi.awprot,
  p_axi_awvalid => dcs_odd_axi.awvalid,
  p_axi_awready => dcs_odd_axi.awready,
  p_axi_wdata   => dcs_odd_axi.wdata,
  p_axi_wstrb   => dcs_odd_axi.wstrb,
  p_axi_wlast   => dcs_odd_axi.wlast,
  p_axi_wvalid  => dcs_odd_axi.wvalid,
  p_axi_wready  => dcs_odd_axi.wready,
  p_axi_bid     => dcs_odd_axi.bid,
  p_axi_bresp   => dcs_odd_axi.bresp,
  p_axi_bvalid  => dcs_odd_axi.bvalid,
  p_axi_bready  => dcs_odd_axi.bready

);

-- reset synchronizers for RX and TX clocks
rx_rst_sync : sync_reset
port map (
    clk => rxclk,
    rst => reset,
    \out\ => rxclk_reset
);

tx_rst_sync : sync_reset
port map (
    clk => txclk,
    rst => reset,
    \out\ => txclk_reset
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

axil_adapter_inst : entity work.axil_adapter
  generic map (
    ADDR_WIDTH => 32,
    S_DATA_WIDTH => 64,
    M_DATA_WIDTH => 32
  )
  port map (
    clk => app_clk,
    rst => app_clk_reset,

    s_axil_awaddr => cmac_reg_axil.awaddr(31 downto 0),
    s_axil_awprot => cmac_reg_axil.awprot,
    s_axil_awvalid => cmac_reg_axil.awvalid,
    s_axil_awready => cmac_reg_axil.awready,
    s_axil_wdata => cmac_reg_axil.wdata,
    s_axil_wstrb => cmac_reg_axil.wstrb,
    s_axil_wvalid => cmac_reg_axil.wvalid,
    s_axil_wready => cmac_reg_axil.wready,
    s_axil_bresp => cmac_reg_axil.bresp,
    s_axil_bvalid => cmac_reg_axil.bvalid,
    s_axil_bready => cmac_reg_axil.bready,
    s_axil_araddr => cmac_reg_axil.araddr(31 downto 0),
    s_axil_arprot => cmac_reg_axil.arprot,
    s_axil_arvalid => cmac_reg_axil.arvalid,
    s_axil_arready => cmac_reg_axil.arready,
    s_axil_rdata => cmac_reg_axil.rdata,
    s_axil_rresp => cmac_reg_axil.rresp,
    s_axil_rvalid => cmac_reg_axil.rvalid,
    s_axil_rready => cmac_reg_axil.rready,

    m_axil_awaddr => cmac_reg_axil_narrow.awaddr,
    m_axil_awprot => cmac_reg_axil_narrow.awprot,
    m_axil_awvalid => cmac_reg_axil_narrow.awvalid,
    m_axil_awready => cmac_reg_axil_narrow.awready,
    m_axil_wdata => cmac_reg_axil_narrow.wdata,
    m_axil_wstrb => cmac_reg_axil_narrow.wstrb,
    m_axil_wvalid => cmac_reg_axil_narrow.wvalid,
    m_axil_wready => cmac_reg_axil_narrow.wready,
    m_axil_bresp => cmac_reg_axil_narrow.bresp,
    m_axil_bvalid => cmac_reg_axil_narrow.bvalid,
    m_axil_bready => cmac_reg_axil_narrow.bready,
    m_axil_araddr => cmac_reg_axil_narrow.araddr,
    m_axil_arprot => cmac_reg_axil_narrow.arprot,
    m_axil_arvalid => cmac_reg_axil_narrow.arvalid,
    m_axil_arready => cmac_reg_axil_narrow.arready,
    m_axil_rdata => cmac_reg_axil_narrow.rdata,
    m_axil_rresp => cmac_reg_axil_narrow.rresp,
    m_axil_rvalid => cmac_reg_axil_narrow.rvalid,
    m_axil_rready => cmac_reg_axil_narrow.rready
  );

  axil_regs_interconnect_inst : entity work.axil_regs_interconnect
  generic map (
    DATA_WIDTH => 64,
    ADDR_WIDTH => 44,
    -- registers for NIC engine [0x0 - 0x200000]
    M00_BASE_ADDR => 0,
    M00_ADDR_WIDTH => 21,
    -- registers for CMAC [0x200000 - 0x2007ff]
    M01_BASE_ADDR => 16#200000#,
    M01_ADDR_WIDTH => 11
  )
  port map (
    clk => app_clk,
    rst => app_clk_reset,

    s00_axil_awaddr => io_reg_axil_cdc.awaddr,
    s00_axil_awprot => io_reg_axil_cdc.awprot,
    s00_axil_awvalid => io_reg_axil_cdc.awvalid,
    s00_axil_awready => io_reg_axil_cdc.awready,
    s00_axil_wdata => io_reg_axil_cdc.wdata,
    s00_axil_wstrb => io_reg_axil_cdc.wstrb,
    s00_axil_wvalid => io_reg_axil_cdc.wvalid,
    s00_axil_wready => io_reg_axil_cdc.wready,
    s00_axil_bresp => io_reg_axil_cdc.bresp,
    s00_axil_bvalid => io_reg_axil_cdc.bvalid,
    s00_axil_bready => io_reg_axil_cdc.bready,
    s00_axil_araddr => io_reg_axil_cdc.araddr,
    s00_axil_arprot => io_reg_axil_cdc.arprot,
    s00_axil_arvalid => io_reg_axil_cdc.arvalid,
    s00_axil_arready => io_reg_axil_cdc.arready,
    s00_axil_rdata => io_reg_axil_cdc.rdata,
    s00_axil_rresp => io_reg_axil_cdc.rresp,
    s00_axil_rvalid => io_reg_axil_cdc.rvalid,
    s00_axil_rready => io_reg_axil_cdc.rready,

    m00_axil_awaddr => nic_engine_axil.awaddr,
    m00_axil_awprot => nic_engine_axil.awprot,
    m00_axil_awvalid => nic_engine_axil.awvalid,
    m00_axil_awready => nic_engine_axil.awready,
    m00_axil_wdata => nic_engine_axil.wdata,
    m00_axil_wstrb => nic_engine_axil.wstrb,
    m00_axil_wvalid => nic_engine_axil.wvalid,
    m00_axil_wready => nic_engine_axil.wready,
    m00_axil_bresp => nic_engine_axil.bresp,
    m00_axil_bvalid => nic_engine_axil.bvalid,
    m00_axil_bready => nic_engine_axil.bready,
    m00_axil_araddr => nic_engine_axil.araddr,
    m00_axil_arprot => nic_engine_axil.arprot,
    m00_axil_arvalid => nic_engine_axil.arvalid,
    m00_axil_arready => nic_engine_axil.arready,
    m00_axil_rdata => nic_engine_axil.rdata,
    m00_axil_rresp => nic_engine_axil.rresp,
    m00_axil_rvalid => nic_engine_axil.rvalid,
    m00_axil_rready => nic_engine_axil.rready,

    m01_axil_awaddr => cmac_reg_axil.awaddr,
    m01_axil_awprot => cmac_reg_axil.awprot,
    m01_axil_awvalid => cmac_reg_axil.awvalid,
    m01_axil_awready => cmac_reg_axil.awready,
    m01_axil_wdata => cmac_reg_axil.wdata,
    m01_axil_wstrb => cmac_reg_axil.wstrb,
    m01_axil_wvalid => cmac_reg_axil.wvalid,
    m01_axil_wready => cmac_reg_axil.wready,
    m01_axil_bresp => cmac_reg_axil.bresp,
    m01_axil_bvalid => cmac_reg_axil.bvalid,
    m01_axil_bready => cmac_reg_axil.bready,
    m01_axil_araddr => cmac_reg_axil.araddr,
    m01_axil_arprot => cmac_reg_axil.arprot,
    m01_axil_arvalid => cmac_reg_axil.arvalid,
    m01_axil_arready => cmac_reg_axil.arready,
    m01_axil_rdata => cmac_reg_axil.rdata,
    m01_axil_rresp => cmac_reg_axil.rresp,
    m01_axil_rvalid => cmac_reg_axil.rvalid,
    m01_axil_rready => cmac_reg_axil.rready
  );

  design_1_i: entity work.design_1
  PORT MAP (
    -- GT connections
    gt_grx_n => F_MAC1_RX_N,
    gt_grx_p => F_MAC1_RX_P,
    gt_gtx_n => F_MAC1_TX_N,
    gt_gtx_p => F_MAC1_TX_P,
    gt_ref_clk_clk_n => F_MAC1C_CLK_N,
    gt_ref_clk_clk_p => F_MAC1C_CLK_P,

    -- clocking & reset
    clk_io => clk_io,
    app_clk => app_clk,
    rxclk => rxclk,
    txclk => txclk,
    reset => reset,
    app_clk_reset => app_clk_reset,

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

    -- AXI lite reg interface
    cmac_regs_axil_araddr => cmac_reg_axil_narrow.araddr,
    cmac_regs_axil_arready => cmac_reg_axil_narrow.arready,
    cmac_regs_axil_arvalid => cmac_reg_axil_narrow.arvalid,
    cmac_regs_axil_awaddr => cmac_reg_axil_narrow.awaddr,
    cmac_regs_axil_awready => cmac_reg_axil_narrow.awready,
    cmac_regs_axil_awvalid => cmac_reg_axil_narrow.awvalid,
    cmac_regs_axil_bready => cmac_reg_axil_narrow.bready,
    cmac_regs_axil_bresp => cmac_reg_axil_narrow.bresp,
    cmac_regs_axil_bvalid => cmac_reg_axil_narrow.bvalid,
    cmac_regs_axil_rdata => cmac_reg_axil_narrow.rdata,
    cmac_regs_axil_rready => cmac_reg_axil_narrow.rready,
    cmac_regs_axil_rresp => cmac_reg_axil_narrow.rresp,
    cmac_regs_axil_rvalid => cmac_reg_axil_narrow.rvalid,
    cmac_regs_axil_wdata => cmac_reg_axil_narrow.wdata,
    cmac_regs_axil_wready => cmac_reg_axil_narrow.wready,
    cmac_regs_axil_wstrb => cmac_reg_axil_narrow.wstrb,
    cmac_regs_axil_wvalid => cmac_reg_axil_narrow.wvalid
  );

NicEngine_inst : entity work.NicEngine
  port map (
    -- 322 MHz clock from ECI
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
    s_axil_ctrl_awvalid => nic_engine_axil.awvalid,
    s_axil_ctrl_awready => nic_engine_axil.awready,
    s_axil_ctrl_awaddr => nic_engine_axil.awaddr,
    s_axil_ctrl_awprot => nic_engine_axil.awprot,
    s_axil_ctrl_wvalid => nic_engine_axil.wvalid,
    s_axil_ctrl_wready => nic_engine_axil.wready,
    s_axil_ctrl_wdata => nic_engine_axil.wdata,
    s_axil_ctrl_wstrb => nic_engine_axil.wstrb,
    s_axil_ctrl_bvalid => nic_engine_axil.bvalid,
    s_axil_ctrl_bready => nic_engine_axil.bready,
    s_axil_ctrl_bresp => nic_engine_axil.bresp,
    s_axil_ctrl_arvalid => nic_engine_axil.arvalid,
    s_axil_ctrl_arready => nic_engine_axil.arready,
    s_axil_ctrl_araddr => nic_engine_axil.araddr,
    s_axil_ctrl_arprot => nic_engine_axil.arprot,
    s_axil_ctrl_rvalid => nic_engine_axil.rvalid,
    s_axil_ctrl_rready => nic_engine_axil.rready,
    s_axil_ctrl_rdata => nic_engine_axil.rdata,
    s_axil_ctrl_rresp => nic_engine_axil.rresp
  );

i_ila_eci_chans_arb : entity work.ila_eci_chans_arb
port map (
    clk            => clk,

    probe0(0)      => link_eci_packet_rx.c7_gsync.valid,
    probe1(0)      => link_eci_packet_rx.c6_gsync.valid,
    probe2(0)      => link_eci_packet_rx.ginv.valid,
    probe3(0)      => link_eci_packet_rx.dcs_c7.valid,
    probe4(0)      => link_eci_packet_rx.dcs_c6.valid,
    probe5(0)      => link_eci_packet_rx.dcs_c11.valid,
    probe6(0)      => link_eci_packet_rx.dcs_c10.valid,
    probe7(0)      => link_eci_packet_rx.dcs_c5.valid,
    probe8(0)      => link_eci_packet_rx.dcs_c4.valid,

    probe9(0)      => link_eci_packet_rx.c7_gsync_ready,
    probe10(0)     => link_eci_packet_rx.c6_gsync_ready,
    probe11(0)     => '1',
    probe12(0)     => link_eci_packet_rx.dcs_c7_ready,
    probe13(0)     => link_eci_packet_rx.dcs_c6_ready,
    probe14(0)     => link_eci_packet_rx.dcs_c11_ready,
    probe15(0)     => link_eci_packet_rx.dcs_c10_ready,
    probe16(0)     => link_eci_packet_rx.dcs_c5_ready,
    probe17(0)     => link_eci_packet_rx.dcs_c4_ready,

    probe18(0)     => link_eci_packet_tx.c11_gsync.valid,
    probe19(0)     => link_eci_packet_tx.c10_gsync.valid,
    probe20(0)     => link_eci_packet_tx.dcs_c11.valid,
    probe21(0)     => link_eci_packet_tx.dcs_c5.valid,
    probe22(0)     => link_eci_packet_tx.dcs_c10.valid,
    probe23(0)     => link_eci_packet_tx.dcs_c4.valid,
    probe24(0)     => link_eci_packet_tx.dcs_c8.valid,
    probe25(0)     => link_eci_packet_tx.dcs_c9.valid,

    probe26(0)     => link_eci_packet_tx.c11_gsync_ready,
    probe27(0)     => link_eci_packet_tx.c10_gsync_ready,
    probe28(0)     => link_eci_packet_tx.dcs_c11_ready,
    probe29(0)     => link_eci_packet_tx.dcs_c5_ready,
    probe30(0)     => link_eci_packet_tx.dcs_c10_ready,
    probe31(0)     => link_eci_packet_tx.dcs_c4_ready,
    probe32(0)     => link_eci_packet_tx.dcs_c8_ready,
    probe33(0)     => link_eci_packet_tx.dcs_c9_ready
);

end Behavioral;
