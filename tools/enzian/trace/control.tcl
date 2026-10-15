# Shared Lauberhorn trace control. All board/session values are arguments.
proc one {objects description} {
 if {[llength $objects] != 1} {error "Expected one $description, got [llength $objects]"}
 return [lindex $objects 0]
}
proc probe {v name} {return [one [get_hw_probes -of_objects $v -filter "NAME =~ */$name"] $name]}
proc status {v prefix} {
 refresh_hw_vio $v
 foreach n {trace_write_slot_1 trace_wrapped_1 trace_sample_lost_1 trace_dma_error_1} {
  puts "$prefix $n [get_property INPUT_VALUE [probe $v $n]]"
 }
}
proc main {} {
 lassign $::argv mode server cable ltx snapshot ip mac helper head tail size
 if {$mode ni {status freeze configure trigger preserve check}} {error "Unknown control operation"}
 open_hw_manager
 connect_hw_server -url $server
 current_hw_target [one [get_hw_targets "*/xilinx_tcf/Digilent/$cable"] target]
 open_hw_target
 set d [one [get_hw_devices xcvu9p_*] device]
 set_property PROBES.FILE $ltx $d
 refresh_hw_device $d
 set v [one [get_hw_vios -filter {CELL_NAME =~ */vio_trace_status}] trace-VIO]
 refresh_hw_vio $v
 if {$mode in {freeze configure}} {
  status $v BEFORE_FREEZE
  set saved {}
  foreach p [get_hw_probes -of_objects $v] {
   if {[get_property TYPE $p] eq "vio_output"} {lappend saved [list [get_property NAME $p] [get_property OUTPUT_VALUE $p]]}
  }
  if {[llength $saved]!=9} {error "Unexpected trace VIO layout"}
  set f [open $snapshot {WRONLY CREAT EXCL}];puts $f $saved;close $f
  set_property OUTPUT_VALUE 1 [probe $v trace_stop]
  if {$mode eq "configure"} {
   foreach {n value} [list trace_dump_gateway_mac $mac trace_dump_server_ip $ip trace_dump_override_valid 18 trace_dump_over_network 0] {
    set_property OUTPUT_VALUE $value [probe $v $n]
   }
  }
  commit_hw_vio $v
  after 1000
  refresh_hw_vio $v
  set previous [get_property INPUT_VALUE [probe $v trace_write_slot_1]]
  after 200
  refresh_hw_vio $v
  if {$previous ne [get_property INPUT_VALUE [probe $v trace_write_slot_1]]} {error "Frozen write pointer is not stable"}
 }
 if {$mode in {trigger check}} {
  if {[get_property OUTPUT_VALUE [probe $v trace_stop]] ne "1"} {error "Recorder must be frozen"}
 }
 if {$mode eq "trigger"} {
  set p [probe $v trace_dump_over_network]
  set_property OUTPUT_VALUE 0 $p;commit_hw_vio $v;after 200
  set_property OUTPUT_VALUE 1 $p;commit_hw_vio $v
  puts "UDP_DUMP_TRIGGERED"
 }
 if {$mode eq "preserve"} {
  set f [open $snapshot r];set saved [read $f];close $f
  foreach item $saved {
   lassign $item name value
   set_property OUTPUT_VALUE $value [one [get_hw_probes -of_objects $v -filter "NAME == $name"] saved-probe]
  }
  set_property OUTPUT_VALUE 1 [probe $v trace_stop]
  commit_hw_vio $v
 }
 if {$mode eq "check"} {
  if {![string is integer -strict $size] || $size<4096 || $size%64} {error "Invalid capture size"}
  source $helper
  lhtrace::dump -out $head -bytes 4096 -address 0 -use-vio-status 0
  lhtrace::dump -out $tail -bytes 4096 -address [expr {$size-4096}] -use-vio-status 0
 }
 status $v TRACE_STATUS
 foreach p [get_hw_probes -of_objects $v] {
  set type [get_property TYPE $p]
  if {$type eq "vio_input"} {set value [get_property INPUT_VALUE $p]} else {set value [get_property OUTPUT_VALUE $p]}
  puts "DIAG $type [get_property NAME $p] $value"
 }
 close_hw_target
}
if {[catch {main} message]} {puts stderr "ERROR: $message";exit 1}
exit 0
