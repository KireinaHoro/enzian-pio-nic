#!/usr/bin/env python3
"""Run one echo role inside the staged client image's private mount namespace.

Called by the hardware adapter through tools/enzian/run.py, never directly as a
substitute for reservation/boot checks. No FPGA recording or trace readout code
is present here. All uploads are streamed from files after timed acquisition.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import tarfile
import time
import urllib.error
import urllib.request


def put(url, data):
    request=urllib.request.Request(url,data=data,method='PUT')
    with urllib.request.urlopen(request,timeout=180) as response: response.read()


def present(url):
    try:
        with urllib.request.urlopen(url,timeout=5) as response: response.read()
        return True
    except urllib.error.HTTPError as error:
        if error.code==404: return False
        raise


def upload(root, url):
    hashes={}
    for path in root.rglob('*'):
        if not path.is_file() or path.name=='files.json': continue
        with path.open('rb') as stream:
            sha=hashlib.sha256()
            for block in iter(lambda:stream.read(1024*1024),b''): sha.update(block)
        hashes[str(path.relative_to(root))]=sha.hexdigest()
    (root/'files.json').write_text(json.dumps(hashes,sort_keys=True)+'\n')
    archive=root.with_suffix('.tar.gz')
    # Level 9's CPU cost can exceed the entire role deadline on large CSVs.
    # Acquisition is over; retain lossless bytes while favoring export speed.
    with tarfile.open(archive,'x:gz',compresslevel=1) as stream: stream.add(root,arcname=root.name)
    with archive.open('rb') as stream:
        request=urllib.request.Request(url+'/'+archive.name,data=stream,method='PUT',
            headers={'Content-Length':str(archive.stat().st_size)})
        with urllib.request.urlopen(request,timeout=600) as response: response.read()


def server_nic_prepared(state, source_cidr):
    """Accept an idle prepared VF or its networkd-managed normal state."""
    if not state['addr_info'] and 'UP' not in state['flags']:
        return False
    ipv4={a['local']+'/'+str(a['prefixlen']) for a in state['addr_info'] if a['family']=='inet'}
    unexpected_v6=any(a['family']=='inet6' and a.get('scope')!='link' for a in state['addr_info'])
    if 'UP' not in state['flags'] or ipv4!={source_cidr} or unexpected_v6:
        raise ValueError('DUT data NIC has unexpected addresses or link state')
    return True


def run(config):
    if config['role']=='server' and config['system']=='CS-A64':
        helper=config['caladan_helper']
        data=urllib.request.urlopen(helper['url'],timeout=30).read()
        if hashlib.sha256(data).hexdigest()!=helper['sha256']: raise ValueError('Caladan helper mismatch')
        namespace={'__name__':'caladan_peer'}
        exec(compile(data,'caladan_peer.py','exec'),namespace)
        return namespace['run'](config,globals())
    def interrupted(signum, frame): raise KeyboardInterrupt('Campaign role interrupted')
    signal.signal(signal.SIGTERM,interrupted)
    root=Path(config['output']); root.mkdir(parents=True,exist_ok=False)
    (root/'request.json').write_text(json.dumps(config,indent=2)+'\n')
    manifest=Path(config['manifest'])
    (root/'manifest.json').write_bytes(manifest.read_bytes())
    server=config['role']=='server'; loaded=config['mode']=='loaded'
    if loaded and not server:
        slots=config['rate']*(config['warmup_ms']+config['duration_ms'])//1000
        import math
        slots+=math.ceil(32*math.sqrt(slots)+1024)
        # ThunderX cache-line alignment makes each tracking record 128 bytes.
        tracking=slots*128;output=slots*256
        if config.get('aggregate',False):
            cores=len(config['cpus'])
            window=(config['rate']*50_000_000+cores*1_000_000_000-1)//(cores*1_000_000_000)+1024
            stride=((slots+cores-1)//cores+63)//64*64
            tracking=cores*(stride+window*128+100001*8+8192*128)
            output=4*1024*1024
        windows=(config['duration_ms']+config.get('window_ms',0)-1)//config['window_ms'] if config.get('window_ms',0) else 0
        tracking+=8*slots+len(config['cpus'])*windows*(128+100001*8)
        output+=windows*2*1024*1024
        mem=dict(line.split(':',1) for line in Path('/proc/meminfo').read_text().splitlines())
        available=int(mem['MemAvailable'].split()[0])*1024
        space=os.statvfs(root);free=space.f_bavail*space.f_frsize
        if tracking>config['tracking_mib']*1024*1024 or free<2*output or available<tracking+2*output+1024**3:
            raise ValueError('Peer tracking/CSV/archive budget exhausted before acquisition')
    cores=config['cpus']
    if not cores or len(set(cores))!=len(cores): raise ValueError('Invalid CPU list')
    if not server and ((not 1 <= len(cores) <= 32) if loaded else len(cores)!=1): raise ValueError('Wrong generator CPU count')
    if loaded and config['deadline_ms']!=10: raise ValueError('Loaded deadline must be 10 ms')
    env=dict(os.environ)
    for key in list(env):
        if key.startswith(('LH_BENCH_','LH_ECHO_','LAUBERHORN_','ADDER_SERVER_TRACE')):
            del env[key]
    env['LH_BENCH_FLOWS']=str(config['flows'])
    url=config['transfer_url'].rstrip('/'); label=config['label']
    runner=['lh-dpdk-run','--logs',str(root/'runner'),'--lcore',str(cores[0]),'--no-iommu',
            '--timeout',str(config['timeout_seconds'])]
    if len(cores)>1: runner+=['--worker-lcores',','.join(map(str,cores[1:]))]
    interface=config.get('interface','cpu40g1')
    runner+=['--interface',interface]
    if server:
        env['LH_ECHO_READY_FILE']=str(root/'ready')
        if loaded:
            env['LH_ECHO_STOP_FILE']=str(root/'stop')
        else:
            if config['instrumentation']=='breakdown': env['LH_ECHO_PROFILE']='1'
            if config.get('chunk_requests'):env['LH_ECHO_CHUNK_REQUESTS']=str(config['chunk_requests'])
    elif loaded:
        env.update(LH_BENCH_AGGREGATE='1' if config.get('aggregate',False) else '0',
                   LH_BENCH_WINDOW_MS=str(config.get('window_ms',0)),LH_BENCH_SLOT_NS=str(config['slot_ns']),LH_BENCH_SEED=str(config['seed']),
                   LH_BENCH_MAX_SEND_LAG_NS=str(config['max_send_lag_ns']),
                   LH_BENCH_RATE=str(config['rate']),LH_BENCH_WARMUP_MS=str(config['warmup_ms']),
                   LH_BENCH_DURATION_MS=str(config['duration_ms']),
                   LH_BENCH_TRACKING_MIB=str(config['tracking_mib']),LH_BENCH_DRAINED_FILE=str(root/'drained'))
        if config.get('aggregate',False):
            accounting_cpu=config.get('accounting_cpu',8)
            if type(accounting_cpu) is not int or accounting_cpu<0 or accounting_cpu in cores:
                raise ValueError('Accounting CPU must be outside generator CPUs')
            env['LH_BENCH_ACCOUNTING_CPU']=str(accounting_cpu)
    if not loaded and not server:
        # With no window override, the single-outstanding client loop carries
        # next_send across warmup and measurement. load_phase resets it.
        env['LH_BENCH_GAP_NS']=str(config.get('gap_ns',0))
    count=config.get('count',1)
    # Loaded responder activity covers all traffic; only the client owns the
    # sampled warmup/measurement split. Never estimate an XID threshold as rate*T.
    warmup=0 if loaded and server else config.get('warmup',0)
    args=runner+[config['binary'],'--',*map(str,config['endpoint']),str(count),str(warmup),
                 str(config['deadline_ms']*1000),'echo'+str(config['payload_bytes']),str(root/'samples.csv')]
    original=None; process=None; failure=None; code=None
    try:
        if server:
            original=json.loads(subprocess.check_output(['ip','-j','addr','show','dev',interface]))[0]
            if not server_nic_prepared(original,config['source_cidr']):
                subprocess.run(['ip','addr','add',config['source_cidr'],'dev',interface,'noprefixroute'],check=True)
                subprocess.run(['ip','link','set',interface,'up'],check=True)
        elif config['system'] in ('DPDK','CS-A64'):
            deadline=time.monotonic()+120
            while not present(url+'/'+config.get('resident_label',label)+'-ready'):
                if time.monotonic()>deadline: raise TimeoutError('Server readiness')
                time.sleep(.1)
        with (root/'process.log').open('w') as log:
            process=subprocess.Popen(args,env=env,stdout=log,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+config['timeout_seconds']+30
            if server:
                while not (root/'ready').exists() and process.poll() is None:
                    if time.monotonic()>deadline: raise TimeoutError('Local server readiness')
                    time.sleep(.05)
                if not (root/'ready').exists(): raise RuntimeError('Server exited before readiness')
                put(url+'/'+config.get('resident_label',label)+'-ready',b'ready\n')
                if loaded:
                    while process.poll() is None and not present(url+'/'+label+'-stop'):
                        if time.monotonic()>deadline: raise TimeoutError('Campaign stop control')
                        time.sleep(.1)
                    (root/'stop').touch(exist_ok=False)
            if not server and loaded and config['system'] in ('DPDK','CS-A64'):
                while process.poll() is None and not (root/'drained').exists():
                    if time.monotonic()>deadline: raise TimeoutError('Client drain completion')
                    time.sleep(.01)
                if (root/'drained').exists(): put(url+'/'+label+'-stop',b'drained\n')
            code=process.wait(timeout=max(1,deadline-time.monotonic()))
    except BaseException as error:
        failure=str(error)
    finally:
        if not server and config['system'] in ('DPDK','CS-A64'):
            try:
                if not present(url+'/'+label+'-stop'): put(url+'/'+label+'-stop',b'stop\n')
            except Exception as error: failure=f'{failure or ""}; stop delivery: {error}'
        if process and process.poll() is None:
            if server and loaded: (root/'stop').touch(exist_ok=True)
            # The NIC wrapper catches SIGINT and executes its restoration path.
            # SIGTERM's default disposition would skip that Python finally block.
            process.send_signal(signal.SIGINT)
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(); failure=f'{failure or ""}; forced wrapper termination'
        if original is not None and not original['addr_info'] and 'UP' not in original['flags']:
            # Driver rebinding may already have removed the temporary address.
            current=json.loads(subprocess.check_output(['ip','-j','addr','show','dev',interface]))[0]
            commands=[]
            if any(a.get('local')+'/'+str(a.get('prefixlen'))==config['source_cidr']
                   for a in current['addr_info'] if a.get('local')):
                commands.append(['ip','addr','del',config['source_cidr'],'dev',interface])
            commands.append(['ip','link','set',interface,'down'])
            for command in commands:
                if subprocess.run(command).returncode: failure=f'{failure or ""}; NIC restoration failed'
        (root/'result.json').write_text(json.dumps(dict(returncode=code,error=failure),indent=2)+'\n')
        upload(root,url)
    if code or failure: raise RuntimeError(f'Acquisition failed; preserved at {root}: {failure}, exit {code}')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('request',type=Path)
    args=parser.parse_args();run(json.loads(args.request.read_text()))
