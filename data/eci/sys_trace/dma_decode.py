import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

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

    if row["type"] == "eci":
        eci_header = bits(payload, 0, 64)
        row["eci_header"] = f"0x{eci_header:016x}"
        row["stall_counter_shift"] = eci_stall_counter_shift
        row["stall_cycles"] = int(row.get("stall_count", 0)) << eci_stall_counter_shift

    if row["type"] == "lauberhorn_event":
        events = payload_format.get("events", {})
        event_id = int(row.get("event_id", 0))
        row["event"] = events.get(str(event_id), f"event_{event_id}")

    return row


def decode_samples(
    samples: List[Tuple[int, int]],
    trace_map: Dict[str, Any],
    limit: Optional[int] = None,
) -> List[Dict[str, Any]]:
    samples = realign_samples_by_timestamp(samples, trace_map)
    if limit is not None:
        samples = samples[:limit]
    return [
        decode_sample(logical_index, physical_index, sample, trace_map)
        for logical_index, (physical_index, sample) in enumerate(samples)
    ]
