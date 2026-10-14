proc main {} {
 open_hw_manager
 connect_hw_server -url enzian-gateway:3121
 set targets [get_hw_targets */xilinx_tcf/Digilent/210357B4B301A]
 if {[llength $targets] != 1} {error "target mismatch"}
 current_hw_target [lindex $targets 0]
 open_hw_target
 set d [get_hw_devices xcvu9p_*]
 if {[llength $d] != 1} {error "device mismatch"}
 set_property PROBES.FILE /tmp/lauberhorn-519555/shell_lauberhorn-eci.ltx $d
 refresh_hw_device $d
 set v [get_hw_vios -filter {CELL_NAME =~ */vio_trace_status}]
 refresh_hw_vio $v
 set stop [get_hw_probes -of_objects $v -filter {NAME =~ */trace_stop}]
 if {[get_property OUTPUT_VALUE $stop] ne "1"} {error "Trace must be frozen"}
 source /tmp/lauberhorn-519555/dump_trace_hw_axi.tcl
 lhtrace::dump -out /tmp/lauberhorn-519555/grid-jtag-head.bin -bytes 4096 -address 0 -use-vio-status 0
 lhtrace::dump -out /tmp/lauberhorn-519555/grid-jtag-tail.bin -bytes 4096 -address 2272269376 -use-vio-status 0 -progress-interval-ms 10000
 close_hw_target
}
if {[catch {main} message]} {puts stderr $message;exit 1}
exit 0
