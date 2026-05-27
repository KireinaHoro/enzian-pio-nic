import struct
from typing import Any, Dict


MAGIC = b"LHTR"
VERSION = 1

KIND_UNKNOWN = 0
KIND_DCS_EVENT = 1
KIND_ECI = 2
KIND_LOST = 3
KIND_BUBBLE = 4

CLOCK_DOMAIN_IDS = {
    "app": 1,
    "sys": 2,
}

DCS_IDS = {
    "even": 1,
    "odd": 2,
}

FLAG_ECI_ACCEPTED = 1 << 0
FLAG_DCS_ERROR = 1 << 1


def _int_value(value: Any, default: int = 0) -> int:
    if value is None or value == "":
        return default
    if isinstance(value, int):
        return value
    text = str(value).strip()
    try:
        return int(text, 0)
    except ValueError:
        try:
            return int(text, 16)
        except ValueError:
            return default


def _kind(row: Dict[str, Any]) -> int:
    typ = row.get("type")
    if typ == "dcs_event":
        return KIND_DCS_EVENT
    if typ == "eci":
        return KIND_ECI
    if typ == "lost":
        return KIND_LOST
    if typ == "bubble":
        return KIND_BUBBLE
    return KIND_UNKNOWN


def _flags(row: Dict[str, Any]) -> int:
    flags = 0
    if _int_value(row.get("accepted")):
        flags |= FLAG_ECI_ACCEPTED
    if _int_value(row.get("error")):
        flags |= FLAG_DCS_ERROR
    return flags


def _payload_bytes(row: Dict[str, Any]) -> bytes:
    payload = _int_value(row.get("payload"))
    width = max(1, (payload.bit_length() + 7) // 8)
    return payload.to_bytes(width, "little")


def row_to_packet(row: Dict[str, Any]) -> bytes:
    payload = _payload_bytes(row)
    header = struct.pack(
        "<4sBBHQQQHHBBBBIBBBBQQBBHHH",
        MAGIC,
        VERSION,
        _kind(row),
        _flags(row),
        _int_value(row.get("sample")),
        _int_value(row.get("physical_sample")),
        _int_value(row.get("timestamp")),
        _int_value(row.get("source")),
        _int_value(row.get("local_source")),
        _int_value(row.get("vc"), 0xFF),
        CLOCK_DOMAIN_IDS.get(str(row.get("clock_domain", "")), 0),
        DCS_IDS.get(str(row.get("dcs", "")), 0),
        0,
        _int_value(row.get("lost_count")),
        _int_value(row.get("error")),
        _int_value(row.get("state")),
        _int_value(row.get("action")),
        _int_value(row.get("request")),
        _int_value(row.get("cli")),
        _int_value(row.get("eci_header") or row.get("raw")),
        _int_value(row.get("stall_count")),
        _int_value(row.get("stall_counter_shift")),
        0,
        len(payload),
        0,
    )
    return header + payload


def packet_timestamp_ns(row: Dict[str, Any], cycle_ns: int) -> int:
    return _int_value(row.get("timestamp")) * cycle_ns
