import struct
from typing import BinaryIO


LINKTYPE_USER0 = 147


def _pad4(data: bytes) -> bytes:
    return data + b"\x00" * ((4 - len(data) % 4) % 4)


def _option(code: int, data: bytes) -> bytes:
    return struct.pack("<HH", code, len(data)) + _pad4(data)


def _block(block_type: int, body: bytes) -> bytes:
    total_len = 12 + len(body)
    return struct.pack("<II", block_type, total_len) + body + struct.pack("<I", total_len)


class PcapngWriter:
    def __init__(self, output: BinaryIO, linktype: int = LINKTYPE_USER0, snaplen: int = 65535):
        self.output = output
        self.linktype = linktype
        self.snaplen = snaplen

    def write_header(self) -> None:
        shb_body = struct.pack("<IHHq", 0x1A2B3C4D, 1, 0, -1)
        self.output.write(_block(0x0A0D0D0A, shb_body))

        idb_body = struct.pack("<HHI", self.linktype, 0, self.snaplen)
        idb_body += _option(2, b"lauberhorn-trace")
        idb_body += _option(9, b"\x09")  # timestamp resolution: 10^-9 seconds
        idb_body += struct.pack("<HH", 0, 0)
        self.output.write(_block(1, idb_body))

    def write_packet(self, packet: bytes, timestamp_ns: int = 0, interface_id: int = 0) -> None:
        timestamp_ns = int(timestamp_ns)
        ts_high = (timestamp_ns >> 32) & 0xFFFFFFFF
        ts_low = timestamp_ns & 0xFFFFFFFF
        body = struct.pack("<IIIII", interface_id, ts_high, ts_low, len(packet), len(packet))
        body += _pad4(packet)
        self.output.write(_block(6, body))
