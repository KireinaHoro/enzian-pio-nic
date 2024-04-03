open_project pio-nic-pcie/pio-nic-pcie.xpr

reset_run synth_1
launch_runs -jobs 8 synth_1
wait_on_run synth_1

reset_run impl_1
launch_runs -jobs 8 impl_1
wait_on_run impl_1

open_run impl_1
report_utilization -file utilization.rpt
report_utilization -hierarchical -file utilization_hierarchical.rpt
report_timing_summary -file timing.rpt

write_bitstream pio-nic-pcie/pio-nic-pcie.runs/impl_1/pio-nic-pcie.bit
write_debug_probes pio-nic-pcie/pio-nic-pcie.runs/impl_1/pio-nic-pcie.ltx
