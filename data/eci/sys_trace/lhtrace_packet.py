import json
import struct
from typing import Any, Dict


MAGIC = b"LHTR"
VERSION = 2

KIND_METADATA = 1
KIND_SAMPLE = 2
KIND_LOST = 3
KIND_BUBBLE = 4

SOURCE_METADATA = 0xFFFF

HEADER = struct.Struct("<4sBBHQQQHHII")
HEADER_LEN = HEADER.size


def packet_timestamp_ns(timestamp: int, cycle_ns: int) -> int:
    return int(timestamp) * cycle_ns


def _packet(
    kind: int,
    logical_sample: int = 0,
    physical_sample: int = 0,
    timestamp: int = 0,
    source: int = 0,
    lost_count: int = 0,
    payload: bytes = b"",
) -> bytes:
    header = HEADER.pack(
        MAGIC,
        VERSION,
        kind,
        0,
        int(logical_sample),
        int(physical_sample),
        int(timestamp),
        int(source),
        0,
        int(lost_count),
        len(payload),
    )
    return header + payload


def metadata_packet(trace_map: Dict[str, Any]) -> bytes:
    export_map = {key: value for key, value in trace_map.items() if key != "sources_by_id"}
    payload = json.dumps(export_map, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _packet(KIND_METADATA, source=SOURCE_METADATA, payload=payload)


def sample_packet(logical_sample: int, physical_sample: int, timestamp: int, source: int, sample: int, sample_bytes: int) -> bytes:
    return _packet(
        KIND_SAMPLE,
        logical_sample=logical_sample,
        physical_sample=physical_sample,
        timestamp=timestamp,
        source=source,
        payload=int(sample).to_bytes(sample_bytes, "little"),
    )


def marker_packet(logical_sample: int, physical_sample: int, timestamp: int, source: int, lost_count: int) -> bytes:
    kind = KIND_BUBBLE if lost_count == 0 else KIND_LOST
    return _packet(
        kind,
        logical_sample=logical_sample,
        physical_sample=physical_sample,
        timestamp=timestamp,
        source=source,
        lost_count=lost_count,
    )
