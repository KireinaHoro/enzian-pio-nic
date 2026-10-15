"""DUT-side echo service launch and quiescent provenance snapshots.

Invoked through the hardware harness after matched module verification. Workers
remain resident; this helper never kills them or attempts core reclamation.
"""
import hashlib
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import time
import urllib.request


def sha(path):
    value=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''): value.update(block)
    return value.hexdigest()


def start(config):
    root=Path(config['service_root']);root.mkdir(exist_ok=False)
    binary=root/'microbenchmarks'
    with urllib.request.urlopen(config['binary_url'],timeout=60) as source, binary.open('xb') as target:
        while block:=source.read(1024*1024): target.write(block)
    if sha(binary)!=config['binary_sha256']: raise ValueError('Responder hash mismatch')
    binary.chmod(0o755)
    env={k:v for k,v in os.environ.items() if not k.startswith(('LAUBERHORN_','ADDER_SERVER_TRACE','LH_BENCH_'))}
    if config['mode']=='loaded': env['LH_BENCH_ACTIVITY_FILE']=str(root/'activity.bin')
    elif config['instrumentation']=='breakdown': env['LAUBERHORN_SERVER_TRACE_CSV']=str(root/'server.csv')
    with (root/'server.log').open('w') as log:
        process=subprocess.Popen([str(binary),'echo'+str(config['payload_bytes']),str(config['workers'])],
            env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    (root/'server.pid').write_text(str(process.pid)+'\n')
    (root/'config.json').write_text(json.dumps(config,indent=2)+'\n')
    time.sleep(2)
    if process.poll() is not None: raise RuntimeError('Resident responder failed to start')


def snapshot(config, output, *, export=False):
    root=Path(config['service_root']);output=Path(output);output.mkdir(exist_ok=False)
    pid=int((root/'server.pid').read_text());proc=Path('/proc')/str(pid)
    args=[s.decode() for s in (proc/'cmdline').read_bytes().split(b'\0') if s]
    if args[-2:]!=['echo'+str(config['payload_bytes']),str(config['workers'])]:
        raise ValueError('Unexpected resident service')
    if sha(proc/'exe')!=config['binary_sha256']: raise ValueError('Resident binary differs')
    if config['runtime']+'/lib/liblauberhorn.so' not in (proc/'maps').read_text():
        raise ValueError('Resident runtime differs from matched build')
    env=dict(s.split('=',1) for s in (proc/'environ').read_bytes().decode().split('\0') if '=' in s)
    traces={k:v for k,v in env.items() if 'TRACE' in k or 'PMU' in k}
    expected={'LAUBERHORN_SERVER_TRACE_CSV':str(root/'server.csv')} if config.get('instrumentation')=='breakdown' else {}
    if traces!=expected: raise ValueError('Unexpected software instrumentation')
    if Path('/sys/kernel/tracing/tracing_on').read_text().strip()!='0':
        raise ValueError('Kernel tracing enabled')
    activity=[]
    if config['mode']=='loaded':
        data=(root/'activity.bin').read_bytes()
        if len(data)!=64*config['workers']: raise ValueError('Incomplete activity records')
        for offset in range(0,len(data),64):
            count,cpu,tid=struct.unpack_from('<Qii',data,offset)
            activity.append(dict(calls=count,cpu=cpu,tid=tid))
    if export:
        if config.get('instrumentation')!='breakdown': raise ValueError('Export requires instrumented cohort')
        csv=root/'server.csv';status=Path(str(csv)+'.status.json')
        if csv.exists() or status.exists(): raise ValueError('Runtime export already consumed')
        os.kill(pid,signal.SIGUSR2)
        deadline=time.monotonic()+60
        while not status.exists():
            if time.monotonic()>deadline: raise TimeoutError('Runtime export deadline')
            time.sleep(.1)
        result=json.loads(status.read_text())
        if not result['loss_free'] or result['dropped']: raise ValueError('Runtime trace loss')
        for path in (csv,status): (output/path.name).write_bytes(path.read_bytes())
    provenance=dict(pid=pid,argv=args,binary_sha256=config['binary_sha256'],runtime=config['runtime'],
        trace_environment=traces,kernel_tracing=False,activity=activity,
        manifest=json.loads(Path('/nix/store/lauberhorn/manifest.json').read_text()),
        kernel=subprocess.check_output(['uname','-r'],text=True).strip())
    (output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    (output/'tad.txt').write_text(Path('/sys/module/lauberhorn/parameters/l2c_tad_snapshot').read_text())
    (output/'kernel.txt').write_bytes(subprocess.check_output(['dmesg']))
    return provenance
