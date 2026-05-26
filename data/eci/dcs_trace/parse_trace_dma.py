#!/usr/bin/env python3

"""Decode raw Lauberhorn TraceBufferDMA DRAM contents.

The physical sample layout is supplied by `lauberhorn_trace_dma_map.json`,
which is emitted by the LauberhornTraceDma generator. For the current ECI
design, each sample is 128 bits:

  [payload_width-1:0]                       source-specific payload
  [payload_width+source_width-1:payload]    source id
  [sample_width-1:payload+source]           timestamp

The all-ones source id for the configured source width is reserved for marker
frames: non-zero payload reports lost samples, while zero payload is a bubble
inserted to flush a partial AXI beat.
"""

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

try:
    from parse_eci_trace import decode_by_opcode
except ImportError:
    decode_by_opcode = None


def bits(value: int, offset: int, width: int) -> int:
    return (value >> offset) & ((1 << width) - 1)


def parse_hex_int(text: str) -> Optional[int]:
    text = text.strip().lower()
    if text.startswith("0x"):
        text = text[2:]
    if not text:
        return None
    try:
        return int(text, 16)
    except ValueError:
        return None


def platform_root() -> Path:
    return Path(__file__).resolve().parents[3]


def default_map_path() -> Path:
    candidates = [
        Path(__file__).with_name("lauberhorn_trace_dma_map.json"),
        platform_root() / "out" / "eci" / "generateVerilog.dest" / "lauberhorn_trace_dma_map.json",
        Path.cwd() / "lauberhorn_trace_dma_map.json",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return candidates[1]


def load_map(path: Path) -> Dict[str, Any]:
    with path.open() as f:
        trace_map = json.load(f)
    trace_map["sources_by_id"] = {int(src["source"]): src for src in trace_map.get("sources", [])}
    return trace_map


def iter_binary_samples(data: bytes, offset: int, sample_width: int) -> Iterable[Tuple[int, int]]:
    value = int.from_bytes(data[offset:], "little")
    sample_count = ((len(data) - offset) * 8) // sample_width
    for index in range(sample_count):
        yield index, bits(value, index * sample_width, sample_width)


def iter_hex_samples(data: bytes) -> Iterable[Tuple[int, int]]:
    for index, token in enumerate(data.decode("ascii", errors="ignore").replace(",", " ").split()):
        value = parse_hex_int(token)
        if value is not None:
            yield index, value


def decode_fields(payload: int, fields: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    decoded: Dict[str, Any] = {}
    for name, spec in fields.items():
        value = bits(payload, int(spec["offset"]), int(spec["width"]))
        if spec.get("format") == "hex":
            width = int(spec["width"])
            decoded[name] = f"0x{value:0{(width + 3) // 4}x}"
        else:
            decoded[name] = value
    return decoded


def decode_sample(index: int, sample: int, trace_map: Dict[str, Any]) -> Dict[str, Any]:
    sample_cfg = trace_map["sample"]
    payload_width = int(sample_cfg["payload_width"])
    source_width = int(sample_cfg["source_width"])
    timestamp_width = int(sample_cfg["timestamp_width"])
    sample_width = int(sample_cfg["sample_width"])
    beat_bits = int(sample_cfg.get("axi_data_width", 512))
    lost_source = int(sample_cfg.get("lost_source", (1 << source_width) - 1))
    lost_count_width = int(sample_cfg.get("lost_count_width", 32))
    eci_stall_counter_shift = int(sample_cfg.get("eci_stall_counter_shift", 0))

    payload = bits(sample, 0, payload_width)
    source = bits(sample, payload_width, source_width)
    timestamp = bits(sample, payload_width + source_width, timestamp_width)
    source_info = trace_map["sources_by_id"].get(source, {"type": "unknown"})

    row: Dict[str, Any] = {
        "sample": index,
        "beat": (index * sample_width) // beat_bits,
        "timestamp": timestamp,
        "source": source,
        "port": source_info.get("port", ""),
        "type": source_info.get("type", "unknown"),
        "clock_domain": source_info.get("clock_domain", ""),
        "dcs": source_info.get("dcs", ""),
        "local_source": source_info.get("local_source", ""),
        "channel": source_info.get("channel", ""),
        "payload": f"0x{payload:0{(payload_width + 3) // 4}x}",
    }

    if source == lost_source or row["type"] == "lost":
        lost_count = bits(payload, 0, lost_count_width)
        row["type"] = "bubble" if lost_count == 0 else "lost"
        row["lost_count"] = lost_count
        return row

    payload_format = trace_map.get("payload_formats", {}).get(row["type"], {})
    row.update(decode_fields(payload, payload_format.get("fields", {})))

    if row["type"] == "eci":
        eci_header = bits(payload, 0, 64)
        row["eci_header"] = f"0x{eci_header:016x}"
        row["stall_counter_shift"] = eci_stall_counter_shift
        row["stall_cycles"] = int(row.get("stall_count", 0)) << eci_stall_counter_shift
        if decode_by_opcode is not None:
            row.update(decode_by_opcode(eci_header, vc=row.get("vc"), src=row.get("local_source")))

    return row


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", help="Raw binary DRAM dump, or text with one sample per hex token")
    parser.add_argument("-o", "--output", default="-", help="Output CSV path, or '-' for stdout")
    parser.add_argument("--map", default=str(default_map_path()), help="Trace map JSON emitted by LauberhornTraceDma")
    parser.add_argument("--offset", type=lambda x: int(x, 0), default=0, help="Byte offset into the binary dump")
    parser.add_argument("--samples", type=int, default=None, help="Maximum number of samples to decode")
    parser.add_argument("--hex-text", action="store_true", help="Read input as hex text instead of raw binary")
    parser.add_argument("--include-zero", action="store_true", help="Do not stop at an all-zero sample")
    args = parser.parse_args()

    trace_map = load_map(Path(args.map))
    sample_width = int(trace_map["sample"]["sample_width"])
    data = Path(args.input).read_bytes()
    iterator = iter_hex_samples(data) if args.hex_text else iter_binary_samples(data, args.offset, sample_width)

    rows: List[Dict[str, Any]] = []
    for index, sample in iterator:
        if args.samples is not None and len(rows) >= args.samples:
            break
        if not args.include_zero and sample == 0:
            break
        rows.append(decode_sample(index, sample, trace_map))

    fieldnames = [
        "sample", "beat", "timestamp", "source", "port", "type", "clock_domain",
        "dcs", "local_source", "channel", "payload", "lost_count",
        "error", "cli", "state", "action", "request",
        "eci_header", "vc", "stall_count", "stall_counter_shift", "stall_cycles", "accepted",
        "opcode", "message", "aliased_addr", "unaliased_addr",
    ]
    extra = sorted({key for row in rows for key in row.keys() if key not in fieldnames})
    fieldnames.extend(extra)

    out = sys.stdout if args.output == "-" else open(args.output, "w", newline="")
    try:
        writer = csv.DictWriter(out, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, "") for key in fieldnames})
    finally:
        if out is not sys.stdout:
            out.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
