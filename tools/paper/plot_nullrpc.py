#!/usr/bin/env python3
"""Validate tracked null-RPC measurements, compute statistics, render paper PDFs."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

import paper_chart
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/paper/nullrpc"
OUTPUT = ROOT.parent / "paper/plots"


def measured(path):
    with path.open() as stream:
        rows = list(csv.DictReader(stream))
    if not rows or any(row["ok"] != "1" or row["rpc_status"] != "0" for row in rows):
        raise ValueError(f"Empty or failed run: {path}")
    ids = [int(row["request_id"]) for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError(f"Duplicate request IDs: {path}")
    if any(row["warmup"] not in ("0", "1") for row in rows):
        raise ValueError(f"Invalid warmup marker: {path}")
    rows = [row for row in rows if row["warmup"] == "0"]
    ns = np.array([int(row["client_call_exit_ns"]) - int(row["client_call_enter_ns"])
                   for row in rows], dtype=np.int64)
    if not len(ns) or np.any(ns <= 0):
        raise ValueError(f"Missing or nonpositive measured RTT: {path}")
    return rows, ns / 1000


def cdf(condition, manifest, output):
    entry = manifest["latency"][condition]
    path = DATA / entry["file"]
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
        raise ValueError(f"Input hash mismatch: {path}")
    rows, values = measured(path)
    if len(rows) != entry["measured_count"]:
        raise ValueError("Sample count differs from provenance")
    fig, ax = paper_chart.figure()
    ordered = np.sort(values)
    ax.step(np.r_[ordered[0], ordered], np.r_[0, np.arange(1, len(values)+1)/len(values)], where="post")
    ax.set(xlabel=r"Round-trip latency ($\mu$s)", ylabel="CDF", ylim=(0, 1))
    ax.set_xlim(left=0)
    paper_chart.save(fig, output / f"nullrpc-{condition}-latency-cdf.pdf")
    return {"count": len(rows), "rtt_us": dict(zip(("p50", "p99", "max"),
                                                    np.percentile(values, [50, 99, 100]).tolist()))}


def load_points(manifest):
    points = []
    for entry in manifest["load_runs"]:
        path = DATA / entry["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError(f"Input hash mismatch: {path}")
        rows, values = measured(path)
        start, end = entry["observation_start_ns"], entry["observation_end_ns"]
        if end <= start or any(not start <= int(row["client_call_enter_ns"]) < int(row["client_call_exit_ns"]) <= end for row in rows):
            raise ValueError("Invalid observation window")
        if len(rows) != entry["measured_count"]:
            raise ValueError("Incomplete measurement")
        points.append(dict(entry, throughput=len(rows)*1e9/(end-start),
                           p50=float(np.median(values)), p99=float(np.percentile(values, 99))))
    return points


def throughput_latency(condition, points, output):
    selected = sorted((p for p in points if p["condition"] == condition and p["workers"] == 1),
                      key=lambda p: p["throughput"])
    if len(selected) < 3:
        raise ValueError(f"Need at least three measured one-worker {condition} load points")
    fig, ax = paper_chart.figure()
    for statistic, label, marker in (("p50", "Median", "o"), ("p99", "99th percentile", "s")):
        ax.plot([p["throughput"]/1000 for p in selected], [p[statistic] for p in selected], marker=marker, label=label)
    ax.set(xlabel="Throughput (kRPC/s)", ylabel=r"Round-trip latency ($\mu$s)")
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
    parser.add_argument("--charts", choices=("cdf", "load", "all"), default="all")
    args = parser.parse_args()
    manifest = json.loads((DATA / "manifest.json").read_text())
    if args.charts in ("cdf", "all"):
        print(json.dumps({c: cdf(c, manifest, args.output) for c in ("hot", "cold")}, indent=2))
    if args.charts in ("load", "all"):
        points = load_points(manifest)
        for condition in ("hot", "cold"):
            throughput_latency(condition, points, args.output)
        worker_scaling(points, args.output)


if __name__ == "__main__":
    main()
