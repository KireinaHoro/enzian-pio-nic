#!/usr/bin/python3

import argparse
import csv
import datetime
import math
import os
import pathlib
import subprocess
import sys


def positive_int(text):
    try:
        value = int(text, 0)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"invalid integer: {text}") from exc
    if value <= 0:
        raise argparse.ArgumentTypeError(f"expected positive integer: {text}")
    return value


def default_out_dir():
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    return pathlib.Path(f"microbench-results-{stamp}")


def percentile(values, pct):
    if not values:
        return None
    ordered = sorted(values)
    idx = max(0, math.ceil(len(ordered) * pct / 100.0) - 1)
    return ordered[min(idx, len(ordered) - 1)]


def fmt_ns(value):
    if value is None:
        return "n/a"
    return f"{value} ns ({value / 1000.0:.3f} us)"


def summarize(out_dir, pattern, calls, streams):
    latencies = []
    switch_latencies = []
    failures = 0
    rows = 0

    for path in sorted(out_dir.glob("client_*.csv")):
        with path.open(newline="") as f:
            for row in csv.DictReader(f):
                rows += 1
                if row["ok"] != "1":
                    failures += 1
                    continue
                latency = int(row["latency_ns"])
                latencies.append(latency)
                if row["switch_request"] == "1":
                    switch_latencies.append(latency)

    print(f"out_dir={out_dir}")
    print(f"pattern={pattern} calls_per_stream={calls} streams={streams}")
    print(f"rows={rows} ok={len(latencies)} failures={failures}")
    print(
        "all_rpc "
        f"p50={fmt_ns(percentile(latencies, 50))} "
        f"p99={fmt_ns(percentile(latencies, 99))}"
    )
    print(
        "switch_rpc "
        f"samples={len(switch_latencies)} "
        f"p50={fmt_ns(percentile(switch_latencies, 50))} "
        f"p99={fmt_ns(percentile(switch_latencies, 99))}"
    )

def parse_args():
    script_dir = pathlib.Path(__file__).resolve().parent

    parser = argparse.ArgumentParser(
        description="Run add/mul microbenchmark traffic and report switch-path latency."
    )
    parser.add_argument("server_host")
    parser.add_argument("out_dir", nargs="?", type=pathlib.Path, default=default_out_dir())
    parser.add_argument(
        "--client",
        type=pathlib.Path,
        default=script_dir / "bench_client",
        metavar="path",
        help="bench_client path",
    )
    parser.add_argument(
        "--calls",
        type=positive_int,
        default=10000,
        metavar="n",
        help="calls per stream",
    )
    parser.add_argument(
        "--streams",
        type=positive_int,
        default=1,
        metavar="n",
        help="parallel client streams",
    )
    parser.add_argument(
        "--pattern",
        choices=("add", "mul", "alternate", "burst"),
        default="alternate",
        metavar="name",
        help="client traffic pattern",
    )
    parser.add_argument(
        "--burst-len",
        type=positive_int,
        default=4,
        metavar="n",
        help="requests per operation in burst mode",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    client = args.client.resolve()

    if not client.is_file() or not os.access(client, os.X_OK):
        print(f"client binary not executable: {client}", file=sys.stderr)
        return 1

    args.out_dir.mkdir(parents=True, exist_ok=True)

    procs = []
    for stream in range(args.streams):
        csv_path = args.out_dir / f"client_{stream}.csv"
        log_path = args.out_dir / f"client_{stream}.log"
        log = log_path.open("w")
        cmd = [
            str(client),
            args.server_host,
            args.pattern,
            str(args.calls),
            str(csv_path),
            str(args.burst_len),
        ]
        procs.append((subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT), log))

    status = 0
    for proc, log in procs:
        if proc.wait() != 0:
            status = 1
        log.close()

    summarize(args.out_dir, args.pattern, args.calls, args.streams)

    return status


if __name__ == "__main__":
    sys.exit(main())
