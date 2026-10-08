#!/usr/bin/env python3
"""Sample thread CPU time and last observed CPU; snapshots are not assignments."""
import argparse
import json
import os
from pathlib import Path
import time


def snapshot(pids):
    rows = []
    for pid in pids:
        for path in sorted(Path(f'/proc/{pid}/task').glob('*/stat')):
            try:
                raw = path.read_text()
                name = raw[raw.index('(')+1:raw.rindex(')')]
                fields = raw[raw.rindex(')')+2:].split()
                tid = int(path.parent.name)
                rows.append(dict(pid=pid,tid=tid,name=name,
                    role='main' if tid == pid else 'worker' if name.startswith('lh-mc-') else 'background',
                    user_ticks=int(fields[11]),system_ticks=int(fields[12]),cpu=int(fields[36])))
            except (FileNotFoundError,ProcessLookupError):
                continue
    return dict(monotonic_ns=time.monotonic_ns(),threads=rows)


if __name__ == '__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--pids',type=int,nargs='+',required=True)
    p.add_argument('--seconds',type=float,default=42)
    p.add_argument('--interval',type=float,default=0.1)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    if args.seconds <= 0 or args.interval <= 0: p.error('durations must be positive')
    deadline=time.monotonic()+args.seconds
    with args.output.open('x') as out:
        out.write(json.dumps(dict(schema=1,clock_ticks=os.sysconf('SC_CLK_TCK')))+'\n')
        while time.monotonic()<deadline:
            out.write(json.dumps(snapshot(args.pids))+'\n');out.flush()
            time.sleep(args.interval)
