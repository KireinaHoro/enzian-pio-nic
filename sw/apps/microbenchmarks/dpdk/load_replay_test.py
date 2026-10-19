#!/usr/bin/env python3
"""Exercise concurrent reply ordering, duplicates, missing/invalid replies and pacing."""
import csv
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
from replay_test import packet

binary, output = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=False)
core = min(os.sched_getaffinity(0))
results = []
def flow_packet(id, port):
    data = bytearray(packet(18, id))
    struct.pack_into('!H', data, 36, port)
    return bytes(data)

for name, frames, ok in [
    ('reordered', [packet(18, i) for i in (4, 2, 1, 3)], True),
    ('duplicate', [packet(18, i) for i in (1, 1, 2, 3)], False),
    ('missing', [packet(18, i) for i in (1, 2, 3)], False),
    ('invalid', [packet(19, 1)], False),
    ('unsent', [packet(18, 5)], False),
    ('flows', [flow_packet(i,49152+(i-1)%4) for i in (4,2,1,3)], True),
    ('wrong-flow', [flow_packet(1,49153)], False),
]:
    rx, tx, out = [output/(name+s) for s in ('.rx.pcap', '.tx.pcap', '.csv')]
    with rx.open('wb') as f:
        f.write(struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1))
        for frame in frames:
            f.write(struct.pack('<IIII', 0, 0, len(frame), len(frame))+frame)
    cmd = [str(binary), '-l', str(core), '--no-huge', '--no-pci', '--no-shconf',
           '--no-telemetry', '--vdev', f'net_pcap0,rx_pcap={rx},tx_pcap={tx}', '--',
           '192.0.2.1', '192.0.2.2', '02:00:00:00:00:02', '9999', '4', '0',
           '10000', 'add', str(out)]
    with (output/(name+'.log')).open('w') as log:
        result = subprocess.run(cmd, env=dict(os.environ, LH_BENCH_WINDOW='4', LH_BENCH_FLOWS='4' if name in ('flows','wrong-flow') else '1'),
                                stdout=log, stderr=subprocess.STDOUT, timeout=30)
    rows = list(csv.DictReader(out.open()))
    summary = json.loads(Path(str(out)+'.load.json').read_text())
    passed = (result.returncode == 0) == ok and len(rows) == 4 and (summary['failed'] == 0) == ok
    if ok:
        passed &= all(r['ok'] == '1' and summary['observation_start_ns'] <= int(r['client_call_enter_ns']) < int(r['client_call_exit_ns']) <= summary['observation_end_ns'] for r in rows)
    results.append(dict(case=name, passed=passed, returncode=result.returncode))
(output/'summary.json').write_text(json.dumps(results, indent=2)+'\n')
print(json.dumps(results))
sys.exit(not all(r['passed'] for r in results))
