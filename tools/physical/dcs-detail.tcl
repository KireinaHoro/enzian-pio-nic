# Read-only checkpoint comparison; no synthesis, placement or routing.
set_param general.maxThreads 2
set dcp [lindex $argv 0]
set output [lindex $argv 1]
file mkdir $output
open_checkpoint $dcp
set f [open "$output/paths.tsv" w]
puts $f "scope\tslack\tdelay\tlevels\tstart\tend\tclock"
foreach scope {dcs_even dcs_odd} {
    set root "i_app/$scope/i_dcs"
    set regs [get_cells -hier -filter "IS_SEQUENTIAL == 1 && NAME =~ $root/*"]
    puts "DC_REGS $scope [llength $regs]"
    if {[llength $regs] == 0} {error "no DCS registers"}
    report_utilization -cells [get_cells $root] -hierarchical -hierarchical_depth 8 -file "$output/$scope-util.rpt"
    foreach kind {internal into outof} {
        set opts {}
        if {$kind ne "into"} {lappend opts -from $regs}
        if {$kind ne "outof"} {lappend opts -to $regs}
        set paths [get_timing_paths -delay_type max -max_paths 1 {*}$opts]
        foreach path $paths {
            set row [list "$scope/$kind"]
            foreach prop {SLACK DATAPATH_DELAY LOGIC_LEVELS STARTPOINT_PIN ENDPOINT_PIN ENDPOINT_CLOCK} {
                lappend row [get_property $prop $path]
            }
            puts $f [join $row "\t"]
        }
        report_timing -delay_type max -max_paths 1 -input_pins {*}$opts -file "$output/$scope-$kind.rpt"
    }
}
close $f
close_design
puts "DCS_COMPARISON_COMPLETE"
