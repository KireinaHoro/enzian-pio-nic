#!/usr/bin/env python3
"""Join loopback client cohorts to a complete, validated LH MAC/worker capture."""
import argparse
from collections import Counter
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import numpy as np
from scipy.optimize import linprog
import import_nullrpc_mac as mac
from import_loopback_dpdk import read_archive, table


def fit_clock_bounds(bounds):
    """Check causality under bounded smooth relative clock drift; never alter RTT."""
    values=np.asarray(bounds,dtype=float)
    if (values.ndim!=2 or values.shape[1]!=4 or not len(values) or
        not np.isfinite(values).all() or np.any(values[:,1]<=values[:,0]) or
        np.any(values[:,3]<=values[:,2])):
        raise ValueError('Invalid clock-bound observations')
    x0=values[0,0];base=values[0,2]-x0
    rx=(values[:,0]-x0)/1e9;tx=(values[:,1]-x0)/1e9
    matrix=np.vstack([np.column_stack([-np.ones(len(values)),-rx]),
                      np.column_stack([np.ones(len(values)),tx])])
    limits=np.r_[(values[:,0]+base-values[:,2])/1000,
                 (values[:,3]-values[:,1]-base)/1000]
    fit=linprog([0,0],A_ub=matrix,b_ub=limits,
                bounds=[(None,None),(-1000,1000)],method='highs')
    affine=bool(fit.success);curvature=0.0
    if not affine:
        # A minutes-long acquisition need not have a constant relative rate.
        # Bound the instantaneous rate at both ends of the quadratic mapping.
        end=float(max(tx))
        matrix=np.vstack([np.column_stack([-np.ones(len(values)),-rx,-rx*rx]),
                          np.column_stack([np.ones(len(values)),tx,tx*tx]),
                          [0,1,2*end],[0,-1,-2*end]])
        limits=np.r_[limits,1000,1000]
        fit=linprog([0,0,0],A_ub=matrix,b_ub=limits,
                    bounds=[(None,None),(-1000,1000),(None,None)],method='highs')
        if fit.success:curvature=float(fit.x[2])
    if not fit.success or np.max(matrix@fit.x-limits)>1e-6:
        raise ValueError('Client/MAC causal bounds reject the cohort-order join')
    return dict(causal_clock_model='affine' if affine else 'quadratic',
                global_affine_fit_passed=affine,causal_offset_us=float(fit.x[0]),
                causal_rate_ppm=float(fit.x[1]),
                causal_rate_end_ppm=float(fit.x[1]+2*curvature*max(tx)),
                causal_curvature_us_per_s2=curvature,causal_constraints=2*len(values),
                clock_alignment_applied_to_rtt=False)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('plan','archives','map','capture-check','health','output','manifest'):
        p.add_argument('--'+name,type=Path,required=True)
    source=p.add_mutually_exclusive_group(required=True)
    source.add_argument('--trace',type=Path)
    source.add_argument('--events',type=Path,help='previously archived verbatim compact events')
    a=p.parse_args();plan=json.loads(a.plan.read_text());campaign=plan['campaign']
    if not json.loads(a.health.read_text())['passed']:raise ValueError('Hardware health failed')
    check=json.loads(a.capture_check.read_text())
    expected=plan['setup_count']+sum(r['count']+r['warmup'] for r in plan['runs'])
    if not check['passed'] or check['expected_rpcs']!=expected:raise ValueError('Capture plan count mismatch')
    a.output.mkdir(parents=True,exist_ok=False);(a.output/'acquisition').mkdir()
    shutil.copyfile(a.capture_check,a.output/'acquisition'/(campaign+'-trace-check.json'))
    shutil.copyfile(a.health,a.output/'health.json');shutil.copyfile(a.plan,a.output/'plan.json')
    if a.trace:
        mac.extract(a.trace,a.map,data=a.output,campaign=campaign,events=mac.EVENTS|{42,44})
    else:
        if mac.digest(a.map)!=check['map_sha256']:raise ValueError('Map hash mismatch')
        provenance=json.loads((a.events.parent/'summary.json').read_text())
        if mac.digest(a.events)!=provenance['files'][a.events.name]:raise ValueError('Compact event hash mismatch')
        shutil.copyfile(a.events,a.output/(campaign+'-mac-events.bin.gz'))
        shutil.copyfile(a.map,a.output/(campaign+'-trace-map.json'))
    calls=mac.correlate(data=a.output,campaign=campaign,include_workers=True)
    cursor=plan['setup_count'];entries=[];bounds=[]
    for run in plan['runs']:
        label=run['label'];source=a.archives/(label+'.tar.gz');record=read_archive(source)
        status=json.loads(record['runner/summary.json'])
        if record['exit-code'].strip()!=b'0' or status['status']!='pass' or not status['restored']:
            raise ValueError('Client failed or NIC not restored')
        if 'generator_lcores' in run and json.loads(record['runner/before.json']).get('lcores')!=run['generator_lcores']:
            raise ValueError('Generator CPU provenance mismatch')
        client=table(record['samples.csv']);total=run['count']+run['warmup']
        if len(client)!=total or [int(r['request_id']) for r in client]!=list(range(1,total+1)):
            raise ValueError('Incomplete client identities')
        window=json.loads(record['samples.csv.load.json']);start,end=window['observation_start_ns'],window['observation_end_ns']
        if window['failed'] or window['expected_measured']!=run['count'] or end<=start:raise ValueError('Invalid window')
        if any(window[k]!=run[k] for k in ('window','gap_ns','warmup')):
            raise ValueError('Measured load configuration differs from plan')
        measured_mac=[];workers=Counter()
        generators=window.get('generator_cores',1)
        if 'generator_lcores' in run and generators!=len(run['generator_lcores']):raise ValueError('Generator core count mismatch')
        cohort=calls[cursor:cursor+total]
        if len(cohort)!=total:raise ValueError('Incomplete hardware cohort')
        if generators>1:
            # Warmup drains before measurement. Within each phase, source cores
            # can reorder requests: use cohort boundaries, never invent XID joins.
            for lo,hi in ((0,run['warmup']),(run['warmup'],total)):
                if lo==hi:continue
                phase=client[lo:hi];hardware=cohort[lo:hi]
                bounds.append((min(c['rx_mac_ns'] for c in hardware),max(c['tx_mac_ns'] for c in hardware),
                               min(int(r['client_call_enter_ns']) for r in phase),max(int(r['client_rx_burst_ns']) for r in phase)))
        for i,row in enumerate(client):
            if row['ok']!='1' or row['rpc_status']!='0' or int(row['warmup'])!=int(i<run['warmup']) or row['benchmark']!=f"loopback{run['payload_bytes']}":raise ValueError('Invalid client row')
            call=calls[cursor];cursor+=1
            rx,tx=call['rx_mac_ns'],call['tx_mac_ns']
            enter,received=int(row['client_call_enter_ns']),int(row['client_rx_burst_ns'])
            if tx is None or tx<=rx:raise ValueError('Invalid MAC pair')
            if generators==1:
                if tx-rx>received-enter:raise ValueError('MAC interval outside client request')
                bounds.append((rx,tx,enter,received))
            if i>=run['warmup']:
                if not start<=enter<int(row['client_call_exit_ns'])<=end:raise ValueError('Request outside observation window')
                measured_mac.append((int(row['request_id']),tx-rx));workers[call['worker']]+=1
        if not 0<len(workers)<=run['workers']:raise ValueError('Invalid observed worker count')
        if not run.get('target_rpc_s') and len(workers)!=run['workers']:
            raise ValueError('Unpaced cohort did not exercise every configured worker')
        root=a.output/label;root.mkdir();shutil.copyfile(source,root/'client-acquisition.tar.gz')
        def save(name,data):
            path=root/name;path.write_bytes(gzip.compress(data,mtime=0) if name.endswith('.gz') else data)
            return dict(file=str(path.relative_to(a.manifest.parent)),sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        entry=dict(label=label,system='LH',payload_bytes=run['payload_bytes'],workers=run['workers'],kind=run['kind'],software_tracing=False,
                   warmup_count=run['warmup'],measured_count=run['count'],pipeline=519555,hardware_revision='0e58f0582b6f0039ceeff2c4714ceb4451cca490')
        entry['generator_lcores']=run.get('generator_lcores',[6])
        entry['target_rpc_s']=run.get('target_rpc_s')
        entry['ptp']=run.get('ptp',False)
        entry['hardware_join']='phase-count and causal cohort bounds' if generators>1 else 'ordered per-request MAC join'
        entry['client']=save('client.csv.gz',record['samples.csv']);entry['window']=save('window.json',record['samples.csv.load.json'])
        entry['worker_evidence']=save('workers.csv.gz',('worker,measured\n'+''.join(f'{w},{n}\n' for w,n in sorted(workers.items()))).encode())
        if run['kind']=='latency' and generators==1:
            entry['mac']=save('mac.csv.gz',('request_id,mac_to_mac_ns\n'+''.join(f'{i},{v}\n' for i,v in measured_mac)).encode())
            entry['mac']['boundary']='RX MAC -> TX MAC'
        entries.append(entry)
    if cursor!=len(calls):raise ValueError('Unattributed hardware calls')
    clock=fit_clock_bounds(bounds)
    result=dict(passed=True,measured=sum(r['count'] for r in plan['runs']),whole_capture_rpcs=len(calls),**clock,
                files={name:mac.digest(a.output/name) for name in (campaign+'-mac-events.bin.gz',campaign+'-trace-map.json')})
    (a.output/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    m=json.loads(a.manifest.read_text())
    if {r['label'] for r in m['runs']} & {r['label'] for r in entries}:raise ValueError('Duplicate cohort')
    m['runs']+=entries;m['status']='partial_measurements';a.manifest.write_text(json.dumps(m,indent=2)+'\n')
    print(json.dumps(result))


if __name__=='__main__':main()
