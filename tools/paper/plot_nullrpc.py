#!/usr/bin/env python3
"""Validate tracked null-RPC measurements, compute statistics, render paper PDFs."""
import argparse
import csv
import hashlib
import gzip
import json
from pathlib import Path

import paper_chart
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/paper/nullrpc"
OUTPUT = ROOT.parent / "paper/plots"


def measured(path, end_field="client_call_exit_ns"):
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt") as stream:
        rows = list(csv.DictReader(stream))
    if not rows or any(row["ok"] != "1" or row["rpc_status"] != "0" for row in rows):
        raise ValueError(f"Empty or failed run: {path}")
    ids = [int(row["request_id"]) for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError(f"Duplicate request IDs: {path}")
    if any(row["warmup"] not in ("0", "1") for row in rows):
        raise ValueError(f"Invalid warmup marker: {path}")
    rows = [row for row in rows if row["warmup"] == "0"]
    ns = np.array([int(row[end_field]) - int(row["client_call_enter_ns"])
                   for row in rows], dtype=np.int64)
    if not len(ns) or np.any(ns <= 0):
        raise ValueError(f"Missing or nonpositive measured RTT: {path}")
    return rows, ns / 1000


def mac_values(entry, manifest):
    evidence = entry.get("mac_latency", manifest.get("mac_latency"))
    if evidence is None:
        raise ValueError("Current paper charts require MAC evidence; RTT-only plots are retired")
    if evidence["unit"] != "ns" or evidence["interval"] != "RxCmacEntry -> TxCmacExit":
        raise ValueError("Unexpected MAC latency boundary or units")
    path = DATA / evidence["file"]
    if hashlib.sha256(path.read_bytes()).hexdigest() != evidence["sha256"]:
        raise ValueError(f"MAC input hash mismatch: {path}")
    with gzip.open(path, "rt") as stream:
        rows = [r for r in csv.DictReader(stream)
                if r["label"] == entry["label"] and r["warmup"] == "0"]
    client_rows, _ = measured(DATA / entry["file"], entry["latency_end"])
    if len(rows) != entry["measured_count"] or {r["request_id"] for r in rows} != {
            r["request_id"] for r in client_rows}:
        raise ValueError("MAC/client request coverage mismatch")
    values = np.array([int(r["tx_mac_ns"]) - int(r["rx_mac_ns"]) for r in rows])
    if np.any(values <= 0) or any(int(r["mac_latency_ns"]) != v for r, v in zip(rows, values)):
        raise ValueError("Invalid MAC interval")
    return rows, values / 1000


def cdf(manifest, output):
    fig, ax = paper_chart.figure()
    statistics = {}
    for condition in ("hot", "cold"):
        entry = manifest["latency"][condition]
        path = DATA / entry["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError(f"Input hash mismatch: {path}")
        rows, values = mac_values(entry, manifest)
        if len(rows) != entry["measured_count"]:
            raise ValueError("Sample count differs from provenance")
        ordered = np.sort(values)
        line, = ax.step(np.r_[ordered[0], ordered],
                        np.r_[0, np.arange(1, len(values)+1)/len(values)],
                        where="post", label=condition.capitalize())
        quantiles = np.percentile(values, [50, 99, 100])
        for name, value, fraction, offset in zip(
                ("median", "P99", "max"), quantiles, (.5, .99, 1),
                ((4, 0), (4, -24), (4 if condition == "hot" else 0, -12))):
            ax.plot(value, fraction, marker="o", markersize=3, color=line.get_color())
            ax.annotate(rf"{name}={value:.2f} $\mu$s", (value, fraction),
                        xytext=offset, textcoords="offset points",
                        ha="right" if name == "max" and condition == "cold" else "left",
                        va="center" if name == "median" else "top", fontsize=7,
                        color=line.get_color())
        statistics[condition] = {"count": len(rows), "mac_us": dict(zip(
            ("p50", "p99", "max"), quantiles.tolist()))}
    ax.set(xlabel=r"MAC-to-MAC latency ($\mu$s)", ylabel="CDF", ylim=(0, 1.05))
    ax.set_xlim(left=0)
    ax.legend(loc="lower right")
    paper_chart.save(fig, output / "nullrpc-hot-cold-latency-cdf.pdf")
    return statistics


def load_points(manifest):
    points = []
    for entry in manifest["load_runs"]:
        if entry["condition"] != "hot" or (not entry.get("scaling") and not entry.get("paper_throughput", True)):
            continue
        path = DATA / entry["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError(f"Input hash mismatch: {path}")
        rows, values = measured(path, entry.get("latency_end", "client_call_exit_ns"))
        mac = None if entry.get("scaling") else mac_values(entry, manifest)[1]
        start, end = entry["observation_start_ns"], entry["observation_end_ns"]
        if end <= start or any(not start <= int(row["client_call_enter_ns"]) < int(row["client_call_exit_ns"]) <= end for row in rows):
            raise ValueError("Invalid observation window")
        if len(rows) != entry["measured_count"]:
            raise ValueError("Incomplete measurement")
        points.append(dict(entry, throughput=len(rows)*1e9/(end-start),
                           p50=None if mac is None else float(np.median(mac)),
                           p99=None if mac is None else float(np.percentile(mac, 99))))
    return points


def throughput_latency(points, output):
    condition = "hot"
    selected = sorted((p for p in points if p["condition"] == condition and p["workers"] == 1 and not p.get("scaling")),
                      key=lambda p: p["throughput"])
    if len(selected) < 3:
        raise ValueError(f"Need at least three measured one-worker {condition} load points")
    fig, ax = paper_chart.figure()
    for statistic, label, marker in (("p50", "Median", "o"), ("p99", "99th percentile", "s")):
        ax.plot([p["throughput"]/1000 for p in selected], [p[statistic] for p in selected], marker=marker, label=label)
    ax.set(xlabel="Throughput (kRPC/s)", ylabel=r"MAC-to-MAC latency ($\mu$s)")
    ax.set_ylim(bottom=0)
    ax.legend()
    paper_chart.save(fig, output / f"nullrpc-{condition}-throughput-latency.pdf")


def worker_scaling(points, output):
    selected = [p for p in points if p["condition"] == "hot" and p.get("scaling")]
    workers = sorted(set(p["workers"] for p in selected))
    if workers != [1, 2, 3, 4]:
        raise ValueError("Worker scaling needs measured pools 1, 2, 3, 4")
    if any(p["observed_workers"] != p["workers"] for p in selected):
        raise ValueError("Worker allocation has not been confirmed")
    values = [np.array([p["throughput"]/1000 for p in selected if p["workers"] == w]) for w in workers]
    centers = np.array([np.median(v) for v in values])
    fig, ax = paper_chart.figure()
    ax.errorbar(workers, centers, yerr=[centers-[min(v) for v in values],
                                     [max(v) for v in values]-centers], marker="o", capsize=3)
    ax.set(xlabel="Workers", ylabel="Throughput (kRPC/s)", xticks=workers, ylim=(0, None))
    paper_chart.save(fig, output / "nullrpc-hot-workers-throughput.pdf")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--manifest", type=Path, default=DATA / "manifest.json")
    parser.add_argument("--charts", choices=("cdf", "throughput", "workers", "load", "all"), default="all")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    statistics = {}
    if args.charts in ("cdf", "all"):
        statistics.update(cdf(manifest, args.output))
    if args.charts in ("throughput", "workers", "load", "all"):
        points = load_points(manifest)
        statistics["load_runs"] = [dict(label=p["label"], workers=p["workers"],
            throughput_krpc_s=p["throughput"]/1000, p50_us=p["p50"], p99_us=p["p99"])
            for p in points]
    if args.charts in ("throughput", "load", "all"):
        throughput_latency(points, args.output)
    if args.charts in ("workers", "load", "all"):
        worker_scaling(points, args.output)
    print(json.dumps(statistics, indent=2))


if __name__ == "__main__":
    main()
