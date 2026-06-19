#!/usr/bin/env python3

import argparse
import csv
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


ROOT = Path(__file__).resolve().parents[3]
SYS_TRACE = ROOT / "data" / "eci" / "sys_trace"
if str(SYS_TRACE) not in sys.path:
    sys.path.insert(0, str(SYS_TRACE))

try:
    from common import bits
    from dma_decode import (
        adjusted_chronological_samples,
        default_map_path,
        load_map,
        sample_payload,
        sample_source,
        source_info,
    )
    from export_trace_pcap import prepared_raw_input
    from sample_window import parse_sample_window
except ImportError as e:
    raise SystemExit(f"failed to import sys_trace helpers from {SYS_TRACE}: {e}")


def int_field(row: Dict[str, str], name: str, default: int = 0) -> int:
    value = row.get(name, "")
    if value == "":
        return default
    return int(value, 0)


def load_csv_by_request_id(path: Path) -> Dict[int, Dict[str, str]]:
    with path.open(newline="") as f:
        return {int(row["request_id"], 0): row for row in csv.DictReader(f)}


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
        server_timestamp_count = int_field(s, "timestamp_call_count", 10)

        e2e_ns = corrected_delta(
            int_field(c, "client_call_enter_ns"),
            int_field(c, "client_call_exit_ns"),
            c_oh,
            client_timestamp_count(c),
        )
        e2e_ns = max(0.0, e2e_ns - s_oh * server_timestamp_count)

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


def decode_trace_events(
    trace_dump: Path,
    trace_map_path: Path,
    cycle_ns: int,
    sample_window_text: Optional[str],
) -> List[Dict[str, Any]]:
    trace_map = load_map(trace_map_path)
    decode = event_decoder(trace_map)
    sample_window = parse_sample_window(sample_window_text)
    events: List[Dict[str, Any]] = []

    with prepared_raw_input(trace_dump) as (raw_input, cache_path):
        samples = adjusted_chronological_samples(
            raw_input,
            trace_map,
            0,
            sample_window=sample_window,
            cache_path=cache_path,
        )
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


def correlate_trace(rows: List[Dict[str, Any]], events: List[Dict[str, Any]]) -> None:
    by_host: Dict[int, Dict[str, Any]] = {}
    ipi_by_core: Dict[int, List[Dict[str, Any]]] = defaultdict(list)

    for ev in events:
        data = ev["data"]
        if "CoreID" in data and ev["event"] == "PreemptCtrlIpiIssued":
            ipi_by_core[data["CoreID"]].append(ev)
        if "HostMsgID" not in data:
            continue
        host_id = data["HostMsgID"]
        rec = by_host.setdefault(host_id, {"host_msg_id": host_id, "events": []})
        rec["events"].append(ev)
        rec.setdefault(ev["event"], ev["time_ns"])

    dispatches_by_core: Dict[int, List[Dict[str, Any]]] = defaultdict(list)
    for rec in by_host.values():
        dispatched = rec.get("SchedulerRequestDispatched")
        if dispatched is None:
            continue
        core = None
        for ev in rec["events"]:
            if ev["event"] == "SchedulerRequestDispatched":
                core = ev["data"].get("CoreID")
                break
        if core is not None:
            dispatches_by_core[core].append(rec)

    for recs in dispatches_by_core.values():
        recs.sort(key=lambda rec: rec["SchedulerRequestDispatched"])
    for recs in ipi_by_core.values():
        recs.sort(key=lambda ev: ev["time_ns"])

    matched: List[Tuple[Dict[str, Any], Dict[str, Any]]] = []
    row_order: Dict[int, int] = defaultdict(int)
    for row in sorted(rows, key=lambda r: r["server_rx_enter_ns"]):
        core = row["worker_id"] + 1
        idx = row_order[core]
        row_order[core] += 1
        recs = dispatches_by_core.get(core, [])
        if idx >= len(recs):
            continue
        rec = recs[idx]
        row["trace_host_msg_id"] = rec["host_msg_id"]
        row["trace_rx_enqueue_ns"] = rec.get("RxRpcEnqueueToHost")
        row["trace_request_queued_ns"] = rec.get("SchedulerRequestQueued")
        row["trace_request_dispatched_ns"] = rec.get("SchedulerRequestDispatched")

        delivery_events = [
            ev["time_ns"]
            for ev in rec["events"]
            if ev["event"] in {"EciRxDescSent", "EciRxCtrlUnlocked", "EciRxDataLciaUlDone"}
        ]
        if delivery_events:
            row["trace_core_delivery_ns"] = max(delivery_events)
        else:
            row["trace_core_delivery_ns"] = rec.get("SchedulerRequestDispatched")

        ipis = [
            ev for ev in ipi_by_core.get(core, [])
            if ev["time_ns"] >= rec["SchedulerRequestDispatched"]
        ]
        if ipis:
            row["trace_ipi_issued_ns"] = ipis[0]["time_ns"]

        if row.get("trace_core_delivery_ns") is not None:
            matched.append((row, rec))

    if not matched:
        return

    # The CPU monotonic clock and FPGA trace clock do not share an epoch.  Use
    # the lower envelope of matched delivery->userspace gaps as the clock offset,
    # so the remaining positive gap is an estimate of kernel wakeup latency.
    offset = min(row["server_rx_enter_ns"] - row["trace_core_delivery_ns"] for row, _ in matched)
    for row, _rec in matched:
        enqueue = row.get("trace_rx_enqueue_ns")
        dispatched = row.get("trace_request_dispatched_ns")
        delivered = row.get("trace_core_delivery_ns")
        if enqueue is not None and dispatched is not None:
            row["trace_hw_queue_dispatch_ns"] = max(0.0, dispatched - enqueue)
        if dispatched is not None and delivered is not None:
            row["trace_hw_core_delivery_ns"] = max(0.0, delivered - dispatched)
        if delivered is not None:
            row["kernel_wakeup_ns"] = max(0.0, row["server_rx_enter_ns"] - (delivered + offset))


def breakdown(row: Dict[str, Any]) -> Dict[str, float]:
    sw_runtime = row["client_xdr_ns"] + row["server_unmarshal_ns"] + row["server_marshal_ns"]
    hw_dispatch = row.get("trace_hw_queue_dispatch_ns", 0.0) + row.get("trace_hw_core_delivery_ns", 0.0)
    if hw_dispatch == 0.0:
        hw_dispatch = row["server_rx_ns"] + row["server_tx_ns"]
    kernel = row.get("kernel_wakeup_ns", 0.0)
    known = sw_runtime + hw_dispatch + kernel + row["handler_ns"]
    network = max(0.0, row["e2e_ns"] - known)
    return {
        "network_or_residual": network,
        "hw_dispatch": hw_dispatch,
        "kernel_wakeup": kernel,
        "sw_xdr_runtime": sw_runtime,
        "handler": row["handler_ns"],
    }


def write_breakdown_csv(path: Path, selected: Dict[str, Dict[str, Any]]) -> None:
    names = ["network_or_residual", "hw_dispatch", "kernel_wakeup", "sw_xdr_runtime", "handler"]
    with path.open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["label", "request_id", "e2e_ns", *names])
        for label, row in selected.items():
            b = breakdown(row)
            writer.writerow([label, row["request_id"], f"{row['e2e_ns']:.0f}", *[f"{b[name]:.0f}" for name in names]])


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
    component_names = ["network_or_residual", "hw_dispatch", "kernel_wakeup", "sw_xdr_runtime", "handler"]
    colors = ["#5b8cc0", "#8a9a5b", "#c47f4b", "#7c6fb0", "#b45d6c"]

    fig, ax = plt.subplots(figsize=(9, 3.8))
    left = [0.0] * len(labels)
    for name, color in zip(component_names, colors):
        values = [breakdown(selected[label])[name] / 1000.0 for label in labels]
        ax.barh(labels, values, left=left, label=name.replace("_", " "), color=color)
        left = [l + v for l, v in zip(left, values)]
    ax.set_xlabel("latency (us)")
    ax.set_title("Adder RPC E2E latency breakdown")
    ax.legend(loc="lower right", fontsize="small")
    fig.tight_layout()
    fig.savefig(output_prefix.with_name(output_prefix.name + "_breakdown.png"), dpi=160)
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(8, 4.5))
    e2e_us = [value / 1000.0 for value in e2e]
    ax.hist(e2e_us, bins=min(80, max(10, int(math.sqrt(len(e2e_us))))), color="#6b8fb3", edgecolor="white")
    for label, value, color in [("P50", p50, "#2d5f8b"), ("P90", p90, "#8a7a2f"), ("P99", p99, "#9b3d45")]:
        ax.axvline(value / 1000.0, color=color, linewidth=1.6)
        ax.text(value / 1000.0, ax.get_ylim()[1] * 0.92, f"{label} {value / 1000.0:.2f} us",
                rotation=90, va="top", ha="right", color=color)
    ax.set_xlabel("E2E latency (us)")
    ax.set_ylabel("requests")
    ax.set_title("Adder RPC E2E latency distribution")
    fig.tight_layout()
    fig.savefig(output_prefix.with_name(output_prefix.name + "_hist.png"), dpi=160)
    plt.close(fig)

    print(f"P50={p50 / 1000.0:.3f} us P90={p90 / 1000.0:.3f} us P99={p99 / 1000.0:.3f} us")
    print(f"wrote {output_prefix.name}_breakdown.png, {output_prefix.name}_hist.png")


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

    client_rows = load_csv_by_request_id(args.client_csv)
    server_rows = load_csv_by_request_id(args.server_csv)
    rows = build_rows(client_rows, server_rows)
    if not rows:
        raise SystemExit("no matching request_id rows between client and server CSVs")

    failures = [row for row in rows if not row["client_ok"] or not row["server_ok"]]
    if failures:
        raise SystemExit(f"{len(failures)} request(s) failed correctness checks; refusing to plot")

    if args.trace_dump:
        events = decode_trace_events(args.trace_dump, args.trace_map, args.trace_cycle_ns, args.trace_samples)
        correlate_trace(rows, events)
        matched = sum(1 for row in rows if "trace_host_msg_id" in row)
        print(f"matched {matched}/{len(rows)} rows with sys_trace HostMsgID/CoreID events")

    plot(rows, args.output_prefix)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
