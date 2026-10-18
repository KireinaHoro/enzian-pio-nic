#!/usr/bin/env python3
"""Validate paired DPDK archives, preserve source records, and import chart inputs."""
import argparse
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile


def read_archive(path):
    with tarfile.open(path) as archive:
        return {m.name.split('/',1)[1]:archive.extractfile(m).read() for m in archive if m.isfile()}


def table(data):
    return list(csv.DictReader(io.StringIO(data.decode())))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--archives',type=Path,required=True)
    p.add_argument('--plan',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--pilot',action='store_true')
    p.add_argument('--manifest',type=Path)
    a=p.parse_args(); a.output.mkdir(parents=True,exist_ok=False)
    plan=json.loads(a.plan.read_text()); imported=[]; summaries=[]
    for run in plan:
        label=run['label']; records={}
        for role in ('client','server'):
            path=a.archives/(label+'-'+role+'.tar.gz'); record=read_archive(path);records[role]=record
            status=json.loads(record['runner/summary.json'])
            if record['exit-code'].strip()!=b'0' or status['status']!='pass' or not status['restored']:
                raise ValueError(f'{label} {role}: failed run or restoration')
            if json.loads(record['run.json'])!=run: raise ValueError('Plan mismatch')
            if 'generator_lcores' in run:
                before=json.loads(record['runner/before.json'])
                expected=run['generator_lcores'] if role=='client' else ([45] if run['workers']==1 else list(range(44,44+run['workers'])))
                if before.get('lcores')!=expected: raise ValueError('Polling CPU provenance mismatch')
        if records['client']['image.sha256']!=records['server']['image.sha256']:
            raise ValueError('Client/server deployment image mismatch')
        if json.loads(records['client']['manifest.json'])['sourceHashes']!=json.loads(records['server']['manifest.json'])['sourceHashes']:
            raise ValueError('Client/server source mismatch')
        client=table(records['client']['samples.csv']); server=table(records['server']['samples.csv'])
        aggregate=bool(server and 'queue' in server[0])
        if not aggregate:server.sort(key=lambda row:int(row['request_id']))
        total=run['count']+run['warmup']
        if len(client)!=total or [int(r['request_id']) for r in client]!=list(range(1,total+1)):
            raise ValueError('Incomplete client identities')
        for i,r in enumerate(client):
            if r['ok']!='1' or r['rpc_status']!='0' or int(r['warmup'])!=int(i<run['warmup']) or r['benchmark']!=f"loopback{run['payload_bytes']}":
                raise ValueError('Invalid client sample')
        window=json.loads(records['client']['samples.csv.load.json'])
        if 'generator_lcores' in run and window.get('generator_cores')!=len(run['generator_lcores']):
            raise ValueError('Generator core count mismatch')
        if any(window[k]!=run[k] for k in ('window','gap_ns','warmup')):
            raise ValueError('Measured load configuration differs from plan')
        start,end=window['observation_start_ns'],window['observation_end_ns']
        measured=client[run['warmup']:]
        if window['failed'] or window['expected_measured']!=run['count'] or not start<end or any(not start<=int(r['client_call_enter_ns'])<int(r['client_call_exit_ns'])<=end for r in measured):
            raise ValueError('Invalid measurement window')
        mac=[]; workers=[]
        if not aggregate:
            if run['workers']!=1:raise ValueError('Per-request timestamp mode needs one worker')
            counters=[json.loads(line) for line in records['server']['runner/client.log'].decode().splitlines() if line.startswith('{')]
            if len(counters)!=1 or not counters[0]['passed'] or bool(counters[0]['hardware'])!=run['ptp'] or any(counters[0][k] for k in ('rx_errors','tx_errors','rx_missed')):
                raise ValueError('Invalid server instrumentation or NIC counters')
            if len(server)!=total: raise ValueError('Incomplete server records')
            for i,(s,c) in enumerate(zip(server,client)):
                if int(s['request_id'])!=i+1 or s['status']!='1' or s['warmup']!=c['warmup'] or s['profile']!='0':
                    raise ValueError('Invalid server identity or profiling')
                if run['ptp']:
                    rx,tx=int(s['rx_hw_ns']),int(s['tx_hw_ns'])
                    elapsed=int(s['mac_to_mac_ns'])
                    if not 0<rx<tx or tx-rx!=elapsed or elapsed>int(c['client_call_exit_ns'])-int(c['client_call_enter_ns']):
                        raise ValueError('Invalid paired PTP interval')
                    if i>=run['warmup']:mac.append((i+1,elapsed))
                elif int(s['rx_hw_ns']) or int(s['tx_hw_ns']):raise ValueError('Unexpected PTP in throughput run')
            workers=[(0,run['count'])]
        else:
            if run['ptp']:raise ValueError('Batched throughput must disable PTP')
            if len(server)!=run['workers'] or sum(int(s['completed']) for s in server)!=total or sum(int(s['measured']) for s in server)!=run['count'] or any(int(s['measured'])<=0 for s in server):
                raise ValueError('Incomplete worker evidence')
            if len({s['lcore'] for s in server})!=run['workers']:raise ValueError('Duplicate worker CPUs')
            workers=[(s['queue'],int(s['measured'])) for s in server]
        root=a.output/label;root.mkdir()
        def save(name,data):
            path=root/name
            path.write_bytes(gzip.compress(data,mtime=0) if name.endswith('.gz') else data)
            return dict(file=str(path.relative_to(a.manifest.parent if a.manifest else a.output)),sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        for role in ('server','client'):
            shutil.copyfile(a.archives/(label+'-'+role+'.tar.gz'),root/(role+'-acquisition.tar.gz'))
        entry=dict(system='DPDK',payload_bytes=run['payload_bytes'],workers=run['workers'],kind=run['kind'],label=label,
                   software_tracing=False,warmup_count=run['warmup'],measured_count=run['count'])
        entry['generator_lcores']=run.get('generator_lcores',[6])
        entry['target_rpc_s']=run.get('target_rpc_s')
        entry['ptp']=run.get('ptp',False)
        entry['client']=save('client.csv.gz',records['client']['samples.csv'])
        entry['window']=save('window.json',records['client']['samples.csv.load.json'])
        if mac:
            entry['mac']=save('mac.csv.gz',('request_id,mac_to_mac_ns\n'+''.join(f'{i},{v}\n' for i,v in mac)).encode())
            entry['mac']['boundary']='RX MAC -> TX MAC'
        else:
            entry['worker_evidence']=save('workers.csv.gz',('worker,measured\n'+''.join(f'{w},{n}\n' for w,n in workers)).encode())
        imported.append(entry)
        summaries.append(dict(label=label,measured=run['count'],workers=run['workers'],throughput_rpc_s=run['count']*1e9/(end-start),passed=True))
    (a.output/'summary.json').write_text(json.dumps(summaries,indent=2)+'\n')
    shutil.copyfile(a.plan,a.output/'plan.json')
    if a.manifest and not a.pilot:
        m=json.loads(a.manifest.read_text())
        if {r['label'] for r in m['runs']} & {r['label'] for r in imported}:raise ValueError('Duplicate cohort')
        m['runs']+=imported;m['status']='partial_measurements'
        a.manifest.write_text(json.dumps(m,indent=2)+'\n')
    print(json.dumps(summaries))


if __name__=='__main__':
    main()
