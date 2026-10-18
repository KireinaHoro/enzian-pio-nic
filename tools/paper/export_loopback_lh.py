#!/usr/bin/env python3
"""Export uninstrumented LH process provenance and before/after hardware health."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('label');p.add_argument('size',type=int);p.add_argument('workers',type=int)
p.add_argument('app_sha256');p.add_argument('transfer_url')
a=p.parse_args()
if not a.label.replace('-','').replace('_','').isalnum():p.error('unsafe label')
root=Path('/dev/shm')/a.label
pid=int((root/'server.pid').read_text());proc=Path('/proc')/str(pid)
argv=[x.decode() for x in (proc/'cmdline').read_bytes().split(b'\0') if x]
if argv[-2:]!=[f'loopback{a.size}',str(a.workers)]:raise ValueError('Unexpected service')
sha=hashlib.sha256((proc/'exe').read_bytes()).hexdigest()
if sha!=a.app_sha256:raise ValueError('Application binary mismatch')
env=dict(x.split('=',1) for x in (proc/'environ').read_bytes().decode().split('\0') if '=' in x)
tracing={k:v for k,v in env.items() if 'TRACE' in k or 'PMU' in k}
if any(env.get(k) for k in ('LAUBERHORN_SERVER_TRACE_CSV','ADDER_SERVER_TRACE_CSV','LAUBERHORN_EVENT_TRACE_CSV','LAUBERHORN_PMU')):raise ValueError('Software tracing enabled')
if Path('/sys/kernel/tracing/tracing_on').read_text().strip()!='0':raise ValueError('Kernel tracing enabled')
runtime='/nix/store/inr7lnnk4yh0bflln45hvk786p3c69sv-lauberhorn-rt-aarch64-unknown-linux-gnu-0.0.1'
if runtime+'/lib/liblauberhorn.so' not in (proc/'maps').read_text():raise ValueError('Runtime differs from matched 519555 runtime')
provenance=dict(pid=pid,argv=argv,binary_sha256=sha,trace_environment=tracing,kernel_tracing=False,
                runtime=runtime,manifest=json.loads(Path('/nix/store/lauberhorn/manifest.json').read_text()),
                thread_cpu_affinity={t.name:sorted(os.sched_getaffinity(int(t.name))) for t in (proc/'task').iterdir()},
                kernel=subprocess.check_output(['uname','-r'],text=True).strip())
(root/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
(root/'tad-after.txt').write_text(Path('/sys/module/lauberhorn/parameters/l2c_tad_snapshot').read_text())
(root/'kernel-after.txt').write_bytes(subprocess.check_output(['dmesg']))
data=io.BytesIO()
with tarfile.open(fileobj=data,mode='w:gz') as archive:archive.add(root,arcname='dut')
request=urllib.request.Request(a.transfer_url.rstrip('/')+'/'+a.label+'-dut.tar.gz',data=data.getvalue(),method='PUT')
urllib.request.urlopen(request,timeout=120).read()
print('DUT_PROVENANCE_EXPORTED')
