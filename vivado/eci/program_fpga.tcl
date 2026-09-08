# CPU must be held at BDK Boot Options and FPGA powered up before programming.
# Use a Vivado version matching hw_server.
# vivado -mode batch -source program_fpga.tcl -tclargs SERVER Digilent/SERIAL BIT [LTX]
# BIT may be --probe to check target/device selection without programming.
proc main {argv} {
    if {[llength $argv] < 3 || [llength $argv] > 4} {
        error "usage: server Digilent/serial bitstream|--probe ?probes.ltx?"
    }
    lassign $argv server jtag bitstream probes
    if {![regexp {^Digilent/[A-Za-z0-9]+$} $jtag]} {
        error "Expected an exact Digilent/serial JTAG ID"
    }
    set probe [expr {$bitstream eq "--probe"}]
    if {!$probe} {
        if {![file isfile $bitstream] || ![file readable $bitstream]} {
            error "Unreadable bitstream: $bitstream"
        }
        set bitstream [file normalize $bitstream]
    }
    if {$probes ne "" && (![file isfile $probes] || ![file readable $probes])} {
        error "Unreadable probes file: $probes"
    }
    open_hw_manager
    connect_hw_server -url $server
    set targets {}
    foreach target [get_hw_targets] {
        if {[string match "*/xilinx_tcf/$jtag" $target]} {
            lappend targets $target
        }
    }
    if {[llength $targets] != 1} {
        error "Expected exactly one target for $jtag, found: $targets"
    }
    current_hw_target [lindex $targets 0]
    open_hw_target
    set devices [get_hw_devices -quiet xcvu9p_*]
    if {[llength $devices] != 1} {
        error "Expected exactly one xcvu9p on $jtag, found: $devices"
    }
    set device [lindex $devices 0]
    current_hw_device $device
    puts "Selected target=[current_hw_target] device=$device Vivado=[version -short]"
    if {!$probe} {
        set_property PROGRAM.FILE $bitstream $device
        set_property PROBES.FILE {} $device
        set_property FULL_PROBES.FILE {} $device
        if {$probes ne ""} {
            set_property PROBES.FILE [file normalize $probes] $device
            set_property FULL_PROBES.FILE [file normalize $probes] $device
        }
        program_hw_devices $device
        refresh_hw_device $device
        puts "PROGRAM_SUCCESS target=$jtag bitstream=$bitstream"
    } else {
        puts "PROBE_SUCCESS target=$jtag device=$device"
    }
    close_hw_target
    disconnect_hw_server
    close_hw_manager
}
if {[catch {main $argv} message]} {
    puts stderr "FPGA operation failed: $message"
    catch {close_hw_target}
    catch {disconnect_hw_server}
    catch {close_hw_manager}
    exit 1
}
exit 0
