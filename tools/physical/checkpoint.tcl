# Read-only STA. vivado -mode batch -source checkpoint.tcl -tclargs DCP NEW_DIR ?N? ?ENDPOINT_GLOB?
if {$argc < 2 || $argc > 4} { error "usage: DCP NEW_DIR ?N=200? ?ENDPOINT_GLOB?" }
set dcp [file normalize [lindex $argv 0]]
set output [file normalize [lindex $argv 1]]
set count 200
if {$argc >= 3} {set count [lindex $argv 2]}
if {![string is integer -strict $count] || $count < 1} {error "N must be positive"}
if {[file exists $output]} {error "output directory already exists: $output"}
file mkdir $output
set_param general.maxThreads 8
open_checkpoint $dcp
set meta [open "$output/metadata.txt" w]
puts $meta "checkpoint=$dcp\nvivado=[version -short]\ncreated=[clock format [clock seconds] -format {%Y-%m-%dT%H:%M:%S%z}]\nsample_per_delay_type=$count"
close $meta
report_route_status -file "$output/route.rpt"
report_timing_summary -report_unconstrained -file "$output/timing.rpt"
report_utilization -hierarchical -hierarchical_depth 3 -file "$output/utilization.rpt"
report_clock_interaction -file "$output/clocks.rpt"
report_exceptions -summary -file "$output/exceptions.rpt"
report_bus_skew -file "$output/bus-skew.rpt"
set select {}
if {$argc == 4} {
    set pins [get_pins -hier -quiet [lindex $argv 3]]
    if {[llength $pins] == 0} {error "endpoint glob matched no pins"}
    set select [list -to $pins]
}
set columns {STARTPOINT_PIN ENDPOINT_PIN SLACK DATAPATH_DELAY LOGIC_LEVELS STARTPOINT_CLOCK ENDPOINT_CLOCK REQUIREMENT}
set f [open "$output/paths.tsv" w]
puts $f "type\trank\t[join $columns \t]"
foreach type {max min} {
    set paths [get_timing_paths -delay_type $type -max_paths $count -nworst 1 {*}$select]
    set rank 0
    foreach path $paths {
        incr rank
        set row [list $type $rank]
        foreach prop $columns {lappend row [get_property $prop $path]}
        puts $f [join $row \t]
    }
    report_timing -delay_type $type -max_paths 10 -nworst 1 -input_pins {*}$select -file "$output/$type-detail.rpt"
    if {$type eq "max"} {
        report_design_analysis -of_timing_paths [lrange $paths 0 19] -file "$output/analysis.rpt"
    }
}
close $f
# Full CDC can be expensive; retained on disk, never emit it into agent context.
report_cdc -details -file "$output/cdc.rpt"
set done [open "$output/COMPLETE" w]
puts $done "CHECKPOINT_REPORTS_READY"
close $done
puts "CHECKPOINT_REPORTS_READY $output"
close_design
