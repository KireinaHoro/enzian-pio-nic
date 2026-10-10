proc main {mode} {
set label [lindex $::argv 1]
if {![regexp {^[A-Za-z0-9_-]+$} $label]} {error "Expected evidence label"}
open_hw_manager
connect_hw_server -url enzian-gateway:3121
set targets [get_hw_targets */xilinx_tcf/Digilent/210357B4B301A]
if {[llength $targets] != 1} {error "target mismatch"}
current_hw_target [lindex $targets 0]
open_hw_target
set d [get_hw_devices xcvu9p_*]
set_property PROBES.FILE /tmp/lauberhorn-518689/shell_lauberhorn-eci.ltx $d
refresh_hw_device $d
set v [get_hw_vios -filter {CELL_NAME =~ */vio_trace_status}]
set link [get_hw_vios -filter {CELL_NAME =~ */hier_trace_cmac_ctrl_stat/vio_0}]
if {[llength $v] != 1 || [llength $link] != 1} {error "VIO mismatch"}

if {$mode ne "window"} {error "window mode required"}
set stop [get_hw_probes -of_objects $v -filter {NAME =~ */trace_stop}]
set ready 0
for {set i 0} {$i < 240} {incr i} {
 if {[file exists /tmp/lauberhorn-518689/$label-ready]} {set ready 1;break}
 after 500
}
if {!$ready} {error "kernel capture not ready"}
set_property OUTPUT_VALUE 0 $stop
commit_hw_vio $v
after 1000
refresh_hw_vio $v
foreach n {trace_write_slot_1 trace_wrapped_1 trace_sample_lost_1 trace_dma_error_1} {
 puts "WINDOW_START $n [get_property INPUT_VALUE [get_hw_probes -of_objects $v -filter "NAME =~ */$n"]]"
}
flush stdout
set done 0
for {set i 0} {$i < 240} {incr i} {
 if {[file exists /tmp/lauberhorn-518689/$label-done]} {set done 1;break}
 after 500
}
refresh_hw_vio $v
foreach n {trace_write_slot_1 trace_wrapped_1 trace_sample_lost_1 trace_dma_error_1} {
 puts "BEFORE_FREEZE $n [get_property INPUT_VALUE [get_hw_probes -of_objects $v -filter "NAME =~ */$n"]]"
}
set_property OUTPUT_VALUE 1 $stop
commit_hw_vio $v
after 1000
refresh_hw_vio $v
foreach n {trace_write_slot_1 trace_wrapped_1 trace_sample_lost_1 trace_dma_error_1} {
 puts "WINDOW_END $n [get_property INPUT_VALUE [get_hw_probes -of_objects $v -filter "NAME =~ */$n"]]"
}
puts "WINDOW_COMPLETE $done"
close_hw_target
close_hw_manager
if {!$done} {error "workload did not finish; trace frozen"}
}
if {[catch {main [lindex $argv 0]} msg]} {puts stderr $msg;exit 1}
exit 0
