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
set_property PROBES.FILE /tmp/lauberhorn-519555/shell_lauberhorn-eci.ltx $d
refresh_hw_device $d
set v [get_hw_vios -filter {CELL_NAME =~ */vio_trace_status}]
set link [get_hw_vios -filter {CELL_NAME =~ */hier_trace_cmac_ctrl_stat/vio_0}]
if {[llength $v] != 1 || [llength $link] != 1} {error "VIO mismatch"}
if {$mode eq "configure"} {
 set original {}
 refresh_hw_vio $v
 foreach n {trace_write_slot_1 trace_wrapped_1 trace_sample_lost_1 trace_dma_error_1} {
  puts "BEFORE_FREEZE $n [get_property INPUT_VALUE [get_hw_probes -of_objects $v -filter "NAME =~ */$n"]]"
 }
 foreach p [get_hw_probes -of_objects $v] {
  if {[get_property TYPE $p] eq "vio_output"} {lappend original [list [get_property NAME $p] [get_property OUTPUT_VALUE $p]]}
 }
 if {[llength $original] != 9} {error "Expected nine saved trace VIO outputs, got [llength $original]"}
 set f [open /tmp/lauberhorn-519555/original-vio-$label.tcl {WRONLY CREAT EXCL}];puts $f [list set saved $original];close $f
 foreach {n value} {trace_dump_gateway_mac 0c53310300e1 trace_dump_server_ip c0a880e1 trace_dump_override_valid 18 trace_dump_over_network 0 trace_stop 1} {
  set p [get_hw_probes -of_objects $v -filter "NAME =~ */$n"]
  if {[llength $p] != 1} {error "probe mismatch $n"}
  set_property OUTPUT_VALUE $value $p
 }
 commit_hw_vio $v
 after 1000
}
if {$mode eq "loopback-on"} {
 refresh_hw_vio $link
 set original {}
 foreach p [get_hw_probes -of_objects $link] {
  if {[get_property TYPE $p] eq "vio_output"} {lappend original [list [get_property NAME $p] [get_property OUTPUT_VALUE $p]]}
 }
 set f [open /tmp/lauberhorn-519555/original-link-vio-$label.tcl {WRONLY CREAT EXCL}];puts $f [list set savedLink $original];close $f
 set p [get_hw_probes -of_objects $link -filter {NAME =~ */loopback}]
 if {[llength $p] != 1} {error "loopback probe mismatch"}
 set_property OUTPUT_VALUE 1 $p;commit_hw_vio $link;after 2000
}
if {$mode eq "loopback-off"} {
 source /tmp/lauberhorn-519555/original-link-vio-$label.tcl
 foreach item $savedLink {lassign $item name value;set_property OUTPUT_VALUE $value [get_hw_probes $name]}
 commit_hw_vio $link;after 2000
}
if {$mode eq "reset-tx"} {
 refresh_hw_vio $link
 set p [get_hw_probes -of_objects $link -filter {NAME =~ */gtwiz_reset_tx_datapath}]
 if {[llength $p] != 1} {error "TX reset probe mismatch"}
 set original [get_property OUTPUT_VALUE $p]
 if {$original ne "0"} {error "TX reset already asserted"}
 set_property OUTPUT_VALUE 1 $p;commit_hw_vio $link;after 200
 set_property OUTPUT_VALUE $original $p;commit_hw_vio $link;after 3000
 puts "TRACE_TX_DATAPATH_RESET_PULSED"
}
if {$mode eq "reset-core"} {
 refresh_hw_vio $link
 set selected {}
 foreach n {core_tx_reset core_rx_reset} {
  set p [get_hw_probes -of_objects $link -filter "NAME =~ */$n"]
  if {[llength $p] != 1 || [get_property OUTPUT_VALUE $p] ne "0"} {error "Core reset state mismatch"}
  lappend selected $p
 }
 foreach p $selected {set_property OUTPUT_VALUE 1 $p}
 commit_hw_vio $link;after 200
 foreach p $selected {set_property OUTPUT_VALUE 0 $p}
 commit_hw_vio $link;after 3000
 puts "TRACE_CORE_RESETS_PULSED"
}
if {$mode eq "trigger-retry"} {
 set p [get_hw_probes -of_objects $v -filter {NAME =~ */trace_dump_over_network}]
 for {set i 0} {$i < 10} {incr i} {
  set_property OUTPUT_VALUE 0 $p;commit_hw_vio $v;after 200
  set_property OUTPUT_VALUE 1 $p;commit_hw_vio $v;after 1800
 }
 puts "TEN_BOUNDED_METADATA_TRIGGERS"
}
if {$mode eq "trigger"} {
 set p [get_hw_probes -of_objects $v -filter {NAME =~ */trace_dump_over_network}]
 set_property OUTPUT_VALUE 0 $p;commit_hw_vio $v;after 200
 set_property OUTPUT_VALUE 1 $p;commit_hw_vio $v
 puts "UDP_DUMP_TRIGGERED"
}
if {$mode eq "restore" || $mode eq "preserve"} {
 source /tmp/lauberhorn-519555/original-vio-$label.tcl
 foreach item $saved {lassign $item name value;set_property OUTPUT_VALUE $value [get_hw_probes $name]}
 if {$mode eq "preserve"} {set_property OUTPUT_VALUE 1 [get_hw_probes -of_objects $v -filter {NAME =~ */trace_stop}]}
 commit_hw_vio $v
}
for {set i 0} {$i < 10} {incr i} {
 refresh_hw_vio $link
 set vals {}
 foreach n {cmac_usplus_0_stat_rx_aligned cmac_usplus_0_stat_rx_status cmac_usplus_0_stat_rx_local_fault cmac_usplus_0_stat_rx_remote_fault} {
  lappend vals [get_property INPUT_VALUE [get_hw_probes -of_objects $link -filter "NAME =~ */$n"]]
 }
 puts "LINK_SAMPLE $i $vals"
 after 200
}
refresh_hw_vio $v
foreach n {trace_write_slot_1 trace_wrapped_1 trace_sample_lost_1 trace_dma_error_1} {
 puts "TRACE_STATUS $n [get_property INPUT_VALUE [get_hw_probes -of_objects $v -filter "NAME =~ */$n"]]"
}
foreach target [list $v $link] {
 refresh_hw_vio $target
 foreach p [get_hw_probes -of_objects $target] {
  set type [get_property TYPE $p]
  if {$type eq "vio_input"} {set value [get_property INPUT_VALUE $p]} else {set value [get_property OUTPUT_VALUE $p]}
  puts "DIAG $type [get_property NAME $p] $value"
 }
}
close_hw_target
}
if {[catch {main [lindex $argv 0]} message]} {puts stderr $message;exit 1}
exit
