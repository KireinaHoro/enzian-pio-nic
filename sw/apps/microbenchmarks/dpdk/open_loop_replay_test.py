#!/usr/bin/env python3
"""Exercise the real send loop with complete loss and an induced generator stall.

No hardware or measured performance claims. PMD output preserves transmitted
frames; SIGSTOP proves missed slots aren't converted into server loss.
"""
import csv
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import sys
import time

binary, output = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=False)
core = min(os.sched_getaffinity(0))
results = []
for name, stall in [('complete-loss', False), ('generator-stall', True)]:
    root=output/name; root.mkdir()
    rx,tx,out,ready,start=[root/n for n in ('rx.pcap','tx.pcap','samples.csv','ready','start')]
    rx.write_bytes(struct.pack('<IHHIIII',0xa1b2c3d4,2,4,0,0,65535,1))
    env=dict(os.environ,LH_BENCH_RATE='100',LH_BENCH_DURATION_MS='500',
             LH_BENCH_WARMUP_MS='0',LH_BENCH_FLOWS='1',
             LH_BENCH_READY_FILE=str(ready),LH_BENCH_START_FILE=str(start))
    for key in ('LH_BENCH_WINDOW','LH_BENCH_GAP_NS'): env.pop(key,None)
    argv=[str(binary),'-l',str(core),'--no-huge','--no-pci','--no-shconf','--no-telemetry',
          '--vdev',f'net_pcap0,rx_pcap={rx},tx_pcap={tx}','--','192.0.2.1','192.0.2.2',
          '02:00:00:00:00:02','9999','1','0','10000','echo0',str(out)]
    with (root/'process.log').open('w') as log:
        process=subprocess.Popen(argv,env=env,stdout=log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+10
        while not ready.exists() and process.poll() is None and time.monotonic()<deadline: time.sleep(.001)
        if not ready.exists():
            process.kill(); process.wait(); raise RuntimeError('Readiness failed; see '+str(root))
        start.touch()
        if stall:
            time.sleep(.1); process.send_signal(signal.SIGSTOP)
            time.sleep(.06); process.send_signal(signal.SIGCONT)
        rc=process.wait(timeout=10)
    summary=json.loads(Path(str(out)+'.open.json').read_text())
    rows=list(csv.DictReader(out.open()))
    assert len(rows)==50 and summary['duration_ns']==500000000
    assert not summary['on_time'] and not summary['late']
    assert all(int(b['scheduled_ns'])-int(a['scheduled_ns'])==10000000 for a,b in zip(rows,rows[1:]))
    assert summary['transmitted']+summary['generator_misses']==50
    assert (rc!=0)==stall and (summary['generator_misses']>0)==stall
    assert summary['valid']==(not stall)
    results.append(dict(case=name,passed=True,summary=summary))
(output/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results))
