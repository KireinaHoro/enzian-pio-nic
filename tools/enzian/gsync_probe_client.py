#!/usr/bin/env python3
"""Bounded sequential RPCs to gsync_probe.c; preserves worker syscall durations."""

import argparse, csv, json, math, socket, struct, time
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--bind", required=True)
p.add_argument("--nic", default="192.168.129.200")
p.add_argument("--count", type=int, default=20)
p.add_argument("--output", type=Path, required=True)
p.add_argument("--modes", default="0,1,2")
p.add_argument("--gap-ms", type=float, default=3)
a = p.parse_args()
if (
    a.count <= 0
    or not math.isfinite(a.gap_ms)
    or a.gap_ms < 0
    or not a.modes
    or any(v not in ("0", "1", "2") for v in a.modes.split(","))
):
    p.error("positive count, nonnegative finite gap, and modes 0/1/2 required")
a.output.mkdir(parents=True, exist_ok=False)
rows = []
error = None
try:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind((a.bind, 0))
        s.settimeout(2)
        for i in range(a.count):
            for mode in map(int, a.modes.split(",")):
                ident = 1 + len(rows)
                header = struct.pack(
                    "!10I", ident, 0, 2, 0x10234567, 0x12345678, 0x23456789, 0, 0, 0, 0
                )
                t = time.monotonic_ns()
                s.sendto(header + struct.pack("!4I", ident, mode, 0, 0), (a.nic, 22346))
                data, peer = s.recvfrom(4096)
                end = time.monotonic_ns()
                assert (
                    peer == (a.nic, 22346)
                    and len(data) == 72
                    and struct.unpack("!6I", data[:24]) == (ident, 1, 0, 0, 0, 0)
                ), (peer, data.hex())
                rid, rmode, cpu, err, start, finish, restore, value = struct.unpack(
                    "!4I4Q", data[24:]
                )
                assert (rid, rmode, err) == (ident, mode, 0)
                rows.append(
                    dict(
                        id=ident,
                        mode=mode,
                        cpu=cpu,
                        client_start_ns=t,
                        e2e_ns=end - t,
                        op_start_ns=start,
                        op_end_ns=finish,
                        restore_end_ns=restore,
                        operation_ns=finish - start,
                        restore_ns=restore - finish,
                        value=value,
                    )
                )
                time.sleep(a.gap_ms / 1000)
except Exception as exc:
    error = repr(exc)
finally:
    with (a.output / "requests.csv").open("w") as f:
        if rows:
            w = csv.DictWriter(f, rows[0])
            w.writeheader()
            w.writerows(rows)
    result = dict(
        passed=error is None,
        error=error,
        completed=len(rows),
        modes={
            str(m): {
                "count": len(v := [r for r in rows if r["mode"] == m]),
                "max_operation_ns": max((r["operation_ns"] for r in v), default=None),
                "max_e2e_ns": max((r["e2e_ns"] for r in v), default=None),
            }
            for m in map(int, a.modes.split(","))
        },
    )
    (a.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
raise SystemExit(0 if error is None else 1)
