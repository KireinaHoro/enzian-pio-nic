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
for name, stall, batched, aggregate in [('complete-loss',False,False,False), ('generator-stall',True,False,False),
                             ('batch-loss',False,True,False), ('batch-stall',True,True,False),
                             ('aggregate-loss',False,True,True), ('aggregate-stall',True,True,True)]:
    root=output/name; root.mkdir()
    rx,tx,out,ready,start=[root/n for n in ('rx.pcap','tx.pcap','samples.csv','ready','start')]
    rx.write_bytes(struct.pack('<IHHIIII',0xa1b2c3d4,2,4,0,0,65535,1))
    env=dict(os.environ,LH_BENCH_RATE='100',LH_BENCH_DURATION_MS='500',
             LH_BENCH_WARMUP_MS='0',LH_BENCH_FLOWS='1',
             LH_BENCH_DRAINED_FILE=str(root/'drained'),
             LH_BENCH_READY_FILE=str(ready),LH_BENCH_START_FILE=str(start))
    if batched:
        env.update(LH_BENCH_RATE='8000',LH_BENCH_BATCH_MAX='64',LH_BENCH_BATCH_TARGET_NS='1000000')
    env['LH_BENCH_AGGREGATE']='1' if aggregate else '0'
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
    count=4000 if batched else 50
    assert (len(rows)<=3 if aggregate else len(rows)==count) and summary['duration_ns']==500000000
    if aggregate:
        assert summary['schema']==3 and summary['histogram_bin_ns']==100
        assert not summary['latency_histogram']
        assert summary['phases'][1]['transmitted']==summary['transmitted']
    assert not summary['on_time'] and not summary['late']
    if batched:
        assert summary['batch_size']==8 and summary['arrival_model']=='paced-batch'
        if not aggregate: assert all(int(row['scheduled_ns'])==int(rows[0]['scheduled_ns'])+(i//8)*1000000 for i,row in enumerate(rows) if int(row['scheduled_ns']))
        for i in range(0,0 if aggregate else len(rows),8):
            burst=[row for row in rows[i:i+8] if int(row['client_tx_submit_ns'])]
            assert len({row['client_tx_admit_enter_ns'] for row in burst})<=1
            assert len({row['client_tx_submit_ns'] for row in burst})<=1
    else:
        assert all(int(b['scheduled_ns'])-int(a['scheduled_ns'])==10000000 for a,b in zip(rows,rows[1:]))
    assert summary['transmitted']+summary['generator_misses']<=count
    if not stall: assert summary['transmitted']==count
    assert (rc!=0)==stall
    if stall: assert summary['generator_misses']>0 or 'overran send opportunity' in (root/'process.log').read_text()
    assert summary['valid']==(not stall)
    assert (root/'drained').exists()
    results.append(dict(case=name,passed=True,summary=summary))

# Reject a schedule that cannot fit its declared budget before touching the NIC
# or allocating observation output. It must not become a high-loss server point.
root=output/'tracking-limit';root.mkdir()
rx=root/'rx.pcap';rx.write_bytes(struct.pack('<IHHIIII',0xa1b2c3d4,2,4,0,0,65535,1))
out=root/'samples.csv'
argv=[str(binary),'-l',str(core),'--no-huge','--no-pci','--no-shconf','--no-telemetry',
      '--vdev',f'net_pcap0,rx_pcap={rx},tx_pcap={root/"tx.pcap"}','--',
      '192.0.2.1','192.0.2.2','02:00:00:00:00:02','9999','1','0','10000','echo0',str(out)]
env=dict(os.environ,LH_BENCH_RATE='100000',LH_BENCH_DURATION_MS='1000',
         LH_BENCH_WARMUP_MS='0',LH_BENCH_TRACKING_MIB='1')
result=subprocess.run(argv,env=env,capture_output=True,text=True,timeout=10)
(root/'process.log').write_text(result.stdout+result.stderr)
assert result.returncode and 'open-loop tracking requires' in result.stderr and not out.exists()
results.append(dict(case='tracking-limit',passed=True))
(output/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results))
