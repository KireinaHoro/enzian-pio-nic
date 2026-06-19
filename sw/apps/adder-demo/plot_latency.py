#!/usr/bin/env python3

import argparse
import bisect
import csv
import math
import sys
from collections import defaultdict
from itertools import chain
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


ROOT = Path(__file__).resolve().parents[3]
SYS_TRACE = ROOT / "data" / "eci" / "sys_trace"
if str(SYS_TRACE) not in sys.path:
    sys.path.insert(0, str(SYS_TRACE))

try:
    from common import bits
    from dma_decode import (
        default_map_path,
        filtered_adjusted_chronological_samples,
        load_map,
        sample_payload,
        sample_bytes,
        sample_source,
        source_info,
    )
    from export_trace_pcap import ProgressBar, prepared_raw_input, raw_sample_count
    from sample_window import parse_sample_window
except ImportError as e:
    raise SystemExit(f"failed to import sys_trace helpers from {SYS_TRACE}: {e}")


CLIENT_COLUMNS = [
    "request_id",
    "a",
    "b",
    "expected",
    "result",
    "response_request_id",
    "rpc_status",
    "ok",
    "client_call_enter_ns",
    "client_xdr_call_enter_ns",
    "client_xdr_call_exit_ns",
    "client_xdr_resp_enter_ns",
    "client_xdr_resp_exit_ns",
    "client_call_exit_ns",
    "xdr_call_count",
    "xdr_resp_count",
    "timestamp_overhead_ns",
]

SERVER_COLUMNS = [
    "request_id",
    "xid",
    "worker_id",
    "ok",
    "request_bytes",
    "response_bytes",
    "server_rx_enter_ns",
    "server_rx_exit_ns",
    "server_unmarshal_enter_ns",
    "server_unmarshal_exit_ns",
    "server_handler_enter_ns",
    "server_handler_exit_ns",
    "server_marshal_enter_ns",
    "server_marshal_exit_ns",
    "server_tx_enter_ns",
    "server_tx_exit_ns",
    "timestamp_overhead_ns",
    "timestamp_call_count",
]


def int_field(row: Dict[str, str], name: str, default: int = 0) -> int:
    value = row.get(name, "")
    if value == "":
        return default
    return int(value, 0)


def load_csv_by_request_id(path: Path, fallback_columns: List[str]) -> Dict[int, Dict[str, str]]:
    with path.open(newline="") as f:
        reader = csv.reader(f)
        try:
            first = next(reader)
        except StopIteration:
            raise SystemExit(f"{path}: empty CSV")

        if "request_id" in first:
            rows = csv.DictReader(f, fieldnames=first)
        elif len(first) == len(fallback_columns):
            print(f"warning: {path} has no header; assuming {len(fallback_columns)}-column adder trace schema")
            rows = (dict(zip(fallback_columns, values)) for values in chain([first], reader))
        else:
            raise SystemExit(
                f"{path}: CSV header does not contain request_id and row has {len(first)} columns; "
                f"expected {len(fallback_columns)} for headerless fallback"
            )

        ret: Dict[int, Dict[str, str]] = {}
        for row in rows:
            try:
                ret[int(row["request_id"], 0)] = row
            except KeyError:
                raise SystemExit(f"{path}: CSV does not contain request_id column")
        return ret


def corrected_delta(start: int, end: int, overhead_ns: int, timestamp_calls: int = 1) -> float:
    return max(0.0, float(end - start - overhead_ns * timestamp_calls))


def percentile(sorted_values: List[float], pct: float) -> float:
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]
    rank = pct / 100.0 * (len(sorted_values) - 1)
    lo = int(math.floor(rank))
    hi = int(math.ceil(rank))
    if lo == hi:
        return sorted_values[lo]
    frac = rank - lo
    return sorted_values[lo] * (1.0 - frac) + sorted_values[hi] * frac


def nearest_request(rows: List[Dict[str, Any]], target_ns: float) -> Dict[str, Any]:
    return min(rows, key=lambda row: abs(row["e2e_ns"] - target_ns))


def client_timestamp_count(row: Dict[str, str]) -> int:
    xdr_call_count = int_field(row, "xdr_call_count")
    xdr_resp_count = int_field(row, "xdr_resp_count")
    count = 2
    if xdr_call_count:
        count += 1 + xdr_call_count
    if xdr_resp_count:
        count += 1 + xdr_resp_count
    return count


def build_rows(
    client_rows: Dict[int, Dict[str, str]],
    server_rows: Dict[int, Dict[str, str]],
) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for request_id in sorted(set(client_rows) & set(server_rows)):
        c = client_rows[request_id]
        s = server_rows[request_id]
        c_oh = int_field(c, "timestamp_overhead_ns")
        s_oh = int_field(s, "timestamp_overhead_ns")
        e2e_ns = corrected_delta(
            int_field(c, "client_call_enter_ns"),
            int_field(c, "client_call_exit_ns"),
            c_oh,
            1,
        )

        client_xdr_ns = 0.0
        if int_field(c, "client_xdr_call_enter_ns") and int_field(c, "client_xdr_call_exit_ns"):
            client_xdr_ns += corrected_delta(
                int_field(c, "client_xdr_call_enter_ns"),
                int_field(c, "client_xdr_call_exit_ns"),
                c_oh,
            )
        if int_field(c, "client_xdr_resp_enter_ns") and int_field(c, "client_xdr_resp_exit_ns"):
            client_xdr_ns += corrected_delta(
                int_field(c, "client_xdr_resp_enter_ns"),
                int_field(c, "client_xdr_resp_exit_ns"),
                c_oh,
            )

        server_rx_ns = corrected_delta(
            int_field(s, "server_rx_enter_ns"),
            int_field(s, "server_rx_exit_ns"),
            s_oh,
        )
        server_unmarshal_ns = corrected_delta(
            int_field(s, "server_unmarshal_enter_ns"),
            int_field(s, "server_unmarshal_exit_ns"),
            s_oh,
        )
        handler_ns = corrected_delta(
            int_field(s, "server_handler_enter_ns"),
            int_field(s, "server_handler_exit_ns"),
            s_oh,
        )
        server_marshal_ns = corrected_delta(
            int_field(s, "server_marshal_enter_ns"),
            int_field(s, "server_marshal_exit_ns"),
            s_oh,
        )
        server_tx_ns = corrected_delta(
            int_field(s, "server_tx_enter_ns"),
            int_field(s, "server_tx_exit_ns"),
            s_oh,
        )

        rows.append({
            "request_id": request_id,
            "worker_id": int_field(s, "worker_id"),
            "client_ok": int_field(c, "ok"),
            "server_ok": int_field(s, "ok", 1),
            "e2e_ns": e2e_ns,
            "client_xdr_ns": client_xdr_ns,
            "server_unmarshal_ns": server_unmarshal_ns,
            "server_marshal_ns": server_marshal_ns,
            "handler_ns": handler_ns,
            "server_rx_ns": server_rx_ns,
            "server_tx_ns": server_tx_ns,
            "server_rx_enter_ns": int_field(s, "server_rx_enter_ns"),
            "server_rx_exit_ns": int_field(s, "server_rx_exit_ns"),
            "server_tx_enter_ns": int_field(s, "server_tx_enter_ns"),
            "server_tx_exit_ns": int_field(s, "server_tx_exit_ns"),
            "client_timestamp_overhead_ns": c_oh,
            "client_timestamp_count": client_timestamp_count(c),
            "server_timestamp_overhead_ns": s_oh,
            "server_timestamp_count": int_field(s, "timestamp_call_count", 10),
        })
    return rows


def event_decoder(trace_map: Dict[str, Any]):
    fmt = trace_map["payload_formats"]["lauberhorn_event"]
    id_field = fmt["fields"]["event_id"]
    event_id_offset = int(id_field["offset"])
    event_id_width = int(id_field["width"])
    data_offset = max(int(field["offset"]) + int(field["width"]) for field in fmt["fields"].values())
    event_names = {int(k): v for k, v in fmt["events"].items()}
    event_data = {int(k): v for k, v in fmt.get("event_data", {}).items()}
    widths = {k: int(v["width"]) for k, v in fmt.get("trace_data_keys", {}).items()}

    def decode(payload: int) -> Tuple[str, Dict[str, int]]:
        event_id = bits(payload, event_id_offset, event_id_width)
        offset = data_offset
        values: Dict[str, int] = {}
        for name in event_data.get(event_id, []):
            width = widths[name]
            values[name] = bits(payload, offset, width)
            offset += width
        return event_names.get(event_id, f"event_{event_id}"), values

    return decode


TRACE_EVENT_NAMES = {
    "RxCmacEntry",
    "RxAfterCdcQueue",
    "EthernetDecoder",
    "IpDecoder",
    "UdpDecoder",
    "OncRpcCallDecoder",
    "RxRpcEnqueueToHost",
    "SchedulerRequestQueued",
    "SchedulerPreemptCore",
    "PreemptCtrlBootstrap",
    "PreemptCtrlIpiIssued",
    "PreemptCtrlPreemptDatapath",
    "PreemptCtrlFetchReady",
    "PreemptCtrlAssertedReady",
    "SchedulerProcessRun",
    "PreemptCtrlDone",
    "SchedulerRequestDispatched",
    "EciRxDescSent",
    "EciTxAcquire",
    "EciTxCommitRead",
    "EciTxCtrlInvalidate",
    "EciTxCtrlUnlocked",
    "TxHostReqAccepted",
    "EciTxSubmit",
    "OncRpcReplyEncode",
    "UdpEncoder",
    "IpEncoder",
    "EthernetEncoder",
    "TxNoDmaRead",
    "TxAfterDmaRead",
    "TxBeforeCdcQueue",
    "TxCmacExit",
}


def decode_trace_events(
    trace_dump: Path,
    trace_map_path: Path,
    cycle_ns: int,
    sample_window_text: Optional[str],
) -> List[Dict[str, Any]]:
    trace_map = load_map(trace_map_path)
    decode = event_decoder(trace_map)
    fmt = trace_map["payload_formats"]["lauberhorn_event"]
    id_field = fmt["fields"]["event_id"]
    event_id_offset = int(id_field["offset"])
    event_id_width = int(id_field["width"])
    event_ids = {int(k) for k, name in fmt["events"].items() if name in TRACE_EVENT_NAMES}
    event_sources = {
        int(src["source"])
        for src in trace_map.get("sources", [])
        if src.get("type") == "lauberhorn_event"
    }
    sample_window = parse_sample_window(sample_window_text)
    events: List[Dict[str, Any]] = []

    def keep_sample(sample: int) -> bool:
        if sample_source(sample, trace_map) not in event_sources:
            return False
        event_id = bits(sample_payload(sample, trace_map), event_id_offset, event_id_width)
        return event_id in event_ids

    with prepared_raw_input(trace_dump) as (raw_input, cache_path):
        total_samples = raw_sample_count(raw_input, 0, sample_bytes(trace_map))
        scan_progress = ProgressBar(total_samples, label="samples scanned")
        filter_progress: Optional[ProgressBar] = None

        def update_filter_progress(current: int) -> None:
            nonlocal filter_progress
            if filter_progress is None:
                scan_progress.finish()
                filter_progress = ProgressBar(total_samples, label="samples filtered")
            filter_progress.update(current)

        samples = filtered_adjusted_chronological_samples(
            raw_input,
            trace_map,
            0,
            keep_sample,
            sample_window=sample_window,
            scan_progress_update=scan_progress.update,
            filter_progress_update=update_filter_progress,
            cache_path=cache_path,
        )
        scan_progress.finish()
        if filter_progress is not None:
            filter_progress.finish()
        for _logical, _physical, sample, timestamp, _raw_timestamp in samples:
            src = sample_source(sample, trace_map)
            info = source_info(trace_map, src)
            if info.get("type") != "lauberhorn_event":
                continue
            name, values = decode(sample_payload(sample, trace_map))
            events.append({
                "time_ns": int(timestamp) * cycle_ns,
                "source": src,
                "source_name": info.get("name", ""),
                "event": name,
                "data": values,
            })
    return events


def last_event_before(
    events_by_name: Dict[str, Tuple[List[int], List[Dict[str, Any]]]],
    event_name: str,
    before_ns: int,
    data_match: Optional[Dict[str, int]] = None,
) -> Optional[Dict[str, Any]]:
    times, events = events_by_name.get(event_name, ([], []))
    idx = bisect.bisect_left(times, before_ns) - 1
    while idx >= 0:
        ev = events[idx]
        if data_match is not None and any(ev["data"].get(k) != v for k, v in data_match.items()):
            idx -= 1
            continue
        return ev
    return None


def first_event_after(
    events_by_name: Dict[str, Tuple[List[int], List[Dict[str, Any]]]],
    event_name: str,
    after_ns: int,
    data_match: Optional[Dict[str, int]] = None,
    max_delta_ns: Optional[int] = None,
) -> Optional[Dict[str, Any]]:
    times, events = events_by_name.get(event_name, ([], []))
    idx = bisect.bisect_left(times, after_ns)
    while idx < len(events):
        ev = events[idx]
        if max_delta_ns is not None and ev["time_ns"] - after_ns > max_delta_ns:
            return None
        if data_match is not None and any(ev["data"].get(k) != v for k, v in data_match.items()):
            idx += 1
            continue
        return ev
    return None


def events_in_window(
    events_by_name: Dict[str, Tuple[List[int], List[Dict[str, Any]]]],
    event_name: str,
    start_ns: int,
    end_ns: int,
    data_match: Optional[Dict[str, int]] = None,
) -> List[Dict[str, Any]]:
    times, events = events_by_name.get(event_name, ([], []))
    idx = bisect.bisect_left(times, start_ns)
    ret = []
    while idx < len(events) and events[idx]["time_ns"] <= end_ns:
        ev = events[idx]
        if data_match is None or all(ev["data"].get(k) == v for k, v in data_match.items()):
            ret.append(ev)
        idx += 1
    return ret


def closest_event_in_window(
    events_by_name: Dict[str, Tuple[List[int], List[Dict[str, Any]]]],
    event_name: str,
    start_ns: int,
    end_ns: int,
    target_ns: int,
    data_match: Optional[Dict[str, int]] = None,
) -> Optional[Dict[str, Any]]:
    candidates = events_in_window(events_by_name, event_name, start_ns, end_ns, data_match)
    if not candidates:
        return None
    return min(candidates, key=lambda ev: abs(ev["time_ns"] - target_ns))


def build_event_index(events: List[Dict[str, Any]]) -> Dict[str, Tuple[List[int], List[Dict[str, Any]]]]:
    grouped: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for ev in events:
        grouped[ev["event"]].append(ev)
    return {name: ([ev["time_ns"] for ev in grouped_events], grouped_events) for name, grouped_events in grouped.items()}


def rec_event(rec: Dict[str, Any], event_name: str) -> Optional[Dict[str, Any]]:
    for ev in rec["events"]:
        if ev["event"] == event_name:
            return ev
    return None


def add_segment(segments: Dict[str, float], name: str, start: Optional[int], end: Optional[int]) -> None:
    if start is None or end is None or end < start:
        return
    segments[name] = float(end - start)


def attach_trace_segments(
    row: Dict[str, Any],
    rec: Dict[str, Any],
    events_by_name: Dict[str, Tuple[List[int], List[Dict[str, Any]]]],
    core: int,
    cpu_trace_offset_ns: int,
) -> None:
    segments: Dict[str, float] = {}
    rx_enqueue = rec_event(rec, "RxRpcEnqueueToHost")
    queued = rec_event(rec, "SchedulerRequestQueued")
    ipi = first_event_after(events_by_name, "PreemptCtrlIpiIssued", queued["time_ns"] if queued else rec["SchedulerRequestDispatched"], {"CoreID": core})
    process_run = rec_event(rec, "SchedulerProcessRun")
    dispatched = rec_event(rec, "SchedulerRequestDispatched")
    delivered_ns = row.get("trace_core_delivery_ns")

    rpc_decoder = None
    if rx_enqueue is not None:
        rpc_decoder = last_event_before(
            events_by_name,
            "OncRpcCallDecoder",
            rx_enqueue["time_ns"] + 1,
            {"RpcID": rx_enqueue["data"]["RpcID"]},
        )

    packet_id = rpc_decoder["data"]["PacketID"] if rpc_decoder is not None else None
    eth_decoder = ip_decoder = udp_decoder = rx_cmac = rx_after_cdc = None
    if packet_id is not None and rpc_decoder is not None:
        eth_decoder = last_event_before(events_by_name, "EthernetDecoder", rpc_decoder["time_ns"] + 1, {"PacketID": packet_id})
        ip_decoder = last_event_before(events_by_name, "IpDecoder", rpc_decoder["time_ns"] + 1, {"PacketID": packet_id})
        udp_decoder = last_event_before(events_by_name, "UdpDecoder", rpc_decoder["time_ns"] + 1, {"PacketID": packet_id})
        if eth_decoder is not None:
            rx_after_cdc = last_event_before(events_by_name, "RxAfterCdcQueue", eth_decoder["time_ns"] + 1)
            rx_cmac = last_event_before(events_by_name, "RxCmacEntry", eth_decoder["time_ns"] + 1)

    add_segment(segments, "lh_rx_cmac_to_cdc", rx_cmac["time_ns"] if rx_cmac else None, rx_after_cdc["time_ns"] if rx_after_cdc else None)
    add_segment(segments, "lh_rx_eth_decode", rx_after_cdc["time_ns"] if rx_after_cdc else None, eth_decoder["time_ns"] if eth_decoder else None)
    add_segment(segments, "lh_rx_ip_decode", eth_decoder["time_ns"] if eth_decoder else None, ip_decoder["time_ns"] if ip_decoder else None)
    add_segment(segments, "lh_rx_udp_decode", ip_decoder["time_ns"] if ip_decoder else None, udp_decoder["time_ns"] if udp_decoder else None)
    add_segment(segments, "lh_rx_rpc_decode", udp_decoder["time_ns"] if udp_decoder else None, rpc_decoder["time_ns"] if rpc_decoder else None)
    add_segment(segments, "lh_rx_host_enqueue", rpc_decoder["time_ns"] if rpc_decoder else None, rx_enqueue["time_ns"] if rx_enqueue else None)
    add_segment(segments, "lh_scheduler_enqueue", rx_enqueue["time_ns"] if rx_enqueue else None, queued["time_ns"] if queued else None)
    add_segment(segments, "lh_scheduler_preempt", queued["time_ns"] if queued else None, ipi["time_ns"] if ipi else None)
    add_segment(segments, "kernel_wakeup", ipi["time_ns"] if ipi else None, process_run["time_ns"] if process_run else None)
    add_segment(segments, "lh_scheduler_queue", process_run["time_ns"] if process_run else None, dispatched["time_ns"] if dispatched else None)
    add_segment(segments, "lh_rx_eci_delivery", dispatched["time_ns"] if dispatched else None, delivered_ns)

    tx_margin_ns = 25_000
    tx_window_start = int(row["server_tx_enter_ns"] - cpu_trace_offset_ns - tx_margin_ns)
    tx_window_end = int(row["server_tx_exit_ns"] - cpu_trace_offset_ns + tx_margin_ns)
    tx_target = int(row["server_tx_exit_ns"] - cpu_trace_offset_ns)
    tx_submit = closest_event_in_window(
        events_by_name,
        "EciTxSubmit",
        tx_window_start,
        tx_window_end,
        tx_target,
        {"CoreID": core},
    )
    tx_host_id = tx_submit["data"]["HostMsgID"] if tx_submit is not None else None
    reply_encode = udp_encoder = ip_encoder = eth_encoder = None
    tx_commit = tx_invalidate = tx_unlocked = host_accept = tx_no_dma = tx_after_dma = tx_dma_done = None
    if tx_host_id is not None and tx_submit is not None:
        row["trace_tx_host_msg_id"] = tx_host_id
        row["trace_core_rx_return_to_tx_submit_ns"] = float(
            tx_submit["time_ns"] - int(row["server_rx_exit_ns"] - cpu_trace_offset_ns)
        )
        row["trace_core_tx_return_to_tx_submit_ns"] = float(
            tx_submit["time_ns"] - int(row["server_tx_exit_ns"] - cpu_trace_offset_ns)
        )
        match = {"CoreID": core, "HostMsgID": tx_host_id}
        tx_commit = last_event_before(events_by_name, "EciTxCommitRead", tx_submit["time_ns"] + 1, match)
        tx_invalidate = first_event_after(
            events_by_name,
            "EciTxCtrlInvalidate",
            tx_commit["time_ns"] if tx_commit else tx_submit["time_ns"],
            match,
            max_delta_ns=100_000,
        )
        tx_unlocked = first_event_after(
            events_by_name,
            "EciTxCtrlUnlocked",
            tx_invalidate["time_ns"] if tx_invalidate else tx_submit["time_ns"],
            match,
            max_delta_ns=100_000,
        )
        host_accept = first_event_after(events_by_name, "TxHostReqAccepted", tx_submit["time_ns"], {"HostMsgID": tx_host_id})
        tx_no_dma = first_event_after(events_by_name, "TxNoDmaRead", tx_submit["time_ns"], {"HostMsgID": tx_host_id})
        tx_after_dma = first_event_after(events_by_name, "TxAfterDmaRead", tx_submit["time_ns"], {"HostMsgID": tx_host_id})
        if tx_submit is not None:
            tx_dma_done = tx_no_dma if tx_no_dma is not None else tx_after_dma
            encoder_start_ns = tx_submit["time_ns"]
            reply_encode = first_event_after(
                events_by_name,
                "OncRpcReplyEncode",
                encoder_start_ns,
                max_delta_ns=100_000,
            )
            response_rpc_id = reply_encode["data"]["RpcID"] if reply_encode is not None else None
            udp_encoder = first_event_after(
                events_by_name,
                "UdpEncoder",
                reply_encode["time_ns"] if reply_encode is not None else tx_submit["time_ns"],
                {"RpcID": response_rpc_id} if response_rpc_id is not None else None,
                max_delta_ns=100_000,
            )
            response_packet_id = udp_encoder["data"]["PacketID"] if udp_encoder is not None else None
            ip_encoder = first_event_after(
                events_by_name,
                "IpEncoder",
                udp_encoder["time_ns"] if udp_encoder is not None else tx_submit["time_ns"],
                {"PacketID": response_packet_id} if response_packet_id is not None else None,
                max_delta_ns=100_000,
            )
            eth_encoder = first_event_after(
                events_by_name,
                "EthernetEncoder",
                ip_encoder["time_ns"] if ip_encoder is not None else tx_submit["time_ns"],
                {"PacketID": response_packet_id} if response_packet_id is not None else None,
                max_delta_ns=100_000,
            )

    add_segment(segments, "lh_tx_2f2f_ctrl", tx_invalidate["time_ns"] if tx_invalidate else None, tx_unlocked["time_ns"] if tx_unlocked else None)
    add_segment(segments, "lh_tx_host_submit", tx_unlocked["time_ns"] if tx_unlocked else None, tx_submit["time_ns"] if tx_submit else None)
    add_segment(segments, "lh_tx_reply_encode", tx_dma_done["time_ns"] if tx_dma_done else None, reply_encode["time_ns"] if reply_encode else None)
    add_segment(segments, "lh_tx_udp_encode", reply_encode["time_ns"] if reply_encode else None, udp_encoder["time_ns"] if udp_encoder else None)
    add_segment(segments, "lh_tx_ip_encode", udp_encoder["time_ns"] if udp_encoder else None, ip_encoder["time_ns"] if ip_encoder else None)
    add_segment(segments, "lh_tx_eth_encode", ip_encoder["time_ns"] if ip_encoder else None, eth_encoder["time_ns"] if eth_encoder else None)
    add_segment(segments, "lh_tx_dma_read", host_accept["time_ns"] if host_accept else None, tx_dma_done["time_ns"] if tx_dma_done else None)

    if eth_encoder is not None:
        if eth_decoder is not None:
            row["trace_eth_decoder_to_encoder_ns"] = float(eth_encoder["time_ns"] - eth_decoder["time_ns"])
        tx_before_cdc = first_event_after(events_by_name, "TxBeforeCdcQueue", eth_encoder["time_ns"], max_delta_ns=10_000)
        tx_cmac = first_event_after(
            events_by_name,
            "TxCmacExit",
            tx_before_cdc["time_ns"] if tx_before_cdc else eth_encoder["time_ns"],
            max_delta_ns=10_000,
        )
        add_segment(segments, "lh_tx_output_queue", eth_encoder["time_ns"], tx_before_cdc["time_ns"] if tx_before_cdc else None)
        add_segment(segments, "lh_tx_cdc_to_cmac", tx_before_cdc["time_ns"] if tx_before_cdc else None, tx_cmac["time_ns"] if tx_cmac else None)
        if rx_cmac is not None and tx_cmac is not None:
            row["trace_lh_cmac_to_cmac_ns"] = float(tx_cmac["time_ns"] - rx_cmac["time_ns"])

    if segments:
        row["trace_segments"] = segments


def correlate_trace(rows: List[Dict[str, Any]], events: List[Dict[str, Any]]) -> None:
    events_by_name = build_event_index(events)
    ipi_by_core: Dict[int, List[Dict[str, Any]]] = defaultdict(list)

    for ev in events:
        data = ev["data"]
        if "CoreID" in data and ev["event"] == "PreemptCtrlIpiIssued":
            ipi_by_core[data["CoreID"]].append(ev)

    dispatches_by_core: Dict[int, List[Dict[str, Any]]] = defaultdict(list)
    for dispatch in events:
        if dispatch["event"] != "SchedulerRequestDispatched":
            continue
        host_id = dispatch["data"]["HostMsgID"]
        core = dispatch["data"]["CoreID"]
        data_match = {"HostMsgID": host_id}
        core_match = {"HostMsgID": host_id, "CoreID": core}
        rec_events = [
            last_event_before(events_by_name, "RxRpcEnqueueToHost", dispatch["time_ns"] + 1, data_match),
            last_event_before(events_by_name, "SchedulerRequestQueued", dispatch["time_ns"] + 1, data_match),
            last_event_before(events_by_name, "SchedulerProcessRun", dispatch["time_ns"] + 1, core_match),
            dispatch,
        ]
        for name in ("EciRxDescSent", "EciRxCtrlUnlocked", "EciRxDataLciaUlDone"):
            rec_events.append(first_event_after(events_by_name, name, dispatch["time_ns"], core_match, max_delta_ns=1_000_000))

        rec: Dict[str, Any] = {"host_msg_id": host_id, "events": []}
        seen = set()
        for ev in sorted((ev for ev in rec_events if ev is not None), key=lambda item: item["time_ns"]):
            key = (ev["event"], ev["time_ns"], tuple(sorted(ev["data"].items())))
            if key in seen:
                continue
            seen.add(key)
            rec["events"].append(ev)
            rec.setdefault(ev["event"], ev["time_ns"])
        dispatches_by_core[core].append(rec)

    for recs in dispatches_by_core.values():
        recs.sort(key=lambda rec: rec["SchedulerRequestDispatched"])
    for recs in ipi_by_core.values():
        recs.sort(key=lambda ev: ev["time_ns"])

    def delivery_time(rec: Dict[str, Any]) -> Optional[int]:
        delivery_events = [
            ev["time_ns"]
            for ev in rec["events"]
            if ev["event"] in {"EciRxDescSent", "EciRxCtrlUnlocked", "EciRxDataLciaUlDone"}
        ]
        if delivery_events:
            return max(delivery_events)
        return rec.get("SchedulerRequestDispatched")

    rows_by_core: Dict[int, List[Dict[str, Any]]] = defaultdict(list)
    for row in sorted(rows, key=lambda r: r["server_rx_exit_ns"]):
        rows_by_core[row["worker_id"] + 1].append(row)

    core_offsets: Dict[int, int] = {}
    for core, core_rows in rows_by_core.items():
        recs = dispatches_by_core.get(core, [])
        extra = len(recs) - len(core_rows)
        if extra <= 0:
            core_offsets[core] = 0
            continue
        best_offset = 0
        best_score: Optional[float] = None
        for offset in range(extra + 1):
            gaps = []
            for row, rec in zip(core_rows[:256], recs[offset:]):
                delivered = delivery_time(rec)
                if delivered is not None:
                    gaps.append(row["server_rx_exit_ns"] - delivered)
            if len(gaps) < 2:
                continue
            gaps.sort()
            score = gaps[int(0.95 * (len(gaps) - 1))] - gaps[int(0.05 * (len(gaps) - 1))]
            if best_score is None or score < best_score:
                best_score = score
                best_offset = offset
        core_offsets[core] = best_offset

    matched: List[Tuple[Dict[str, Any], Dict[str, Any]]] = []
    row_order: Dict[int, int] = defaultdict(int)
    for row in sorted(rows, key=lambda r: r["server_rx_exit_ns"]):
        core = row["worker_id"] + 1
        idx = row_order[core] + core_offsets.get(core, 0)
        row_order[core] += 1
        recs = dispatches_by_core.get(core, [])
        if idx >= len(recs):
            continue
        rec = recs[idx]
        row["trace_host_msg_id"] = rec["host_msg_id"]
        row["trace_rx_enqueue_ns"] = rec.get("RxRpcEnqueueToHost")
        row["trace_request_queued_ns"] = rec.get("SchedulerRequestQueued")
        row["trace_request_dispatched_ns"] = rec.get("SchedulerRequestDispatched")

        row["trace_core_delivery_ns"] = delivery_time(rec)

        ipis = [
            ev for ev in ipi_by_core.get(core, [])
            if rec.get("SchedulerRequestQueued", 0) <= ev["time_ns"] <= rec["SchedulerRequestDispatched"]
        ]
        if ipis:
            row["trace_ipi_issued_ns"] = ipis[0]["time_ns"]

        if row.get("trace_core_delivery_ns") is not None:
            matched.append((row, rec))

    if not matched:
        return

    # The CPU monotonic clock and FPGA trace clock do not share an epoch.  Use
    # the lower envelope at core_eci_rx() return as the clock offset.  The entry
    # timestamp can be from an earlier polling attempt and is not a request
    # delivery point.
    offset = min(row["server_rx_exit_ns"] - row["trace_core_delivery_ns"] for row, _ in matched)
    for row, rec in matched:
        core = row["worker_id"] + 1
        row["trace_cpu_offset_ns"] = offset
        enqueue = row.get("trace_rx_enqueue_ns")
        dispatched = row.get("trace_request_dispatched_ns")
        delivered = row.get("trace_core_delivery_ns")
        if enqueue is not None and dispatched is not None:
            row["trace_hw_queue_dispatch_ns"] = max(0.0, dispatched - enqueue)
        if dispatched is not None and delivered is not None:
            row["trace_hw_core_delivery_ns"] = max(0.0, delivered - dispatched)
        if delivered is not None:
            row["kernel_wakeup_ns"] = max(0.0, row["server_rx_enter_ns"] - (delivered + offset))
        attach_trace_segments(row, rec, events_by_name, core, offset)


def breakdown(row: Dict[str, Any]) -> Dict[str, float]:
    sw_xdr_runtime = row["client_xdr_ns"] + row["server_unmarshal_ns"] + row["server_marshal_ns"]
    server_rpc_runtime = (
        row["server_rx_ns"]
        + row["server_unmarshal_ns"]
        + row["server_marshal_ns"]
        + row["server_tx_ns"]
    )
    kernel = row.get("kernel_wakeup_ns", 0.0)

    if row.get("trace_segments"):
        ret = dict(row["trace_segments"])
        tx_eci_read = ret.pop("lh_tx_eci_read", 0.0)
        ret["host_response_wait"] = max(0.0, tx_eci_read - server_rpc_runtime - row["handler_ns"])
        ret["client_xdr_runtime"] = row["client_xdr_ns"]
        ret["sw_rpc_runtime"] = server_rpc_runtime
        ret["handler"] = row["handler_ns"]
        known = sum(ret.values())
        outside = row["e2e_ns"] - known
        if outside < 0.0 and ret["host_response_wait"] > 0.0:
            trim = min(ret["host_response_wait"], -outside)
            ret["host_response_wait"] -= trim
            outside += trim
        ret["outside_lh_client_network"] = max(0.0, outside)
        return ret

    hw_dispatch = row.get("trace_hw_queue_dispatch_ns", 0.0) + row.get("trace_hw_core_delivery_ns", 0.0)
    if hw_dispatch == 0.0:
        hw_dispatch = row["server_rx_ns"] + row["server_tx_ns"]
    known = sw_xdr_runtime + hw_dispatch + kernel + row["handler_ns"]
    return {
        "network_or_residual": max(0.0, row["e2e_ns"] - known),
        "hw_dispatch": hw_dispatch,
        "kernel_wakeup": kernel,
        "sw_xdr_runtime": sw_xdr_runtime,
        "handler": row["handler_ns"],
    }


def breakdown_names(selected: Dict[str, Dict[str, Any]]) -> List[str]:
    preferred = [
        "network_or_residual",
        "outside_lh_client_network",
        "lh_rx_cmac_to_cdc",
        "lh_rx_eth_decode",
        "lh_rx_ip_decode",
        "lh_rx_udp_decode",
        "lh_rx_rpc_decode",
        "lh_rx_host_enqueue",
        "lh_scheduler_enqueue",
        "lh_scheduler_preempt",
        "kernel_wakeup",
        "lh_scheduler_queue",
        "lh_rx_eci_delivery",
        "hw_dispatch",
        "host_response_wait",
        "client_xdr_runtime",
        "sw_rpc_runtime",
        "sw_xdr_runtime",
        "handler",
        "lh_tx_eci_read",
        "lh_tx_2f2f_ctrl",
        "lh_tx_host_submit",
        "lh_tx_reply_encode",
        "lh_tx_udp_encode",
        "lh_tx_ip_encode",
        "lh_tx_eth_encode",
        "lh_tx_dma_read",
        "lh_tx_output_queue",
        "lh_tx_cdc_to_cmac",
    ]
    present = set()
    for row in selected.values():
        present.update(breakdown(row))
    return [name for name in preferred if name in present] + sorted(present - set(preferred))


def write_breakdown_csv(path: Path, selected: Dict[str, Dict[str, Any]]) -> None:
    names = breakdown_names(selected)
    with path.open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "label",
            "request_id",
            "e2e_ns",
            "trace_rx_host_msg_id",
            "trace_tx_host_msg_id",
            "trace_lh_cmac_to_cmac_ns",
            "trace_eth_decoder_to_encoder_ns",
            "trace_core_rx_return_to_tx_submit_ns",
            "trace_core_tx_return_to_tx_submit_ns",
            *names,
        ])
        for label, row in selected.items():
            b = breakdown(row)
            writer.writerow([
                label,
                row["request_id"],
                f"{row['e2e_ns']:.0f}",
                row.get("trace_host_msg_id", ""),
                row.get("trace_tx_host_msg_id", ""),
                f"{row.get('trace_lh_cmac_to_cmac_ns', 0.0):.0f}",
                f"{row.get('trace_eth_decoder_to_encoder_ns', 0.0):.0f}",
                f"{row.get('trace_core_rx_return_to_tx_submit_ns', 0.0):.0f}",
                f"{row.get('trace_core_tx_return_to_tx_submit_ns', 0.0):.0f}",
                *[f"{b.get(name, 0.0):.0f}" for name in names],
            ])


def histogram_break_threshold(sorted_values_us: List[float], p99_us: float) -> Optional[float]:
    if len(sorted_values_us) < 8:
        return None

    q1 = percentile(sorted_values_us, 25)
    q3 = percentile(sorted_values_us, 75)
    iqr = q3 - q1
    if iqr <= 0:
        fence = p99_us * 3.0
    else:
        fence = q3 + 3.0 * iqr

    threshold = max(fence, p99_us * 1.10)
    if sorted_values_us[-1] <= threshold * 1.5:
        return None
    if not any(value > threshold for value in sorted_values_us):
        return None
    return threshold


def annotate_percentiles(
    ax: Any,
    percentile_lines: List[Tuple[str, float, str]],
    hidden_range: Optional[Tuple[float, float]] = None,
) -> None:
    lo, hi = ax.get_xlim()
    hidden_lo, hidden_hi = hidden_range if hidden_range is not None else (None, None)
    visible = []
    for label, value, color in percentile_lines:
        if not (lo <= value <= hi):
            continue
        if hidden_lo is not None and hidden_hi is not None and hidden_lo <= value <= hidden_hi:
            continue
        visible.append((label, value, color))
    if not visible:
        return

    span = max(hi - lo, 1e-9)
    visible.sort(key=lambda item: item[1])
    label_levels: Dict[str, int] = {}
    cluster: List[Tuple[str, float, str]] = []
    cluster_start: Optional[float] = None
    min_separation = span * 0.08
    levels = [0.94, 0.80, 0.66]

    for item in visible:
        if cluster and cluster_start is not None and item[1] - cluster_start > min_separation:
            for idx, clustered in enumerate(cluster):
                label_levels[clustered[0]] = idx % len(levels)
            cluster = []
            cluster_start = None
        if not cluster:
            cluster_start = item[1]
        cluster.append(item)
    for idx, clustered in enumerate(cluster):
        label_levels[clustered[0]] = idx % len(levels)

    ymax = ax.get_ylim()[1]
    for label, value_us, color in visible:
        ax.axvline(value_us, color=color, linewidth=1.6)
        ax.text(
            value_us,
            ymax * levels[label_levels[label]],
            f"{label} {value_us:.2f} us",
            rotation=90,
            va="top",
            ha="right",
            color=color,
            bbox={"facecolor": "white", "edgecolor": "none", "alpha": 0.65, "pad": 1.5},
        )


def format_latency_us(value_us: float) -> str:
    if abs(value_us) >= 1_000_000.0:
        return f"{round(value_us / 1_000_000.0):.0f} s"
    if abs(value_us) >= 1_000.0:
        return f"{round(value_us / 1_000.0):.0f} ms"
    return f"{round(value_us):.0f} us"


def place_tail_label(
    fig: Any,
    ax: Any,
    target_x: float,
    target_y: float,
    label: str,
    label_x: float,
    label_y: float,
) -> Any:
    return ax.annotate(
        label,
        xy=(target_x, target_y),
        xycoords="data",
        xytext=(label_x, label_y),
        textcoords="axes fraction",
        ha="center",
        va="center",
        fontsize="small",
        bbox={"facecolor": "white", "edgecolor": "#777777", "alpha": 0.85, "pad": 2.0},
        arrowprops={"arrowstyle": "->", "color": "#333333", "linewidth": 0.9},
        annotation_clip=False,
    )


def plot_histogram(plt: Any, e2e_us: List[float], p50_us: float, p90_us: float, p99_us: float, output_path: Path) -> None:
    threshold = histogram_break_threshold(e2e_us, p99_us)
    percentile_lines = [("P50", p50_us, "#2d5f8b"), ("P90", p90_us, "#8a7a2f"), ("P99", p99_us, "#9b3d45")]
    bins = min(80, max(10, int(math.sqrt(len(e2e_us)))))

    if threshold is None:
        fig, ax = plt.subplots(figsize=(8, 4.5))
        ax.hist(e2e_us, bins=bins, color="#6b8fb3", edgecolor="white")
        annotate_percentiles(ax, percentile_lines)
        ax.set_xlabel("E2E latency (us)")
        ax.set_ylabel("requests")
        ax.set_title("Adder RPC E2E latency distribution")
        fig.tight_layout()
        fig.savefig(output_path, dpi=160)
        plt.close(fig)
        return

    outliers = [value for value in e2e_us if value > threshold]
    right_min = min(outliers)
    right_max = max(outliers)
    left_min = min(e2e_us)
    left_pad = max((threshold - left_min) * 0.02, 1.0)
    left_lo = max(0.0, left_min - left_pad)
    bin_width = max((threshold - left_lo) / bins, 1e-9)

    fig, (ax_left, ax_right) = plt.subplots(
        1,
        2,
        sharey=True,
        figsize=(9.2, 4.5),
        gridspec_kw={"width_ratios": [4.5, 1.2], "wspace": 0.06},
    )
    ax_left.hist(
        e2e_us,
        bins=bins,
        range=(left_lo, threshold),
        color="#6b8fb3",
        edgecolor="white",
    )

    tail_bins: Dict[int, int] = defaultdict(int)
    for value in outliers:
        tail_bins[int(math.floor((value - left_lo) / bin_width))] += 1
    tail_left_edges = [left_lo + idx * bin_width for idx in sorted(tail_bins)]
    tail_counts = [tail_bins[idx] for idx in sorted(tail_bins)]
    ax_right.bar(
        tail_left_edges,
        tail_counts,
        width=bin_width,
        align="edge",
        color="#6b8fb3",
        edgecolor="white",
        linewidth=0.8,
    )

    tail_x_min = min(tail_left_edges)
    tail_x_max = max(tail_left_edges) + bin_width
    tail_pad = max(bin_width * 2.0, (tail_x_max - tail_x_min) * 0.05)
    ax_left.set_xlim(left_lo, threshold)
    ax_right.set_xlim(tail_x_min - tail_pad, tail_x_max + tail_pad)
    ymax = max(ax_left.get_ylim()[1], ax_right.get_ylim()[1])
    ax_left.set_ylim(0, ymax)
    ax_right.set_ylim(0, ymax)

    tail_values_by_bin: Dict[int, List[float]] = defaultdict(list)
    for value in outliers:
        tail_values_by_bin[int(math.floor((value - left_lo) / bin_width))].append(value)
    tail_bin_items = sorted(tail_values_by_bin.items())
    label_y_levels = [0.82, 0.66, 0.50, 0.34, 0.18]
    close_px = 70.0
    previous_px: Optional[float] = None
    cluster_lane = 0
    for bin_idx, values in tail_bin_items:
        edge = left_lo + bin_idx * bin_width
        center = edge + bin_width / 2.0
        count = tail_bins[bin_idx]
        if len(values) == 1:
            label = format_latency_us(values[0])
        else:
            label = f"{format_latency_us(min(values))}-{format_latency_us(max(values))}"
        target_px = ax_right.transData.transform((center, count))[0]
        if previous_px is None or target_px - previous_px > close_px:
            cluster_lane = 0
        else:
            cluster_lane += 1
        previous_px = target_px

        xlo, xhi = ax_right.get_xlim()
        target_frac = min(max((center - xlo) / max(xhi - xlo, 1e-9), 0.12), 0.88)
        lane = cluster_lane % len(label_y_levels)
        row = cluster_lane // len(label_y_levels)
        side_offset = 0.0 if row == 0 else (0.16 if row % 2 else -0.16)
        label_x = min(max(target_frac + side_offset, 0.16), 0.84)
        label_y = label_y_levels[lane]
        place_tail_label(fig, ax_right, center, count, label, label_x, label_y)

    annotate_percentiles(ax_left, percentile_lines)
    annotate_percentiles(ax_right, percentile_lines, hidden_range=ax_left.get_xlim())

    ax_left.spines["right"].set_visible(False)
    ax_right.spines["left"].set_visible(False)
    ax_right.tick_params(labelleft=False, left=False)
    ax_left.set_ylabel("requests")
    fig.supxlabel("E2E latency (us)")
    ax_left.set_title("Adder RPC E2E latency distribution")
    ax_right.set_title("tail")

    marker_kwargs = dict(marker=[(-1, -0.5), (1, 0.5)], markersize=10, linestyle="none", color="k", mec="k", mew=1)
    ax_left.plot([1, 1], [0, 1], transform=ax_left.transAxes, clip_on=False, **marker_kwargs)
    ax_right.plot([0, 0], [0, 1], transform=ax_right.transAxes, clip_on=False, **marker_kwargs)
    ax_right.text(
        0.5,
        0.95,
        f"{len(outliers)} tail sample(s)",
        transform=ax_right.transAxes,
        ha="center",
        va="top",
        fontsize="small",
    )

    fig.subplots_adjust(left=0.10, right=0.98, bottom=0.16, top=0.86, wspace=0.06)
    fig.savefig(output_path, dpi=160)
    plt.close(fig)


def plot(rows: List[Dict[str, Any]], output_prefix: Path) -> None:
    try:
        import matplotlib.pyplot as plt
    except ImportError as e:
        raise SystemExit(f"matplotlib is required for plotting: {e}")

    e2e = sorted(row["e2e_ns"] for row in rows)
    p50 = percentile(e2e, 50)
    p90 = percentile(e2e, 90)
    p99 = percentile(e2e, 99)
    selected = {
        "P50": nearest_request(rows, p50),
        "P99": nearest_request(rows, p99),
    }

    write_breakdown_csv(output_prefix.with_name(output_prefix.name + "_breakdown.csv"), selected)

    labels = list(selected.keys())
    component_names = breakdown_names(selected)
    colors = [
        "#5b8cc0",
        "#8a9a5b",
        "#c47f4b",
        "#7c6fb0",
        "#b45d6c",
        "#4b9a8a",
        "#a87545",
        "#6c8f3d",
        "#a35c8f",
        "#4c6f91",
        "#9a6b5b",
        "#667c45",
        "#b07a8f",
        "#5f7f7b",
        "#8b7355",
        "#7d6f9e",
        "#4f8c5f",
        "#986c3f",
        "#6e7894",
        "#9f5f5f",
        "#558b9a",
        "#888a4c",
        "#7b7b7b",
    ]

    fig, ax = plt.subplots(figsize=(12, 4.2))
    left = [0.0] * len(labels)
    row_breakdowns = {label: breakdown(row) for label, row in selected.items()}
    for idx, name in enumerate(component_names):
        values = [row_breakdowns[label].get(name, 0.0) / 1000.0 for label in labels]
        if not any(values):
            continue
        color = colors[idx % len(colors)]
        ax.barh(labels, values, left=left, label=name.replace("_", " "), color=color)
        left = [l + v for l, v in zip(left, values)]
    ax.set_xlabel("latency (us)")
    ax.set_title("Adder RPC E2E latency breakdown")
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1.0), fontsize="x-small")
    fig.tight_layout()
    fig.savefig(output_prefix.with_name(output_prefix.name + "_breakdown.pdf"), dpi=160)
    plt.close(fig)

    e2e_us = [value / 1000.0 for value in e2e]
    plot_histogram(
        plt,
        e2e_us,
        p50 / 1000.0,
        p90 / 1000.0,
        p99 / 1000.0,
        output_prefix.with_name(output_prefix.name + "_hist.pdf"),
    )

    print(f"P50={p50 / 1000.0:.3f} us P90={p90 / 1000.0:.3f} us P99={p99 / 1000.0:.3f} us")
    print(f"wrote {output_prefix.name}_breakdown.pdf, {output_prefix.name}_hist.pdf")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("client_csv", type=Path)
    parser.add_argument("server_csv", type=Path)
    parser.add_argument("-o", "--output-prefix", type=Path, default=Path("adder_latency"))
    parser.add_argument("--trace-dump", type=Path, help="optional raw sys_trace dump, optionally .gz")
    parser.add_argument("--trace-map", type=Path, default=default_map_path())
    parser.add_argument("--trace-cycle-ns", type=int, default=5)
    parser.add_argument("--trace-samples", default=None, help="optional Python slice, e.g. -1000000:")
    args = parser.parse_args()

    client_rows = load_csv_by_request_id(args.client_csv, CLIENT_COLUMNS)
    server_rows = load_csv_by_request_id(args.server_csv, SERVER_COLUMNS)
    rows = build_rows(client_rows, server_rows)
    if not rows:
        raise SystemExit("no matching request_id rows between client and server CSVs")

    failures = [row for row in rows if not row["client_ok"] or not row["server_ok"]]
    if failures:
        raise SystemExit(f"{len(failures)} request(s) failed correctness checks; refusing to plot")

    if args.trace_dump:
        events = decode_trace_events(args.trace_dump, args.trace_map, args.trace_cycle_ns, args.trace_samples)
        correlate_trace(rows, events)
        matched_rows = [row for row in rows if "trace_host_msg_id" in row]
        matched = len(matched_rows)
        print(f"matched {matched}/{len(rows)} rows with sys_trace HostMsgID/CoreID events")
        if matched_rows:
            sample = ", ".join(str(row["request_id"]) for row in matched_rows[:8])
            suffix = "" if len(matched_rows) <= 8 else ", ..."
            print(f"trace-matched request_id(s): {sample}{suffix}")
            missing_tx_cmac = [row for row in matched_rows if row.get("trace_segments") and not row.get("trace_lh_cmac_to_cmac_ns")]
            if missing_tx_cmac:
                sample = ", ".join(str(row["request_id"]) for row in missing_tx_cmac[:8])
                suffix = "" if len(missing_tx_cmac) <= 8 else ", ..."
                print(f"warning: no matching TX CMAC exit found for trace-matched request_id(s): {sample}{suffix}")
        if matched == 1:
            print("warning: only one trace-matched request; kernel_wakeup cannot be baseline-estimated and will be 0")

    plot(rows, args.output_prefix)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
