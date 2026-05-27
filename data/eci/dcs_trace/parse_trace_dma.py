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
import json
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


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


def bit_range(value: int, hi: int, lo: int) -> int:
    return bits(value, lo, hi - lo + 1)


def unalias_cacheline_index(aliased_cli: int) -> int:
    cli = 0
    cli |= bit_range(aliased_cli, 32, 13) << 13
    cli |= (bit_range(aliased_cli, 12, 8) ^ bit_range(aliased_cli, 17, 13)) << 8
    cli |= (bit_range(aliased_cli, 7, 5) ^ bit_range(aliased_cli, 20, 18)) << 5
    cli |= (
        bit_range(aliased_cli, 4, 3)
        ^ bit_range(aliased_cli, 19, 18)
        ^ bit_range(aliased_cli, 17, 16)
        ^ bit_range(aliased_cli, 6, 5)
    ) << 3
    cli |= (
        bit_range(aliased_cli, 2, 0)
        ^ bit_range(aliased_cli, 20, 18)
        ^ bit_range(aliased_cli, 15, 13)
        ^ bit_range(aliased_cli, 7, 5)
    )
    return cli


def unalias_address(aliased_addr: int) -> int:
    aliased_cli = bits(aliased_addr, 7, 33)
    return unalias_cacheline_index(aliased_cli) << 7


def fmt_hex_no0x(value: Optional[int]) -> str:
    return "" if value is None else format(int(value), "x")


DCS_REQUEST_NAMES = [
    "UNKNOWN_REQ", "A11", "A21", "A22", "A31", "A31d", "A32", "A32d",
    "F21", "F31", "F32", "ICI", "LC", "LCI", "LR", "LW", "R12", "R13",
    "R23", "RDDA", "RR", "RW", "UL", "V21", "V31", "V31d", "V32", "V32d",
    "WDDA",
]

DCS_STATE_NAMES = [
    "s1__1", "s1__1_A11", "s1__1_A22", "s1__1_V21", "s1__1_V32",
    "s1__1_WDDA", "s1__1pRRA", "s1__1pRWA", "s1__2", "s1__2_A22",
    "s1__2_WDDA", "s1__2pRA2", "s1__3", "s1__3pRA3", "s1pCI__1_A21",
    "s1pCI__1_A31d", "s1pCI__1_V31", "s1pCI__1_V32",
    "s1pCI__1_V32_A11", "s1pCI__1_WDDA", "s1pCI__1_WDDA_A11",
    "s1pCI__1_WDDA_A21", "s1pCI__1_WDDA_V21", "s1pC__1_A32",
    "s1pC__1_V31", "s1pC__1_V32", "s1pC__1_WDDA", "s1pC__1_WDDA_A11",
    "s1pC__1_WDDA_A22", "s1pC__1_WDDA_V21", "s1pC__2_A32d",
    "s1pC__2_V32", "s1pC__2_WDDA", "s1pC__2_WDDA_A22", "s1pICI__1_A21",
    "s1pICI__1_A31d", "s1pICI__1_V31", "s1pICI__1_V32",
    "s1pICI__1_V32_A11", "s1pICI__1_WDDA", "s1pICI__1_WDDA_A11",
    "s1pICI__1_WDDA_A21", "s1pICI__1_WDDA_V21", "s1pLRA__1",
    "s1pLRA__1_A11", "s1pLRA__1_A22", "s1pLRA__1_V21", "s1pLRA__2",
    "s1pLRA__2_A22", "s1pLWA__1", "s1pLWA__1_A11", "s1pLWA__1_V21",
    "s1pR__1_A32", "s1pR__1_V31", "s1pR__1_V32", "s1pR__1_WDDA",
    "s1pR__1_WDDA_A11", "s1pR__1_WDDA_A22", "s1pR__1_WDDA_V21",
    "s1pR__2_A32d", "s1pR__2_V32", "s1pR__2_WDDA",
    "s1pR__2_WDDA_A22", "s1pUL__1", "s1pUL__1_A11", "s1pUL__1_A22",
    "s1pUL__1_V21", "s1pUL__2", "s1pUL__2_A22", "s1pW__1_A21",
    "s1pW__1_A31d", "s1pW__1_V31", "s1pW__1_V32", "s1pW__1_V32_A11",
    "s1pW__1_WDDA", "s1pW__1_WDDA_A11", "s1pW__1_WDDA_A21",
    "s1pW__1_WDDA_V21",
]

DCS_ACTION_NAMES = [
    "NO_ACTION", "NOT_ALLOWED", "RDD", "SEND_F21", "SEND_F31", "SEND_F32",
    "SEND_LCA", "SEND_LCIA", "SEND_LRA", "SEND_LWA", "SEND_RA2",
    "SEND_RA3", "SEND_RRA", "SEND_RWA", "STALL", "WDD",
]


def indexed_name(names: List[str], value: Any, prefix: str) -> str:
    try:
        index = int(value)
    except (TypeError, ValueError):
        return ""
    return names[index] if 0 <= index < len(names) else f"{prefix}_{index}"


def decode_dcs_event(row: Dict[str, Any]) -> None:
    row["dcs_trace.event.req"] = row.get("request", "")
    row["dcs_trace.event.cli"] = fmt_hex_no0x(parse_hex_int(str(row.get("cli", ""))))
    row["dcs_trace.event.action"] = row.get("action", "")
    row["dcs_trace.event.state"] = row.get("state", "")
    row["request_name"] = indexed_name(DCS_REQUEST_NAMES, row.get("request"), "REQ")
    row["state_name"] = indexed_name(DCS_STATE_NAMES, row.get("state"), "STATE")
    row["action_name"] = indexed_name(DCS_ACTION_NAMES, row.get("action"), "ACTION")


def decode_eci_by_opcode(word: int, vc: Optional[int] = None, src: Optional[int] = None) -> Dict[str, Any]:
    res: Dict[str, Any] = {"raw": f"0x{word:016x}"}
    opcode = bit_range(word, 63, 59)
    res["opcode"] = opcode

    mreq_map = {
        0: "ECI_CMD_MREQ_RLDD", 1: "ECI_CMD_MREQ_RLDI",
        2: "ECI_CMD_MREQ_RLDT", 3: "ECI_CMD_MREQ_RLDY",
        4: "ECI_CMD_MREQ_RLDWB", 5: "ECI_CMD_MREQ_RLDX",
        6: "ECI_CMD_MREQ_RC2D_O", 7: "ECI_CMD_MREQ_RC2D_S",
        8: "ECI_CMD_MREQ_RSTT", 9: "ECI_CMD_MREQ_RSTY",
        10: "ECI_CMD_MREQ_RSTP", 24: "ECI_CMD_MREQ_GSYNC",
    }
    mrsp_map = {
        0: "ECI_CMD_MRSP_VICD", 1: "ECI_CMD_MRSP_VICC",
        2: "ECI_CMD_MRSP_VICS", 3: "ECI_CMD_MRSP_VICDHI",
        4: "ECI_CMD_MRSP_HAKD", 5: "ECI_CMD_MRSP_HAKN_S",
        6: "ECI_CMD_MRSP_HAKI", 7: "ECI_CMD_MRSP_HAKS",
        8: "ECI_CMD_MRSP_HAKV", 9: "ECI_CMD_MRSP_PSHA",
        10: "ECI_CMD_MRSP_PEMD", 24: "ECI_CMD_MRSP_GSDN",
    }
    mfwd_map = {
        0: "ECI_CMD_MFWD_FLDRO_E", 1: "ECI_CMD_MFWD_FLDRO_O",
        2: "ECI_CMD_MFWD_FLDRS_E", 3: "ECI_CMD_MFWD_FLDRS_O",
        4: "ECI_CMD_MFWD_FLDRS_EH", 5: "ECI_CMD_MFWD_FLDRS_OH",
        6: "ECI_CMD_MFWD_FLDT_E", 7: "ECI_CMD_MFWD_FLDX_E",
        8: "ECI_CMD_MFWD_FLDX_O", 11: "ECI_CMD_MFWD_FEVX_EH",
        12: "ECI_CMD_MFWD_FEVX_OH", 13: "ECI_CMD_MFWD_SINV",
        14: "ECI_CMD_MFWD_SINV_H",
    }

    if src is None:
        if opcode in mrsp_map:
            cls = "mrsp"
        elif opcode in mreq_map:
            cls = "mreq"
        elif opcode in mfwd_map:
            cls = "mfwd"
        else:
            cls = "unknown"
    elif src == 0:
        cls = "mreq"
    elif 1 <= src <= 4:
        cls = "mrsp"
    elif src == 5:
        cls = "mfwd"
    else:
        cls = "unknown"

    if cls == "mreq":
        name = mreq_map.get(opcode, f"MREQ_OP_{opcode}")
    elif cls == "mrsp":
        name = mrsp_map.get(opcode, f"MRSP_OP_{opcode}")
    elif cls == "mfwd":
        name = mfwd_map.get(opcode, f"MFWD_OP_{opcode}")
    else:
        name = f"OP_{opcode}"
    res["message"] = name

    if cls == "mreq" and opcode == 24:
        res.update({
            "xb3": bit_range(word, 58, 55),
            "rreq_id": bit_range(word, 54, 50),
            "xb40": bit_range(word, 49, 10),
            "rtad": bit_range(word, 9, 7),
            "xb1": bit_range(word, 6, 6),
            "ppvid": bit_range(word, 5, 0),
        })
        return res

    if cls == "mreq":
        aliased = bit_range(word, 39, 0)
        res.update({
            "xb4": bit_range(word, 58, 55),
            "rreq_id": bit_range(word, 54, 50),
            "dmask": bit_range(word, 49, 46),
            "ns": bit_range(word, 45, 45),
            "xb3": bit_range(word, 44, 42),
            "xb2": bit_range(word, 41, 40),
            "aliased_addr": fmt_hex_no0x(aliased),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased)),
        })
        return res

    if cls == "mrsp" and opcode in (0, 1, 2):
        aliased = bit_range(word, 39, 0)
        res.update({
            "xb10": bit_range(word, 58, 50),
            "dmask": bit_range(word, 49, 46),
            "ns": bit_range(word, 45, 45),
            "xb5": bit_range(word, 44, 40),
            "aliased_addr": fmt_hex_no0x(aliased),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased)),
        })
        return res

    if cls == "mrsp" and 3 <= opcode <= 8:
        aliased = bit_range(word, 39, 0)
        res.update({
            "xb3": bit_range(word, 58, 56),
            "hreq_id": bit_range(word, 55, 50),
            "dmask": bit_range(word, 49, 46),
            "ns": bit_range(word, 45, 45),
            "xb5": bit_range(word, 44, 40),
            "aliased_addr": fmt_hex_no0x(aliased),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased)),
        })
        return res

    if cls == "mrsp" and opcode == 24:
        res.update({
            "xb12": bit_range(word, 58, 46),
            "ns": bit_range(word, 45, 45),
            "xb35": bit_range(word, 44, 10),
            "rtad": bit_range(word, 9, 7),
            "xb1": bit_range(word, 6, 6),
            "ppvid": bit_range(word, 5, 0),
        })
        return res

    if cls == "mrsp" and opcode in (9, 10):
        aliased_cli = bit_range(word, 39, 7)
        aliased_addr = (aliased_cli << 7) & ((1 << 40) - 1)
        res.update({
            "nxm": bit_range(word, 58, 58),
            "xb3": bit_range(word, 57, 55),
            "rreq_id": bit_range(word, 54, 50),
            "dmask": bit_range(word, 49, 46),
            "xb1": bit_range(word, 45, 45),
            "dirty": bit_range(word, 44, 41),
            "xb1_2": bit_range(word, 40, 40),
            "aliased_addr": fmt_hex_no0x(aliased_addr),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased_addr)),
            "fillo": bit_range(word, 6, 5),
            "xb5": bit_range(word, 4, 0),
        })
        return res

    if cls == "mfwd":
        aliased = bit_range(word, 39, 0)
        res.update({
            "xb3": bit_range(word, 58, 56),
            "hreq_id": bit_range(word, 55, 50),
            "dmask": bit_range(word, 49, 46),
            "ns": bit_range(word, 45, 45),
            "xb1": bit_range(word, 44, 44),
            "rnode": bit_range(word, 43, 42),
            "xb2": bit_range(word, 41, 40),
            "aliased_addr": fmt_hex_no0x(aliased),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased)),
        })
        return res

    res.update({
        "sz": bit_range(word, 58, 56),
        "xb1": bit_range(word, 55, 55),
        "rreq_id": bit_range(word, 54, 50),
        "dmask": bit_range(word, 49, 46),
        "rest_cmd": bit_range(word, 35, 0),
    })
    return res


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


def sample_timestamp(sample: int, trace_map: Dict[str, Any]) -> int:
    sample_cfg = trace_map["sample"]
    payload_width = int(sample_cfg["payload_width"])
    source_width = int(sample_cfg["source_width"])
    timestamp_width = int(sample_cfg["timestamp_width"])
    return bits(sample, payload_width + source_width, timestamp_width)


def realign_samples_by_timestamp(
    samples: List[Tuple[int, int]],
    trace_map: Dict[str, Any],
) -> List[Tuple[int, int]]:
    if len(samples) < 2:
        return samples

    timestamps = [sample_timestamp(sample, trace_map) for _, sample in samples]
    for index in range(1, len(samples)):
        if timestamps[index] < timestamps[index - 1]:
            return samples[index:] + samples[:index]
    return samples


def decode_sample(logical_index: int, physical_index: int, sample: int, trace_map: Dict[str, Any]) -> Dict[str, Any]:
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
        "sample": logical_index,
        "beat": (logical_index * sample_width) // beat_bits,
        "physical_sample": physical_index,
        "physical_beat": (physical_index * sample_width) // beat_bits,
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

    if row["type"] == "dcs_event":
        decode_dcs_event(row)

    if row["type"] == "eci":
        eci_header = bits(payload, 0, 64)
        row["eci_header"] = f"0x{eci_header:016x}"
        row["stall_counter_shift"] = eci_stall_counter_shift
        row["stall_cycles"] = int(row.get("stall_count", 0)) << eci_stall_counter_shift
        row.update(decode_eci_by_opcode(eci_header, vc=row.get("vc"), src=row.get("local_source")))

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

    samples: List[Tuple[int, int]] = []
    for index, sample in iterator:
        if not args.include_zero and sample == 0:
            break
        samples.append((index, sample))

    samples = realign_samples_by_timestamp(samples, trace_map)
    if args.samples is not None:
        samples = samples[:args.samples]

    rows = [
        decode_sample(logical_index, physical_index, sample, trace_map)
        for logical_index, (physical_index, sample) in enumerate(samples)
    ]

    fieldnames = [
        "sample", "beat", "physical_sample", "physical_beat", "timestamp",
        "source", "port", "type", "clock_domain", "dcs", "local_source",
        "channel", "payload", "lost_count",
        "error", "cli", "state", "state_name", "action", "action_name",
        "request", "request_name", "dcs_trace.event.req", "dcs_trace.event.cli",
        "dcs_trace.event.action", "dcs_trace.event.state",
        "eci_header", "vc", "stall_count", "stall_counter_shift", "stall_cycles", "accepted",
        "raw", "opcode", "message", "aliased_addr", "unaliased_addr",
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
