#!/usr/bin/env python3

import argparse
import sys
import time
from pathlib import Path
from typing import Any, Dict, Optional

try:
    from .common import bits
    from .dma_decode import (
        adjusted_chronological_samples,
        default_map_path,
        load_map,
        sample_bytes,
        sample_payload,
        sample_source,
        source_info,
        source_matches,
    )
    from .eci_state_output import IncompleteTraceError, run_eci_state_output
    from .legacy_ila import chronological_legacy_samples
    from .lhtrace_packet import marker_packet, metadata_packet, packet_timestamp_ns, sample_packet
    from .pcapng import PcapngWriter
    from .sample_window import SampleWindow, parse_sample_window
    from .trace_metadata import enrich_trace_map
except ImportError:
    from common import bits
    from dma_decode import (
        adjusted_chronological_samples,
        default_map_path,
        load_map,
        sample_bytes,
        sample_payload,
        sample_source,
        source_info,
        source_matches,
    )
    from eci_state_output import IncompleteTraceError, run_eci_state_output
    from legacy_ila import chronological_legacy_samples
    from lhtrace_packet import marker_packet, metadata_packet, packet_timestamp_ns, sample_packet
    from pcapng import PcapngWriter
    from sample_window import SampleWindow, parse_sample_window
    from trace_metadata import enrich_trace_map


class ProgressBar:
    def __init__(self, total: int, label: str = "samples written", stream=sys.stderr) -> None:
        self.total = total
        self.label = label
        self.stream = stream
        self.current = 0
        self.enabled = total > 0 and stream.isatty()
        self._last_draw = 0.0
        self._finished = False
        if self.enabled:
            self._draw(force=True)

    def advance(self) -> None:
        self.update(self.current + 1)

    def update(self, current: int) -> None:
        self.current = min(current, self.total)
        self._draw()

    def finish(self) -> None:
        if not self.enabled or self._finished:
            return
        if self.current < self.total:
            self.current = self.total
            self._draw(force=True)
        self.stream.write("\n")
        self.stream.flush()
        self._finished = True

    def _draw(self, force: bool = False) -> None:
        if not self.enabled:
            return
        now = time.monotonic()
        if not force and self.current < self.total and now - self._last_draw < 0.1:
            return
        self._last_draw = now
        width = 32
        filled = width if self.total == 0 else int(width * self.current / self.total)
        bar = "#" * filled + "-" * (width - filled)
        self.stream.write(f"\r[{bar}] {self.current}/{self.total} {self.label}")
        self.stream.flush()


def validate_output_path(output_path: Path, parser: argparse.ArgumentParser) -> None:
    if output_path.exists() and output_path.is_dir():
        parser.error(f"output path is a directory: {output_path}")


def validate_raw_input(input_path: Path, output_path: Optional[Path], parser: argparse.ArgumentParser) -> None:
    if not input_path.exists():
        parser.error(f"input dump file does not exist: {input_path}")
    if not input_path.is_file():
        parser.error(f"input dump path is not a file: {input_path}")
    if output_path is not None and output_path.exists() and input_path.samefile(output_path):
        parser.error(f"input and output refer to the same file: {input_path}")


def validate_legacy_input(trace_dir: Path, parser: argparse.ArgumentParser) -> None:
    if not trace_dir.exists():
        parser.error(f"legacy ILA directory does not exist: {trace_dir}")
    if not trace_dir.is_dir():
        parser.error(f"legacy ILA path is not a directory: {trace_dir}")


def validate_raw_decode_options(
    trace_map: Dict[str, Any],
    input_order: str,
    vivado_transaction_bytes: int,
    parser: argparse.ArgumentParser,
) -> None:
    if input_order != "vivado-hw-axi":
        return
    width = sample_bytes(trace_map)
    if vivado_transaction_bytes < width or vivado_transaction_bytes % width != 0:
        parser.error(
            f"--vivado-transaction-bytes must be a multiple of the {width}-byte trace sample size"
        )


def dma_sample_is_written(sample: int, trace_map: Dict[str, Any], lost_source: int, source: Optional[int]) -> bool:
    src = sample_source(sample, trace_map)
    if src == lost_source:
        return source is None or source == lost_source
    return source_matches(sample, trace_map, source)


def raw_sample_count(input_path: Path, offset: int, width: int) -> int:
    size = input_path.stat().st_size
    if offset >= size:
        return 0
    return (size - offset) // width


def write_pcap(
    input_path: Path,
    output_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    start_sample: int,
    sample_window: Optional[SampleWindow],
    source: Optional[int],
    cycle_ns: int,
    input_order: str,
    vivado_transaction_bytes: int,
) -> None:
    width = sample_bytes(trace_map)
    lost_source = int(trace_map["sample"]["lost_source"])
    lost_count_width = int(trace_map["sample"].get("lost_count_width", 32))
    export_map = enrich_trace_map(trace_map)

    with output_path.open("wb") as f:
        writer = PcapngWriter(f)
        writer.write_header()
        writer.write_packet(metadata_packet(export_map), timestamp_ns=0)

        total_samples = raw_sample_count(input_path, offset, width)
        scan_progress = ProgressBar(total_samples, label="samples scanned")
        order_progress: Optional[ProgressBar] = None

        def update_order_progress(current: int) -> None:
            nonlocal order_progress
            if order_progress is None:
                scan_progress.finish()
                order_progress = ProgressBar(total_samples, label="samples ordered")
            order_progress.update(current)

        samples = adjusted_chronological_samples(
            input_path,
            trace_map,
            offset,
            start_sample=start_sample,
            sample_window=sample_window,
            input_order=input_order,
            vivado_transaction_bytes=vivado_transaction_bytes,
            scan_progress_update=scan_progress.update,
            order_progress_update=update_order_progress,
        )
        scan_progress.finish()
        if order_progress is not None:
            order_progress.finish()
        progress = ProgressBar(
            sum(1 for _, _, sample, _, _ in samples if dma_sample_is_written(sample, trace_map, lost_source, source))
        )
        for logical_index, physical_index, sample, timestamp, raw_timestamp in samples:
            src = sample_source(sample, trace_map)
            if src == lost_source:
                lost_count = bits(sample_payload(sample, trace_map), 0, lost_count_width)
                if source is None or source == lost_source:
                    packet = marker_packet(logical_index, physical_index, timestamp, raw_timestamp, src, lost_count)
                    writer.write_packet(packet, timestamp_ns=packet_timestamp_ns(timestamp, cycle_ns))
                    progress.advance()
                continue

            if source_matches(sample, trace_map, source):
                packet = sample_packet(
                    logical_index,
                    physical_index,
                    timestamp,
                    raw_timestamp,
                    src,
                    sample,
                    width,
                    source_info(trace_map, src),
                )
                writer.write_packet(packet, timestamp_ns=packet_timestamp_ns(timestamp, cycle_ns))
                progress.advance()
        progress.finish()


def write_legacy_ila_pcap(
    trace_dir: Path,
    output_path: Path,
    trace_map: Dict[str, Any],
    start_sample: int,
    sample_window: Optional[SampleWindow],
    source: Optional[int],
) -> None:
    width = sample_bytes(trace_map)
    export_map = enrich_trace_map(trace_map)
    with output_path.open("wb") as f:
        writer = PcapngWriter(f)
        writer.write_header()
        writer.write_packet(metadata_packet(export_map), timestamp_ns=0)

        samples = list(chronological_legacy_samples(
            trace_dir,
            export_map,
            start_sample=start_sample,
            sample_window=sample_window,
        ))
        progress = ProgressBar(sum(1 for _, sample in samples if source_matches(sample.sample, trace_map, source)))
        for logical_index, sample in samples:
            if source_matches(sample.sample, trace_map, source):
                packet = sample_packet(
                    logical_index,
                    sample.physical_sample,
                    sample.timestamp,
                    sample.timestamp,
                    sample.source,
                    sample.sample,
                    width,
                    source_info(trace_map, sample.source),
                )
                writer.write_packet(packet, timestamp_ns=sample.timestamp_ns)
                progress.advance()
        progress.finish()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", nargs="?", help="Raw binary DRAM dump")
    parser.add_argument("-o", "--output", help="Output pcapng path")
    parser.add_argument(
        "--output-mode",
        choices=("pcapng", "eci-state"),
        default="pcapng",
        help="pcapng writes packets; eci-state calls the sys-clock ECI state-machine hook",
    )
    parser.add_argument("--map", default=str(default_map_path()), help="Trace map JSON emitted by LauberhornTraceDma")
    parser.add_argument("--legacy-ila", type=Path, default=None,
                        help="Read legacy Vivado ILA CSVs from a dcs_trace directory instead of a DRAM dump")
    parser.add_argument("--offset", type=lambda x: int(x, 0), default=0, help="Byte offset into the binary dump")
    parser.add_argument("--input-order", choices=("memory-little", "vivado-hw-axi"), default="memory-little",
                        help="Raw dump byte order. Use vivado-hw-axi for dumps written from Vivado transaction DATA before this tool reversed transaction display order.")
    parser.add_argument("--vivado-transaction-bytes", type=lambda x: int(x, 0), default=2048,
                        help="Bytes per Vivado Hardware Manager AXI read transaction for --input-order vivado-hw-axi")
    parser.add_argument("--start", type=int, default=0, help="First chronological sample to export after wrap realignment")
    parser.add_argument(
        "--samples",
        default=None,
        help="Sample window after --start: N exports the last N samples; start:stop or [start,stop) accepts negative indices from the end",
    )
    parser.add_argument("--source", type=lambda x: int(x, 0), default=None, help="Only export one global source id")
    parser.add_argument("--cycle-ns", type=int, default=5, help="Scale trace timestamp cycles to pcapng nanoseconds")
    args = parser.parse_args()
    try:
        sample_window = parse_sample_window(args.samples)
    except ValueError as e:
        parser.error(f"--samples: {e}")

    output_path: Optional[Path]
    if args.output_mode == "pcapng":
        if args.output is None:
            parser.error("-o/--output is required in pcapng output mode")
        output_path = Path(args.output)
        validate_output_path(output_path, parser)
    else:
        if args.output is not None:
            parser.error("-o/--output is only valid in pcapng output mode")
        if args.legacy_ila is not None:
            parser.error("--output-mode eci-state does not support --legacy-ila")
        if args.start != 0:
            parser.error("--output-mode eci-state requires the full trace and does not support --start")
        if args.samples is not None:
            parser.error("--output-mode eci-state requires the full trace and does not support --samples")
        if args.source is not None:
            parser.error("--output-mode eci-state processes all sys-clock ECI sources and does not support --source")
        output_path = None

    if args.legacy_ila is not None:
        input_path = None
        validate_legacy_input(args.legacy_ila, parser)
    elif args.input is None:
        parser.error("input dump file is required")
    else:
        input_path = Path(args.input)
        validate_raw_input(input_path, output_path, parser)

    trace_map = load_map(Path(args.map))
    if args.output_mode == "eci-state":
        assert input_path is not None
        validate_raw_decode_options(trace_map, args.input_order, args.vivado_transaction_bytes, parser)
        total_samples = raw_sample_count(input_path, args.offset, sample_bytes(trace_map))
        scan_progress = ProgressBar(total_samples, label="samples scanned")
        order_progress: Optional[ProgressBar] = None

        def update_order_progress(current: int) -> None:
            nonlocal order_progress
            if order_progress is None:
                scan_progress.finish()
                order_progress = ProgressBar(total_samples, label="samples ordered")
            order_progress.update(current)

        try:
            frames = run_eci_state_output(
                input_path=input_path,
                trace_map=trace_map,
                offset=args.offset,
                cycle_ns=args.cycle_ns,
                input_order=args.input_order,
                vivado_transaction_bytes=args.vivado_transaction_bytes,
                scan_progress_update=scan_progress.update,
                order_progress_update=update_order_progress,
            )
        except IncompleteTraceError as e:
            scan_progress.finish()
            if order_progress is not None:
                order_progress.finish()
            parser.error(str(e))
        scan_progress.finish()
        if order_progress is not None:
            order_progress.finish()
        print(f"ECI state hook processed {frames} sys-clock ECI frames", file=sys.stderr)
    elif args.legacy_ila is not None:
        assert output_path is not None
        write_legacy_ila_pcap(
            trace_dir=args.legacy_ila,
            output_path=output_path,
            trace_map=trace_map,
            start_sample=args.start,
            sample_window=sample_window,
            source=args.source,
        )
    else:
        assert input_path is not None
        assert output_path is not None
        validate_raw_decode_options(trace_map, args.input_order, args.vivado_transaction_bytes, parser)
        write_pcap(
            input_path=input_path,
            output_path=output_path,
            trace_map=trace_map,
            offset=args.offset,
            start_sample=args.start,
            sample_window=sample_window,
            source=args.source,
            cycle_ns=args.cycle_ns,
            input_order=args.input_order,
            vivado_transaction_bytes=args.vivado_transaction_bytes,
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
