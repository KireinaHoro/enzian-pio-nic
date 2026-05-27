#!/usr/bin/env python3

import argparse
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, Optional, Tuple

try:
    from .dma_decode import decode_sample, default_map_path, load_map, sample_timestamp
    from .lhtrace_packet import packet_timestamp_ns, row_to_packet
    from .pcapng import PcapngWriter
except ImportError:
    from dma_decode import decode_sample, default_map_path, load_map, sample_timestamp
    from lhtrace_packet import packet_timestamp_ns, row_to_packet
    from pcapng import PcapngWriter


def sample_bytes(trace_map: Dict[str, Any]) -> int:
    sample_width = int(trace_map["sample"]["sample_width"])
    if sample_width % 8 != 0:
        raise ValueError("pcapng export currently requires byte-aligned trace samples")
    return sample_width // 8


def iter_sample_range(
    path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    start: int = 0,
    stop: Optional[int] = None,
) -> Iterator[Tuple[int, int]]:
    width = sample_bytes(trace_map)
    with path.open("rb") as f:
        f.seek(offset + start * width)
        index = start
        while stop is None or index < stop:
            chunk = f.read(width)
            if len(chunk) < width:
                break
            yield index, int.from_bytes(chunk, "little")
            index += 1


def scan_samples(input_path: Path, trace_map: Dict[str, Any], offset: int) -> Tuple[Optional[int], int]:
    previous_ts: Optional[int] = None
    wrap_index: Optional[int] = None
    count = 0
    for index, sample in iter_sample_range(input_path, trace_map, offset):
        ts = sample_timestamp(sample, trace_map)
        if previous_ts is not None and ts < previous_ts and wrap_index is None:
            wrap_index = index
        previous_ts = ts
        count = index + 1
    return wrap_index, count


def iter_chronological_samples(
    input_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    sample_limit: Optional[int] = None,
) -> Iterator[Tuple[int, int, int]]:
    wrap_index, count = scan_samples(input_path, trace_map, offset)
    ranges: Iterable[Tuple[int, Optional[int]]]
    if wrap_index is None:
        ranges = [(0, count)]
    else:
        ranges = [(wrap_index, count), (0, wrap_index)]

    logical_index = 0
    for start, stop in ranges:
        for physical_index, sample in iter_sample_range(input_path, trace_map, offset, start=start, stop=stop):
            yield logical_index, physical_index, sample
            logical_index += 1
            if sample_limit is not None and logical_index >= sample_limit:
                return


def row_matches(row: Dict[str, Any], trace_type: Optional[str], source: Optional[int]) -> bool:
    if trace_type is not None and row.get("type") != trace_type:
        return False
    if source is not None and int(row.get("source", -1)) != source:
        return False
    return True


def write_pcap(
    input_path: Path,
    output_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    sample_limit: Optional[int],
    trace_type: Optional[str],
    source: Optional[int],
    cycle_ns: int,
) -> None:
    with output_path.open("wb") as f:
        writer = PcapngWriter(f)
        writer.write_header()
        for logical_index, physical_index, sample in iter_chronological_samples(
            input_path,
            trace_map,
            offset,
            sample_limit=sample_limit,
        ):
            row = decode_sample(logical_index, physical_index, sample, trace_map)
            if row_matches(row, trace_type, source):
                writer.write_packet(row_to_packet(row), timestamp_ns=packet_timestamp_ns(row, cycle_ns))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", nargs="?", help="Raw binary DRAM dump")
    parser.add_argument("-o", "--output", required=True, help="Output pcapng path")
    parser.add_argument("--map", default=str(default_map_path()), help="Trace map JSON emitted by LauberhornTraceDma")
    parser.add_argument("--offset", type=lambda x: int(x, 0), default=0, help="Byte offset into the binary dump")
    parser.add_argument("--samples", type=int, default=None, help="Maximum number of samples to decode after wrap realignment")
    parser.add_argument("--type", choices=["dcs_event", "eci", "lost", "bubble"], default=None, help="Only export one decoded trace type")
    parser.add_argument("--source", type=int, default=None, help="Only export one global source id")
    parser.add_argument("--cycle-ns", type=int, default=5, help="Scale trace timestamp cycles to pcapng nanoseconds")
    parser.add_argument("--from-vivado", action="store_true",
                        help="Reserved: stream the trace buffer through Vivado hardware manager/JTAG AXI without an intermediate dump")
    args = parser.parse_args()

    if args.from_vivado:
        raise SystemExit("--from-vivado is reserved for the JTAG AXI hardware-manager path and is not implemented yet")
    if args.input is None:
        parser.error("input dump file is required")

    input_path = Path(args.input)
    output_path = Path(args.output)
    map_path = Path(args.map)
    offset = args.offset
    sample_limit = args.samples
    trace_type = args.type
    source = args.source
    cycle_ns = args.cycle_ns

    trace_map = load_map(map_path)
    write_pcap(input_path, output_path, trace_map, offset, sample_limit, trace_type, source, cycle_ns)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
