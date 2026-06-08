import csv
import tarfile
import tempfile
from collections import OrderedDict
from pathlib import Path
from typing import Any, Callable, Dict, Optional, TextIO, Tuple

try:
    from .common import bit_range, bits
    from .dma_decode import (
        adjust_timestamp,
        chronological_ranges,
        iter_sample_range,
        raw_sample_timestamp,
        sample_payload,
        sample_source,
        scan_samples,
        source_info,
        timestamp_modulus,
    )
    from .trace_metadata import enrich_trace_map
except ImportError:
    from common import bit_range, bits
    from dma_decode import (
        adjust_timestamp,
        chronological_ranges,
        iter_sample_range,
        raw_sample_timestamp,
        sample_payload,
        sample_source,
        scan_samples,
        source_info,
        timestamp_modulus,
    )
    from trace_metadata import enrich_trace_map


ECI_OPCODE_OFFSET = 59
ECI_OPCODE_WIDTH = 5
ECI_DMASK_OFFSET = 46
ECI_DMASK_WIDTH = 4
MAX_OPEN_SPOOL_FILES = 64


class IncompleteTraceError(RuntimeError):
    pass


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


def _address_filename(address: Optional[int]) -> str:
    if address is None:
        return "addr_none.csv"
    return f"addr_0x{address:010x}.csv"


def _address_sort_key(address: Optional[int]) -> Tuple[int, int]:
    if address is None:
        return (0, 0)
    return (1, address)


class _CsvSpool:
    def __init__(self, temp_dir: Path, max_open_files: int = MAX_OPEN_SPOOL_FILES) -> None:
        self.temp_dir = temp_dir
        self.max_open_files = max_open_files
        self.addresses: set[Optional[int]] = set()
        self._open: OrderedDict[Optional[int], Tuple[TextIO, Any]] = OrderedDict()

    def write_event(self, address: Optional[int], timestamp: int, opcode_name: str, dmask: int) -> None:
        writer = self._writer(address)
        writer.writerow([timestamp, opcode_name, dmask])

    def close(self) -> None:
        while self._open:
            _address, (file_obj, _writer) = self._open.popitem()
            file_obj.close()

    def write_archive(self, output_path: Path) -> int:
        self.close()
        files = 0
        with tarfile.open(output_path, "w:gz") as archive:
            for address in sorted(self.addresses, key=_address_sort_key):
                path = self._path(address)
                info = tarfile.TarInfo(_address_filename(address))
                info.size = path.stat().st_size
                with path.open("rb") as f:
                    archive.addfile(info, f)
                files += 1
        return files

    def _writer(self, address: Optional[int]) -> Any:
        opened = self._open.get(address)
        if opened is not None:
            self._open.move_to_end(address)
            return opened[1]

        path = self._path(address)
        is_new = address not in self.addresses
        file_obj = path.open("a", newline="")
        writer = csv.writer(file_obj)
        if is_new:
            writer.writerow(["time", "opcode_name", "dmask"])
            self.addresses.add(address)
        self._open[address] = (file_obj, writer)

        while len(self._open) > self.max_open_files:
            _old_address, (old_file, _old_writer) = self._open.popitem(last=False)
            old_file.close()

        return writer

    def _path(self, address: Optional[int]) -> Path:
        return self.temp_dir / _address_filename(address)


def _sys_eci_pipeline_stages(trace_map: Dict[str, Any]) -> set[int]:
    stages = set()
    for src in trace_map.get("sources", []):
        if _is_sys_eci_source(src):
            stages.add(int(src.get("pipeline_stages", 0)))
    return stages


def run_eci_state_output(
    input_path: Path,
    output_path: Path,
    trace_map: Dict[str, Any],
    offset: int,
    scan_progress_update: Optional[Callable[[int], None]] = None,
    order_progress_update: Optional[Callable[[int], None]] = None,
    cache_path: Optional[Path] = None,
) -> Tuple[int, int]:
    wrap_index, count = scan_samples(
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

    sys_eci_stages = _sys_eci_pipeline_stages(trace_map)
    if len(sys_eci_stages) > 1:
        raise IncompleteTraceError(
            "ECI state output requires all sys-clock ECI sources to have the same "
            f"pipeline_stages for streaming order; found {sorted(sys_eci_stages)}"
        )

    frames = 0
    processed = 0
    modulus = timestamp_modulus(trace_map)
    previous_ts: Optional[int] = None
    timestamp_epoch = 0

    output_parent = output_path.parent if output_path.parent != Path("") else Path(".")
    with tempfile.TemporaryDirectory(prefix=f"{output_path.name}.", dir=output_parent) as temp_dir_name:
        spool = _CsvSpool(Path(temp_dir_name))
        try:
            for start, stop in chronological_ranges(wrap_index, count):
                for _physical_index, sample in iter_sample_range(
                    input_path,
                    trace_map,
                    offset,
                    start=start,
                    stop=stop,
                ):
                    raw_ts = raw_sample_timestamp(sample, trace_map)
                    if previous_ts is not None and raw_ts < previous_ts:
                        timestamp_epoch += modulus
                    previous_ts = raw_ts
                    timestamp = adjust_timestamp(timestamp_epoch + raw_ts, sample, trace_map)

                    source = sample_source(sample, trace_map)
                    payload = sample_payload(sample, trace_map)
                    if source == lost_source:
                        lost_count = bits(payload, 0, lost_count_width)
                        if lost_count != 0:
                            raise IncompleteTraceError(
                                f"ECI state output requires a complete trace; lost control frame reported {lost_count} lost samples"
                            )
                    else:
                        src_info = source_info(trace_map, source)
                        if _is_sys_eci_source(src_info) and _is_accepted_eci_payload(payload, trace_map):
                            raw_header = _field(payload, trace_map, "eci_header")
                            opcode = bits(raw_header, ECI_OPCODE_OFFSET, ECI_OPCODE_WIDTH)
                            unaliased_address = _unaliased_header_address(src_info, opcode, raw_header)
                            spool.write_event(
                                unaliased_address,
                                timestamp,
                                _opcode_name(export_map, src_info, opcode),
                                bits(raw_header, ECI_DMASK_OFFSET, ECI_DMASK_WIDTH),
                            )
                            frames += 1

                    processed += 1
                    if order_progress_update is not None and processed % 65536 == 0:
                        order_progress_update(processed)

            if order_progress_update is not None:
                order_progress_update(processed)
            files = spool.write_archive(output_path)
        finally:
            spool.close()

    return frames, files
