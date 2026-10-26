# Unit-test the actual Tcl walker with a small netlist API fixture.
source [file join [file dirname [info script]] .. trace_latency.tcl]
proc get_property {property object} {
    global refs
    if {$property eq "REF_NAME"} {return [dict get $refs $object]}
    if {$property eq "REF_PIN_NAME"} {return [lindex [split $object /] end]}
    error "unexpected property $property"
}
proc get_pins {args} {
    global drivers
    if {[lsearch $args -of_objects] >= 0} {
        set net [lindex $args [expr {[lsearch $args -of_objects]+1}]]
        if {[lindex $args end] eq "REF_PIN_NAME == D"} {return "$net/D"}
        return [dict get $drivers $net]
    }
    return [lindex $args end]
}
proc get_nets {args} {return [lindex $args end]}
proc get_cells {args} {return [join [lrange [split [lindex $args end] /] 0 end-1] /]}
set refs [dict create {p/source/data_reg[0]} FDRE {p/sink/data_reg[0]} FDRE {p/inserted} FDRE]
set drivers [dict create {p/sink/data_reg[0]/D} {p/inserted/Q} {p/inserted/D} {p/source/data_reg[0]/Q}]
if {[trace_latency::depth {p/sink/data_reg[0]} p/source 12] != 3} {error "wrong inserted depth"}
if {![catch {trace_latency::depth {p/sink/data_reg[0]} p/source 2}]} {error "accepted excessive depth"}
dict set refs p/inserted LUT1
if {![catch {trace_latency::depth {p/sink/data_reg[0]} p/source 12}]} {error "accepted combinational transport"}
dict set refs p/inserted FDRE
dict set drivers p/inserted/D {p/sink/data_reg[0]/Q}
if {![catch {trace_latency::depth {p/sink/data_reg[0]} p/source 12}]} {error "accepted cyclic transport"}
puts TRACE_LATENCY_WALKER_TESTS_PASSED
