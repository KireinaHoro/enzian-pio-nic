import re
import socket
import subprocess
import tempfile
from pathlib import Path
from typing import Optional


VIVADO_JTAG_AXI_DUMP_TCL = r"""
proc fail {msg} {
    puts stderr "ERROR: $msg"
    exit 1
}

proc normalize_id {text} {
    set value [string trim $text]
    regsub -nocase {^0x} $value {} value
    return [string toupper $value]
}

proc prop_or_empty {obj prop} {
    if {[catch {get_property $prop $obj} value]} {
        return ""
    }
    return $value
}

proc select_hw_device {jtag_id} {
    set devices [get_hw_devices]
    if {[llength $devices] == 0} {
        fail "No hardware devices found on the open hardware target"
    }

    if {$jtag_id eq ""} {
        return [lindex $devices 0]
    }

    set wanted [normalize_id $jtag_id]
    foreach dev $devices {
        set idcode [normalize_id [prop_or_empty $dev IDCODE]]
        set name [prop_or_empty $dev NAME]
        set target [prop_or_empty [current_hw_target] NAME]
        if {$idcode eq $wanted ||
            [string match $jtag_id $name] || [string match "*$jtag_id*" $name] ||
            [string match $jtag_id $dev] || [string match "*$jtag_id*" $dev] ||
            [string match $jtag_id $target] || [string match "*$jtag_id*" $target]} {
            return $dev
        }
    }

    set seen {}
    foreach dev $devices {
        lappend seen "[prop_or_empty $dev NAME](IDCODE=[prop_or_empty $dev IDCODE], target=[prop_or_empty [current_hw_target] NAME])"
    }
    fail "No hardware device matched --fpga-jtag-id '$jtag_id'. Available devices: [join $seen {, }]"
}

proc object_matches {obj pattern} {
    if {$pattern eq ""} {
        return 1
    }
    foreach candidate [list $obj [prop_or_empty $obj NAME] [prop_or_empty $obj CELL_NAME]] {
        if {[string match $pattern $candidate] || [string match "*$pattern*" $candidate]} {
            return 1
        }
    }
    return 0
}

proc select_hw_axi {dev pattern} {
    set axis [get_hw_axis -quiet -of_objects $dev]
    if {[llength $axis] == 0} {
        fail "No JTAG-to-AXI master cores found on hardware device $dev"
    }

    foreach axi $axis {
        if {[object_matches $axi $pattern]} {
            return $axi
        }
    }

    set seen {}
    foreach axi $axis {
        lappend seen "[prop_or_empty $axi NAME](CELL_NAME=[prop_or_empty $axi CELL_NAME])"
    }
    fail "No JTAG-to-AXI core matched --jtag-axi-name '$pattern'. Available AXI cores: [join $seen {, }]"
}

proc split_data_words {data word_hex expected_words} {
    set tokens [regexp -all -inline {[0-9A-Fa-f_]+} $data]
    set clean_tokens {}
    foreach token $tokens {
        set clean [string map {_ ""} $token]
        if {$clean ne ""} {
            lappend clean_tokens [string toupper $clean]
        }
    }

    if {[llength $clean_tokens] == $expected_words} {
        return $clean_tokens
    }

    if {[llength $clean_tokens] == 1} {
        set joined [lindex $clean_tokens 0]
        set expected_hex [expr {$word_hex * $expected_words}]
        while {[string length $joined] < $expected_hex} {
            set joined "0$joined"
        }
        if {[string length $joined] != $expected_hex} {
            fail "Read DATA length [string length $joined] does not match expected $expected_hex hex digits"
        }

        set words {}
        for {set i 0} {$i < $expected_words} {incr i} {
            set lo [expr {$i * $word_hex}]
            set hi [expr {$lo + $word_hex - 1}]
            lappend words [string range $joined $lo $hi]
        }
        return $words
    }

    fail "Read DATA returned [llength $clean_tokens] words, expected $expected_words"
}

proc write_word_le {fh hex width_bytes limit_bytes} {
    set word_hex [expr {$width_bytes * 2}]
    set clean [string map {_ ""} $hex]
    while {[string length $clean] < $word_hex} {
        set clean "0$clean"
    }
    if {[string length $clean] > $word_hex} {
        set clean [string range $clean end-[expr {$word_hex - 1}] end]
    }

    set written 0
    for {set byte_index [expr {$width_bytes - 1}]} {$byte_index >= 0 && $written < $limit_bytes} {incr byte_index -1} {
        set pos [expr {$byte_index * 2}]
        puts -nonewline $fh [binary format H2 [string range $clean $pos [expr {$pos + 1}]]]
        incr written
    }
}

if {[llength $argv] != 9} {
    fail "Usage: <hw_host> <hw_port> <fpga_jtag_id> <jtag_axi_name> <address> <bytes> <word_bits> <max_beats> <out_file>"
}

set hw_host [lindex $argv 0]
set hw_port [lindex $argv 1]
set fpga_jtag_id [lindex $argv 2]
set jtag_axi_name [lindex $argv 3]
set base_addr [expr {wide([lindex $argv 4])}]
set total_bytes [expr {wide([lindex $argv 5])}]
set word_bits [expr {int([lindex $argv 6])}]
set max_beats [expr {int([lindex $argv 7])}]
set out_file [lindex $argv 8]

if {$total_bytes <= 0} {
    fail "Dump byte count must be positive"
}
if {$word_bits != 32 && $word_bits != 64} {
    fail "Only 32-bit and 64-bit JTAG AXI read words are supported"
}
if {$max_beats <= 0 || $max_beats > 256} {
    fail "max_beats must be in the range 1..256"
}

set word_bytes [expr {$word_bits / 8}]
set word_hex [expr {$word_bytes * 2}]
set max_txn_bytes [expr {$word_bytes * $max_beats}]

open_hw
connect_hw_server -url "$hw_host:$hw_port"

set targets [get_hw_targets]
if {[llength $targets] == 0} {
    fail "No hardware targets found on $hw_host:$hw_port"
}

current_hw_target [lindex $targets 0]
open_hw_target

set dev [select_hw_device $fpga_jtag_id]
current_hw_device $dev
refresh_hw_device -quiet $dev

set axi [select_hw_axi $dev $jtag_axi_name]
reset_hw_axi $axi

set fh [open $out_file "wb"]
fconfigure $fh -translation binary -encoding binary

set remaining $total_bytes
set addr $base_addr
set txn_index 0

while {$remaining > 0} {
    set txn_bytes $remaining
    if {$txn_bytes > $max_txn_bytes} {
        set txn_bytes $max_txn_bytes
    }
    set beats [expr {($txn_bytes + $word_bytes - 1) / $word_bytes}]
    set txn_name "lauberhorn_trace_rd_$txn_index"

    delete_hw_axi_txn -quiet [get_hw_axi_txns $txn_name]
    create_hw_axi_txn $txn_name $axi -type read -address [format %x $addr] -len $beats -size $word_bits -force
    run_hw_axi [get_hw_axi_txns $txn_name]

    set txn [get_hw_axi_txns $txn_name]
    set data [get_property DATA $txn]
    set words [split_data_words $data $word_hex $beats]

    set bytes_left_in_txn $txn_bytes
    foreach word $words {
        set limit $word_bytes
        if {$bytes_left_in_txn < $limit} {
            set limit $bytes_left_in_txn
        }
        write_word_le $fh $word $word_bytes $limit
        set bytes_left_in_txn [expr {$bytes_left_in_txn - $limit}]
    }

    delete_hw_axi_txn -quiet $txn
    set remaining [expr {$remaining - $txn_bytes}]
    set addr [expr {$addr + $txn_bytes}]
    incr txn_index
}

close $fh
close_hw_target
disconnect_hw_server
"""


def write_dump_script(path: Path) -> None:
    path.write_text(VIVADO_JTAG_AXI_DUMP_TCL)


def dump_trace_buffer(
    *,
    output_path: Path,
    byte_count: int,
    address: int = 0,
    hw_server_host: str = "localhost",
    hw_server_port: int = 3121,
    fpga_jtag_id: Optional[str] = None,
    jtag_axi_name: Optional[str] = None,
    vivado_bin: str = "vivado",
    word_bits: int = 64,
    max_beats: int = 256,
    tcl_path: Optional[Path] = None,
) -> None:
    if byte_count <= 0:
        raise ValueError("byte_count must be positive")
    if word_bits not in (32, 64):
        raise ValueError("word_bits must be 32 or 64")
    if max_beats <= 0 or max_beats > 256:
        raise ValueError("max_beats must be in the range 1..256")

    output_path.parent.mkdir(parents=True, exist_ok=True)

    remove_tcl = False
    if tcl_path is None:
        with tempfile.NamedTemporaryFile("w", suffix=".tcl", prefix="lauberhorn_vivado_dump_", delete=False) as f:
            script_path = Path(f.name)
            f.write(VIVADO_JTAG_AXI_DUMP_TCL)
        remove_tcl = True
    else:
        script_path = tcl_path
        write_dump_script(script_path)

    try:
        subprocess.run(
            [
                vivado_bin,
                "-mode",
                "batch",
                "-source",
                str(script_path),
                "-tclargs",
                hw_server_host,
                str(hw_server_port),
                fpga_jtag_id or "",
                jtag_axi_name or "",
                str(address),
                str(byte_count),
                str(word_bits),
                str(max_beats),
                str(output_path),
            ],
            check=True,
        )
    finally:
        if remove_tcl:
            script_path.unlink(missing_ok=True)


class XsdbError(RuntimeError):
    pass


class XsdbClient:
    def __init__(self, host: str, port: int, timeout_s: float = 30.0):
        try:
            self._sock = socket.create_connection((host, port), timeout=timeout_s)
        except OSError as e:
            raise XsdbError(f"Could not connect to XSDB command server at {host}:{port}: {e}") from e
        self._file = self._sock.makefile("rwb")

    def close(self) -> None:
        self._file.close()
        self._sock.close()

    def command(self, command: str) -> str:
        self._file.write(command.encode("utf-8") + b"\n")
        self._file.flush()
        line = self._file.readline()
        if not line:
            raise XsdbError("XSDB command server closed the connection")

        status, _, payload = line.decode("utf-8", errors="replace").rstrip("\r\n").partition(" ")
        result = _tcl_backslash_unquote(payload)
        if status == "okay":
            return result
        if status == "error":
            raise XsdbError(result or f"XSDB command failed: {command}")
        raise XsdbError(f"Unexpected XSDB response to {command!r}: {line!r}")

    def __enter__(self) -> "XsdbClient":
        return self

    def __exit__(self, _exc_type, _exc, _tb) -> None:
        self.close()


def _tcl_backslash_unquote(text: str) -> str:
    """Decode the xsdbserver single-line backslash-quoted result format."""
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if c != "\\" or i + 1 >= len(text):
            out.append(c)
            i += 1
            continue

        nxt = text[i + 1]
        simple = {"n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f", "v": "\v"}
        if nxt in simple:
            out.append(simple[nxt])
            i += 2
        elif nxt == "x":
            j = i + 2
            while j < len(text) and j < i + 4 and text[j] in "0123456789abcdefABCDEF":
                j += 1
            if j == i + 2:
                out.append("x")
            else:
                out.append(chr(int(text[i + 2:j], 16)))
            i = j
        elif nxt == "u" and i + 5 < len(text):
            digits = text[i + 2:i + 6]
            if all(ch in "0123456789abcdefABCDEF" for ch in digits):
                out.append(chr(int(digits, 16)))
                i += 6
            else:
                out.append(nxt)
                i += 2
        elif nxt in "\r\n":
            i += 2
            if nxt == "\r" and i < len(text) and text[i] == "\n":
                i += 1
            while i < len(text) and text[i] in " \t":
                i += 1
        else:
            out.append(nxt)
            i += 2
    return "".join(out)


def _xsdb_quote(value: str) -> str:
    return "{" + value.replace("\\", "\\\\").replace("}", "\\}") + "}"


def _xsdb_target_filter(fpga_jtag_id: Optional[str]) -> str:
    terms = ['name =~ "JTAG2AXI"']
    if fpga_jtag_id:
        raw = fpga_jtag_id
        token = raw.rsplit("/", 1)[-1]
        norm = token[2:] if token.lower().startswith("0x") else token
        id_terms = [
            f'jtag_device_ctx =~ "*{token}*"',
            f'jtag_device_ctx =~ "*{norm}*"',
            f'jtag_cable_name =~ "*{token}*"',
            f'jtag_cable_serial =~ "*{token}*"',
            f'jtag_device_name =~ "*{token}*"',
        ]
        terms.append("(" + " || ".join(id_terms) + ")")
    return " && ".join(terms)


def _xsdb_connect(xsdb: XsdbClient, hw_server_host: str, hw_server_port: int) -> None:
    url = f"TCP:{hw_server_host}:{int(hw_server_port)}"
    try:
        xsdb.command(f"connect -url {_xsdb_quote(url)}")
    except XsdbError:
        xsdb.command(f"connect -host {_xsdb_quote(hw_server_host)} -port {int(hw_server_port)}")


def _parse_mrd_words(text: str, expected_words: int) -> list[int]:
    words = [_parse_int_word(token) for token in re.findall(r"(?:0x)?[0-9a-fA-F]{1,16}", text)]
    if len(words) != expected_words:
        raise XsdbError(f"mrd returned {len(words)} words, expected {expected_words}: {text[:200]!r}")
    return words


def _parse_int_word(token: str) -> int:
    if token.lower().startswith("0x") or any(ch in "abcdefABCDEF" for ch in token):
        return int(token, 16)
    return int(token, 10)


def _write_le_word(f, word: int, word_bytes: int, limit: int) -> None:
    f.write(int(word).to_bytes(word_bytes, "little")[:limit])


def dump_trace_buffer_xsdb(
    *,
    output_path: Path,
    byte_count: int,
    address: int = 0,
    hw_server_host: str = "localhost",
    hw_server_port: int = 3121,
    fpga_jtag_id: Optional[str] = None,
    jtag_axi_name: Optional[str] = None,
    xsdb_server_host: str = "localhost",
    xsdb_server_port: int = 3010,
    word_bits: int = 32,
    max_beats: int = 256,
) -> None:
    if byte_count <= 0:
        raise ValueError("byte_count must be positive")
    if word_bits != 32:
        raise ValueError("XSDB mrd readout currently supports 32-bit words only")
    if max_beats <= 0 or max_beats > 256:
        raise ValueError("max_beats must be in the range 1..256")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    word_bytes = word_bits // 8
    max_txn_bytes = word_bytes * max_beats

    with XsdbClient(xsdb_server_host, xsdb_server_port) as xsdb, output_path.open("wb") as f:
        _xsdb_connect(xsdb, hw_server_host, hw_server_port)
        xsdb.command(f"targets -set -filter {{{_xsdb_target_filter(fpga_jtag_id)}}}")

        remaining = byte_count
        addr = address
        while remaining > 0:
            txn_bytes = min(remaining, max_txn_bytes)
            beats = (txn_bytes + word_bytes - 1) // word_bytes
            words = _parse_mrd_words(xsdb.command(f"mrd -value 0x{addr:x} {beats}"), beats)
            bytes_left = txn_bytes
            for word in words:
                limit = min(word_bytes, bytes_left)
                _write_le_word(f, word, word_bytes, limit)
                bytes_left -= limit
            remaining -= txn_bytes
            addr += txn_bytes
