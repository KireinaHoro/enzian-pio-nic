create_clock -period 4.000 -name clk -waveform {0.000 2.000} [get_ports clk]
create_clock -period 4.000 -name cmacRxClock_clk -waveform {0.000 2.000} [get_ports cmacRxClock_clk]
set_clock_groups -asynchronous -group [get_clocks cmacRxClock_clk] -group [get_clocks clk]