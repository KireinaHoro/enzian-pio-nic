import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Optional

try:
    from .common import parse_hex_int
    from .trace_metadata import enum_value
except ImportError:
    from common import parse_hex_int
    from trace_metadata import enum_value


APP_CYCLE_NS = 5.0
SYS_CYCLE_NS = 1_000_000_000.0 / 322_265_625.0

ECI_FILES = {
    "even_app.csv": {"base_source": 4, "cycle_ns": APP_CYCLE_NS},
    "odd_app.csv": {"base_source": 10, "cycle_ns": APP_CYCLE_NS},
    "even_sys.csv": {"base_source": 16, "cycle_ns": SYS_CYCLE_NS},
    "odd_sys.csv": {"base_source": 22, "cycle_ns": SYS_CYCLE_NS},
}

DCS_SOURCE_NAMES = {
    "even_0": 0,
    "even_1": 1,
    "odd_0": 2,
    "odd_1": 3,
}


@dataclass(frozen=True)
class LegacySample:
    timestamp: int
    timestamp_ns: int
    source: int
    sample: int
    physical_sample: int


def _column(headers: Iterable[str], *needles: str) -> str:
    lowered = [(header.lower(), header) for header in headers]
    for needle in needles:
        needle = needle.lower()
        for lowered_header, header in lowered:
            if needle in lowered_header:
                return header
    raise ValueError(f"could not find CSV column matching any of: {', '.join(needles)}")


def _dump_window(rows: List[Dict[str, str]], dump_col: str) -> Iterable[tuple[int, Dict[str, str]]]:
    dump_indices = [
        index for index, row in enumerate(rows)
        if row.get(dump_col, "").strip().lower() in {"1", "0x1"}
    ]
    if len(dump_indices) < 2:
        raise ValueError(f"found only {len(dump_indices)} dump rows in legacy ILA CSV")
    for index in range(dump_indices[1], len(rows)):
        yield index, rows[index]


def _hex_cell(row: Dict[str, str], column: str, field_name: str) -> int:
    value = parse_hex_int(row.get(column, ""))
    if value is None:
        raise ValueError(f"invalid hex value for {field_name}: {row.get(column, '')!r}")
    return value


def _enum_cell(row: Dict[str, str], column: str, enum: Dict[str, str], field_name: str) -> int:
    text = row.get(column, "").strip()
    try:
        return enum_value(enum, text)
    except ValueError:
        pass
    value = parse_hex_int(text)
    if value is not None:
        return value
    raise ValueError(f"invalid enum value for {field_name}: {text!r}")


def _dcs_source(row: Dict[str, str], column: str) -> int:
    text = row.get(column, "").strip()
    if text in DCS_SOURCE_NAMES:
        return DCS_SOURCE_NAMES[text]
    value = parse_hex_int(text)
    if value is None:
        raise ValueError(f"invalid DCS source value: {text!r}")
    return value


def _set_bits(payload: int, value: int, fields: Dict[str, Any], field_name: str) -> int:
    field = fields[field_name]
    offset = int(field["offset"])
    width = int(field["width"])
    if value < 0 or value >= (1 << width):
        raise ValueError(f"{field_name}={value} does not fit in {width} bits")
    return payload | (value << offset)


def _sample_word(payload: int, source: int, timestamp: int, trace_map: Dict[str, Any]) -> int:
    sample_cfg = trace_map["sample"]
    payload_width = int(sample_cfg["payload_width"])
    source_width = int(sample_cfg["source_width"])
    timestamp_width = int(sample_cfg["timestamp_width"])
    if payload >= (1 << payload_width):
        raise ValueError(f"payload does not fit in {payload_width} bits")
    if source >= (1 << source_width):
        raise ValueError(f"source {source} does not fit in {source_width} bits")
    timestamp &= (1 << timestamp_width) - 1
    return payload | (source << payload_width) | (timestamp << (payload_width + source_width))


def _read_csv(path: Path) -> tuple[List[str], List[Dict[str, str]]]:
    with path.open(newline="") as f:
        reader = csv.DictReader(f)
        return reader.fieldnames or [], list(reader)


def _iter_dcs_samples(path: Path, trace_map: Dict[str, Any], file_id: int) -> Iterator[LegacySample]:
    headers, rows = _read_csv(path)
    ts_col = _column(headers, "dcs_trace.ts", "ts")
    src_col = _column(headers, "dcs_trace.src")
    req_col = _column(headers, "dcs_trace.event.req", "event.req")
    action_col = _column(headers, "dcs_trace.event.action", "event.action")
    cli_col = _column(headers, "dcs_trace.event.cli", "event.cli")
    state_col = _column(headers, "dcs_trace.event.state", "event.state")
    error_col = _column(headers, "dcs_trace.event.error", "event.error")
    dump_col = _column(headers, "dcs_trace.dump", "dump")
    fields = trace_map["payload_formats"]["dcs_event"]["fields"]

    for row_index, row in _dump_window(rows, dump_col):
        timestamp = _hex_cell(row, ts_col, "dcs timestamp")
        if timestamp == 0:
            break
        source = _dcs_source(row, src_col)
        payload = 0
        payload = _set_bits(payload, _hex_cell(row, error_col, "dcs error"), fields, "error")
        payload = _set_bits(payload, _hex_cell(row, cli_col, "dcs cli"), fields, "cli")
        payload = _set_bits(payload, _enum_cell(row, state_col, fields["state"]["enum"], "dcs state"), fields, "state")
        payload = _set_bits(payload, _enum_cell(row, action_col, fields["action"]["enum"], "dcs action"), fields, "action")
        payload = _set_bits(payload, _enum_cell(row, req_col, fields["request"]["enum"], "dcs request"), fields, "request")
        yield LegacySample(
            timestamp=timestamp,
            timestamp_ns=int(timestamp * APP_CYCLE_NS),
            source=source,
            sample=_sample_word(payload, source, timestamp, trace_map),
            physical_sample=(file_id << 32) | row_index,
        )


def _iter_eci_samples(path: Path, trace_map: Dict[str, Any], file_id: int, base_source: int, cycle_ns: float) -> Iterator[LegacySample]:
    headers, rows = _read_csv(path)
    data_col = _column(headers, "traceout_event_data", "event_data")
    vc_col = _column(headers, "traceout_event_vc", "event_vc", "vc")
    src_col = _column(headers, "traceout_src", "src")
    ts_col = _column(headers, "traceout_ts", "ts")
    dump_col = _column(headers, "dump")
    fields = trace_map["payload_formats"]["eci"]["fields"]

    for row_index, row in _dump_window(rows, dump_col):
        timestamp = _hex_cell(row, ts_col, "eci timestamp")
        if timestamp == 0:
            break
        local_source = _hex_cell(row, src_col, "eci source")
        source = base_source + local_source
        payload = 0
        payload = _set_bits(payload, _hex_cell(row, data_col, "eci raw header"), fields, "eci_header")
        payload = _set_bits(payload, _hex_cell(row, vc_col, "eci vc"), fields, "vc")
        payload = _set_bits(payload, 0, fields, "stall_count")
        payload = _set_bits(payload, 1, fields, "accepted")
        yield LegacySample(
            timestamp=timestamp,
            timestamp_ns=int(timestamp * cycle_ns),
            source=source,
            sample=_sample_word(payload, source, timestamp, trace_map),
            physical_sample=(file_id << 32) | row_index,
        )


def iter_legacy_ila_samples(trace_dir: Path, trace_map: Dict[str, Any]) -> Iterator[LegacySample]:
    dcs_path = trace_dir / "dcs.csv"
    if dcs_path.exists():
        yield from _iter_dcs_samples(dcs_path, trace_map, file_id=0)

    for file_id, (name, config) in enumerate(ECI_FILES.items(), start=1):
        path = trace_dir / name
        if path.exists():
            yield from _iter_eci_samples(
                path,
                trace_map,
                file_id=file_id,
                base_source=int(config["base_source"]),
                cycle_ns=float(config["cycle_ns"]),
            )


def chronological_legacy_samples(
    trace_dir: Path,
    trace_map: Dict[str, Any],
    start_sample: int = 0,
    sample_limit: Optional[int] = None,
) -> Iterator[tuple[int, LegacySample]]:
    samples = sorted(
        iter_legacy_ila_samples(trace_dir, trace_map),
        key=lambda sample: (sample.timestamp_ns, sample.source, sample.physical_sample),
    )
    emitted = 0
    for logical_index, sample in enumerate(samples):
        if logical_index < start_sample:
            continue
        if sample_limit is not None and emitted >= sample_limit:
            break
        emitted += 1
        yield logical_index, sample
