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

TraceBufferDMA writes the DRAM region as a circular buffer. When the dumped
buffer has wrapped, the newest samples appear at the physical beginning of the
dump and the oldest remaining samples appear after the current write position.
The parser detects that case from the timestamp discontinuity and rotates the
samples before decoding.
"""

import argparse
import csv
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple

try:
    from .dma_decode import (
        FIELDNAMES,
        decode_samples,
        default_map_path,
        iter_binary_samples,
        iter_hex_samples,
        load_map,
    )
except ImportError:
    from dma_decode import (
        FIELDNAMES,
        decode_samples,
        default_map_path,
        iter_binary_samples,
        iter_hex_samples,
        load_map,
    )


def read_samples(args: argparse.Namespace, trace_map: Dict[str, Any]) -> List[Tuple[int, int]]:
    sample_width = int(trace_map["sample"]["sample_width"])
    data = Path(args.input).read_bytes()
    iterator = iter_hex_samples(data) if args.hex_text else iter_binary_samples(data, args.offset, sample_width)

    samples: List[Tuple[int, int]] = []
    for index, sample in iterator:
        if not args.include_zero and sample == 0:
            break
        samples.append((index, sample))

    return samples


def write_csv(rows: List[Dict[str, Any]], output: str) -> None:
    fieldnames = FIELDNAMES.copy()
    extra = sorted({key for row in rows for key in row.keys() if key not in fieldnames})
    fieldnames.extend(extra)

    out = sys.stdout if output == "-" else open(output, "w", newline="")
    try:
        writer = csv.DictWriter(out, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, "") for key in fieldnames})
    finally:
        if out is not sys.stdout:
            out.close()


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
    rows = decode_samples(read_samples(args, trace_map), trace_map, limit=args.samples)
    write_csv(rows, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
