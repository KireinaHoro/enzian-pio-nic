import argparse
import re
import socket
from pathlib import Path
from typing import Optional


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


def _xsdb_select_jtag_axi(
    xsdb: XsdbClient,
    *,
    hw_server_host: str,
    hw_server_port: int,
    fpga_jtag_id: Optional[str],
) -> None:
    _xsdb_connect(xsdb, hw_server_host, hw_server_port)
    xsdb.command(f"targets -set -filter {{{_xsdb_target_filter(fpga_jtag_id)}}}")


def _xsdb_read_words(xsdb: XsdbClient, address: int, word_count: int) -> list[int]:
    return _parse_mrd_words(xsdb.command(f"mrd -force -value 0x{address:x} {word_count}"), word_count)


def _xsdb_write_words(xsdb: XsdbClient, address: int, words: list[int]) -> None:
    for index, word in enumerate(words):
        xsdb.command(f"mwr -force 0x{address + index * 4:x} 0x{int(word) & 0xffffffff:08x}")


def _test_pattern(address: int, word_count: int) -> list[int]:
    return [
        (0xa5a50000 ^ ((address + index * 4) & 0xffff) ^ (index * 0x01010101)) & 0xffffffff
        for index in range(word_count)
    ]


def test_jtag_axi_memory_xsdb(
    *,
    address: int,
    byte_count: int,
    hw_server_host: str = "localhost",
    hw_server_port: int = 3121,
    fpga_jtag_id: Optional[str] = None,
    xsdb_server_host: str = "localhost",
    xsdb_server_port: int = 3010,
    restore: bool = True,
) -> bool:
    if byte_count <= 0 or byte_count % 4 != 0:
        raise ValueError("byte_count must be a positive multiple of 4")

    word_count = byte_count // 4
    pattern = _test_pattern(address, word_count)

    with XsdbClient(xsdb_server_host, xsdb_server_port) as xsdb:
        _xsdb_select_jtag_axi(
            xsdb,
            hw_server_host=hw_server_host,
            hw_server_port=hw_server_port,
            fpga_jtag_id=fpga_jtag_id,
        )

        before = _xsdb_read_words(xsdb, address, word_count)
        _xsdb_write_words(xsdb, address, pattern)
        after = _xsdb_read_words(xsdb, address, word_count)
        matched = after == pattern

        restored = None
        if restore:
            _xsdb_write_words(xsdb, address, before)
            restored = _xsdb_read_words(xsdb, address, word_count)

    print(f"address: 0x{address:x}")
    print(f"words:   {word_count}")
    print("before:  " + " ".join(f"0x{word:08x}" for word in before))
    print("pattern: " + " ".join(f"0x{word:08x}" for word in pattern))
    print("after:   " + " ".join(f"0x{word:08x}" for word in after))
    print(f"match:   {'yes' if matched else 'no'}")
    if all(word == 0xdec0dee3 for word in before + after):
        print("warning: repeated 0xdec0dee3 before/after is consistent with an AXI DECERR read response")
    if restored is not None:
        print("restore: " + " ".join(f"0x{word:08x}" for word in restored))
        print(f"restored:{' yes' if restored == before else ' no'}")

    return matched


def dump_trace_buffer(
    *,
    output_path: Path,
    byte_count: int,
    address: int = 0,
    hw_server_host: str = "localhost",
    hw_server_port: int = 3121,
    fpga_jtag_id: Optional[str] = None,
    xsdb_server_host: str = "localhost",
    xsdb_server_port: int = 3010,
    max_beats: int = 256,
) -> None:
    if byte_count <= 0:
        raise ValueError("byte_count must be positive")
    if max_beats <= 0 or max_beats > 256:
        raise ValueError("max_beats must be in the range 1..256")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    word_bytes = 4
    max_txn_bytes = word_bytes * max_beats

    with XsdbClient(xsdb_server_host, xsdb_server_port) as xsdb, output_path.open("wb") as f:
        _xsdb_select_jtag_axi(
            xsdb,
            hw_server_host=hw_server_host,
            hw_server_port=hw_server_port,
            fpga_jtag_id=fpga_jtag_id,
        )

        remaining = byte_count
        addr = address
        while remaining > 0:
            txn_bytes = min(remaining, max_txn_bytes)
            beats = (txn_bytes + word_bytes - 1) // word_bytes
            words = _xsdb_read_words(xsdb, addr, beats)
            bytes_left = txn_bytes
            for word in words:
                limit = min(word_bytes, bytes_left)
                _write_le_word(f, word, word_bytes, limit)
                bytes_left -= limit
            remaining -= txn_bytes
            addr += txn_bytes


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    test_parser = subparsers.add_parser("test-xsdb-memory", help="Write/read/restore a small JTAG AXI memory window")
    test_parser.add_argument("--xsdb-server-host", default="localhost")
    test_parser.add_argument("--xsdb-server-port", type=int, default=3010)
    test_parser.add_argument("--hw-server-host", default="localhost")
    test_parser.add_argument("--hw-server-port", type=int, default=3121)
    test_parser.add_argument("--fpga-jtag-id", default=None)
    test_parser.add_argument("--address", type=lambda x: int(x, 0), default=0)
    test_parser.add_argument("--bytes", type=lambda x: int(x, 0), default=16)
    test_parser.add_argument("--no-restore", action="store_true")

    args = parser.parse_args()
    if args.command == "test-xsdb-memory":
        try:
            matched = test_jtag_axi_memory_xsdb(
                address=args.address,
                byte_count=args.bytes,
                hw_server_host=args.hw_server_host,
                hw_server_port=args.hw_server_port,
                fpga_jtag_id=args.fpga_jtag_id,
                xsdb_server_host=args.xsdb_server_host,
                xsdb_server_port=args.xsdb_server_port,
                restore=not args.no_restore,
            )
        except XsdbError as e:
            raise SystemExit(str(e)) from e
        return 0 if matched else 1

    raise SystemExit(f"Unknown command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
