import json
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Optional, Tuple

try:
    from .common import bits, platform_root
except ImportError:
    from common import bits, platform_root


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


def timestamp_modulus(trace_map: Dict[str, Any]) -> int:
    return 1 << int(trace_map["sample"]["timestamp_width"])


def sample_bytes(trace_map: Dict[str, Any]) -> int:
    sample_width = int(trace_map["sample"]["sample_width"])
    if sample_width % 8 != 0:
        raise ValueError("pcapng export requires byte-aligned trace samples")
    return sample_width // 8


def sample_source(sample: int, trace_map: Dict[str, Any]) -> int:
    sample_cfg = trace_map["sample"]
    payload_width = int(sample_cfg["payload_width"])
    source_width = int(sample_cfg["source_width"])
    return bits(sample, payload_width, source_width)


def sample_payload(sample: int, trace_map: Dict[str, Any]) -> int:
    payload_width = int(trace_map["sample"]["payload_width"])
    return bits(sample, 0, payload_width)


def source_info(trace_map: Dict[str, Any], source: int) -> Dict[str, Any]:
    return trace_map.get("sources_by_id", {}).get(source, {})


def source_pipeline_stages(source: int, trace_map: Dict[str, Any]) -> int:
    return int(source_info(trace_map, source).get("pipeline_stages", 0))


def raw_sample_timestamp(sample: int, trace_map: Dict[str, Any]) -> int:
    sample_cfg = trace_map["sample"]
    payload_width = int(sample_cfg["payload_width"])
    source_width = int(sample_cfg["source_width"])
    timestamp_width = int(sample_cfg["timestamp_width"])
    return bits(sample, payload_width + source_width, timestamp_width)


def sample_timestamp(sample: int, trace_map: Dict[str, Any]) -> int:
    return adjust_timestamp(raw_sample_timestamp(sample, trace_map), sample, trace_map)


def adjust_timestamp(raw_timestamp: int, sample: int, trace_map: Dict[str, Any]) -> int:
    return adjust_timestamp_for_source(raw_timestamp, sample_source(sample, trace_map), trace_map)


def adjust_timestamp_for_source(raw_timestamp: int, source: int, trace_map: Dict[str, Any]) -> int:
    """Correct fixed pipeline latency in the hardware timestamp modulo domain."""
    stages = source_pipeline_stages(source, trace_map)
    return (raw_timestamp - stages) % timestamp_modulus(trace_map)


def source_matches(sample: int, trace_map: Dict[str, Any], source: Optional[int]) -> bool:
    if source is None:
        return True
    return sample_source(sample, trace_map) == source


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
    timestamp counter wrap is therefore not treated as the buffer wrap.
    """
    modulus = timestamp_modulus(trace_map)
    wrap_index: Optional[int] = None
    max_delta = 0
    previous_ts: Optional[int] = None
    count = 0

    for index, sample in iter_sample_range(input_path, trace_map, offset):
        ts = raw_sample_timestamp(sample, trace_map)
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
) -> Iterator[Tuple[int, int, int, int]]:
    wrap_index, count = scan_samples(input_path, trace_map, offset)
    modulus = timestamp_modulus(trace_map)
    logical_index = 0
    previous_ts: Optional[int] = None
    timestamp_epoch = 0

    for start, stop in chronological_ranges(wrap_index, count):
        for physical_index, sample in iter_sample_range(input_path, trace_map, offset, start=start, stop=stop):
            raw_ts = raw_sample_timestamp(sample, trace_map)
            if previous_ts is not None and raw_ts < previous_ts:
                timestamp_epoch += modulus
            previous_ts = raw_ts

            yield logical_index, physical_index, sample, timestamp_epoch + raw_ts
            logical_index += 1


def adjusted_chronological_samples(
    input_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    start_sample: int = 0,
    sample_limit: Optional[int] = None,
) -> List[Tuple[int, int, int, int, int]]:
    rows = []
    for raw_logical_index, physical_index, sample, raw_timestamp in iter_chronological_samples(input_path, trace_map, offset):
        timestamp = adjust_timestamp(raw_timestamp, sample, trace_map)
        rows.append((timestamp, raw_logical_index, physical_index, sample, raw_timestamp))

    rows.sort(key=lambda row: (row[0], row[1]))
    if start_sample:
        rows = rows[start_sample:]
    if sample_limit is not None:
        rows = rows[:sample_limit]

    return [
        (logical_index, physical_index, sample, timestamp, raw_timestamp)
        for logical_index, (timestamp, _raw_logical_index, physical_index, sample, raw_timestamp) in enumerate(rows)
    ]


def realign_samples_by_timestamp(
    samples: List[Tuple[int, int]],
    trace_map: Dict[str, Any],
) -> List[Tuple[int, int]]:
    if len(samples) < 2:
        return samples

    timestamps = [raw_sample_timestamp(sample, trace_map) for _, sample in samples]
    for index in range(1, len(samples)):
        if timestamps[index] < timestamps[index - 1]:
            return samples[index:] + samples[:index]
    return samples


def decode_sample(logical_index: int, physical_index: int, sample: int, trace_map: Dict[str, Any]) -> Dict[str, Any]:
    sample_cfg = trace_map["sample"]
    payload_width = int(sample_cfg["payload_width"])
    source_width = int(sample_cfg["source_width"])
    sample_width = int(sample_cfg["sample_width"])
    beat_bits = int(sample_cfg.get("axi_data_width", 512))
    lost_source = int(sample_cfg.get("lost_source", (1 << source_width) - 1))
    lost_count_width = int(sample_cfg.get("lost_count_width", 32))

    payload = sample_payload(sample, trace_map)
    source = sample_source(sample, trace_map)
    raw_timestamp = raw_sample_timestamp(sample, trace_map)
    src_info = source_info(trace_map, source) or {"type": "unknown"}
    pipeline_stages = source_pipeline_stages(source, trace_map)
    timestamp = adjust_timestamp_for_source(raw_timestamp, source, trace_map)

    row: Dict[str, Any] = {
        "sample": logical_index,
        "beat": (logical_index * sample_width) // beat_bits,
        "physical_sample": physical_index,
        "physical_beat": (physical_index * sample_width) // beat_bits,
        "timestamp": timestamp,
        "raw_timestamp": raw_timestamp,
        "source": source,
        "port": src_info.get("port", ""),
        "type": src_info.get("type", "unknown"),
        "clock_domain": src_info.get("clock_domain", ""),
        "dcs": src_info.get("dcs", ""),
        "local_source": src_info.get("local_source", ""),
        "channel": src_info.get("channel", ""),
        "pipeline_stages": pipeline_stages,
        "payload": f"0x{payload:0{(payload_width + 3) // 4}x}",
    }

    if source == lost_source or row["type"] == "lost":
        lost_count = bits(payload, 0, lost_count_width)
        row["type"] = "bubble" if lost_count == 0 else "lost"
        row["lost_count"] = lost_count
        return row

    return row


def decode_samples(
    samples: List[Tuple[int, int]],
    trace_map: Dict[str, Any],
    limit: Optional[int] = None,
) -> List[Dict[str, Any]]:
    samples = realign_samples_by_timestamp(samples, trace_map)
    rows = [
        decode_sample(logical_index, physical_index, sample, trace_map)
        for logical_index, (physical_index, sample) in enumerate(samples)
    ]
    rows.sort(key=lambda row: (row["timestamp"], row["sample"]))
    if limit is not None:
        rows = rows[:limit]

    sample_width = int(trace_map["sample"]["sample_width"])
    beat_bits = int(trace_map["sample"].get("axi_data_width", 512))
    for logical_index, row in enumerate(rows):
        row["sample"] = logical_index
        row["beat"] = (logical_index * sample_width) // beat_bits

    return rows
