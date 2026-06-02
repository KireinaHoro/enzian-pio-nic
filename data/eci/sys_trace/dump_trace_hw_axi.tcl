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

proc lhtrace::configure_messages {} {
    global LH_TRACE_SUPPRESS_READ_MESSAGES
    if {$LH_TRACE_SUPPRESS_READ_MESSAGES} {
        catch {set_msg_config -id {Labtoolstcl 44-481} -suppress}
    }
}

proc lhtrace::read_hex {axi address beats} {
    set name lhtrace_rd
    if {[llength [info commands delete_hw_axi_txn]] != 0} {
        catch {delete_hw_axi_txn [get_hw_axi_txns $name]}
    }
    create_hw_axi_txn $name $axi -type read -address [format "0x%x" $address] -len $beats -force
    set txn [get_hw_axi_txns $name]
    run_hw_axi $txn
    return [lhtrace::txn_data_hex $txn]
}

proc lhtrace::find_pattern_run_hex {hex patterns min_bytes alignment} {
    set hex [lhtrace::normalize_hex $hex]
    set hex_len [string length $hex]
    set min_hex_len [expr {$min_bytes * 2}]
    set step_hex [expr {$alignment * 2}]
    if {$step_hex <= 0} {
        set step_hex 2
    }
    if {$hex_len < $min_hex_len} {
        return -1
    }

    for {set start 0} {$start <= $hex_len - $min_hex_len} {incr start $step_hex} {
        foreach pattern $patterns {
            set pattern [lhtrace::normalize_hex $pattern]
            set pattern_len [string length $pattern]
            if {$pattern_len == 0 || [expr {$pattern_len % 2}] != 0} {
                error "Invalid trim pattern '$pattern'"
            }

            set pos $start
            while {$pos < $hex_len} {
                set expected_end [expr {$pos + $pattern_len - 1}]
                if {$expected_end >= $hex_len} {
                    break
                }
                if {[string range $hex $pos $expected_end] ne $pattern} {
                    break
                }
                incr pos $pattern_len
            }

            if {$pos - $start >= $min_hex_len} {
                return [expr {$start / 2}]
            }
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
            if {$beats > $LH_TRACE_AXI_LEN} {
                set beats $LH_TRACE_AXI_LEN
            }

            set hex [lhtrace::read_hex $axi [expr {$address + $written}] $beats]
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
