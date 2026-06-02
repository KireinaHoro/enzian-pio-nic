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
if {![info exists LH_TRACE_AXI_LEN_AUTO]} {
    set LH_TRACE_AXI_LEN_AUTO 1
}
if {![info exists LH_TRACE_AXI_LEN_CANDIDATES]} {
    set LH_TRACE_AXI_LEN_CANDIDATES {65536 32768 16384 8192 4096 2048 1024 512 256 128 64 32 16 8 4}
}
if {![info exists LH_TRACE_BYTES_PER_BEAT]} {
    set LH_TRACE_BYTES_PER_BEAT 8
}
if {![info exists LH_TRACE_BYTES_PER_BEAT_AUTO]} {
    set LH_TRACE_BYTES_PER_BEAT_AUTO 1
}
if {![info exists LH_TRACE_TRIM_DEFAULT_PATTERN]} {
    set LH_TRACE_TRIM_DEFAULT_PATTERN 1
}
if {![info exists LH_TRACE_TRIM_PATTERNS]} {
    set LH_TRACE_TRIM_PATTERNS {00ff ff00}
}
if {![info exists LH_TRACE_TRIM_MIN_BYTES]} {
    set LH_TRACE_TRIM_MIN_BYTES 256
}
if {![info exists LH_TRACE_TRIM_ALIGNMENT]} {
    set LH_TRACE_TRIM_ALIGNMENT 16
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

proc lhtrace::candidate_axi_lengths {axi} {
    global LH_TRACE_AXI_LEN_CANDIDATES

    set lengths $LH_TRACE_AXI_LEN_CANDIDATES
    set property_len [lhtrace::property_int $axi {
        MAX_BURST_LENGTH
        MAX_BURST_LEN
        C_MAX_BURST_LEN
        C_M_AXI_MAX_BURST_LEN
        CONFIG.MAX_BURST_LENGTH
        CONFIG.MAX_BURST_LEN
        CONFIG.C_MAX_BURST_LEN
        CONFIG.C_M_AXI_MAX_BURST_LEN
    }]
    if {$property_len ne "" && $property_len > 0} {
        set lengths [linsert $lengths 0 $property_len]
    }

    set unique {}
    foreach len $lengths {
        if {[regexp {^[0-9]+$} $len] && $len > 0 && [lsearch -exact $unique $len] < 0} {
            lappend unique $len
        }
    }
    return $unique
}

proc lhtrace::select_axi_len {axi address} {
    global LH_TRACE_AXI_LEN LH_TRACE_AXI_LEN_AUTO
    if {!$LH_TRACE_AXI_LEN_AUTO} {
        puts "Using configured AXI read length $LH_TRACE_AXI_LEN beats"
        return $LH_TRACE_AXI_LEN
    }

    set name lhtrace_probe
    foreach len [lhtrace::candidate_axi_lengths $axi] {
        set rc [catch {
            create_hw_axi_txn $name $axi -type read -address [format "0x%x" $address] -len $len -force
        } err]
        if {$rc == 0} {
            set LH_TRACE_AXI_LEN $len
            puts "Using AXI read length $len beats"
            return $len
        }
    }

    puts "Could not probe AXI read length; using configured $LH_TRACE_AXI_LEN beats"
    return $LH_TRACE_AXI_LEN
}

proc lhtrace::configure_messages {} {
    global LH_TRACE_SUPPRESS_READ_MESSAGES
    if {$LH_TRACE_SUPPRESS_READ_MESSAGES} {
        catch {set_msg_config -id {Labtoolstcl 44-481} -suppress}
    }
}

proc lhtrace::read_hex {axi address beats} {
    set name lhtrace_rd
    create_hw_axi_txn $name $axi -type read -address [format "0x%x" $address] -len $beats -force
    set txn [get_hw_axi_txns $name]
    run_hw_axi $txn
    return [lhtrace::txn_data_hex $txn]
}

proc lhtrace::find_pattern_run_hex {hex patterns min_bytes alignment} {
    set hex [lhtrace::normalize_hex $hex]
    set hex_len [string length $hex]
    set min_hex_len [expr {$min_bytes * 2}]
    if {$hex_len < $min_hex_len} {
        return -1
    }

    foreach pattern $patterns {
        set pattern [lhtrace::normalize_hex $pattern]
        set pattern_len [string length $pattern]
        if {$pattern_len == 0 || [expr {$pattern_len % 2}] != 0} {
            error "Invalid trim pattern '$pattern'"
        }

        set repeated ""
        while {[string length $repeated] < $min_hex_len} {
            append repeated $pattern
        }
        set repeated [string range $repeated 0 [expr {$min_hex_len - 1}]]

        set start [string first $repeated $hex]
        while {$start >= 0} {
            set byte_start [expr {$start / 2}]
            if {$alignment <= 0 || [expr {$byte_start % $alignment}] == 0} {
                return [expr {$start / 2}]
            }
            set start [string first $repeated $hex [expr {$start + 2}]]
        }
    }
    return -1
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

proc lhtrace::truncate_file {path bytes} {
    if {[llength [info commands chan]] != 0} {
        set fd [open $path r+]
        fconfigure $fd -translation binary -encoding binary
        if {![catch {chan truncate $fd $bytes} err]} {
            close $fd
            return
        }
        close $fd
    }

    set in_fd [open $path rb]
    fconfigure $in_fd -translation binary -encoding binary
    set data [read $in_fd $bytes]
    close $in_fd

    set fd [open $path r+]
    fconfigure $fd -translation binary -encoding binary
    puts -nonewline $fd $data
    if {[llength [info commands ftruncate]] != 0} {
        ftruncate $fd $bytes
    } else {
        close $fd
        set fd [open $path wb]
        fconfigure $fd -translation binary -encoding binary
        puts -nonewline $fd $data
    }
    close $fd
}

proc lhtrace::dump {{out_path ""} {byte_count ""} {address ""}} {
    global LH_TRACE_OUT LH_TRACE_ADDRESS LH_TRACE_BYTES LH_TRACE_AXI_LEN
    global LH_TRACE_BYTES_PER_BEAT LH_TRACE_TRIM_DEFAULT_PATTERN
    global LH_TRACE_TRIM_PATTERNS LH_TRACE_TRIM_MIN_BYTES LH_TRACE_TRIM_ALIGNMENT

    if {$out_path eq ""} {
        set out_path $LH_TRACE_OUT
    }
    if {$byte_count eq ""} {
        set byte_count $LH_TRACE_BYTES
    }
    if {$address eq ""} {
        set address $LH_TRACE_ADDRESS
    }

    set axi [lhtrace::find_axi]
    lhtrace::print_axi_properties $axi
    lhtrace::configure_axi_width $axi
    set selected_axi_len [lhtrace::select_axi_len $axi $address]
    lhtrace::configure_messages
    set fd [open $out_path wb]
    fconfigure $fd -translation binary -encoding binary

    set written 0
    set trim_offset -1
    set tail_hex ""
    set max_tail_hex [expr {($LH_TRACE_TRIM_MIN_BYTES + $LH_TRACE_TRIM_ALIGNMENT + 64) * 2}]
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
                if {$beats > 1} {
                    set selected_axi_len [expr {int($beats / 2)}]
                    if {$selected_axi_len < 1} {
                        set selected_axi_len 1
                    }
                    puts ""
                    puts "AXI read of $beats beats failed: $err"
                    puts "Retrying with $selected_axi_len beats"
                    continue
                }
                error $err
            }

            set chunk_bytes [expr {[string length $hex] / 2}]
            if {$chunk_bytes > $remaining} {
                set hex [string range $hex 0 [expr {$remaining * 2 - 1}]]
                set chunk_bytes $remaining
            }

            set search_hex "${tail_hex}${hex}"
            if {$LH_TRACE_TRIM_DEFAULT_PATTERN} {
                set found [lhtrace::find_pattern_run_hex \
                    $search_hex $LH_TRACE_TRIM_PATTERNS \
                    $LH_TRACE_TRIM_MIN_BYTES $LH_TRACE_TRIM_ALIGNMENT]
                if {$found >= 0} {
                    set tail_bytes [expr {[string length $tail_hex] / 2}]
                    set trim_offset [expr {$written - $tail_bytes + $found}]
                }
            }

            puts -nonewline $fd [binary format H* $hex]
            incr written $chunk_bytes

            if {[string length $search_hex] > $max_tail_hex} {
                set tail_hex [string range $search_hex [expr {[string length $search_hex] - $max_tail_hex}] end]
            } else {
                set tail_hex $search_hex
            }

            lhtrace::progress $written $byte_count $start_ms
            if {$trim_offset >= 0} {
                break
            }
        }
    } err]
    close $fd
    if {$rc != 0} {
        error $err
    }

    if {$trim_offset >= 0} {
        lhtrace::truncate_file $out_path $trim_offset
        set written $trim_offset
        lhtrace::progress $written $byte_count $start_ms 1
        puts ""
        puts [format "Detected default DDR tail and trimmed dump to %s (%d bytes)" \
            [lhtrace::fmt_bytes $written] $written]
    } else {
        lhtrace::progress $written $byte_count $start_ms 1
        puts ""
        puts [format "Finished dump: %s (%d bytes)" [lhtrace::fmt_bytes $written] $written]
    }

    return $written
}

puts "Loaded lhtrace::dump. Configure LH_TRACE_OUT/LH_TRACE_BYTES/LH_TRACE_ADDRESS if needed, then run lhtrace::dump."
