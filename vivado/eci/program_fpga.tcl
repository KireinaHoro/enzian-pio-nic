# CPU must be held at BDK Boot Options and FPGA powered up before programming.
# Use a Vivado version matching hw_server.
# vivado -mode batch -source program_fpga.tcl -tclargs SERVER Digilent/SERIAL BIT [LTX]
# BIT may be --probe to check target/device selection without programming.
# Or: SERVER Digilent/SERIAL --capture-ila LTX EXACT_CELL OUTPUT.csv
# Or: SERVER Digilent/SERIAL --report-mig LTX EXACT_CELL OUTPUT_PREFIX
# Calibration is a prerequisite for trace writes and reads, even with DONE high.
proc require_trace_calibration {device} {
    set selected {}
    foreach mig [get_hw_migs -of_objects $device] {
        if {[get_property CELL_NAME $mig] eq "i_app/design_1_i/ddr4_4"} {lappend selected $mig}
    }
    if {[llength $selected] != 1} {error "Expected exactly one trace DDR MIG"}
    set mig [lindex $selected 0]
    set deadline [expr {[clock seconds] + 30}]
    while {1} {
        refresh_hw_mig $mig
        set report [report_hw_mig -return_string $mig]
        if {[regexp {Overall Status:[ \t]+CAL PASS} $report]} {
            puts "TRACE_DDR_CALIBRATION_PASS cell=[get_property CELL_NAME $mig]"
            return
        }
        if {[regexp {Overall Status:[ \t]+CAL FAIL} $report] || [clock seconds] >= $deadline} {
            puts $report
            report_property $mig
            return -code error -errorcode {LAUBERHORN CALIBRATION} "Trace DDR calibration failed or timed out"
        }
        after 1000
    }
}
proc main {argv} {
    if {[llength $argv] < 3 || [llength $argv] > 6} {
        error "usage: server Digilent/serial bitstream|--probe ?probes.ltx?"
    }
    lassign $argv server jtag bitstream probes cell output
    set capture [expr {$bitstream eq "--capture-ila"}]
    set migReport [expr {$bitstream eq "--report-mig"}]
    set diagnostic [expr {$capture || $migReport}]
    if {($diagnostic && [llength $argv] != 6) || (!$diagnostic && [llength $argv] > 4)} {
        error "diagnostics require LTX, exact core cell and output path"
    }
    if {![regexp {^Digilent/[A-Za-z0-9]+$} $jtag]} {
        error "Expected an exact Digilent/serial JTAG ID"
    }
    set probe [expr {$bitstream eq "--probe" || $diagnostic}]
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
    if {$migReport} {
        set_property PROBES.FILE [file normalize $probes] $device
        set_property FULL_PROBES.FILE [file normalize $probes] $device
        refresh_hw_device $device
        set selected {}
        foreach mig [get_hw_migs -of_objects $device] {
            if {[get_property CELL_NAME $mig] eq $cell} {lappend selected $mig}
        }
        if {[llength $selected] != 1} {
            foreach mig [get_hw_migs -of_objects $device] {puts "MIG_CELL=[get_property CELL_NAME $mig]"}
            error "Expected one MIG at $cell"
        }
        set mig [lindex $selected 0]
        refresh_hw_mig $mig
        report_property -file ${output}.properties.txt $mig
        report_hw_mig -file ${output}.report.txt $mig
        puts "MIG_REPORT_SUCCESS cell=$cell output=$output"
    } elseif {$capture} {
        set_property PROBES.FILE [file normalize $probes] $device
        set_property FULL_PROBES.FILE [file normalize $probes] $device
        refresh_hw_device $device
        set selected {}
        foreach ila [get_hw_ilas -of_objects $device] {
            if {[get_property CELL_NAME $ila] eq $cell} {lappend selected $ila}
        }
        if {[llength $selected] != 1} {error "Expected one ILA at $cell"}
        set ila [lindex $selected 0]
        run_hw_ila -trigger_now $ila
        wait_on_hw_ila -timeout 30 $ila
        set data [upload_hw_ila_data $ila]
        write_hw_ila_data -csv_file $output $data
        puts "CAPTURE_SUCCESS cell=$cell output=$output"
    } elseif {!$probe} {
        set_property PROGRAM.FILE $bitstream $device
        set_property PROBES.FILE {} $device
        set_property FULL_PROBES.FILE {} $device
        if {$probes ne ""} {
            set_property PROBES.FILE [file normalize $probes] $device
            set_property FULL_PROBES.FILE [file normalize $probes] $device
        }
        program_hw_devices $device
        refresh_hw_device $device
        require_trace_calibration $device
        puts "PROGRAM_SUCCESS target=$jtag bitstream=$bitstream"
    } else {
        puts "PROBE_SUCCESS target=$jtag device=$device"
    }
    close_hw_target
    disconnect_hw_server
    close_hw_manager
}
if {[catch {main $argv} message options]} {
    puts stderr "FPGA operation failed: $message"
    catch {close_hw_target}
    catch {disconnect_hw_server}
    catch {close_hw_manager}
    if {[dict get $options -errorcode] eq "LAUBERHORN CALIBRATION"} {exit 43}
    exit 1
}
exit 0
