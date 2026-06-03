namespace eval lhtrace {
    variable progress_last_ms 0
    variable last_wrapped ""
}

proc lhtrace::default_options {} {
    return [dict create \
        -out "data/eci/sys_trace/trace-dram.bin" \
        -address 0x0 \
        -bytes 0x800000000 \
        -axi-len 256 \
        -bytes-per-beat 8 \
        -bytes-per-beat-auto 1 \
        -slot-bytes 64 \
        -use-vio-status 1 \
        -vio-filter {CELL_NAME =~ *vio_trace_status*} \
        -vio-probes {
            trace_write_slot
            trace_wrapped
            trace_sample_lost
            trace_dma_error
        } \
        -progress-interval-ms 1000 \
        -suppress-read-messages 1 \
        -print-axi-properties 1 \
        -append 0 \
        -append-verify-bytes 4096 \
    ]
}

proc lhtrace::usage {command} {
    set append_default 0
    if {$command eq "lhtrace::append"} {
        set append_default 1
    }
    return "usage: $command ?-out path? ?-bytes count? ?-address addr? ?-axi-len beats? ?-bytes-per-beat bytes? ?-bytes-per-beat-auto 0|1? ?-slot-bytes bytes? ?-use-vio-status 0|1? ?-vio-filter filter? ?-vio-probes probes? ?-progress-interval-ms ms? ?-suppress-read-messages 0|1? ?-print-axi-properties 0|1? ?-append-verify-bytes bytes? ?-append 0|1?\n\nDefaults:\n  -out data/eci/sys_trace/trace-dram.bin\n  -bytes 0x800000000\n  -address 0x0\n  -axi-len 256\n  -bytes-per-beat 8\n  -bytes-per-beat-auto 1\n  -slot-bytes 64\n  -use-vio-status 1\n  -vio-filter {CELL_NAME =~ *vio_trace_status*}\n  -vio-probes {trace_write_slot trace_wrapped trace_sample_lost trace_dma_error}\n  -progress-interval-ms 1000\n  -suppress-read-messages 1\n  -print-axi-properties 1\n  -append-verify-bytes 4096\n  -append $append_default"
}

proc lhtrace::wants_help {argv} {
    if {[llength $argv] != 1} {
        return 0
    }
    set arg [lindex $argv 0]
    return [expr {$arg eq "-help" || $arg eq "--help"}]
}

proc lhtrace::parse_options {argv command {append_default ""}} {
    set opts [lhtrace::default_options]
    if {$append_default ne ""} {
        dict set opts -append $append_default
    }

    for {set i 0} {$i < [llength $argv]} {incr i} {
        set name [lindex $argv $i]
        if {$name eq "-help" || $name eq "--help"} {
            error [lhtrace::usage $command]
        }
        if {![dict exists $opts $name]} {
            error "Unknown option '$name'\n[lhtrace::usage $command]"
        }
        incr i
        if {$i >= [llength $argv]} {
            error "Missing value for option '$name'\n[lhtrace::usage $command]"
        }
        dict set opts $name [lindex $argv $i]
    }

    return $opts
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
        ::append ret [string range $hex $pos [expr {$pos + 1}]]
    }
    return $ret
}

proc lhtrace::file_tail_hex {path byte_count} {
    set size [file size $path]
    if {$byte_count > $size} {
        set byte_count $size
    }
    if {$byte_count <= 0} {
        return ""
    }

    set fd [open $path rb]
    seek $fd [expr {$size - $byte_count}] start
    set data [read $fd $byte_count]
    close $fd

    binary scan $data H* hex
    return [string tolower $hex]
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

proc lhtrace::find_trace_vio {vio_filter} {
    set vios [get_hw_vios -filter $vio_filter]
    if {[llength $vios] == 0} {
        error "No trace status VIO found with filter '$vio_filter'"
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

proc lhtrace::vio_probe {vio short_name vio_probes} {
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
    set configured_index [lsearch -exact $vio_probes $short_name]
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

proc lhtrace::vio_probe_value {vio short_name vio_probes} {
    set probe [lhtrace::vio_probe $vio $short_name $vio_probes]
    foreach prop {INPUT_VALUE VALUE} {
        if {![catch {set value [get_property $prop $probe]}] && $value ne ""} {
            return [lhtrace::parse_hw_int $value 1]
        }
    }
    catch {report_property $probe}
    error "Could not read INPUT_VALUE/VALUE from VIO probe $short_name on $vio"
}

proc lhtrace::trace_status {vio_filter vio_probes} {
    set vio [lhtrace::find_trace_vio $vio_filter]
    if {[llength [info commands refresh_hw_vio]] != 0} {
        catch {refresh_hw_vio $vio}
    }

    if {[llength $vio_probes] != 4} {
        error "-vio-probes must contain exactly four probe names: writeSlot wrapped sampleLost dmaError"
    }

    set write_slot [lhtrace::vio_probe_value $vio [lindex $vio_probes 0] $vio_probes]
    set wrapped [lhtrace::vio_probe_value $vio [lindex $vio_probes 1] $vio_probes]
    set sample_lost [lhtrace::vio_probe_value $vio [lindex $vio_probes 2] $vio_probes]
    set dma_error [lhtrace::vio_probe_value $vio [lindex $vio_probes 3] $vio_probes]

    puts "Trace status: writeSlot=$write_slot wrapped=$wrapped sampleLost=$sample_lost dmaError=$dma_error"
    return [list $write_slot $wrapped $sample_lost $dma_error]
}

proc lhtrace::effective_dump_bytes {requested_bytes use_vio_status slot_bytes vio_filter vio_probes} {
    variable last_wrapped
    set last_wrapped ""
    if {!$use_vio_status} {
        return $requested_bytes
    }

    lassign [lhtrace::trace_status $vio_filter $vio_probes] write_slot wrapped sample_lost dma_error
    set last_wrapped $wrapped
    set status_bytes [expr {$write_slot * $slot_bytes}]
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

proc lhtrace::print_axi_properties {axi print_axi_properties} {
    if {!$print_axi_properties} {
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

proc lhtrace::configure_axi_width {axi bytes_per_beat bytes_per_beat_auto} {
    if {!$bytes_per_beat_auto} {
        return $bytes_per_beat
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
        set bytes_per_beat [expr {$width / 8}]
        puts "Using $bytes_per_beat bytes per AXI beat from DATA_WIDTH=$width"
    } else {
        puts "Using configured $bytes_per_beat bytes per AXI beat"
    }
    return $bytes_per_beat
}

proc lhtrace::configure_messages {suppress_read_messages} {
    if {$suppress_read_messages} {
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

proc lhtrace::read_bytes_hex {axi address byte_count max_beats bytes_per_beat} {
    if {$bytes_per_beat <= 0 || $max_beats <= 0} {
        error "AXI read geometry must be positive"
    }

    set ret ""
    set read_bytes 0
    while {$read_bytes < $byte_count} {
        set remaining [expr {$byte_count - $read_bytes}]
        set beats [expr {int(ceil(double($remaining) / $bytes_per_beat))}]
        if {$beats > $max_beats} {
            set beats $max_beats
        }

        set hex [lhtrace::read_hex $axi [expr {$address + $read_bytes}] $beats]

        # Vivado reports the transaction DATA property in display order
        # (most-significant byte first for the whole read). The trace DMA
        # stores little-endian sample words in increasing address order.
        set hex [lhtrace::reverse_hex_bytes $hex]
        set chunk_bytes [expr {[string length $hex] / 2}]
        if {$chunk_bytes > $remaining} {
            set hex [string range $hex 0 [expr {$remaining * 2 - 1}]]
            set chunk_bytes $remaining
        }
        if {$chunk_bytes <= 0} {
            error "AXI read returned no data"
        }
        ::append ret $hex
        incr read_bytes $chunk_bytes
    }
    return $ret
}

proc lhtrace::progress {written total start_ms progress_interval_ms {force 0}} {
    variable progress_last_ms
    set now [clock milliseconds]
    if {!$force && $now - $progress_last_ms < $progress_interval_ms} {
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

proc lhtrace::append_start_offset {axi out_path byte_count address selected_axi_len slot_bytes append_verify_bytes bytes_per_beat} {
    variable last_wrapped

    if {$last_wrapped ne "" && $last_wrapped} {
        error "Cannot append trace dump after the trace buffer has wrapped; take a fresh full dump instead"
    }

    if {![file exists $out_path]} {
        puts "Append requested but $out_path does not exist; starting a new dump"
        return 0
    }
    if {[file isdirectory $out_path]} {
        error "Cannot append trace dump; output path is a directory: $out_path"
    }

    set size [file size $out_path]
    if {$size == 0} {
        puts "Append requested and $out_path is empty; starting at offset 0"
        return 0
    }
    if {$size > $byte_count} {
        error [format "Cannot append trace dump: existing file size %s exceeds current capture size %s" \
            [lhtrace::fmt_bytes $size] [lhtrace::fmt_bytes $byte_count]]
    }
    if {[expr {$size % $slot_bytes}] != 0} {
        error [format "Cannot append trace dump: existing file size %s is not aligned to trace slot size %s" \
            [lhtrace::fmt_bytes $size] [lhtrace::fmt_bytes $slot_bytes]]
    }
    if {$size == $byte_count} {
        puts [format "Existing dump already reaches current write slot (%s); nothing to append" \
            [lhtrace::fmt_bytes $byte_count]]
        return $size
    }

    set verify_bytes $append_verify_bytes
    if {$verify_bytes > $size} {
        set verify_bytes $size
    }
    if {$verify_bytes <= 0} {
        return $size
    }

    set verify_offset [expr {$size - $verify_bytes}]
    puts [format "Verifying existing dump tail: %s at file offset 0x%x against AXI address 0x%x" \
        [lhtrace::fmt_bytes $verify_bytes] $verify_offset [expr {$address + $verify_offset}]]
    set file_hex [lhtrace::file_tail_hex $out_path $verify_bytes]
    set axi_hex [lhtrace::read_bytes_hex $axi [expr {$address + $verify_offset}] $verify_bytes $selected_axi_len $bytes_per_beat]
    if {$file_hex ne $axi_hex} {
        error [format "Cannot append trace dump: existing file tail does not match trace buffer at offset 0x%x" \
            $verify_offset]
    }

    puts [format "Append verified; resuming at offset 0x%x (%s)" $size [lhtrace::fmt_bytes $size]]
    return $size
}

proc lhtrace::dump {args} {
    if {[lhtrace::wants_help $args]} {
        puts [lhtrace::usage lhtrace::dump]
        return 0
    }
    set opts [lhtrace::parse_options $args lhtrace::dump]

    set out_path [dict get $opts -out]
    set byte_count_arg [dict get $opts -bytes]
    set address_arg [dict get $opts -address]
    set selected_axi_len_arg [dict get $opts -axi-len]
    set bytes_per_beat_arg [dict get $opts -bytes-per-beat]
    set bytes_per_beat_auto_arg [dict get $opts -bytes-per-beat-auto]
    set slot_bytes_arg [dict get $opts -slot-bytes]
    set use_vio_status_arg [dict get $opts -use-vio-status]
    set byte_count [expr {$byte_count_arg}]
    set address [expr {$address_arg}]
    set selected_axi_len [expr {$selected_axi_len_arg}]
    set bytes_per_beat [expr {$bytes_per_beat_arg}]
    set bytes_per_beat_auto [expr {$bytes_per_beat_auto_arg}]
    set slot_bytes [expr {$slot_bytes_arg}]
    set use_vio_status [expr {$use_vio_status_arg}]
    set vio_filter [dict get $opts -vio-filter]
    set vio_probes [dict get $opts -vio-probes]
    set progress_interval_ms_arg [dict get $opts -progress-interval-ms]
    set suppress_read_messages_arg [dict get $opts -suppress-read-messages]
    set print_axi_properties_arg [dict get $opts -print-axi-properties]
    set append_mode_arg [dict get $opts -append]
    set append_verify_bytes_arg [dict get $opts -append-verify-bytes]
    set progress_interval_ms [expr {$progress_interval_ms_arg}]
    set suppress_read_messages [expr {$suppress_read_messages_arg}]
    set print_axi_properties [expr {$print_axi_properties_arg}]
    set append_mode [expr {$append_mode_arg}]
    set append_verify_bytes [expr {$append_verify_bytes_arg}]

    set byte_count [lhtrace::effective_dump_bytes \
        $byte_count $use_vio_status $slot_bytes $vio_filter $vio_probes]

    set axi [lhtrace::find_axi]
    lhtrace::print_axi_properties $axi $print_axi_properties
    set bytes_per_beat [lhtrace::configure_axi_width $axi $bytes_per_beat $bytes_per_beat_auto]
    puts "Using configured AXI read length $selected_axi_len beats"
    lhtrace::configure_messages $suppress_read_messages

    set start_offset 0
    set open_mode wb
    if {$append_mode} {
        set start_offset [lhtrace::append_start_offset \
            $axi $out_path $byte_count $address $selected_axi_len $slot_bytes \
            $append_verify_bytes $bytes_per_beat]
        set open_mode ab
    }

    set fd [open $out_path $open_mode]
    fconfigure $fd -translation binary -encoding binary

    set written $start_offset
    set start_ms [clock milliseconds]

    if {$append_mode} {
        puts [format "Appending %s from AXI address 0x%x to %s" \
            [lhtrace::fmt_bytes [expr {$byte_count - $start_offset}]] \
            [expr {$address + $start_offset}] $out_path]
    } else {
        puts [format "Dumping %s from AXI address 0x%x to %s" \
            [lhtrace::fmt_bytes $byte_count] $address $out_path]
    }

    set rc [catch {
        while {$written < $byte_count} {
            set remaining [expr {$byte_count - $written}]
            set max_chunk_bytes [expr {$selected_axi_len * $bytes_per_beat}]
            set chunk_target [expr {$remaining < $max_chunk_bytes ? $remaining : $max_chunk_bytes}]
            set hex [lhtrace::read_bytes_hex $axi [expr {$address + $written}] $chunk_target $selected_axi_len $bytes_per_beat]
            set chunk_bytes [expr {[string length $hex] / 2}]

            puts -nonewline $fd [binary format H* $hex]
            incr written $chunk_bytes

            lhtrace::progress $written $byte_count $start_ms $progress_interval_ms
        }
    } err]
    close $fd
    if {$rc != 0} {
        error $err
    }

    lhtrace::progress $written $byte_count $start_ms $progress_interval_ms 1
    puts ""
    puts [format "Finished dump: %s (%d bytes)" [lhtrace::fmt_bytes $written] $written]

    return $written
}

proc lhtrace::append {args} {
    if {[lhtrace::wants_help $args]} {
        puts [lhtrace::usage lhtrace::append]
        return 0
    }
    set opts [lhtrace::parse_options $args lhtrace::append 1]
    dict set opts -append 1
    return [lhtrace::dump {*}$opts]
}

puts "Loaded lhtrace::dump/lhtrace::append. Run lhtrace::dump ?-out path? ?-bytes count? ?-address addr? or lhtrace::append with the same options."
