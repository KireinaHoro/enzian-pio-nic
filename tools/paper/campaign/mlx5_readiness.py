#!/usr/bin/env python3
"""Bounded on-board CX5 acceptance; invoke through the reserved-board harness.

Uses the image's canonical NIC runner for both roles. The local NICVF peer and
mlx5 server use disjoint CPUs; these short tests are not capacity measurements.
No FPGA/NIC timestamping, wire-duration substitution or long collection.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def wait_ready(path, process, deadline):
    while not path.exists():
        if process.poll() is not None: raise RuntimeError('Responder exited before readiness')
        if time.monotonic()>deadline: raise TimeoutError('Responder readiness deadline')
        time.sleep(.05)


def cases(root, server_ip, client_ip, client_mac, server_mac):
    root.mkdir(parents=True,exist_ok=False)
    manifest=json.loads(Path('/nix/store/lauberhorn-client/manifest.json').read_text())
    if manifest.get('queueInstrumentation')!='mlx5-scalar-cpu-v1':
        raise ValueError('Image lacks mlx5 queue instrumentation')
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    results=[]
    for size in (8,128,1432):
        for mode,workers in (('off',1),('breakdown',1),('loaded',1),('loaded',4)):
            name=f'echo{size}-{mode}-{workers}';out=root/name;out.mkdir()
            env={k:v for k,v in os.environ.items() if not k.startswith(('LH_ECHO_','LH_BENCH_','LAUBERHORN_'))}
            loaded=mode=='loaded'; count,warmup=(1,5000) if loaded else (256,16)
            se=dict(env,LH_ECHO_READY_FILE=str(out/'ready'))
            ce=dict(env)
            if loaded:
                se['LH_ECHO_STOP_FILE']=str(out/'stop')
                ce.update(LH_BENCH_RATE='100000',LH_BENCH_WARMUP_MS='50',LH_BENCH_DURATION_MS='250',
                          LH_BENCH_BATCH_MAX='64',LH_BENCH_BATCH_TARGET_NS='250000',LH_BENCH_AGGREGATE='1',
                          LH_BENCH_FLOWS='64',LH_BENCH_DRAINED_FILE=str(out/'drained'))
            elif mode=='breakdown':se['LH_ECHO_PROFILE']='1'
            binary=Path('/nix/store/lauberhorn-client/bin')
            runner=['lh-dpdk-run','--timeout','90','--hugepages-mb','512']
            server=runner+['--logs',str(out/'server-runner'),'--interface','enP5p144s0np0','--lcore','44']
            if workers>1:server+=['--worker-lcores','45,46,47']
            server+=[str(binary/('echo_dpdk' if mode=='breakdown' else 'echo_dpdk_uninstrumented')),'--',
                     server_ip,client_ip,client_mac,'49152',str(count),str(warmup),'10000','echo'+str(size),str(out/'server.csv')]
            client=runner+['--logs',str(out/'client-runner'),'--interface','cpu40g1','--no-iommu','--lcore','16']
            if loaded:client+=['--worker-lcores','17,18,19']
            client+=[str(binary/'bench_dpdk'),'--',client_ip,server_ip,server_mac,'12345',str(count),
                     '0' if loaded else str(warmup),'10000','echo'+str(size),str(out/'client.csv')]
            (out/'commands.json').write_text(json.dumps(dict(server=server,client=client,server_env={k:v for k,v in se.items() if k.startswith('LH_')},client_env={k:v for k,v in ce.items() if k.startswith('LH_')}),indent=2))
            sp=None;error=None;start=time.monotonic()
            try:
                with (out/'server.log').open('w') as slog,(out/'client.log').open('w') as clog:
                    sp=subprocess.Popen(server,env=se,stdout=slog,stderr=subprocess.STDOUT)
                    wait_ready(out/'ready',sp,time.monotonic()+60)
                    cp=subprocess.run(client,env=ce,stdout=clog,stderr=subprocess.STDOUT,timeout=100)
                    if loaded:(out/'stop').touch()
                    sc=sp.wait(timeout=30)
                    if cp.returncode or sc:raise RuntimeError(f'Client exit {cp.returncode}; server exit {sc}')
                for role in ('server','client'):
                    summary=json.loads((out/(role+'-runner')/'summary.json').read_text())
                    if summary['status']!='pass' or not summary['restored']:raise ValueError('NIC restoration failed')
            except BaseException as exc:
                error=str(exc) or type(exc).__name__
            finally:
                if sp and sp.poll() is None:
                    sp.send_signal(signal.SIGINT)
                    try:sp.wait(timeout=30)
                    except subprocess.TimeoutExpired:sp.kill();sp.wait()
                results.append(dict(case=name,size=size,mode=mode,workers=workers,passed=error is None,error=error,seconds=time.monotonic()-start))
                (root/'summary.json').write_text(json.dumps(dict(paper_inputs=False,capacity_measurement=False,wire_available=False,results=results),indent=2)+'\n')
            if error:raise RuntimeError(error)
    hashes={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file()}
    (root/'files.json').write_text(json.dumps(hashes,indent=2)+'\n')


def run(root, server_ip, client_ip, client_mac, server_mac):
    # Both wrappers share this host only for bounded readiness. Reserve resources
    # before either starts, so one role cannot tear down the other's hugepages.
    pages=Path('/proc/sys/vm/nr_hugepages');old=int(pages.read_text())
    size=next(int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('Hugepagesize:'))
    mounted=subprocess.run(['mountpoint','-q','/dev/hugepages']).returncode==0
    try:
        pages.write_text(str(max(old,(2048*1024+size-1)//size)))
        if int(pages.read_text())*size<2048*1024:raise RuntimeError('Readiness hugepage budget unavailable')
        if not mounted:
            Path('/dev/hugepages').mkdir(exist_ok=True)
            subprocess.run(['mount','-t','hugetlbfs','nodev','/dev/hugepages'],check=True)
        cases(root,server_ip,client_ip,client_mac,server_mac)
    finally:
        if not mounted:subprocess.run(['umount','/dev/hugepages'],check=True)
        pages.write_text(str(old))
        if int(pages.read_text())!=old:raise RuntimeError('Readiness hugepage restoration failed')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--server-ip',required=True);p.add_argument('--client-ip',required=True)
    p.add_argument('--server-mac',required=True);p.add_argument('--client-mac',required=True)
    a=p.parse_args();run(a.output.resolve(),a.server_ip,a.client_ip,a.client_mac,a.server_mac)
