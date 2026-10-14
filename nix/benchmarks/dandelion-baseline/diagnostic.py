#!/usr/bin/env python3
"""Separate full-response/timestamp diagnostic; never substitutes for rload."""
import argparse
import csv
import http.client
import json
from pathlib import Path
import re
import statistics
import struct
import time

import bson


POINTS = ["DeserializationEnd", "EnterDispatcher", "ExecutionQueue", "ParsingStart",
          "ParsingEnd", "LoadStart", "TransferStart", "EngineStart", "EngineEnd",
          "FutureReturn"]


def parse_timestamps(raw):
    # Upstream prints the function name without JSON quoting. Match only the
    # numeric array, without editing its original string saved in requests.csv.
    matches = re.findall(r'"time_points":\s*\[([0-9,\s]+)\]', raw)
    if len(matches) != 1:
        raise ValueError("Expected one atomic function timestamp array")
    values = [int(v.strip()) for v in matches[0].split(",")]
    if len(values) != len(POINTS) or any(b < a for a, b in zip(values, values[1:])):
        raise ValueError("Invalid/nonmonotonic upstream timestamp array")
    return dict(zip(POINTS, values))


def components(points):
    return {
        "request_deserialization_us": points["DeserializationEnd"],
        "parse_function_us": points["ParsingEnd"] - points["ParsingStart"],
        "load_context_us": points["TransferStart"] - points["LoadStart"],
        "transfer_input_us": points["EngineStart"] - points["TransferStart"],
        "engine_run_us": points["EngineEnd"] - points["EngineStart"],
        "server_to_future_return_us": points["FutureReturn"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=1000)
    parser.add_argument("--size", type=int, default=1)
    args = parser.parse_args()
    if args.samples <= 0 or args.size <= 0:
        parser.error("samples and size must be positive")
    args.out.mkdir(parents=True, exist_ok=False)
    connection = http.client.HTTPConnection(args.host, args.port, timeout=10)
    name = f"baseline_diagnostic_{args.size}"
    registration = {"name": name, "context_size": 0x800000, "engine_type": "Process",
                    "binary": list(args.binary.read_bytes()),
                    "input_sets": [["", None]], "output_sets": [""]}

    def post(path, body):
        connection.request("POST", path, body=body)
        response = connection.getresponse()
        payload = response.read()
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}: {payload[:200]!r}")
        return payload

    post("/register/function", bson.BSON.encode(registration))
    n = args.size
    matrix = list(range(1, n * n + 1))
    expected = [n] + [sum(matrix[i*n+k]*matrix[j*n+k] for k in range(n))
                      for i in range(n) for j in range(n)]
    body = bson.BSON.encode({"name": name, "sets": [{"identifier": "", "items": [
        {"identifier": "", "key": 0, "data": struct.pack("<" + "q"*(len(matrix)+1), n, *matrix)}]}]})
    rows = []
    with (args.out / "requests.csv").open("w") as f:
        writer = None
        for index in range(args.samples):
            start = time.monotonic_ns()
            payload = post("/cold/matmul", body)
            elapsed = time.monotonic_ns() - start
            response = bson.BSON(payload).decode()
            sets = response["sets"]
            if len(sets) != 1 or len(sets[0]["items"]) != 1:
                raise ValueError("Unexpected output set shape")
            data = sets[0]["items"][0]["data"]
            if len(data) != len(expected)*8 or list(struct.unpack("<"+"q"*len(expected), data)) != expected:
                raise ValueError("Incorrect returned matrix")
            raw = response["timestamps"]
            row = {"request": index, "full_response_ns": elapsed,
                   **components(parse_timestamps(raw)), "raw_timestamps": raw}
            if writer is None:
                writer = csv.DictWriter(f, fieldnames=list(row))
                writer.writeheader()
            writer.writerow(row)
            rows.append(row)
    connection.close()
    summary = {"kind": "diagnostic-not-paper-rload", "samples": len(rows), "size": n,
               "all_results_checked": True, "warmups": 0,
               "limitations": ["Different client from the paper; full-body response timing.",
                               "Server timestamps are truncated to integer microseconds upstream.",
                               "No EndService point: cannot reconstruct paper get/send-output or total."]}
    summary["mean"] = {key: statistics.mean(row[key] for row in rows)
                       for key in rows[0] if key.endswith(("_us", "_ns"))}
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
