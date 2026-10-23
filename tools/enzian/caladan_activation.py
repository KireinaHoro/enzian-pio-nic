#!/usr/bin/env python3
"""Decode bounded Caladan cold evidence and join single-outstanding activations.
Copy mmap captures only after quiescing producers. No board access in this tool.
"""
import argparse
import csv
import json
import re
import struct
from pathlib import Path

EVENTS = {1:'demand',2:'allocate',3:'cede_request',4:'run_request',
          5:'preempt_received',6:'cede_enter',7:'park_enter',8:'park_resume',
          9:'rx_observed',10:'park_attempt',11:'park_result'}
FIELDS = ('ns','event','pid','tid','cpu','subject','core','generation','peer','committed')


def decode(path):
    raw=Path(path).read_bytes()
    if len(raw)<64: raise ValueError('truncated activation header')
    magic,version,capacity,count,dropped,*_=struct.unpack_from('<8Q',raw)
    if magic!=0x43414c4143543031 or version!=1: raise ValueError('unknown activation format')
    if len(raw)!=64+capacity*80 or count>capacity or dropped:
        raise ValueError('truncated or overflowing activation evidence')
    rows=[]
    for i in range(count):
        row=dict(zip(FIELDS,struct.unpack_from('<10Q',raw,64+i*80)))
        if row['committed']!=i+1 or not row['ns'] or row['event'] not in EVENTS:
            raise ValueError('uncommitted/invalid activation event; quiesce producers before export')
        row['event']=EVENTS[row['event']]
        rows.append(row)
    return rows


def kernel_records(text):
    if re.search(r'LOST.*EVENTS',text,re.I): raise ValueError('lost kernel events')
    rows=[]
    for line in text.splitlines():
        if not re.search(r'\b(?:caladan:)?activation:',line): continue
        row={k:int(v) for k,v in re.findall(r'\b(\w+)=(-?\d+)',line)}
        if not {'ns','phase','core','generation','tid','target'} <= row.keys() or row['ns']<=0:
            raise ValueError('incomplete kernel activation event')
        if row['phase'] not in (1,2,3,4): raise ValueError('unknown kernel phase')
        rows.append(row)
    return rows


def only(rows,what):
    if len(rows)!=1: raise ValueError(f'{what}: expected unique match, found {len(rows)}')
    return rows[0]


def latest(rows,what):
    if not rows: raise ValueError(f'missing {what}')
    ns=max(r['ns'] for r in rows)
    return only([r for r in rows if r['ns']==ns],what)


def join(events,kernel,server,mode):
    """Strict cold join; never silently classify a hot row as a new activation.
    Single-outstanding request and controlled eviction are acquisition obligations.
    Times are RAW ns on one host. Grant and KSCHED generation spaces stay separate.
    """
    result=[];used=set()
    for row in server:
        observed=int(row['server_rx_observed_ns'])
        rx=only([e for e in events if e['event']=='rx_observed' and e['ns']==observed],'RX observation')
        resume=latest([e for e in events if e['event']=='park_resume' and e['tid']==rx['tid'] and e['pid']==rx['pid'] and e['ns']<=observed],'runtime reactivation')
        run=latest([e for e in events if e['event']=='run_request' and e['subject']==rx['tid'] and e['core']==resume['core'] and e['ns']<=resume['ns']],'KSCHED request')
        identity=(run['core'],run['generation'])
        if identity in used: raise ValueError('activation reused by multiple requests')
        used.add(identity)
        alloc=latest([e for e in events if e['event']=='allocate' and e['subject']==rx['tid'] and e['core']==run['core'] and e['peer']==rx['pid'] and e['ns']<=run['ns']],'allocation')
        if alloc['generation']!=resume['generation']: raise ValueError('runtime grant generation mismatch')
        demand=latest([e for e in events if e['event']=='demand' and e['subject']==rx['pid'] and e['ns']<=alloc['ns']],'target demand')
        running=only([k for k in kernel if k['phase']==3 and k['tid']==rx['tid'] and k['core']==run['core'] and k['generation']==run['generation'] and run['ns']<=k['ns']<=resume['ns']],'kernel target running')
        park=latest([e for e in events if e['event']=='park_enter' and e['tid']==rx['tid'] and e['pid']==rx['pid'] and e['ns']<demand['ns']],'target prior park')
        parked=latest([k for k in kernel if k['phase']==2 and k['tid']==rx['tid'] and park['ns']<=k['ns']<demand['ns']],'target parked before demand')
        stamps=dict(demand=demand['ns'],allocation=alloc['ns'],run_request=run['ns'],
                    target_running=running['ns'],park_resume=resume['ns'],rx_observed=observed)
        cedes=[e for e in events if e['event']=='cede_request' and e['core']==run['core'] and e['peer']==rx['tid'] and alloc['ns']<=e['ns']<=run['ns']]
        if mode=='parked':
            if not park['peer'] or cedes: raise ValueError('expected voluntary park and idle-core activation')
        else:
            cede=only(cedes,'outgoing cede request')
            entered=only([e for e in events if e['event']=='cede_enter' and e['tid']==cede['subject'] and e['core']==run['core'] and e['generation']==cede['generation'] and cede['ns']<=e['ns']<=running['ns']],'outgoing runtime cede')
            signal=latest([e for e in events if e['event']=='preempt_received' and e['tid']==cede['subject'] and cede['ns']<=e['ns']<=entered['ns']],'preemption delivery')
            admitted=only([k for k in kernel if k['phase']==1 and k['tid']==cede['subject'] and k['core']==run['core'] and entered['ns']<=k['ns']<=running['ns']],'kernel cede admission')
            parked_out=only([k for k in kernel if k['phase']==2 and k['tid']==cede['subject'] and admitted['ns']<=k['ns']<=running['ns']],'outgoing parked')
            stamps.update(cede_request=cede['ns'],preempt_received=signal['ns'],
                          cede_enter=entered['ns'],cede_admitted=admitted['ns'],cede_parked=parked_out['ns'])
        ordered=sorted(stamps.items(),key=lambda item:(item[1],item[0]))
        # One disjoint refinement, including interleaving of publication and cede.
        intervals=[dict(begin=a,end=b,ns=y-x) for (a,x),(b,y) in zip(ordered,ordered[1:])]
        if sum(i['ns'] for i in intervals)!=observed-demand['ns']: raise ValueError('activation partition does not conserve')
        result.append(dict(request_id=int(row['request_id']),mode=mode,tid=rx['tid'],
                           core=run['core'],ksched_generation=run['generation'],grant_generation=resume['generation'],
                           target_parked_ns=parked['ns'],timestamps_ns=stamps,intervals=intervals,
                           activation_ns=observed-demand['ns']))
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--capture',type=Path,action='append',required=True)
    p.add_argument('--kernel',type=Path)
    p.add_argument('--server',type=Path)
    p.add_argument('--mode',choices=('parked','preempt'))
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    events=sorted([r for path in a.capture for r in decode(path)],key=lambda r:r['ns'])
    result=dict(schema=1,clock='CLOCK_MONOTONIC_RAW',events=events)
    if any((a.kernel,a.server,a.mode)):
        if not all((a.kernel,a.server,a.mode)): p.error('joining needs --kernel, --server and --mode')
        with a.server.open() as f: rows=list(csv.DictReader(f))
        metadata=json.loads((a.kernel/'capture.json').read_text())
        if metadata.get('loss_free') is not True or metadata.get('returncode') != 0:
            raise ValueError('incomplete kernel capture')
        result['rows']=join(events,kernel_records((a.kernel/'trace.txt').read_text()),rows,a.mode)
    a.output.write_text(json.dumps(result,indent=2)+'\n')


if __name__=='__main__': main()
