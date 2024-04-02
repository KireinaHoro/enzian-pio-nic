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
-- 100MHz
    clk_io_out              : out std_logic;
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

component eci_gateway is
generic (
    TX_NO_CHANNELS      : integer;
    RX_NO_CHANNELS      : integer;
    RX_FILTER_VC        : VC_BITFIELDS;
    RX_FILTER_TYPE_MASK : ECI_TYPE_MASKS;
    RX_FILTER_TYPE      : ECI_TYPE_MASKS;
    RX_FILTER_CLI_MASK  : CLI_ARRAY;
    RX_FILTER_CLI       : CLI_ARRAY
);
port (
    clk_sys                 : in std_logic;
    clk_io_out              : out std_logic;
    clk_prgc0_out           : out std_logic;
    clk_prgc1_out           : out std_logic;

    prgc0_clk_p             : in std_logic;
    prgc0_clk_n             : in std_logic;
    prgc1_clk_p             : in std_logic;
    prgc1_clk_n             : in std_logic;

    reset_sys               : in std_logic;
    reset_out               : out std_logic;
    reset_n_out             : out std_logic;
    link1_up                : in std_logic;
    link2_up                : in std_logic;

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

    m0_bscan_bscanid_en     : out std_logic;
    m0_bscan_capture        : out std_logic;
    m0_bscan_drck           : out std_logic;
    m0_bscan_reset          : out std_logic;
    m0_bscan_runtest        : out std_logic;
    m0_bscan_sel            : out std_logic;
    m0_bscan_shift          : out std_logic;
    m0_bscan_tck            : out std_logic;
    m0_bscan_tdi            : out std_logic;
    m0_bscan_tdo            : in std_logic;
    m0_bscan_tms            : out std_logic;
    m0_bscan_update         : out std_logic;

    rx_eci_channels         : out ARRAY_ECI_CHANNELS(RX_NO_CHANNELS-1 downto 0);
    rx_eci_channels_ready   : in std_logic_vector(RX_NO_CHANNELS-1 downto 0);

    tx_eci_channels         : in ARRAY_ECI_CHANNELS(TX_NO_CHANNELS-1 downto 0);
    tx_eci_channels_ready   : out std_logic_vector(TX_NO_CHANNELS-1 downto 0)
);
end component;

component eci_channel_bus_converter is
port (
    clk             : in STD_LOGIC;

    in_channel      : in ECI_CHANNEL;
    in_ready        : out STD_LOGIC;

    out_data        : out WORDS(16 downto 0);
    out_vc_no       : out std_logic_vector(3 downto 0);
    out_size        : out std_logic_vector(4 downto 0);
    out_valid       : out std_logic;
    out_ready       : in std_logic
);
end component;

component eci_bus_channel_converter is
port (
    clk             : in STD_LOGIC;

    in_data         : in WORDS(16 downto 0);
    in_vc_no        : in std_logic_vector(3 downto 0);
    in_size         : in std_logic_vector(4 downto 0);
    in_valid        : in std_logic;
    in_ready        : out std_logic;

    out_channel     : out ECI_CHANNEL;
    out_ready       : in STD_LOGIC
);
end component;

component loopback_vc_resp_nodata is
generic (
   WORD_WIDTH : integer;
   GSDN_GSYNC_FN : integer
);
port (
    clk, reset : in std_logic;

    -- ECI Request input stream
    vc_req_i       : in  std_logic_vector(63 downto 0);
    vc_req_valid_i : in  std_logic;
    vc_req_ready_o : out std_logic;

    -- ECI Response output stream
    vc_resp_o       : out std_logic_vector(63 downto 0);
    vc_resp_valid_o : out std_logic;
    vc_resp_ready_i : in  std_logic
);
end component;

component dcs_2_axi is
port (
  clk, reset : in std_logic;

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
  p_axi_arid    : out std_logic_vector( 6 downto 0);
  p_axi_araddr  : out std_logic_vector(37 downto 0);
  p_axi_arlen   : out std_logic_vector( 7 downto 0);
  p_axi_arsize  : out std_logic_vector( 2 downto 0);
  p_axi_arburst : out std_logic_vector( 1 downto 0);
  p_axi_arlock  : out std_logic;
  p_axi_arcache : out std_logic_vector( 3 downto 0);
  p_axi_arprot  : out std_logic_vector( 2 downto 0);
  p_axi_arvalid : out std_logic;
  p_axi_arready : in std_logic;
  p_axi_rid     : in std_logic_vector( 6 downto 0);
  p_axi_rdata   : in std_logic_vector(511 downto 0);
  p_axi_rresp   : in std_logic_vector( 1 downto 0);
  p_axi_rlast   : in std_logic;
  p_axi_rvalid  : in std_logic;
  p_axi_rready  : out std_logic;

  p_axi_awid    : out std_logic_vector ( 6 downto 0);
  p_axi_awaddr  : out std_logic_vector (37 downto 0);
  p_axi_awlen   : out std_logic_vector ( 7 downto 0);
  p_axi_awsize  : out std_logic_vector ( 2 downto 0);
  p_axi_awburst : out std_logic_vector ( 1 downto 0);
  p_axi_awlock  : out std_logic;
  p_axi_awcache : out std_logic_vector ( 3 downto 0);
  p_axi_awprot  : out std_logic_vector ( 2 downto 0);
  p_axi_awvalid : out std_logic;
  p_axi_awready : in std_logic ;
  p_axi_wdata   : out std_logic_vector (511 downto 0);
  p_axi_wstrb   : out std_logic_vector (63 downto 0);
  p_axi_wlast   : out std_logic;
  p_axi_wvalid  : out std_logic;
  p_axi_wready  : in std_logic;
  p_axi_bid     : in std_logic_vector( 6 downto 0);
  p_axi_bresp   : in std_logic_vector( 1 downto 0);
  p_axi_bvalid  : in std_logic;
  p_axi_bready  : out std_logic
);
end component;

component NicEngine is
port (
    clk, reset : in std_logic;

    -- CMAC interface: RX AXIS slave
    s_axis_rx_tvalid    : in std_logic;
    s_axis_rx_tready    : out std_logic;
    s_axis_rx_tdata     : in std_logic_vector(511 downto 0);
    s_axis_rx_tkeep     : in std_logic_vector(63 downto 0);
    s_axis_rx_tlast     : in std_logic;

    -- CMAC interface: TX AXIS master
    m_axis_tx_tvalid    : out std_logic;
    m_axis_tx_tready    : in std_logic;
    m_axis_tx_tdata     : out std_logic_vector(511 downto 0);
    m_axis_tx_tkeep     : out std_logic_vector(63 downto 0);
    m_axis_tx_tlast     : out std_logic;

    -- CMAC interface: RX clock domain
    cmacRxClock_clk, cmacRxClock_reset: in std_logic;

    -- CMAC interface: TX clock domain
    cmacTxClock_clk, cmacTxClock_reset: in std_logic;

    -- DC interface: odd AXI slave
    s_axi_dcs_odd_arid    : out std_logic_vector( 6 downto 0);
    s_axi_dcs_odd_araddr  : out std_logic_vector(37 downto 0);
    s_axi_dcs_odd_arlen   : out std_logic_vector( 7 downto 0);
    s_axi_dcs_odd_arsize  : out std_logic_vector( 2 downto 0);
    s_axi_dcs_odd_arburst : out std_logic_vector( 1 downto 0);
    s_axi_dcs_odd_arlock  : out std_logic;
    s_axi_dcs_odd_arcache : out std_logic_vector( 3 downto 0);
    s_axi_dcs_odd_arprot  : out std_logic_vector( 2 downto 0);
    s_axi_dcs_odd_arvalid : out std_logic;
    s_axi_dcs_odd_arready : in std_logic;
    s_axi_dcs_odd_rid     : in std_logic_vector( 6 downto 0);
    s_axi_dcs_odd_rdata   : in std_logic_vector(511 downto 0);
    s_axi_dcs_odd_rresp   : in std_logic_vector( 1 downto 0);
    s_axi_dcs_odd_rlast   : in std_logic;
    s_axi_dcs_odd_rvalid  : in std_logic;
    s_axi_dcs_odd_rready  : out std_logic;

    s_axi_dcs_odd_awid    : out std_logic_vector ( 6 downto 0);
    s_axi_dcs_odd_awaddr  : out std_logic_vector (37 downto 0);
    s_axi_dcs_odd_awlen   : out std_logic_vector ( 7 downto 0);
    s_axi_dcs_odd_awsize  : out std_logic_vector ( 2 downto 0);
    s_axi_dcs_odd_awburst : out std_logic_vector ( 1 downto 0);
    s_axi_dcs_odd_awlock  : out std_logic;
    s_axi_dcs_odd_awcache : out std_logic_vector ( 3 downto 0);
    s_axi_dcs_odd_awprot  : out std_logic_vector ( 2 downto 0);
    s_axi_dcs_odd_awvalid : out std_logic;
    s_axi_dcs_odd_awready : in std_logic ;
    s_axi_dcs_odd_wdata   : out std_logic_vector (511 downto 0);
    s_axi_dcs_odd_wstrb   : out std_logic_vector (63 downto 0);
    s_axi_dcs_odd_wlast   : out std_logic;
    s_axi_dcs_odd_wvalid  : out std_logic;
    s_axi_dcs_odd_wready  : in std_logic;
    s_axi_dcs_odd_bid     : in std_logic_vector( 6 downto 0);
    s_axi_dcs_odd_bresp   : in std_logic_vector( 1 downto 0);
    s_axi_dcs_odd_bvalid  : in std_logic;
    s_axi_dcs_odd_bready  : out std_logic;

    -- DC interface: odd LCL bus
    dcsOdd_cleanMaybeInvReq_valid   : out std_logic;
    dcsOdd_cleanMaybeInvReq_ready   : in std_logic;
    dcsOdd_cleanMaybeInvReq_payload_data    : out std_logic_vector(63 downto 0);
    dcsOdd_cleanMaybeInvReq_payload_size    : out std_logic_vector(5 downto 0);
    dcsOdd_cleanMaybeInvReq_payload_vc      : out std_logic_vector(5 downto 0);

    dcsOdd_cleanMaybeInvResp_valid   : in std_logic;
    dcsOdd_cleanMaybeInvResp_ready   : out std_logic;
    dcsOdd_cleanMaybeInvResp_payload_data    : in std_logic_vector(63 downto 0);
    dcsOdd_cleanMaybeInvResp_payload_size    : in std_logic_vector(5 downto 0);
    dcsOdd_cleanMaybeInvResp_payload_vc      : in std_logic_vector(5 downto 0);

    dcsOdd_unlockResp_valid   : out std_logic;
    dcsOdd_unlockResp_ready   : in std_logic;
    dcsOdd_unlockResp_payload_data    : out std_logic_vector(63 downto 0);
    dcsOdd_unlockResp_payload_size    : out std_logic_vector(5 downto 0);
    dcsOdd_unlockResp_payload_vc      : out std_logic_vector(5 downto 0);

    -- DC interface: even AXI slave
    s_axi_dcs_even_arid    : out std_logic_vector( 6 downto 0);
    s_axi_dcs_even_araddr  : out std_logic_vector(37 downto 0);
    s_axi_dcs_even_arlen   : out std_logic_vector( 7 downto 0);
    s_axi_dcs_even_arsize  : out std_logic_vector( 2 downto 0);
    s_axi_dcs_even_arburst : out std_logic_vector( 1 downto 0);
    s_axi_dcs_even_arlock  : out std_logic;
    s_axi_dcs_even_arcache : out std_logic_vector( 3 downto 0);
    s_axi_dcs_even_arprot  : out std_logic_vector( 2 downto 0);
    s_axi_dcs_even_arvalid : out std_logic;
    s_axi_dcs_even_arready : in std_logic;
    s_axi_dcs_even_rid     : in std_logic_vector( 6 downto 0);
    s_axi_dcs_even_rdata   : in std_logic_vector(511 downto 0);
    s_axi_dcs_even_rresp   : in std_logic_vector( 1 downto 0);
    s_axi_dcs_even_rlast   : in std_logic;
    s_axi_dcs_even_rvalid  : in std_logic;
    s_axi_dcs_even_rready  : out std_logic;

    s_axi_dcs_even_awid    : out std_logic_vector ( 6 downto 0);
    s_axi_dcs_even_awaddr  : out std_logic_vector (37 downto 0);
    s_axi_dcs_even_awlen   : out std_logic_vector ( 7 downto 0);
    s_axi_dcs_even_awsize  : out std_logic_vector ( 2 downto 0);
    s_axi_dcs_even_awburst : out std_logic_vector ( 1 downto 0);
    s_axi_dcs_even_awlock  : out std_logic;
    s_axi_dcs_even_awcache : out std_logic_vector ( 3 downto 0);
    s_axi_dcs_even_awprot  : out std_logic_vector ( 2 downto 0);
    s_axi_dcs_even_awvalid : out std_logic;
    s_axi_dcs_even_awready : in std_logic ;
    s_axi_dcs_even_wdata   : out std_logic_vector (511 downto 0);
    s_axi_dcs_even_wstrb   : out std_logic_vector (63 downto 0);
    s_axi_dcs_even_wlast   : out std_logic;
    s_axi_dcs_even_wvalid  : out std_logic;
    s_axi_dcs_even_wready  : in std_logic;
    s_axi_dcs_even_bid     : in std_logic_vector( 6 downto 0);
    s_axi_dcs_even_bresp   : in std_logic_vector( 1 downto 0);
    s_axi_dcs_even_bvalid  : in std_logic;
    s_axi_dcs_even_bready  : out std_logic;

    -- DC interface: even LCL bus
    dcsEven_cleanMaybeInvReq_valid   : out std_logic;
    dcsEven_cleanMaybeInvReq_ready   : in std_logic;
    dcsEven_cleanMaybeInvReq_payload_data    : out std_logic_vector(63 downto 0);
    dcsEven_cleanMaybeInvReq_payload_size    : out std_logic_vector(5 downto 0);
    dcsEven_cleanMaybeInvReq_payload_vc      : out std_logic_vector(5 downto 0);

    dcsEven_cleanMaybeInvResp_valid   : in std_logic;
    dcsEven_cleanMaybeInvResp_ready   : out std_logic;
    dcsEven_cleanMaybeInvResp_payload_data    : in std_logic_vector(63 downto 0);
    dcsEven_cleanMaybeInvResp_payload_size    : in std_logic_vector(5 downto 0);
    dcsEven_cleanMaybeInvResp_payload_vc      : in std_logic_vector(5 downto 0);

    dcsEven_unlockResp_valid   : out std_logic;
    dcsEven_unlockResp_ready   : in std_logic;
    dcsEven_unlockResp_payload_data    : out std_logic_vector(63 downto 0);
    dcsEven_unlockResp_payload_size    : out std_logic_vector(5 downto 0);
    dcsEven_unlockResp_payload_vc      : out std_logic_vector(5 downto 0);

    -- control registers: CPU -> FPGA
    s_ctrl_axil_awaddr        : in std_logic_vector(43 downto 0);
    s_ctrl_axil_awprot        : in std_logic_vector(2 downto 0);
    s_ctrl_axil_awvalid       : in std_logic;
    s_ctrl_axil_awready       : out std_logic;
    s_ctrl_axil_wdata         : in std_logic_vector(63 downto 0);
    s_ctrl_axil_wstrb         : in std_logic_vector(7 downto 0);
    s_ctrl_axil_wvalid        : in std_logic;
    s_ctrl_axil_wready        : out std_logic;
    s_ctrl_axil_bresp         : out std_logic_vector(1 downto 0);
    s_ctrl_axil_bvalid        : out std_logic;
    s_ctrl_axil_bready        : in std_logic;
    s_ctrl_axil_araddr        : in std_logic_vector(43 downto 0);
    s_ctrl_axil_arprot        : in std_logic_vector(2 downto 0);
    s_ctrl_axil_arvalid       : in std_logic;
    s_ctrl_axil_arready       : out  std_logic;
    s_ctrl_axil_rdata         : out std_logic_vector(63 downto 0);
    s_ctrl_axil_rresp         : out std_logic_vector(1 downto 0);
    s_ctrl_axil_rvalid        : out std_logic;
    s_ctrl_axil_rready        : in std_logic
);
end component;

COMPONENT cmac_usplus_0
  PORT (
    gt_txp_out : OUT STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txn_out : OUT STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxp_in : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxn_in : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txusrclk2 : OUT STD_LOGIC;
    gt_loopback_in : IN STD_LOGIC_VECTOR(11 DOWNTO 0);
    gt_eyescanreset : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_eyescantrigger : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxcdrhold : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxpolarity : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxrate : IN STD_LOGIC_VECTOR(11 DOWNTO 0);
    gt_txdiffctrl : IN STD_LOGIC_VECTOR(19 DOWNTO 0);
    gt_txpolarity : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txinhibit : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txpippmen : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txpippmsel : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txpostcursor : IN STD_LOGIC_VECTOR(19 DOWNTO 0);
    gt_txprbsforceerr : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txprecursor : IN STD_LOGIC_VECTOR(19 DOWNTO 0);
    gt_eyescandataerror : OUT STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_ref_clk_out : OUT STD_LOGIC;
    gt_rxrecclkout : OUT STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_powergoodout : OUT STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txbufstatus : OUT STD_LOGIC_VECTOR(7 DOWNTO 0);
    gt_rxdfelpmreset : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxlpmen : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxprbscntreset : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxprbserr : OUT STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxprbssel : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt_rxresetdone : OUT STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_txprbssel : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt_txresetdone : OUT STD_LOGIC_VECTOR(3 DOWNTO 0);
    gt_rxbufstatus : OUT STD_LOGIC_VECTOR(11 DOWNTO 0);
    gtwiz_reset_tx_datapath : IN STD_LOGIC;
    gtwiz_reset_rx_datapath : IN STD_LOGIC;
    gt_drpclk : IN STD_LOGIC;
    gt0_drpdo : OUT STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt0_drprdy : OUT STD_LOGIC;
    gt0_drpen : IN STD_LOGIC;
    gt0_drpwe : IN STD_LOGIC;
    gt0_drpaddr : IN STD_LOGIC_VECTOR(9 DOWNTO 0);
    gt0_drpdi : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt1_drpdo : OUT STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt1_drprdy : OUT STD_LOGIC;
    gt1_drpen : IN STD_LOGIC;
    gt1_drpwe : IN STD_LOGIC;
    gt1_drpaddr : IN STD_LOGIC_VECTOR(9 DOWNTO 0);
    gt1_drpdi : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt2_drpdo : OUT STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt2_drprdy : OUT STD_LOGIC;
    gt2_drpen : IN STD_LOGIC;
    gt2_drpwe : IN STD_LOGIC;
    gt2_drpaddr : IN STD_LOGIC_VECTOR(9 DOWNTO 0);
    gt2_drpdi : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt3_drpdo : OUT STD_LOGIC_VECTOR(15 DOWNTO 0);
    gt3_drprdy : OUT STD_LOGIC;
    gt3_drpen : IN STD_LOGIC;
    gt3_drpwe : IN STD_LOGIC;
    gt3_drpaddr : IN STD_LOGIC_VECTOR(9 DOWNTO 0);
    gt3_drpdi : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    s_axi_aclk : IN STD_LOGIC;
    s_axi_sreset : IN STD_LOGIC;
    pm_tick : IN STD_LOGIC;
    s_axi_awaddr : IN STD_LOGIC_VECTOR(31 DOWNTO 0);
    s_axi_awvalid : IN STD_LOGIC;
    s_axi_awready : OUT STD_LOGIC;
    s_axi_wdata : IN STD_LOGIC_VECTOR(31 DOWNTO 0);
    s_axi_wstrb : IN STD_LOGIC_VECTOR(3 DOWNTO 0);
    s_axi_wvalid : IN STD_LOGIC;
    s_axi_wready : OUT STD_LOGIC;
    s_axi_bresp : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    s_axi_bvalid : OUT STD_LOGIC;
    s_axi_bready : IN STD_LOGIC;
    s_axi_araddr : IN STD_LOGIC_VECTOR(31 DOWNTO 0);
    s_axi_arvalid : IN STD_LOGIC;
    s_axi_arready : OUT STD_LOGIC;
    s_axi_rdata : OUT STD_LOGIC_VECTOR(31 DOWNTO 0);
    s_axi_rresp : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    s_axi_rvalid : OUT STD_LOGIC;
    s_axi_rready : IN STD_LOGIC;
    user_reg0 : OUT STD_LOGIC_VECTOR(31 DOWNTO 0);
    sys_reset : IN STD_LOGIC;
    gt_ref_clk_p : IN STD_LOGIC;
    gt_ref_clk_n : IN STD_LOGIC;
    init_clk : IN STD_LOGIC;
    common0_drpaddr : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    common0_drpdi : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    common0_drpwe : IN STD_LOGIC;
    common0_drpen : IN STD_LOGIC;
    common0_drprdy : OUT STD_LOGIC;
    common0_drpdo : OUT STD_LOGIC_VECTOR(15 DOWNTO 0);
    rx_axis_tvalid : OUT STD_LOGIC;
    rx_axis_tdata : OUT STD_LOGIC_VECTOR(511 DOWNTO 0);
    rx_axis_tlast : OUT STD_LOGIC;
    rx_axis_tkeep : OUT STD_LOGIC_VECTOR(63 DOWNTO 0);
    rx_axis_tuser : OUT STD_LOGIC;
    rx_otn_bip8_0 : OUT STD_LOGIC_VECTOR(7 DOWNTO 0);
    rx_otn_bip8_1 : OUT STD_LOGIC_VECTOR(7 DOWNTO 0);
    rx_otn_bip8_2 : OUT STD_LOGIC_VECTOR(7 DOWNTO 0);
    rx_otn_bip8_3 : OUT STD_LOGIC_VECTOR(7 DOWNTO 0);
    rx_otn_bip8_4 : OUT STD_LOGIC_VECTOR(7 DOWNTO 0);
    rx_otn_data_0 : OUT STD_LOGIC_VECTOR(65 DOWNTO 0);
    rx_otn_data_1 : OUT STD_LOGIC_VECTOR(65 DOWNTO 0);
    rx_otn_data_2 : OUT STD_LOGIC_VECTOR(65 DOWNTO 0);
    rx_otn_data_3 : OUT STD_LOGIC_VECTOR(65 DOWNTO 0);
    rx_otn_data_4 : OUT STD_LOGIC_VECTOR(65 DOWNTO 0);
    rx_otn_ena : OUT STD_LOGIC;
    rx_otn_lane0 : OUT STD_LOGIC;
    rx_otn_vlmarker : OUT STD_LOGIC;
    rx_preambleout : OUT STD_LOGIC_VECTOR(55 DOWNTO 0);
    usr_rx_reset : OUT STD_LOGIC;
    gt_rxusrclk2 : OUT STD_LOGIC;
    stat_rx_aligned : OUT STD_LOGIC;
    stat_rx_aligned_err : OUT STD_LOGIC;
    stat_rx_bad_code : OUT STD_LOGIC_VECTOR(2 DOWNTO 0);
    stat_rx_bad_fcs : OUT STD_LOGIC_VECTOR(2 DOWNTO 0);
    stat_rx_bad_preamble : OUT STD_LOGIC;
    stat_rx_bad_sfd : OUT STD_LOGIC;
    stat_rx_bip_err_0 : OUT STD_LOGIC;
    stat_rx_bip_err_1 : OUT STD_LOGIC;
    stat_rx_bip_err_10 : OUT STD_LOGIC;
    stat_rx_bip_err_11 : OUT STD_LOGIC;
    stat_rx_bip_err_12 : OUT STD_LOGIC;
    stat_rx_bip_err_13 : OUT STD_LOGIC;
    stat_rx_bip_err_14 : OUT STD_LOGIC;
    stat_rx_bip_err_15 : OUT STD_LOGIC;
    stat_rx_bip_err_16 : OUT STD_LOGIC;
    stat_rx_bip_err_17 : OUT STD_LOGIC;
    stat_rx_bip_err_18 : OUT STD_LOGIC;
    stat_rx_bip_err_19 : OUT STD_LOGIC;
    stat_rx_bip_err_2 : OUT STD_LOGIC;
    stat_rx_bip_err_3 : OUT STD_LOGIC;
    stat_rx_bip_err_4 : OUT STD_LOGIC;
    stat_rx_bip_err_5 : OUT STD_LOGIC;
    stat_rx_bip_err_6 : OUT STD_LOGIC;
    stat_rx_bip_err_7 : OUT STD_LOGIC;
    stat_rx_bip_err_8 : OUT STD_LOGIC;
    stat_rx_bip_err_9 : OUT STD_LOGIC;
    stat_rx_block_lock : OUT STD_LOGIC_VECTOR(19 DOWNTO 0);
    stat_rx_broadcast : OUT STD_LOGIC;
    stat_rx_fragment : OUT STD_LOGIC_VECTOR(2 DOWNTO 0);
    stat_rx_framing_err_0 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_1 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_10 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_11 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_12 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_13 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_14 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_15 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_16 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_17 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_18 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_19 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_2 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_3 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_4 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_5 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_6 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_7 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_8 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_9 : OUT STD_LOGIC_VECTOR(1 DOWNTO 0);
    stat_rx_framing_err_valid_0 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_1 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_10 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_11 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_12 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_13 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_14 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_15 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_16 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_17 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_18 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_19 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_2 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_3 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_4 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_5 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_6 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_7 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_8 : OUT STD_LOGIC;
    stat_rx_framing_err_valid_9 : OUT STD_LOGIC;
    stat_rx_got_signal_os : OUT STD_LOGIC;
    stat_rx_hi_ber : OUT STD_LOGIC;
    stat_rx_inrangeerr : OUT STD_LOGIC;
    stat_rx_internal_local_fault : OUT STD_LOGIC;
    stat_rx_jabber : OUT STD_LOGIC;
    stat_rx_local_fault : OUT STD_LOGIC;
    stat_rx_mf_err : OUT STD_LOGIC_VECTOR(19 DOWNTO 0);
    stat_rx_mf_len_err : OUT STD_LOGIC_VECTOR(19 DOWNTO 0);
    stat_rx_mf_repeat_err : OUT STD_LOGIC_VECTOR(19 DOWNTO 0);
    stat_rx_misaligned : OUT STD_LOGIC;
    stat_rx_multicast : OUT STD_LOGIC;
    stat_rx_oversize : OUT STD_LOGIC;
    stat_rx_packet_1024_1518_bytes : OUT STD_LOGIC;
    stat_rx_packet_128_255_bytes : OUT STD_LOGIC;
    stat_rx_packet_1519_1522_bytes : OUT STD_LOGIC;
    stat_rx_packet_1523_1548_bytes : OUT STD_LOGIC;
    stat_rx_packet_1549_2047_bytes : OUT STD_LOGIC;
    stat_rx_packet_2048_4095_bytes : OUT STD_LOGIC;
    stat_rx_packet_256_511_bytes : OUT STD_LOGIC;
    stat_rx_packet_4096_8191_bytes : OUT STD_LOGIC;
    stat_rx_packet_512_1023_bytes : OUT STD_LOGIC;
    stat_rx_packet_64_bytes : OUT STD_LOGIC;
    stat_rx_packet_65_127_bytes : OUT STD_LOGIC;
    stat_rx_packet_8192_9215_bytes : OUT STD_LOGIC;
    stat_rx_packet_bad_fcs : OUT STD_LOGIC;
    stat_rx_packet_large : OUT STD_LOGIC;
    stat_rx_packet_small : OUT STD_LOGIC_VECTOR(2 DOWNTO 0);
    core_rx_reset : IN STD_LOGIC;
    rx_clk : IN STD_LOGIC;
    stat_rx_received_local_fault : OUT STD_LOGIC;
    stat_rx_remote_fault : OUT STD_LOGIC;
    stat_rx_status : OUT STD_LOGIC;
    stat_rx_stomped_fcs : OUT STD_LOGIC_VECTOR(2 DOWNTO 0);
    stat_rx_synced : OUT STD_LOGIC_VECTOR(19 DOWNTO 0);
    stat_rx_synced_err : OUT STD_LOGIC_VECTOR(19 DOWNTO 0);
    stat_rx_test_pattern_mismatch : OUT STD_LOGIC_VECTOR(2 DOWNTO 0);
    stat_rx_toolong : OUT STD_LOGIC;
    stat_rx_total_bytes : OUT STD_LOGIC_VECTOR(6 DOWNTO 0);
    stat_rx_total_good_bytes : OUT STD_LOGIC_VECTOR(13 DOWNTO 0);
    stat_rx_total_good_packets : OUT STD_LOGIC;
    stat_rx_total_packets : OUT STD_LOGIC_VECTOR(2 DOWNTO 0);
    stat_rx_truncated : OUT STD_LOGIC;
    stat_rx_undersize : OUT STD_LOGIC_VECTOR(2 DOWNTO 0);
    stat_rx_unicast : OUT STD_LOGIC;
    stat_rx_vlan : OUT STD_LOGIC;
    stat_rx_pcsl_demuxed : OUT STD_LOGIC_VECTOR(19 DOWNTO 0);
    stat_rx_pcsl_number_0 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_1 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_10 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_11 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_12 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_13 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_14 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_15 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_16 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_17 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_18 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_19 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_2 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_3 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_4 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_5 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_6 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_7 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_8 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_rx_pcsl_number_9 : OUT STD_LOGIC_VECTOR(4 DOWNTO 0);
    stat_tx_bad_fcs : OUT STD_LOGIC;
    stat_tx_broadcast : OUT STD_LOGIC;
    stat_tx_frame_error : OUT STD_LOGIC;
    stat_tx_local_fault : OUT STD_LOGIC;
    stat_tx_multicast : OUT STD_LOGIC;
    stat_tx_packet_1024_1518_bytes : OUT STD_LOGIC;
    stat_tx_packet_128_255_bytes : OUT STD_LOGIC;
    stat_tx_packet_1519_1522_bytes : OUT STD_LOGIC;
    stat_tx_packet_1523_1548_bytes : OUT STD_LOGIC;
    stat_tx_packet_1549_2047_bytes : OUT STD_LOGIC;
    stat_tx_packet_2048_4095_bytes : OUT STD_LOGIC;
    stat_tx_packet_256_511_bytes : OUT STD_LOGIC;
    stat_tx_packet_4096_8191_bytes : OUT STD_LOGIC;
    stat_tx_packet_512_1023_bytes : OUT STD_LOGIC;
    stat_tx_packet_64_bytes : OUT STD_LOGIC;
    stat_tx_packet_65_127_bytes : OUT STD_LOGIC;
    stat_tx_packet_8192_9215_bytes : OUT STD_LOGIC;
    stat_tx_packet_large : OUT STD_LOGIC;
    stat_tx_packet_small : OUT STD_LOGIC;
    stat_tx_total_bytes : OUT STD_LOGIC_VECTOR(5 DOWNTO 0);
    stat_tx_total_good_bytes : OUT STD_LOGIC_VECTOR(13 DOWNTO 0);
    stat_tx_total_good_packets : OUT STD_LOGIC;
    stat_tx_total_packets : OUT STD_LOGIC;
    stat_tx_unicast : OUT STD_LOGIC;
    stat_tx_vlan : OUT STD_LOGIC;
    ctl_tx_send_idle : IN STD_LOGIC;
    ctl_tx_send_rfi : IN STD_LOGIC;
    ctl_tx_send_lfi : IN STD_LOGIC;
    core_tx_reset : IN STD_LOGIC;
    tx_axis_tready : OUT STD_LOGIC;
    tx_axis_tvalid : IN STD_LOGIC;
    tx_axis_tdata : IN STD_LOGIC_VECTOR(511 DOWNTO 0);
    tx_axis_tlast : IN STD_LOGIC;
    tx_axis_tkeep : IN STD_LOGIC_VECTOR(63 DOWNTO 0);
    tx_axis_tuser : IN STD_LOGIC;
    tx_ovfout : OUT STD_LOGIC;
    tx_unfout : OUT STD_LOGIC;
    tx_preamblein : IN STD_LOGIC_VECTOR(55 DOWNTO 0);
    usr_tx_reset : OUT STD_LOGIC;
    core_drp_reset : IN STD_LOGIC;
    drp_clk : IN STD_LOGIC;
    drp_addr : IN STD_LOGIC_VECTOR(9 DOWNTO 0);
    drp_di : IN STD_LOGIC_VECTOR(15 DOWNTO 0);
    drp_en : IN STD_LOGIC;
    drp_do : OUT STD_LOGIC_VECTOR(15 DOWNTO 0);
    drp_rdy : OUT STD_LOGIC;
    drp_we : IN STD_LOGIC 
  );
END COMPONENT;

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

signal m0_bscan : BSCAN;

signal link_eci_packet_rx : ECI_PACKET_RX;
signal link_eci_packet_tx : ECI_PACKET_TX;

signal clk, clk_io : std_logic;
signal reset : std_logic;
signal reset_n : std_logic;

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
clk_io_out <= clk_io;

i_eci_gateway : eci_gateway
  generic map (
    TX_NO_CHANNELS      => 8,
    RX_NO_CHANNELS      => 9,
    RX_FILTER_VC        => ("00000100000", "00000010000", "00000110000", "00000100000", "00000010000", "01000000000", "00100000000", "00000001000", "00000000100"),
    RX_FILTER_TYPE_MASK => ("11111", "11111", "11111", "00000", "00000", "00000", "00000", "00000", "00000"),
    RX_FILTER_TYPE      => ("11000", "11000", "10100", "00000", "00000", "00000", "00000", "00000", "00000"),
    RX_FILTER_CLI_MASK  => ((others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0')),
    RX_FILTER_CLI       => ((others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'), (others => '0'))
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

    rx_eci_channels_ready(0)   => link_eci_packet_rx.c7_gsync_ready,
    rx_eci_channels_ready(1)   => link_eci_packet_rx.c6_gsync_ready,
    rx_eci_channels_ready(2)   => '1',
    rx_eci_channels_ready(3)   => link_eci_packet_rx.dcs_c7_ready,
    rx_eci_channels_ready(4)   => link_eci_packet_rx.dcs_c6_ready,
    rx_eci_channels_ready(5)   => link_eci_packet_rx.dcs_c11_ready,
    rx_eci_channels_ready(6)   => link_eci_packet_rx.dcs_c10_ready,
    rx_eci_channels_ready(7)   => link_eci_packet_rx.dcs_c5_ready,
    rx_eci_channels_ready(8)   => link_eci_packet_rx.dcs_c4_ready,

    tx_eci_channels(0)      => link_eci_packet_tx.c11_gsync,
    tx_eci_channels(1)      => link_eci_packet_tx.c10_gsync,
    tx_eci_channels(2)      => link_eci_packet_tx.dcs_c11,
    tx_eci_channels(3)      => link_eci_packet_tx.dcs_c5,
    tx_eci_channels(4)      => link_eci_packet_tx.dcs_c10,
    tx_eci_channels(5)      => link_eci_packet_tx.dcs_c4,
    tx_eci_channels(6)      => link_eci_packet_tx.dcs_c8,
    tx_eci_channels(7)      => link_eci_packet_tx.dcs_c9,

    tx_eci_channels_ready(0)   => link_eci_packet_tx.c11_gsync_ready,
    tx_eci_channels_ready(1)   => link_eci_packet_tx.c10_gsync_ready,
    tx_eci_channels_ready(2)   => link_eci_packet_tx.dcs_c11_ready,
    tx_eci_channels_ready(3)   => link_eci_packet_tx.dcs_c5_ready,
    tx_eci_channels_ready(4)   => link_eci_packet_tx.dcs_c10_ready,
    tx_eci_channels_ready(5)   => link_eci_packet_tx.dcs_c4_ready,
    tx_eci_channels_ready(6)   => link_eci_packet_tx.dcs_c8_ready,
    tx_eci_channels_ready(7)   => link_eci_packet_tx.dcs_c9_ready
);

-- GSYNC response handler, sends GSDN.
-- Odd VCs, GSYNC arrives in VC7 and GSDN sent in VC11.
vc7_vc11_gsync_loopback : loopback_vc_resp_nodata
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
vc6_vc10_gsync_loopback : loopback_vc_resp_nodata
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
i_dcs_c5_eci_channel_to_bus : eci_channel_bus_converter
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
i_dcs_c5_bus_to_eci_channel : eci_bus_channel_converter
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
i_dcs_c4_eci_channel_to_bus : eci_channel_bus_converter
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
i_dcs_c4_bus_to_eci_channel : eci_bus_channel_converter
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
dcs_even : dcs_2_axi
port map (
  clk   => clk,
  reset => reset,

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
dcs_odd : dcs_2_axi
port map (
  clk   => clk,
  reset => reset,

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

cmac : cmac_usplus_0
  PORT MAP (
    gt_txp_out => gt_txp_out,
    gt_txn_out => gt_txn_out,
    gt_rxp_in => gt_rxp_in,
    gt_rxn_in => gt_rxn_in,
    gt_txusrclk2 => gt_txusrclk2,
    gt_loopback_in => gt_loopback_in,
    gt_eyescanreset => gt_eyescanreset,
    gt_eyescantrigger => gt_eyescantrigger,
    gt_rxcdrhold => gt_rxcdrhold,
    gt_rxpolarity => gt_rxpolarity,
    gt_rxrate => gt_rxrate,
    gt_txdiffctrl => gt_txdiffctrl,
    gt_txpolarity => gt_txpolarity,
    gt_txinhibit => gt_txinhibit,
    gt_txpippmen => gt_txpippmen,
    gt_txpippmsel => gt_txpippmsel,
    gt_txpostcursor => gt_txpostcursor,
    gt_txprbsforceerr => gt_txprbsforceerr,
    gt_txprecursor => gt_txprecursor,
    gt_eyescandataerror => gt_eyescandataerror,
    gt_ref_clk_out => gt_ref_clk_out,
    gt_rxrecclkout => gt_rxrecclkout,
    gt_powergoodout => gt_powergoodout,
    gt_txbufstatus => gt_txbufstatus,
    gt_rxdfelpmreset => gt_rxdfelpmreset,
    gt_rxlpmen => gt_rxlpmen,
    gt_rxprbscntreset => gt_rxprbscntreset,
    gt_rxprbserr => gt_rxprbserr,
    gt_rxprbssel => gt_rxprbssel,
    gt_rxresetdone => gt_rxresetdone,
    gt_txprbssel => gt_txprbssel,
    gt_txresetdone => gt_txresetdone,
    gt_rxbufstatus => gt_rxbufstatus,
    gtwiz_reset_tx_datapath => gtwiz_reset_tx_datapath,
    gtwiz_reset_rx_datapath => gtwiz_reset_rx_datapath,
    gt_drpclk => gt_drpclk,
    gt0_drpdo => gt0_drpdo,
    gt0_drprdy => gt0_drprdy,
    gt0_drpen => gt0_drpen,
    gt0_drpwe => gt0_drpwe,
    gt0_drpaddr => gt0_drpaddr,
    gt0_drpdi => gt0_drpdi,
    gt1_drpdo => gt1_drpdo,
    gt1_drprdy => gt1_drprdy,
    gt1_drpen => gt1_drpen,
    gt1_drpwe => gt1_drpwe,
    gt1_drpaddr => gt1_drpaddr,
    gt1_drpdi => gt1_drpdi,
    gt2_drpdo => gt2_drpdo,
    gt2_drprdy => gt2_drprdy,
    gt2_drpen => gt2_drpen,
    gt2_drpwe => gt2_drpwe,
    gt2_drpaddr => gt2_drpaddr,
    gt2_drpdi => gt2_drpdi,
    gt3_drpdo => gt3_drpdo,
    gt3_drprdy => gt3_drprdy,
    gt3_drpen => gt3_drpen,
    gt3_drpwe => gt3_drpwe,
    gt3_drpaddr => gt3_drpaddr,
    gt3_drpdi => gt3_drpdi,
    s_axi_aclk => s_axi_aclk,
    s_axi_sreset => s_axi_sreset,
    pm_tick => pm_tick,
    s_axi_awaddr => s_axi_awaddr,
    s_axi_awvalid => s_axi_awvalid,
    s_axi_awready => s_axi_awready,
    s_axi_wdata => s_axi_wdata,
    s_axi_wstrb => s_axi_wstrb,
    s_axi_wvalid => s_axi_wvalid,
    s_axi_wready => s_axi_wready,
    s_axi_bresp => s_axi_bresp,
    s_axi_bvalid => s_axi_bvalid,
    s_axi_bready => s_axi_bready,
    s_axi_araddr => s_axi_araddr,
    s_axi_arvalid => s_axi_arvalid,
    s_axi_arready => s_axi_arready,
    s_axi_rdata => s_axi_rdata,
    s_axi_rresp => s_axi_rresp,
    s_axi_rvalid => s_axi_rvalid,
    s_axi_rready => s_axi_rready,
    user_reg0 => user_reg0,
    sys_reset => sys_reset,
    gt_ref_clk_p => gt_ref_clk_p,
    gt_ref_clk_n => gt_ref_clk_n,
    init_clk => init_clk,
    common0_drpaddr => common0_drpaddr,
    common0_drpdi => common0_drpdi,
    common0_drpwe => common0_drpwe,
    common0_drpen => common0_drpen,
    common0_drprdy => common0_drprdy,
    common0_drpdo => common0_drpdo,
    rx_axis_tvalid => rx_axis_tvalid,
    rx_axis_tdata => rx_axis_tdata,
    rx_axis_tlast => rx_axis_tlast,
    rx_axis_tkeep => rx_axis_tkeep,
    rx_axis_tuser => rx_axis_tuser,
    rx_otn_bip8_0 => rx_otn_bip8_0,
    rx_otn_bip8_1 => rx_otn_bip8_1,
    rx_otn_bip8_2 => rx_otn_bip8_2,
    rx_otn_bip8_3 => rx_otn_bip8_3,
    rx_otn_bip8_4 => rx_otn_bip8_4,
    rx_otn_data_0 => rx_otn_data_0,
    rx_otn_data_1 => rx_otn_data_1,
    rx_otn_data_2 => rx_otn_data_2,
    rx_otn_data_3 => rx_otn_data_3,
    rx_otn_data_4 => rx_otn_data_4,
    rx_otn_ena => rx_otn_ena,
    rx_otn_lane0 => rx_otn_lane0,
    rx_otn_vlmarker => rx_otn_vlmarker,
    rx_preambleout => rx_preambleout,
    usr_rx_reset => usr_rx_reset,
    gt_rxusrclk2 => gt_rxusrclk2,
    stat_rx_aligned => stat_rx_aligned,
    stat_rx_aligned_err => stat_rx_aligned_err,
    stat_rx_bad_code => stat_rx_bad_code,
    stat_rx_bad_fcs => stat_rx_bad_fcs,
    stat_rx_bad_preamble => stat_rx_bad_preamble,
    stat_rx_bad_sfd => stat_rx_bad_sfd,
    stat_rx_bip_err_0 => stat_rx_bip_err_0,
    stat_rx_bip_err_1 => stat_rx_bip_err_1,
    stat_rx_bip_err_10 => stat_rx_bip_err_10,
    stat_rx_bip_err_11 => stat_rx_bip_err_11,
    stat_rx_bip_err_12 => stat_rx_bip_err_12,
    stat_rx_bip_err_13 => stat_rx_bip_err_13,
    stat_rx_bip_err_14 => stat_rx_bip_err_14,
    stat_rx_bip_err_15 => stat_rx_bip_err_15,
    stat_rx_bip_err_16 => stat_rx_bip_err_16,
    stat_rx_bip_err_17 => stat_rx_bip_err_17,
    stat_rx_bip_err_18 => stat_rx_bip_err_18,
    stat_rx_bip_err_19 => stat_rx_bip_err_19,
    stat_rx_bip_err_2 => stat_rx_bip_err_2,
    stat_rx_bip_err_3 => stat_rx_bip_err_3,
    stat_rx_bip_err_4 => stat_rx_bip_err_4,
    stat_rx_bip_err_5 => stat_rx_bip_err_5,
    stat_rx_bip_err_6 => stat_rx_bip_err_6,
    stat_rx_bip_err_7 => stat_rx_bip_err_7,
    stat_rx_bip_err_8 => stat_rx_bip_err_8,
    stat_rx_bip_err_9 => stat_rx_bip_err_9,
    stat_rx_block_lock => stat_rx_block_lock,
    stat_rx_broadcast => stat_rx_broadcast,
    stat_rx_fragment => stat_rx_fragment,
    stat_rx_framing_err_0 => stat_rx_framing_err_0,
    stat_rx_framing_err_1 => stat_rx_framing_err_1,
    stat_rx_framing_err_10 => stat_rx_framing_err_10,
    stat_rx_framing_err_11 => stat_rx_framing_err_11,
    stat_rx_framing_err_12 => stat_rx_framing_err_12,
    stat_rx_framing_err_13 => stat_rx_framing_err_13,
    stat_rx_framing_err_14 => stat_rx_framing_err_14,
    stat_rx_framing_err_15 => stat_rx_framing_err_15,
    stat_rx_framing_err_16 => stat_rx_framing_err_16,
    stat_rx_framing_err_17 => stat_rx_framing_err_17,
    stat_rx_framing_err_18 => stat_rx_framing_err_18,
    stat_rx_framing_err_19 => stat_rx_framing_err_19,
    stat_rx_framing_err_2 => stat_rx_framing_err_2,
    stat_rx_framing_err_3 => stat_rx_framing_err_3,
    stat_rx_framing_err_4 => stat_rx_framing_err_4,
    stat_rx_framing_err_5 => stat_rx_framing_err_5,
    stat_rx_framing_err_6 => stat_rx_framing_err_6,
    stat_rx_framing_err_7 => stat_rx_framing_err_7,
    stat_rx_framing_err_8 => stat_rx_framing_err_8,
    stat_rx_framing_err_9 => stat_rx_framing_err_9,
    stat_rx_framing_err_valid_0 => stat_rx_framing_err_valid_0,
    stat_rx_framing_err_valid_1 => stat_rx_framing_err_valid_1,
    stat_rx_framing_err_valid_10 => stat_rx_framing_err_valid_10,
    stat_rx_framing_err_valid_11 => stat_rx_framing_err_valid_11,
    stat_rx_framing_err_valid_12 => stat_rx_framing_err_valid_12,
    stat_rx_framing_err_valid_13 => stat_rx_framing_err_valid_13,
    stat_rx_framing_err_valid_14 => stat_rx_framing_err_valid_14,
    stat_rx_framing_err_valid_15 => stat_rx_framing_err_valid_15,
    stat_rx_framing_err_valid_16 => stat_rx_framing_err_valid_16,
    stat_rx_framing_err_valid_17 => stat_rx_framing_err_valid_17,
    stat_rx_framing_err_valid_18 => stat_rx_framing_err_valid_18,
    stat_rx_framing_err_valid_19 => stat_rx_framing_err_valid_19,
    stat_rx_framing_err_valid_2 => stat_rx_framing_err_valid_2,
    stat_rx_framing_err_valid_3 => stat_rx_framing_err_valid_3,
    stat_rx_framing_err_valid_4 => stat_rx_framing_err_valid_4,
    stat_rx_framing_err_valid_5 => stat_rx_framing_err_valid_5,
    stat_rx_framing_err_valid_6 => stat_rx_framing_err_valid_6,
    stat_rx_framing_err_valid_7 => stat_rx_framing_err_valid_7,
    stat_rx_framing_err_valid_8 => stat_rx_framing_err_valid_8,
    stat_rx_framing_err_valid_9 => stat_rx_framing_err_valid_9,
    stat_rx_got_signal_os => stat_rx_got_signal_os,
    stat_rx_hi_ber => stat_rx_hi_ber,
    stat_rx_inrangeerr => stat_rx_inrangeerr,
    stat_rx_internal_local_fault => stat_rx_internal_local_fault,
    stat_rx_jabber => stat_rx_jabber,
    stat_rx_local_fault => stat_rx_local_fault,
    stat_rx_mf_err => stat_rx_mf_err,
    stat_rx_mf_len_err => stat_rx_mf_len_err,
    stat_rx_mf_repeat_err => stat_rx_mf_repeat_err,
    stat_rx_misaligned => stat_rx_misaligned,
    stat_rx_multicast => stat_rx_multicast,
    stat_rx_oversize => stat_rx_oversize,
    stat_rx_packet_1024_1518_bytes => stat_rx_packet_1024_1518_bytes,
    stat_rx_packet_128_255_bytes => stat_rx_packet_128_255_bytes,
    stat_rx_packet_1519_1522_bytes => stat_rx_packet_1519_1522_bytes,
    stat_rx_packet_1523_1548_bytes => stat_rx_packet_1523_1548_bytes,
    stat_rx_packet_1549_2047_bytes => stat_rx_packet_1549_2047_bytes,
    stat_rx_packet_2048_4095_bytes => stat_rx_packet_2048_4095_bytes,
    stat_rx_packet_256_511_bytes => stat_rx_packet_256_511_bytes,
    stat_rx_packet_4096_8191_bytes => stat_rx_packet_4096_8191_bytes,
    stat_rx_packet_512_1023_bytes => stat_rx_packet_512_1023_bytes,
    stat_rx_packet_64_bytes => stat_rx_packet_64_bytes,
    stat_rx_packet_65_127_bytes => stat_rx_packet_65_127_bytes,
    stat_rx_packet_8192_9215_bytes => stat_rx_packet_8192_9215_bytes,
    stat_rx_packet_bad_fcs => stat_rx_packet_bad_fcs,
    stat_rx_packet_large => stat_rx_packet_large,
    stat_rx_packet_small => stat_rx_packet_small,
    core_rx_reset => core_rx_reset,
    rx_clk => rx_clk,
    stat_rx_received_local_fault => stat_rx_received_local_fault,
    stat_rx_remote_fault => stat_rx_remote_fault,
    stat_rx_status => stat_rx_status,
    stat_rx_stomped_fcs => stat_rx_stomped_fcs,
    stat_rx_synced => stat_rx_synced,
    stat_rx_synced_err => stat_rx_synced_err,
    stat_rx_test_pattern_mismatch => stat_rx_test_pattern_mismatch,
    stat_rx_toolong => stat_rx_toolong,
    stat_rx_total_bytes => stat_rx_total_bytes,
    stat_rx_total_good_bytes => stat_rx_total_good_bytes,
    stat_rx_total_good_packets => stat_rx_total_good_packets,
    stat_rx_total_packets => stat_rx_total_packets,
    stat_rx_truncated => stat_rx_truncated,
    stat_rx_undersize => stat_rx_undersize,
    stat_rx_unicast => stat_rx_unicast,
    stat_rx_vlan => stat_rx_vlan,
    stat_rx_pcsl_demuxed => stat_rx_pcsl_demuxed,
    stat_rx_pcsl_number_0 => stat_rx_pcsl_number_0,
    stat_rx_pcsl_number_1 => stat_rx_pcsl_number_1,
    stat_rx_pcsl_number_10 => stat_rx_pcsl_number_10,
    stat_rx_pcsl_number_11 => stat_rx_pcsl_number_11,
    stat_rx_pcsl_number_12 => stat_rx_pcsl_number_12,
    stat_rx_pcsl_number_13 => stat_rx_pcsl_number_13,
    stat_rx_pcsl_number_14 => stat_rx_pcsl_number_14,
    stat_rx_pcsl_number_15 => stat_rx_pcsl_number_15,
    stat_rx_pcsl_number_16 => stat_rx_pcsl_number_16,
    stat_rx_pcsl_number_17 => stat_rx_pcsl_number_17,
    stat_rx_pcsl_number_18 => stat_rx_pcsl_number_18,
    stat_rx_pcsl_number_19 => stat_rx_pcsl_number_19,
    stat_rx_pcsl_number_2 => stat_rx_pcsl_number_2,
    stat_rx_pcsl_number_3 => stat_rx_pcsl_number_3,
    stat_rx_pcsl_number_4 => stat_rx_pcsl_number_4,
    stat_rx_pcsl_number_5 => stat_rx_pcsl_number_5,
    stat_rx_pcsl_number_6 => stat_rx_pcsl_number_6,
    stat_rx_pcsl_number_7 => stat_rx_pcsl_number_7,
    stat_rx_pcsl_number_8 => stat_rx_pcsl_number_8,
    stat_rx_pcsl_number_9 => stat_rx_pcsl_number_9,
    stat_tx_bad_fcs => stat_tx_bad_fcs,
    stat_tx_broadcast => stat_tx_broadcast,
    stat_tx_frame_error => stat_tx_frame_error,
    stat_tx_local_fault => stat_tx_local_fault,
    stat_tx_multicast => stat_tx_multicast,
    stat_tx_packet_1024_1518_bytes => stat_tx_packet_1024_1518_bytes,
    stat_tx_packet_128_255_bytes => stat_tx_packet_128_255_bytes,
    stat_tx_packet_1519_1522_bytes => stat_tx_packet_1519_1522_bytes,
    stat_tx_packet_1523_1548_bytes => stat_tx_packet_1523_1548_bytes,
    stat_tx_packet_1549_2047_bytes => stat_tx_packet_1549_2047_bytes,
    stat_tx_packet_2048_4095_bytes => stat_tx_packet_2048_4095_bytes,
    stat_tx_packet_256_511_bytes => stat_tx_packet_256_511_bytes,
    stat_tx_packet_4096_8191_bytes => stat_tx_packet_4096_8191_bytes,
    stat_tx_packet_512_1023_bytes => stat_tx_packet_512_1023_bytes,
    stat_tx_packet_64_bytes => stat_tx_packet_64_bytes,
    stat_tx_packet_65_127_bytes => stat_tx_packet_65_127_bytes,
    stat_tx_packet_8192_9215_bytes => stat_tx_packet_8192_9215_bytes,
    stat_tx_packet_large => stat_tx_packet_large,
    stat_tx_packet_small => stat_tx_packet_small,
    stat_tx_total_bytes => stat_tx_total_bytes,
    stat_tx_total_good_bytes => stat_tx_total_good_bytes,
    stat_tx_total_good_packets => stat_tx_total_good_packets,
    stat_tx_total_packets => stat_tx_total_packets,
    stat_tx_unicast => stat_tx_unicast,
    stat_tx_vlan => stat_tx_vlan,
    ctl_tx_send_idle => ctl_tx_send_idle,
    ctl_tx_send_rfi => ctl_tx_send_rfi,
    ctl_tx_send_lfi => ctl_tx_send_lfi,
    core_tx_reset => core_tx_reset,
    tx_axis_tready => tx_axis_tready,
    tx_axis_tvalid => tx_axis_tvalid,
    tx_axis_tdata => tx_axis_tdata,
    tx_axis_tlast => tx_axis_tlast,
    tx_axis_tkeep => tx_axis_tkeep,
    tx_axis_tuser => tx_axis_tuser,
    tx_ovfout => tx_ovfout,
    tx_unfout => tx_unfout,
    tx_preamblein => tx_preamblein,
    usr_tx_reset => usr_tx_reset,
    core_drp_reset => core_drp_reset,
    drp_clk => drp_clk,
    drp_addr => drp_addr,
    drp_di => drp_di,
    drp_en => drp_en,
    drp_do => drp_do,
    drp_rdy => drp_rdy,
    drp_we => drp_we
  );
  
engine: NicEngine
port map (

);

end Behavioral;