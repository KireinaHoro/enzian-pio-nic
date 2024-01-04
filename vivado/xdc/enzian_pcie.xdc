# Set to 100MHz
create_clock -period 10.000 -name pcie_clk_clk_p [get_ports pcie_clk_clk_p]

set_property PACKAGE_PIN W36 [get_ports pcie_clk_clk_p]
set_property PACKAGE_PIN W37 [get_ports pcie_clk_clk_n]

set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]

# CMAC clock
# set in block design so we omit here
# create_clock -period 3.103 -name F_MAC0C_CLK_P [get_ports cmac0_ref_clk_p]
set_property PACKAGE_PIN AV38 [get_ports cmac0_ref_clk_p]
set_property PACKAGE_PIN AV39 [get_ports cmac0_ref_clk_n]

# fabric clocks
set_property PACKAGE_PIN AY26 [get_ports {pgrc0_clk_clk_p}]
set_property PACKAGE_PIN AY27 [get_ports {pgrc0_clk_clk_n}]

# Configured to 100MHz
#create_clock -period 10.000 -name clk_io [get_ports pgrc0_clk_clk_p]

