from pathlib import Path
from typing import Any, Callable, Dict, Optional

try:
    from .common import bit_range, bits
    from .dma_decode import (
        adjusted_chronological_samples,
        sample_payload,
        sample_source,
        scan_samples,
        source_info,
    )
    from .trace_metadata import enrich_trace_map
except ImportError:
    from common import bit_range, bits
    from dma_decode import (
        adjusted_chronological_samples,
        sample_payload,
        sample_source,
        scan_samples,
        source_info,
    )
    from trace_metadata import enrich_trace_map


ECI_OPCODE_OFFSET = 59
ECI_OPCODE_WIDTH = 5
ECI_DMASK_OFFSET = 46
ECI_DMASK_WIDTH = 4


class IncompleteTraceError(RuntimeError):
    pass


def handle_eci_frame(
    *,
    time: int,
    time_ns: int,
    opcode: int,
    opcode_name: str,
    dmask: int,
    unaliased_address: Optional[int],
    raw_header: int,
) -> None:
    """Hook for an external ECI state machine model."""
    _ = (time, time_ns, opcode, opcode_name, dmask, unaliased_address, raw_header)


def _field(payload: int, trace_map: Dict[str, Any], name: str) -> int:
    field = trace_map["payload_formats"]["eci"]["fields"][name]
    return bits(payload, int(field["offset"]), int(field["width"]))


def _eci_class(src_info: Dict[str, Any]) -> Optional[str]:
    channel = str(src_info.get("channel", ""))
    if "req" in channel:
        return "mreq"
    if "rsp" in channel:
        return "mrsp"
    if "fwd" in channel:
        return "mfwd"

    local_source = src_info.get("local_source")
    try:
        local_source_int = int(local_source)
    except (TypeError, ValueError):
        return None
    if local_source_int == 0:
        return "mreq"
    if 1 <= local_source_int <= 4:
        return "mrsp"
    if local_source_int == 5:
        return "mfwd"
    return None


def _opcode_name(trace_map: Dict[str, Any], src_info: Dict[str, Any], opcode: int) -> str:
    eci_format = trace_map.get("payload_formats", {}).get("eci", {})
    opcode_enums = eci_format.get("opcode_enums", {})
    eci_class = _eci_class(src_info)
    if eci_class is not None:
        name = opcode_enums.get(eci_class, {}).get(str(opcode))
        if name is not None:
            return str(name)
    return f"opcode_{opcode}"


def _unalias_address(aliased_addr: int) -> int:
    aliased_cli = bit_range(aliased_addr, 39, 7)
    byte_offset = bit_range(aliased_addr, 6, 0)
    cli = 0
    cli += bit_range(aliased_cli, 32, 13) << 13
    cli += (bit_range(aliased_cli, 12, 8) ^ bit_range(aliased_cli, 17, 13)) << 8
    cli += (bit_range(aliased_cli, 7, 5) ^ bit_range(aliased_cli, 20, 18)) << 5
    cli += (
        bit_range(aliased_cli, 4, 3)
        ^ bit_range(aliased_cli, 19, 18)
        ^ bit_range(aliased_cli, 17, 16)
        ^ bit_range(aliased_cli, 6, 5)
    ) << 3
    cli += (
        bit_range(aliased_cli, 2, 0)
        ^ bit_range(aliased_cli, 20, 18)
        ^ bit_range(aliased_cli, 15, 13)
        ^ bit_range(aliased_cli, 7, 5)
    )
    return (cli << 7) + byte_offset


def _unaliased_header_address(src_info: Dict[str, Any], opcode: int, raw_header: int) -> Optional[int]:
    eci_class = _eci_class(src_info)
    if eci_class in ("mreq", "mrsp") and opcode == 24:
        return None
    if eci_class == "mrsp" and opcode in (9, 10):
        aliased_addr = bit_range(raw_header, 39, 7) << 7
    else:
        aliased_addr = bit_range(raw_header, 39, 0)
    return _unalias_address(aliased_addr)


def _is_sys_eci_source(src_info: Dict[str, Any]) -> bool:
    return src_info.get("type") == "eci" and src_info.get("clock_domain") == "sys"


def _is_accepted_eci_payload(payload: int, trace_map: Dict[str, Any]) -> bool:
    try:
        return _field(payload, trace_map, "accepted") != 0
    except KeyError:
        return True


def run_eci_state_output(
    input_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    cycle_ns: int,
    frame_handler: Callable[..., None] = handle_eci_frame,
    scan_progress_update: Optional[Callable[[int], None]] = None,
    order_progress_update: Optional[Callable[[int], None]] = None,
    cache_path: Optional[Path] = None,
) -> int:
    wrap_index, _count = scan_samples(
        input_path,
        trace_map,
        offset,
        progress_update=scan_progress_update,
        cache_path=cache_path,
    )
    if wrap_index is not None:
        raise IncompleteTraceError(
            f"ECI state output requires a complete unwrapped trace; circular-buffer wrap detected at sample {wrap_index}"
        )

    export_map = enrich_trace_map(trace_map)
    lost_source = int(trace_map["sample"]["lost_source"])
    lost_count_width = int(trace_map["sample"].get("lost_count_width", 32))
    frames = 0

    samples = adjusted_chronological_samples(
        input_path,
        trace_map,
        offset,
        scan_progress_update=None,
        order_progress_update=order_progress_update,
        cache_path=cache_path,
    )
    for _logical_index, _physical_index, sample, _timestamp, _raw_timestamp in samples:
        source = sample_source(sample, trace_map)
        payload = sample_payload(sample, trace_map)
        if source == lost_source:
            lost_count = bits(payload, 0, lost_count_width)
            if lost_count != 0:
                raise IncompleteTraceError(
                    f"ECI state output requires a complete trace; lost control frame reported {lost_count} lost samples"
                )

    for _logical_index, _physical_index, sample, timestamp, _raw_timestamp in samples:
        source = sample_source(sample, trace_map)
        payload = sample_payload(sample, trace_map)
        if source == lost_source:
            continue
        src_info = source_info(trace_map, source)
        if not _is_sys_eci_source(src_info) or not _is_accepted_eci_payload(payload, trace_map):
            continue

        raw_header = _field(payload, trace_map, "eci_header")
        opcode = bits(raw_header, ECI_OPCODE_OFFSET, ECI_OPCODE_WIDTH)
        frame_handler(
            time=timestamp,
            time_ns=timestamp * cycle_ns,
            opcode=opcode,
            opcode_name=_opcode_name(export_map, src_info, opcode),
            dmask=bits(raw_header, ECI_DMASK_OFFSET, ECI_DMASK_WIDTH),
            unaliased_address=_unaliased_header_address(src_info, opcode, raw_header),
            raw_header=raw_header,
        )
        frames += 1

    return frames
