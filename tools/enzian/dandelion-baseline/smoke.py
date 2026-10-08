#!/usr/bin/env python3
"""Bounded native protocol check. Run inside private mount/network namespaces."""
import csv
import json
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time


def main():
    paths = json.loads(Path(sys.argv[1]).read_text())
    output = Path(sys.argv[2]).resolve()
    output.mkdir(exist_ok=False)
    runner = str(Path(paths["runner"]) / "bin/dandelion-baseline")
    summary = {"kind": "native-protocol-smoke-not-performance", "checks": []}
    with (output / "server.log").open("w") as log:
        server = subprocess.Popen([runner, "server-timestamps", str(output / "state"), "4"],
                                  stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            deadline = time.monotonic() + 20
            while True:
                if server.poll() is not None:
                    raise RuntimeError("Native server exited during startup; see server.log")
                try:
                    with socket.create_connection(("127.0.0.1", 8080), timeout=0.2):
                        break
                except OSError:
                    if time.monotonic() > deadline:
                        raise RuntimeError("Native server startup timed out")
                    time.sleep(0.05)
            for hot in (0, 1):
                result_dir = output / f"rload-{hot}"
                args = [str(Path(paths["client"]) / "bin/rload"), "--ip", "127.0.0.1",
                        "--target", "dandelion-process", "--requestformat", "matmul",
                        "--model", "closed-unloaded", "--size", "1", "--hotpercent", str(hot),
                        "--cold-instances", "16", "--duration", "16", "--outputdir", str(result_dir)]
                if hot == 0:
                    args += ["--workload-path", str(Path(paths["fixtures"]) / "share/dandelion-baseline/matmul")]
                with (output / f"rload-{hot}.log").open("w") as log:
                    subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
                files = list(result_dir.glob("latencies_*.csv"))
                if len(files) != 1:
                    raise RuntimeError("Expected one original rload CSV")
                with files[0].open() as f:
                    rows = list(csv.DictReader(f))
                assert len(rows) == 16
                assert all(r["statusCode"] == "200" and r["connectionTimeout"] == "false"
                           and r["functionTimeout"] == "false" for r in rows)
                summary["checks"].append({"original_rload_hotpercent": hot, "checked_requests": len(rows)})
            for size in (1, 2):
                result_dir = output / f"diagnostic-{size}"
                with (output / f"diagnostic-{size}.log").open("w") as log:
                    subprocess.run([runner, "diagnostic", "--host", "127.0.0.1", "--out", str(result_dir),
                                    "--samples", "8", "--size", str(size)],
                                   stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
                result = json.loads((result_dir / "summary.json").read_text())
                assert result["all_results_checked"] and result["samples"] == 8
                summary["checks"].append({"diagnostic_matrix_size": size, "checked_requests": 8})
            summary["passed"] = True
        finally:
            if server.poll() is None:
                server.send_signal(signal.SIGTERM)
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    import os
                    os.killpg(server.pid, signal.SIGKILL)
                    server.wait()
            summary["server_exit"] = server.returncode
            (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
