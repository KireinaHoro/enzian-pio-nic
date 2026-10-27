# Read-only extraction from the final checkpoint. A pure FF chain is required;
# unexpected logic, missing endpoints or unequal surviving bit depths fail closed.
namespace eval trace_latency {}
proc trace_latency::depth {sink sourcePrefix maxDepth} {
    set cell $sink
    set stages 0
    set visited {}
    while {1} {
        if {$cell in $visited} {error "cycle in trace pipeline at $cell"}
        lappend visited $cell
        set ref [get_property REF_NAME $cell]
        if {![regexp {^FD} $ref]} {error "non-FF trace transport cell $cell ($ref)"}
        incr stages
        if {$stages > $maxDepth} {error "trace pipeline exceeds limit: $sink"}
        if {[string match "${sourcePrefix}/data_reg*" $cell]} {return $stages}
        set d [get_pins -quiet -of_objects $cell -filter {REF_PIN_NAME == D}]
        set nets [get_nets -quiet -segments -of_objects $d]
        set drivers [get_pins -quiet -leaf -of_objects $nets -filter {DIRECTION == OUT}]
        if {[llength $drivers] != 1} {error "trace transport needs one driver: $cell ($drivers)"}
        set pin [lindex $drivers 0]
        if {[get_property REF_PIN_NAME $pin] ne "Q"} {error "non-Q trace transport driver: $pin"}
        set cell [get_cells -of_objects $pin]
    }
}
proc trace_latency::inactive {instance} {
    set valid [get_pins -quiet -hierarchical -filter "NAME == ${instance}/output_valid"]
    if {[llength $valid] != 1} {return false}
    set nets [get_nets -quiet -segments -of_objects $valid]
    set drivers [get_pins -quiet -leaf -of_objects $nets -filter {DIRECTION == OUT}]
    if {[llength $drivers] != 1} {return false}
    return [expr {[get_property REF_NAME [get_cells -of_objects $drivers]] eq "GND"}]
}
proc trace_latency::extract {template destination revision checkpoint} {
    variable python_helper
    set helper $python_helper
    set plan [exec python3 $helper plan $template]
    set evidence "${destination}.depths.tsv"
    set f [open "${evidence}.tmp" w]
    puts $f "source\tinstance\tbit\tstages"
    foreach row [split $plan \n] {
        lassign [split $row \t] id instance limit width
        set sinks [get_cells -quiet -hierarchical -filter "NAME =~ ${instance}/sink/data_reg*"]
        if {[llength $sinks] == 0} {
            if {![inactive $instance]} {error "missing trace sink without constant-low valid: $instance"}
            puts $f "$id\t$instance\tinactive\tGND"
            continue
        }
        set validSeen false
        foreach sink $sinks {
            if {![regexp {data_reg\[([0-9]+)\]$} $sink -> bit]} {error "unexpected trace sink name: $sink"}
            set n [depth $sink "${instance}/source" [expr {$limit + 2}]]
            if {$bit == $width - 1} {set validSeen true}
            puts $f "$id\t$instance\t$bit\t$n"
        }
        if {!$validSeen} {error "trace valid endpoint was removed: $instance"}
    }
    close $f
    file rename -force "${evidence}.tmp" $evidence
    exec python3 $helper resolve $template $evidence $destination $revision $checkpoint
    puts "TRACE_LATENCY_RESOLVED $destination"
}
set trace_latency::python_helper [file join [file dirname [info script]] trace_latency.py]
