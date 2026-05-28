import json
import struct
from typing import Any, Dict, Optional


MAGIC_METADATA = b"LHTM"
MAGIC_CONTROL = b"LHTC"
MAGIC_EVENT = b"LHTE"
MAGIC_DCS = b"LHTD"
MAGIC_ECI_APP = b"LHEA"
MAGIC_ECI_SYS = b"LHES"
VERSION = 3

KIND_METADATA = 1
KIND_SAMPLE = 2
KIND_LOST = 3
KIND_BUBBLE = 4

SOURCE_METADATA = 0xFFFF

HEADER = struct.Struct("<4sBBHQQQQHII")
HEADER_LEN = HEADER.size


def packet_timestamp_ns(timestamp: int, cycle_ns: int) -> int:
    return int(timestamp) * cycle_ns


def _packet(
    magic: bytes,
    kind: int,
    logical_sample: int = 0,
    physical_sample: int = 0,
    timestamp: int = 0,
    raw_timestamp: int = 0,
    source: int = 0,
    lost_count: int = 0,
    payload: bytes = b"",
) -> bytes:
    header = HEADER.pack(
        magic,
        VERSION,
        kind,
        0,
        int(logical_sample),
        int(physical_sample),
        int(timestamp),
        int(raw_timestamp),
        int(source),
        int(lost_count),
        len(payload),
    )
    return header + payload


def metadata_packet(trace_map: Dict[str, Any]) -> bytes:
    export_map = {key: value for key, value in trace_map.items() if key != "sources_by_id"}
    payload = json.dumps(export_map, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _packet(MAGIC_METADATA, KIND_METADATA, source=SOURCE_METADATA, payload=payload)


def sample_magic(source_info: Optional[Dict[str, Any]]) -> bytes:
    if source_info is None:
        return MAGIC_EVENT

    source_type = source_info.get("type")
    if source_type == "dcs_event":
        return MAGIC_DCS
    if source_type == "lauberhorn_event":
        return MAGIC_EVENT
    if source_type == "eci":
        if source_info.get("clock_domain") == "sys":
            return MAGIC_ECI_SYS
        return MAGIC_ECI_APP

    return MAGIC_EVENT


def sample_packet(
    logical_sample: int,
    physical_sample: int,
    timestamp: int,
    raw_timestamp: int,
    source: int,
    sample: int,
    sample_bytes: int,
    source_info: Optional[Dict[str, Any]] = None,
) -> bytes:
    return _packet(
        sample_magic(source_info),
        KIND_SAMPLE,
        logical_sample=logical_sample,
        physical_sample=physical_sample,
        timestamp=timestamp,
        raw_timestamp=raw_timestamp,
        source=source,
        payload=int(sample).to_bytes(sample_bytes, "little"),
    )


def marker_packet(
    logical_sample: int,
    physical_sample: int,
    timestamp: int,
    raw_timestamp: int,
    source: int,
    lost_count: int,
) -> bytes:
    kind = KIND_BUBBLE if lost_count == 0 else KIND_LOST
    return _packet(
        MAGIC_CONTROL,
        kind,
        logical_sample=logical_sample,
        physical_sample=physical_sample,
        timestamp=timestamp,
        raw_timestamp=raw_timestamp,
        source=source,
        lost_count=lost_count,
    )
