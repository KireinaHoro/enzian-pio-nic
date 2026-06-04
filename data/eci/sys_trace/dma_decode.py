import hashlib
import json
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, Iterator, List, Optional, Tuple

try:
    from .common import bits, platform_root
    from .sample_window import SampleWindow, apply_sample_window, last_samples_window
except ImportError:
    from common import bits, platform_root
    from sample_window import SampleWindow, apply_sample_window, last_samples_window


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


def sample_from_bytes(chunk: bytes) -> int:
    return int.from_bytes(chunk, "little")


def vivado_hw_axi_chunk(f, dump_offset: int, sample_offset: int, width: int, transaction_bytes: int) -> bytes:
    if transaction_bytes < width or transaction_bytes % width != 0:
        raise ValueError("Vivado transaction size must be a multiple of the trace sample size")
    chunk_base = (sample_offset // transaction_bytes) * transaction_bytes
    chunk_offset = sample_offset - chunk_base
    display_offset = chunk_base + transaction_bytes - chunk_offset - width
    f.seek(dump_offset + display_offset)
    chunk = f.read(width)
    return chunk[::-1]


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


SCAN_CACHE_VERSION = 1


def scan_cache_path(input_path: Path) -> Path:
    return input_path.with_name(f"{input_path.name}.lhtrace-scan.json")


def scan_cache_params(
    trace_map: Dict[str, Any],
    offset: int,
    input_order: str,
    vivado_transaction_bytes: int,
) -> Dict[str, Any]:
    sample_cfg = trace_map["sample"]
    return {
        "offset": int(offset),
        "input_order": input_order,
        "vivado_transaction_bytes": int(vivado_transaction_bytes),
        "payload_width": int(sample_cfg["payload_width"]),
        "source_width": int(sample_cfg["source_width"]),
        "timestamp_width": int(sample_cfg["timestamp_width"]),
        "sample_width": int(sample_cfg["sample_width"]),
        "byte_order": sample_cfg.get("byte_order", "little"),
    }


def read_scan_cache(cache_path: Path) -> Optional[Dict[str, Any]]:
    try:
        with cache_path.open() as f:
            cache = json.load(f)
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(cache, dict):
        return None
    return cache


def write_scan_cache(
    cache_path: Path,
    input_hash: str,
    params: Dict[str, Any],
    wrap_index: Optional[int],
    count: int,
) -> None:
    cache = {
        "version": SCAN_CACHE_VERSION,
        "input_hash": input_hash,
        "params": params,
        "scan": {
            "wrap_index": wrap_index,
            "count": int(count),
        },
    }
    tmp_path = cache_path.with_name(f"{cache_path.name}.tmp")
    try:
        with tmp_path.open("w") as f:
            json.dump(cache, f, indent=2, sort_keys=True)
            f.write("\n")
        tmp_path.replace(cache_path)
    except OSError:
        try:
            tmp_path.unlink()
        except OSError:
            pass


def cached_scan_result(
    cache: Dict[str, Any],
    input_hash: str,
    params: Dict[str, Any],
) -> Optional[Tuple[Optional[int], int]]:
    if cache.get("version") != SCAN_CACHE_VERSION:
        return None
    if cache.get("input_hash") != input_hash:
        return None
    if cache.get("params") != params:
        return None
    scan = cache.get("scan")
    if not isinstance(scan, dict):
        return None
    wrap_index = scan.get("wrap_index")
    count = scan.get("count")
    if wrap_index is not None and not isinstance(wrap_index, int):
        return None
    if not isinstance(count, int) or count < 0:
        return None
    return wrap_index, count


def iter_sample_range(
    path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    start: int = 0,
    stop: Optional[int] = None,
    input_order: str = "memory-little",
    vivado_transaction_bytes: int = 2048,
    chunk_update: Optional[Callable[[bytes], None]] = None,
) -> Iterator[Tuple[int, int]]:
    width = sample_bytes(trace_map)
    with path.open("rb") as f:
        index = start
        while stop is None or index < stop:
            sample_offset = index * width
            if input_order == "memory-little":
                f.seek(offset + sample_offset)
                chunk = f.read(width)
            elif input_order == "vivado-hw-axi":
                chunk = vivado_hw_axi_chunk(f, offset, sample_offset, width, vivado_transaction_bytes)
            else:
                raise ValueError(f"unsupported raw input order: {input_order}")
            if len(chunk) < width:
                break
            if chunk_update is not None:
                chunk_update(chunk)
            yield index, sample_from_bytes(chunk)
            index += 1


def hash_sample_stream(
    input_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    input_order: str,
    vivado_transaction_bytes: int,
) -> Tuple[str, int]:
    hasher = hashlib.sha256()
    if input_order == "memory-little":
        width = sample_bytes(trace_map)
        size = input_path.stat().st_size
        count = 0 if offset >= size else (size - offset) // width
        remaining = count * width
        with input_path.open("rb") as f:
            f.seek(offset)
            while remaining > 0:
                chunk = f.read(min(4 * 1024 * 1024, remaining))
                if not chunk:
                    break
                hasher.update(chunk)
                remaining -= len(chunk)
        return hasher.hexdigest(), count

    count = 0
    for index, _sample in iter_sample_range(
        input_path,
        trace_map,
        offset,
        input_order=input_order,
        vivado_transaction_bytes=vivado_transaction_bytes,
        chunk_update=hasher.update,
    ):
        count = index + 1
    return hasher.hexdigest(), count


def scan_samples(
    input_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    input_order: str = "memory-little",
    vivado_transaction_bytes: int = 2048,
    progress_update: Optional[Callable[[int], None]] = None,
) -> Tuple[Optional[int], int]:
    """Find circular-buffer wrap, while allowing the hardware timestamp to wrap.

    Adjacent samples in chronological order have a small positive timestamp delta
    modulo the timestamp width. The physical circular-buffer boundary appears as
    one large modulo jump from the newest sample back to older samples. A natural
    timestamp counter wrap is therefore not treated as the buffer wrap.
    """
    params = scan_cache_params(trace_map, offset, input_order, vivado_transaction_bytes)
    cache_path = scan_cache_path(input_path)
    cache = read_scan_cache(cache_path)
    input_hash: Optional[str] = None
    if cache is not None:
        input_hash, _hash_count = hash_sample_stream(
            input_path,
            trace_map,
            offset,
            input_order=input_order,
            vivado_transaction_bytes=vivado_transaction_bytes,
        )
        result = cached_scan_result(cache, input_hash, params)
        if result is not None:
            _wrap_index, cached_count = result
            if progress_update is not None:
                progress_update(cached_count)
            return result

    modulus = timestamp_modulus(trace_map)
    wrap_index: Optional[int] = None
    max_delta = 0
    previous_ts: Optional[int] = None
    count = 0
    hasher = hashlib.sha256()

    for index, sample in iter_sample_range(
        input_path,
        trace_map,
        offset,
        input_order=input_order,
        vivado_transaction_bytes=vivado_transaction_bytes,
        chunk_update=hasher.update if input_hash is None else None,
    ):
        ts = raw_sample_timestamp(sample, trace_map)
        if previous_ts is not None:
            delta = (ts - previous_ts) % modulus
            if delta > max_delta:
                max_delta = delta
                wrap_index = index
        previous_ts = ts
        count = index + 1
        if progress_update is not None and count % 65536 == 0:
            progress_update(count)

    if progress_update is not None:
        progress_update(count)
    if max_delta <= modulus // 2:
        wrap_index = None
    if input_hash is None:
        input_hash = hasher.hexdigest()
    write_scan_cache(cache_path, input_hash, params, wrap_index, count)
    return wrap_index, count


def chronological_ranges(wrap_index: Optional[int], count: int) -> Iterable[Tuple[int, Optional[int]]]:
    if wrap_index is None:
        return [(0, count)]
    return [(wrap_index, count), (0, wrap_index)]


def iter_chronological_samples(
    input_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    input_order: str = "memory-little",
    vivado_transaction_bytes: int = 2048,
    scan_progress_update: Optional[Callable[[int], None]] = None,
) -> Iterator[Tuple[int, int, int, int]]:
    wrap_index, count = scan_samples(
        input_path,
        trace_map,
        offset,
        input_order=input_order,
        vivado_transaction_bytes=vivado_transaction_bytes,
        progress_update=scan_progress_update,
    )
    modulus = timestamp_modulus(trace_map)
    logical_index = 0
    previous_ts: Optional[int] = None
    timestamp_epoch = 0

    for start, stop in chronological_ranges(wrap_index, count):
        for physical_index, sample in iter_sample_range(
            input_path,
            trace_map,
            offset,
            start=start,
            stop=stop,
            input_order=input_order,
            vivado_transaction_bytes=vivado_transaction_bytes,
        ):
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
    sample_window: Optional[SampleWindow] = None,
    input_order: str = "memory-little",
    vivado_transaction_bytes: int = 2048,
    scan_progress_update: Optional[Callable[[int], None]] = None,
    order_progress_update: Optional[Callable[[int], None]] = None,
) -> List[Tuple[int, int, int, int, int]]:
    rows = []
    for raw_logical_index, physical_index, sample, raw_timestamp in iter_chronological_samples(
        input_path,
        trace_map,
        offset,
        input_order=input_order,
        vivado_transaction_bytes=vivado_transaction_bytes,
        scan_progress_update=scan_progress_update,
    ):
        timestamp = adjust_timestamp(raw_timestamp, sample, trace_map)
        rows.append((timestamp, raw_logical_index, physical_index, sample, raw_timestamp))
        if order_progress_update is not None and len(rows) % 65536 == 0:
            order_progress_update(len(rows))

    if order_progress_update is not None:
        order_progress_update(len(rows))

    rows.sort(key=lambda row: (row[0], row[1]))
    if start_sample:
        rows = rows[start_sample:]
    if sample_window is None and sample_limit is not None:
        sample_window = last_samples_window(sample_limit)
    rows = apply_sample_window(rows, sample_window)

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
