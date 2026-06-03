namespace eval lhtrace {
    variable progress_last_ms 0
}

if {![info exists LH_TRACE_OUT]} {
    set LH_TRACE_OUT "data/eci/sys_trace/trace-dram.bin"
}
if {![info exists LH_TRACE_ADDRESS]} {
    set LH_TRACE_ADDRESS 0x0
}
if {![info exists LH_TRACE_BYTES]} {
    set LH_TRACE_BYTES 0x800000000
}
if {![info exists LH_TRACE_AXI_LEN]} {
    set LH_TRACE_AXI_LEN 256
}
if {![info exists LH_TRACE_BYTES_PER_BEAT]} {
    set LH_TRACE_BYTES_PER_BEAT 8
}
if {![info exists LH_TRACE_BYTES_PER_BEAT_AUTO]} {
    set LH_TRACE_BYTES_PER_BEAT_AUTO 1
}
if {![info exists LH_TRACE_AXI_SLOT_BYTES]} {
    set LH_TRACE_AXI_SLOT_BYTES 64
}
if {![info exists LH_TRACE_USE_VIO_STATUS]} {
    set LH_TRACE_USE_VIO_STATUS 1
}
if {![info exists LH_TRACE_VIO_FILTER]} {
    set LH_TRACE_VIO_FILTER {CELL_NAME =~ *vio_trace_status*}
}
if {![info exists LH_TRACE_VIO_PROBES]} {
    set LH_TRACE_VIO_PROBES {
        trace_write_slot
        trace_wrapped
        trace_sample_lost
        trace_dma_error
    }
}
if {![info exists LH_TRACE_PROGRESS_INTERVAL_MS]} {
    set LH_TRACE_PROGRESS_INTERVAL_MS 1000
}
if {![info exists LH_TRACE_SUPPRESS_READ_MESSAGES]} {
    set LH_TRACE_SUPPRESS_READ_MESSAGES 1
}
if {![info exists LH_TRACE_PRINT_AXI_PROPERTIES]} {
    set LH_TRACE_PRINT_AXI_PROPERTIES 1
}

proc lhtrace::fmt_bytes {bytes} {
    set units {B KiB MiB GiB TiB}
    set value [expr {double($bytes)}]
    set unit "B"
    foreach unit $units {
        if {$value < 1024.0 || $unit eq "TiB"} {
            break
        }
        set value [expr {$value / 1024.0}]
    }
    if {$unit eq "B"} {
        return [format "%d B" $bytes]
    }
    return [format "%.2f %s" $value $unit]
}

proc lhtrace::normalize_hex {hex} {
    set hex [string trim $hex]
    set hex [string map {" " "" "\n" "" "\r" "" "\t" "" "_" "" "0x" "" "0X" ""} $hex]
    return [string tolower $hex]
}

proc lhtrace::txn_data_hex {txn} {
    foreach prop {DATA READ_DATA} {
        if {![catch {set data [get_property $prop $txn]}] && $data ne ""} {
            return [lhtrace::normalize_hex $data]
        }
    }
    catch {report_property $txn}
    error "Could not read AXI transaction data; expected DATA or READ_DATA property on $txn"
}

proc lhtrace::reverse_hex_bytes {hex} {
    set ret ""
    for {set pos [expr {[string length $hex] - 2}]} {$pos >= 0} {incr pos -2} {
        append ret [string range $hex $pos [expr {$pos + 1}]]
    }
    return $ret
}

proc lhtrace::property_value {obj names} {
    foreach name $names {
        if {![catch {set value [get_property $name $obj]}] && $value ne ""} {
            return $value
        }
    }
    return ""
}

proc lhtrace::property_int {obj names} {
    set value [lhtrace::property_value $obj $names]
    if {[regexp {^[0-9]+$} $value]} {
        return $value
    }
    if {[regexp {^0x[0-9a-fA-F]+$} $value]} {
        return [expr {$value}]
    }
    return ""
}

proc lhtrace::parse_hw_int {value {bare_hex 0}} {
    set value [string trim $value]
    set value [string map {"_" "" " " ""} $value]
    if {[regexp {^[0-9]+'h([0-9a-fA-F]+)$} $value _ digits]} {
        return [expr "0x$digits"]
    }
    if {[regexp {^[0-9]+'d([0-9]+)$} $value _ digits]} {
        return [expr {$digits}]
    }
    if {[regexp {^[0-9]+'b([01]+)$} $value _ digits]} {
        set ret 0
        foreach bit [split $digits ""] {
            set ret [expr {$ret * 2 + $bit}]
        }
        return $ret
    }
    if {[regexp {^0x[0-9a-fA-F]+$} $value]} {
        return [expr {$value}]
    }
    if {[regexp {^[0-9]+$} $value]} {
        return [expr {$value}]
    }
    if {$bare_hex && [regexp {^[0-9a-fA-F]+$} $value] && [string length $value] > 1} {
        return [expr "0x$value"]
    }
    error "Cannot parse hardware integer value '$value'"
}

proc lhtrace::find_axi {} {
    set axis [get_hw_axis -filter {NAME =~ *jtag_axi* || CELL_NAME =~ *jtag_axi*}]
    if {[llength $axis] == 0} {
        error "No JTAG AXI master found with NAME/CELL_NAME matching *jtag_axi*"
    }
    set axi [lindex $axis 0]
    if {[llength $axis] > 1} {
        puts "Found multiple JTAG AXI masters, using $axi"
    } else {
        puts "Using JTAG AXI master $axi"
    }
    return $axi
}

proc lhtrace::find_trace_vio {} {
    global LH_TRACE_VIO_FILTER
    set vios [get_hw_vios -filter $LH_TRACE_VIO_FILTER]
    if {[llength $vios] == 0} {
        error "No trace status VIO found with filter '$LH_TRACE_VIO_FILTER'"
    }
    set vio [lindex $vios 0]
    if {[llength $vios] > 1} {
        puts "Found multiple trace status VIOs, using $vio"
    } else {
        puts "Using trace status VIO $vio"
    }
    return $vio
}

proc lhtrace::probe_prop {probe prop} {
    if {![catch {set value [get_property $prop $probe]}] && $value ne ""} {
        return $value
    }
    return ""
}

proc lhtrace::probe_sort_key {probe} {
    foreach prop {PORT_INDEX PROBE_PORT INDEX INPUT_INDEX} {
        set value [lhtrace::probe_prop $probe $prop]
        if {[regexp {^[0-9]+$} $value]} {
            return [format "%08d:%s" $value $probe]
        }
    }
    return [format "99999999:%s" $probe]
}

proc lhtrace::sort_probes {probes} {
    set keyed {}
    foreach probe $probes {
        lappend keyed [list [lhtrace::probe_sort_key $probe] $probe]
    }
    set ret {}
    foreach item [lsort -index 0 $keyed] {
        lappend ret [lindex $item 1]
    }
    return $ret
}

proc lhtrace::vio_probes {vio} {
    set probes {}
    foreach cmd [list \
        [list get_hw_probes -quiet -of_objects $vio] \
        [list get_hw_probes -quiet -filter "CELL_NAME =~ *vio_trace_status* || NAME =~ *vio_trace_status*"] \
        [list get_hw_probes -quiet -filter "NAME =~ *probe_in* || NAME =~ *trace_*"] \
    ] {
        if {![catch {set found [eval $cmd]}] && [llength $found] > 0} {
            foreach probe $found {
                if {[lsearch -exact $probes $probe] < 0} {
                    lappend probes $probe
                }
            }
        }
    }
    return [lhtrace::sort_probes $probes]
}

proc lhtrace::describe_vio_probes {vio} {
    set probes [lhtrace::vio_probes $vio]
    if {[llength $probes] == 0} {
        return "No hardware probes are visible for $vio"
    }

    set lines [list "Visible probes for $vio:"]
    foreach probe $probes {
        set fields [list $probe]
        foreach prop {NAME NAME.SHORT CELL_NAME PORT_INDEX PROBE_PORT INDEX INPUT_INDEX INPUT_VALUE VALUE WIDTH} {
            set value [lhtrace::probe_prop $probe $prop]
            if {$value ne ""} {
                lappend fields "$prop=$value"
            }
        }
        lappend lines "  [join $fields { }]"
    }
    return [join $lines "\n"]
}

proc lhtrace::vio_probe {vio short_name} {
    global LH_TRACE_VIO_PROBES
    set aliases [list $short_name]
    set fallback_index -1
    if {[regexp {^probe_in([0-9]+)$} $short_name _ idx]} {
        set fallback_index $idx
        set named_aliases {
            0 trace_write_slot
            1 trace_wrapped
            2 trace_sample_lost
            3 trace_dma_error
        }
        if {[dict exists $named_aliases $idx]} {
            lappend aliases [dict get $named_aliases $idx]
        }
    }
    set configured_index [lsearch -exact $LH_TRACE_VIO_PROBES $short_name]
    if {$configured_index >= 0} {
        set fallback_index $configured_index
        lappend aliases probe_in$configured_index
    }

    set probes [lhtrace::vio_probes $vio]
    foreach probe $probes {
        foreach alias $aliases {
            foreach prop {NAME NAME.SHORT CELL_NAME} {
                set value [lhtrace::probe_prop $probe $prop]
                if {$value ne "" && [string match "*$alias*" $value]} {
                    return $probe
                }
            }
        }
    }

    if {$fallback_index >= 0 && [llength $probes] > $fallback_index} {
        return [lindex $probes $fallback_index]
    }

    error "No probe '$short_name' found on VIO $vio\n[lhtrace::describe_vio_probes $vio]"
}

proc lhtrace::vio_probe_value {vio short_name} {
    set probe [lhtrace::vio_probe $vio $short_name]
    foreach prop {INPUT_VALUE VALUE} {
        if {![catch {set value [get_property $prop $probe]}] && $value ne ""} {
            return [lhtrace::parse_hw_int $value 1]
        }
    }
    catch {report_property $probe}
    error "Could not read INPUT_VALUE/VALUE from VIO probe $short_name on $vio"
}

proc lhtrace::trace_status {} {
    global LH_TRACE_VIO_PROBES
    set vio [lhtrace::find_trace_vio]
    if {[llength [info commands refresh_hw_vio]] != 0} {
        catch {refresh_hw_vio $vio}
    }

    if {[llength $LH_TRACE_VIO_PROBES] != 4} {
        error "LH_TRACE_VIO_PROBES must contain exactly four probe names: writeSlot wrapped sampleLost dmaError"
    }

    set write_slot [lhtrace::vio_probe_value $vio [lindex $LH_TRACE_VIO_PROBES 0]]
    set wrapped [lhtrace::vio_probe_value $vio [lindex $LH_TRACE_VIO_PROBES 1]]
    set sample_lost [lhtrace::vio_probe_value $vio [lindex $LH_TRACE_VIO_PROBES 2]]
    set dma_error [lhtrace::vio_probe_value $vio [lindex $LH_TRACE_VIO_PROBES 3]]

    puts "Trace status: writeSlot=$write_slot wrapped=$wrapped sampleLost=$sample_lost dmaError=$dma_error"
    return [list $write_slot $wrapped $sample_lost $dma_error]
}

proc lhtrace::effective_dump_bytes {requested_bytes} {
    global LH_TRACE_USE_VIO_STATUS LH_TRACE_AXI_SLOT_BYTES
    if {!$LH_TRACE_USE_VIO_STATUS} {
        return $requested_bytes
    }

    lassign [lhtrace::trace_status] write_slot wrapped sample_lost dma_error
    set status_bytes [expr {$write_slot * $LH_TRACE_AXI_SLOT_BYTES}]
    if {$wrapped} {
        set status_bytes $requested_bytes
        puts [format "Trace buffer wrapped; dumping full configured buffer of %s" \
            [lhtrace::fmt_bytes $status_bytes]]
    } else {
        puts [format "Trace buffer has not wrapped; dumping through writeSlot (%s)" \
            [lhtrace::fmt_bytes $status_bytes]]
    }

    if {$status_bytes > $requested_bytes} {
        puts [format "Capping VIO-derived dump size %s to configured limit %s" \
            [lhtrace::fmt_bytes $status_bytes] [lhtrace::fmt_bytes $requested_bytes]]
        set status_bytes $requested_bytes
    }
    if {$dma_error} {
        puts "WARNING: trace DMA reported dmaError=1"
    }
    if {$sample_lost} {
        puts "WARNING: trace DMA reported sampleLost=1"
    }
    return $status_bytes
}

proc lhtrace::print_axi_properties {axi} {
    global LH_TRACE_PRINT_AXI_PROPERTIES
    if {!$LH_TRACE_PRINT_AXI_PROPERTIES} {
        return
    }
    if {[catch {set props [list_property $axi]}]} {
        return
    }

    set interesting {}
    foreach prop $props {
        if {[regexp -nocase {(width|burst|len|length|protocol|addr|data)} $prop]} {
            if {![catch {set value [get_property $prop $axi]}] && $value ne ""} {
                lappend interesting "$prop=$value"
            }
        }
    }
    if {[llength $interesting] > 0} {
        puts "JTAG AXI properties: [join $interesting {, }]"
    }
}

proc lhtrace::configure_axi_width {axi} {
    global LH_TRACE_BYTES_PER_BEAT LH_TRACE_BYTES_PER_BEAT_AUTO
    if {!$LH_TRACE_BYTES_PER_BEAT_AUTO} {
        return
    }

    set width [lhtrace::property_int $axi {
        DATA_WIDTH
        C_DATA_WIDTH
        C_M_AXI_DATA_WIDTH
        CONFIG.DATA_WIDTH
        CONFIG.C_DATA_WIDTH
        CONFIG.C_M_AXI_DATA_WIDTH
    }]
    if {$width ne "" && $width > 0 && [expr {$width % 8}] == 0} {
        set LH_TRACE_BYTES_PER_BEAT [expr {$width / 8}]
        puts "Using $LH_TRACE_BYTES_PER_BEAT bytes per AXI beat from DATA_WIDTH=$width"
    } else {
        puts "Using configured $LH_TRACE_BYTES_PER_BEAT bytes per AXI beat"
    }
}

proc lhtrace::configure_messages {} {
    global LH_TRACE_SUPPRESS_READ_MESSAGES
    if {$LH_TRACE_SUPPRESS_READ_MESSAGES} {
        catch {set_msg_config -id {Labtoolstcl 44-481} -suppress}
    }
}

proc lhtrace::is_user_interrupt {message} {
    if {$message eq ""} {
        return 1
    }
    return [regexp -nocase {(cancel|interrupt|abort|stopped)} $message]
}

proc lhtrace::read_hex {axi address beats} {
    set name lhtrace_rd
    create_hw_axi_txn $name $axi -type read -address [format "0x%x" $address] -len $beats -force
    set txn [get_hw_axi_txns $name]
    set rc [catch {run_hw_axi $txn} err]
    if {$rc != 0} {
        if {[lhtrace::is_user_interrupt $err]} {
            error "AXI read was interrupted or cancelled"
        }
        error $err
    }
    return [lhtrace::txn_data_hex $txn]
}

proc lhtrace::progress {written total start_ms {force 0}} {
    variable progress_last_ms
    global LH_TRACE_PROGRESS_INTERVAL_MS
    set now [clock milliseconds]
    if {!$force && $now - $progress_last_ms < $LH_TRACE_PROGRESS_INTERVAL_MS} {
        return
    }
    set progress_last_ms $now

    set elapsed_s [expr {($now - $start_ms) / 1000.0}]
    if {$elapsed_s <= 0.0} {
        set elapsed_s 0.001
    }
    set mib_s [expr {$written / 1048576.0 / $elapsed_s}]
    set pct [expr {$total > 0 ? (100.0 * $written / $total) : 0.0}]
    puts -nonewline [format "\r%.2f%%  %s / %s  %.2f MiB/s  elapsed %.1fs" \
        $pct [lhtrace::fmt_bytes $written] [lhtrace::fmt_bytes $total] $mib_s $elapsed_s]
    flush stdout
}

proc lhtrace::dump {{out_path ""} {byte_count ""} {address ""}} {
    global LH_TRACE_OUT LH_TRACE_ADDRESS LH_TRACE_BYTES LH_TRACE_AXI_LEN
    global LH_TRACE_BYTES_PER_BEAT

    if {$out_path eq ""} {
        set out_path $LH_TRACE_OUT
    }
    if {$byte_count eq ""} {
        set byte_count $LH_TRACE_BYTES
    }
    if {$address eq ""} {
        set address $LH_TRACE_ADDRESS
    }
    set byte_count [lhtrace::effective_dump_bytes $byte_count]

    set axi [lhtrace::find_axi]
    lhtrace::print_axi_properties $axi
    lhtrace::configure_axi_width $axi
    set selected_axi_len $LH_TRACE_AXI_LEN
    puts "Using configured AXI read length $selected_axi_len beats"
    lhtrace::configure_messages
    set fd [open $out_path wb]
    fconfigure $fd -translation binary -encoding binary

    set written 0
    set start_ms [clock milliseconds]

    puts [format "Dumping %s from AXI address 0x%x to %s" \
        [lhtrace::fmt_bytes $byte_count] $address $out_path]

    set rc [catch {
        while {$written < $byte_count} {
            set remaining [expr {$byte_count - $written}]
            set beats [expr {int(ceil(double($remaining) / $LH_TRACE_BYTES_PER_BEAT))}]
            if {$beats > $selected_axi_len} {
                set beats $selected_axi_len
            }

            set rc [catch {
                set hex [lhtrace::read_hex $axi [expr {$address + $written}] $beats]
            } err]
            if {$rc != 0} {
                error $err
            }

            # Vivado reports the transaction DATA property in display order
            # (most-significant byte first for the whole read). The trace DMA
            # stores little-endian sample words in increasing address order.
            set hex [lhtrace::reverse_hex_bytes $hex]
            set chunk_bytes [expr {[string length $hex] / 2}]
            if {$chunk_bytes > $remaining} {
                set hex [string range $hex 0 [expr {$remaining * 2 - 1}]]
                set chunk_bytes $remaining
            }

            puts -nonewline $fd [binary format H* $hex]
            incr written $chunk_bytes

            lhtrace::progress $written $byte_count $start_ms
        }
    } err]
    close $fd
    if {$rc != 0} {
        error $err
    }

    lhtrace::progress $written $byte_count $start_ms 1
    puts ""
    puts [format "Finished dump: %s (%d bytes)" [lhtrace::fmt_bytes $written] $written]

    return $written
}

puts "Loaded lhtrace::dump. Configure LH_TRACE_OUT/LH_TRACE_BYTES/LH_TRACE_ADDRESS if needed, then run lhtrace::dump."
