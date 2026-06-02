#!/usr/bin/env python3

import argparse
import tempfile
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
    from .legacy_ila import chronological_legacy_samples
    from .lhtrace_packet import marker_packet, metadata_packet, packet_timestamp_ns, sample_packet
    from .pcapng import PcapngWriter
    from .trace_metadata import enrich_trace_map
    from .vivado_jtag_axi import XsdbError, dump_trace_buffer, dump_trace_buffer_xsdb
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
    from legacy_ila import chronological_legacy_samples
    from lhtrace_packet import marker_packet, metadata_packet, packet_timestamp_ns, sample_packet
    from pcapng import PcapngWriter
    from trace_metadata import enrich_trace_map
    from vivado_jtag_axi import XsdbError, dump_trace_buffer, dump_trace_buffer_xsdb


def acquire_dump_from_vivado(args: argparse.Namespace) -> Path:
    if args.vivado_dump_bytes is None:
        raise SystemExit("--vivado-dump-bytes is required with --from-vivado")

    if args.vivado_dump_out is not None:
        output_path = args.vivado_dump_out
    else:
        with tempfile.NamedTemporaryFile(prefix="lauberhorn-trace-", suffix=".bin", delete=False) as f:
            output_path = Path(f.name)
        args._vivado_temp_dump = output_path

    kwargs = {
        "output_path": output_path,
        "byte_count": args.vivado_dump_bytes,
        "address": args.vivado_address,
        "hw_server_host": args.hw_server_host,
        "hw_server_port": args.hw_server_port,
        "fpga_jtag_id": args.fpga_jtag_id,
        "jtag_axi_name": args.jtag_axi_name,
        "word_bits": args.jtag_axi_word_bits,
        "max_beats": args.jtag_axi_max_beats,
    }

    if args.vivado_readout == "tcl":
        dump_trace_buffer(
            **kwargs,
            vivado_bin=args.vivado_bin,
            tcl_path=args.vivado_tcl_out,
        )
    elif args.vivado_readout == "xsdb":
        try:
            dump_trace_buffer_xsdb(
                **kwargs,
                xsdb_server_host=args.xsdb_server_host,
                xsdb_server_port=args.xsdb_server_port,
            )
        except XsdbError as e:
            raise SystemExit(str(e)) from e
    else:
        raise SystemExit(f"Unknown Vivado readout backend: {args.vivado_readout}")

    return output_path


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

        for logical_index, physical_index, sample, timestamp, raw_timestamp in adjusted_chronological_samples(
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
                    packet = marker_packet(logical_index, physical_index, timestamp, raw_timestamp, src, lost_count)
                    writer.write_packet(packet, timestamp_ns=packet_timestamp_ns(timestamp, cycle_ns))
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
                    sample.timestamp,
                    sample.source,
                    sample.sample,
                    width,
                    source_info(trace_map, sample.source),
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
                        help="Acquire the trace buffer through Vivado hardware manager/JTAG AXI before export")
    parser.add_argument("--vivado-readout", choices=("tcl", "xsdb"), default="tcl",
                        help="Readout backend: Vivado batch Tcl dump, or Python-driven XSDB command server")
    parser.add_argument("--vivado-dump-bytes", type=lambda x: int(x, 0), default=None,
                        help="Number of trace DDR bytes to read with --from-vivado")
    parser.add_argument("--vivado-dump-out", type=Path, default=None,
                        help="Optional path to keep the raw binary dump read through Vivado/JTAG AXI")
    parser.add_argument("--vivado-address", type=lambda x: int(x, 0), default=0,
                        help="AXI byte address to start reading from the JTAG AXI master address space")
    parser.add_argument("--hw-server-host", default="localhost", help="Vivado hw_server host")
    parser.add_argument("--hw-server-port", type=int, default=3121, help="Vivado hw_server TCP port")
    parser.add_argument("--fpga-jtag-id", default=None,
                        help="FPGA JTAG IDCODE, device name, or matching token for selecting the hardware device")
    parser.add_argument("--jtag-axi-name", default=None,
                        help="JTAG AXI core name/pattern for the Tcl backend; XSDB always selects JTAG2AXI")
    parser.add_argument("--jtag-axi-word-bits", type=int, default=32,
                        help="Read word size. XSDB currently supports 32; Tcl supports 32 or 64")
    parser.add_argument("--jtag-axi-max-beats", type=int, default=256,
                        help="Maximum read burst words per JTAG AXI transaction")
    parser.add_argument("--vivado-bin", default="vivado", help="Vivado executable for --vivado-readout=tcl")
    parser.add_argument("--vivado-tcl-out", type=Path, default=None,
                        help="Optional path to write the generated Vivado dump Tcl script")
    parser.add_argument("--xsdb-server-host", default="localhost",
                        help="XSDB command-server host for --vivado-readout=xsdb")
    parser.add_argument("--xsdb-server-port", type=int, default=3010,
                        help="XSDB command-server port for --vivado-readout=xsdb")
    args = parser.parse_args()
    args._vivado_temp_dump = None

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
        try:
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
        finally:
            if args._vivado_temp_dump is not None:
                args._vivado_temp_dump.unlink(missing_ok=True)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
