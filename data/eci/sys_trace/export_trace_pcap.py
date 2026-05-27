#!/usr/bin/env python3

import argparse
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, Optional, Tuple

try:
    from .common import bits
    from .dma_decode import default_map_path, load_map
    from .legacy_ila import chronological_legacy_samples
    from .lhtrace_packet import marker_packet, metadata_packet, packet_timestamp_ns, sample_packet
    from .pcapng import PcapngWriter
    from .trace_metadata import enrich_trace_map
except ImportError:
    from common import bits
    from dma_decode import default_map_path, load_map
    from legacy_ila import chronological_legacy_samples
    from lhtrace_packet import marker_packet, metadata_packet, packet_timestamp_ns, sample_packet
    from pcapng import PcapngWriter
    from trace_metadata import enrich_trace_map


def acquire_dump_from_vivado(_args: argparse.Namespace) -> Path:
    raise SystemExit("Vivado hardware-manager trace dump acquisition is not implemented yet")


def sample_bytes(trace_map: Dict[str, Any]) -> int:
    sample_width = int(trace_map["sample"]["sample_width"])
    if sample_width % 8 != 0:
        raise ValueError("pcapng export requires byte-aligned trace samples")
    return sample_width // 8


def timestamp_modulus(trace_map: Dict[str, Any]) -> int:
    return 1 << int(trace_map["sample"]["timestamp_width"])


def sample_timestamp(sample: int, trace_map: Dict[str, Any]) -> int:
    sample_cfg = trace_map["sample"]
    payload_width = int(sample_cfg["payload_width"])
    source_width = int(sample_cfg["source_width"])
    timestamp_width = int(sample_cfg["timestamp_width"])
    return bits(sample, payload_width + source_width, timestamp_width)


def sample_source(sample: int, trace_map: Dict[str, Any]) -> int:
    sample_cfg = trace_map["sample"]
    payload_width = int(sample_cfg["payload_width"])
    source_width = int(sample_cfg["source_width"])
    return bits(sample, payload_width, source_width)


def sample_payload(sample: int, trace_map: Dict[str, Any]) -> int:
    payload_width = int(trace_map["sample"]["payload_width"])
    return bits(sample, 0, payload_width)


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
    """Find circular-buffer wrap, while allowing the hardware timestamp to wrap.

    Adjacent samples in chronological order have a small positive timestamp delta
    modulo the timestamp width. The physical circular-buffer boundary appears as
    one large modulo jump from the newest sample back to older samples. A natural
    47-bit timestamp counter wrap is therefore not treated as the buffer wrap.
    """
    modulus = timestamp_modulus(trace_map)
    wrap_index: Optional[int] = None
    max_delta = 0
    previous_ts: Optional[int] = None
    count = 0

    for index, sample in iter_sample_range(input_path, trace_map, offset):
        ts = sample_timestamp(sample, trace_map)
        if previous_ts is not None:
            delta = (ts - previous_ts) % modulus
            if delta > max_delta:
                max_delta = delta
                wrap_index = index
        previous_ts = ts
        count = index + 1

    if max_delta <= modulus // 2:
        wrap_index = None
    return wrap_index, count


def chronological_ranges(wrap_index: Optional[int], count: int) -> Iterable[Tuple[int, Optional[int]]]:
    if wrap_index is None:
        return [(0, count)]
    return [(wrap_index, count), (0, wrap_index)]


def iter_chronological_samples(
    input_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    start_sample: int = 0,
    sample_limit: Optional[int] = None,
) -> Iterator[Tuple[int, int, int, int]]:
    wrap_index, count = scan_samples(input_path, trace_map, offset)
    modulus = timestamp_modulus(trace_map)
    logical_index = 0
    emitted = 0
    previous_ts: Optional[int] = None
    timestamp_epoch = 0

    for start, stop in chronological_ranges(wrap_index, count):
        for physical_index, sample in iter_sample_range(input_path, trace_map, offset, start=start, stop=stop):
            raw_ts = sample_timestamp(sample, trace_map)
            if previous_ts is not None and raw_ts < previous_ts:
                timestamp_epoch += modulus
            previous_ts = raw_ts

            if logical_index >= start_sample:
                yield logical_index, physical_index, sample, timestamp_epoch + raw_ts
                emitted += 1
                if sample_limit is not None and emitted >= sample_limit:
                    return
            logical_index += 1


def source_matches(sample: int, trace_map: Dict[str, Any], source: Optional[int]) -> bool:
    if source is None:
        return True
    return sample_source(sample, trace_map) == source


def write_pcap(
    input_path: Path,
    output_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    start_sample: int,
    sample_limit: Optional[int],
    source: Optional[int],
    cycle_ns: int,
) -> None:
    width = sample_bytes(trace_map)
    lost_source = int(trace_map["sample"]["lost_source"])
    lost_count_width = int(trace_map["sample"].get("lost_count_width", 32))
    export_map = enrich_trace_map(trace_map)

    with output_path.open("wb") as f:
        writer = PcapngWriter(f)
        writer.write_header()
        writer.write_packet(metadata_packet(export_map), timestamp_ns=0)

        for logical_index, physical_index, sample, timestamp in iter_chronological_samples(
            input_path,
            trace_map,
            offset,
            start_sample=start_sample,
            sample_limit=sample_limit,
        ):
            src = sample_source(sample, trace_map)
            if src == lost_source:
                lost_count = bits(sample_payload(sample, trace_map), 0, lost_count_width)
                if source is None or source == lost_source:
                    packet = marker_packet(logical_index, physical_index, timestamp, src, lost_count)
                    writer.write_packet(packet, timestamp_ns=packet_timestamp_ns(timestamp, cycle_ns))
                continue

            if source_matches(sample, trace_map, source):
                packet = sample_packet(logical_index, physical_index, timestamp, src, sample, width)
                writer.write_packet(packet, timestamp_ns=packet_timestamp_ns(timestamp, cycle_ns))


def write_legacy_ila_pcap(
    trace_dir: Path,
    output_path: Path,
    trace_map: Dict[str, Any],
    start_sample: int,
    sample_limit: Optional[int],
    source: Optional[int],
) -> None:
    width = sample_bytes(trace_map)
    export_map = enrich_trace_map(trace_map)
    with output_path.open("wb") as f:
        writer = PcapngWriter(f)
        writer.write_header()
        writer.write_packet(metadata_packet(export_map), timestamp_ns=0)

        for logical_index, sample in chronological_legacy_samples(
            trace_dir,
            export_map,
            start_sample=start_sample,
            sample_limit=sample_limit,
        ):
            if source_matches(sample.sample, trace_map, source):
                packet = sample_packet(
                    logical_index,
                    sample.physical_sample,
                    sample.timestamp,
                    sample.source,
                    sample.sample,
                    width,
                )
                writer.write_packet(packet, timestamp_ns=sample.timestamp_ns)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", nargs="?", help="Raw binary DRAM dump")
    parser.add_argument("-o", "--output", required=True, help="Output pcapng path")
    parser.add_argument("--map", default=str(default_map_path()), help="Trace map JSON emitted by LauberhornTraceDma")
    parser.add_argument("--legacy-ila", type=Path, default=None,
                        help="Read legacy Vivado ILA CSVs from a dcs_trace directory instead of a DRAM dump")
    parser.add_argument("--offset", type=lambda x: int(x, 0), default=0, help="Byte offset into the binary dump")
    parser.add_argument("--start", type=int, default=0, help="First chronological sample to export after wrap realignment")
    parser.add_argument("--samples", type=int, default=None, help="Maximum number of chronological samples to export")
    parser.add_argument("--source", type=lambda x: int(x, 0), default=None, help="Only export one global source id")
    parser.add_argument("--cycle-ns", type=int, default=5, help="Scale trace timestamp cycles to pcapng nanoseconds")
    parser.add_argument("--from-vivado", action="store_true",
                        help="Reserved: acquire the trace buffer through Vivado hardware manager/JTAG AXI")
    args = parser.parse_args()

    if args.legacy_ila is not None:
        input_path = None
    elif args.from_vivado:
        input_path = acquire_dump_from_vivado(args)
    elif args.input is None:
        parser.error("input dump file is required")
    else:
        input_path = Path(args.input)

    trace_map = load_map(Path(args.map))
    if args.legacy_ila is not None:
        write_legacy_ila_pcap(
            trace_dir=args.legacy_ila,
            output_path=Path(args.output),
            trace_map=trace_map,
            start_sample=args.start,
            sample_limit=args.samples,
            source=args.source,
        )
    else:
        assert input_path is not None
        write_pcap(
            input_path=input_path,
            output_path=Path(args.output),
            trace_map=trace_map,
            offset=args.offset,
            start_sample=args.start,
            sample_limit=args.samples,
            source=args.source,
            cycle_ns=args.cycle_ns,
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
