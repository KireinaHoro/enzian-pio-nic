#!/usr/bin/env python3
"""Join buffered Dandelion spans by explicit IDs; plot an actual median request.
DUT timestamps are MONOTONIC_RAW. Client RTT is a duration, not a clock anchor.
Parallel child spans are never summed into the parent's critical path.
"""
import argparse
import csv
import hashlib
import json
from collections import defaultdict
from pathlib import Path

EVENT = {1:'entry_return',2:'fiber_resume',3:'fiber_suspend',4:'call_submit',
         5:'call_submitted',6:'rx_packet',100:'entry',101:'resolved',
         102:'prepare',103:'execute',104:'executed',105:'child_begin',
         106:'child_sent',107:'collect_begin',108:'collected',109:'collect_end',110:'done'}

def rows(path):
    with path.open() as f:
        return [{k:int(v) if v and v.lstrip('-').isdigit() else v for k,v in r.items()}
                for r in csv.DictReader(f)]

def distribution(values):
    s=sorted(values)
    if not s: return {'n':0}
    def pct(p):
        i=(len(s)-1)*p; a=int(i); b=min(a+1,len(s)-1)
        return s[a]+(s[b]-s[a])*(i-a)
    return {'n':len(s),**{name:pct(p) for name,p in
            [('p50',.5),('p90',.9),('p95',.95),('p99',.99),('p100',1)]}}

def load(server,events):
    for path in (server,events):
        meta=json.loads(Path(str(path)+'.status.json').read_text())
        if not meta['loss_free']: raise ValueError(f'loss/incomplete export: {path}')
    requests={}
    for r in rows(server):
        if not r['request_id_valid']: raise ValueError('request missing correlation ID')
        key=r['request_id']
        if key in requests: raise ValueError(f'duplicate request ID {key}')
        if not r['ok'] or r['reply_status'] or r['transport_status']:
            raise ValueError(f'failed request {key}')
        requests[key]=r
    by_id=defaultdict(list)
    for e in rows(events): by_id[e['request_id']].append(e)
    for ev in by_id.values(): ev.sort(key=lambda e:e['timestamp_ns'])
    children=defaultdict(list)
    owners={}
    for parent,ev in by_id.items():
        for e in ev:
            if e['event']!=106: continue
            child=e['arg0']
            if not child or child not in requests: raise ValueError(f'missing child {child}')
            if child in owners: raise ValueError(f'duplicate child submission {child}')
            owners[child]=parent; children[parent].append(child)
            collected=[c for c in ev if c['event']==108 and c['arg0']==child]
            if len(collected)!=1: raise ValueError(f'child not collected exactly once: {child}')
    return requests,by_id,children

def spans(row,ev):
    result=[]
    def add(name,a,b):
        if not a or not b: return
        if b<a: raise ValueError(f'reversed {name}')
        result.append((name,a,b))
    def field(name,a,b): add(name,row.get('server_'+a+'_ns',0),row.get('server_'+b+'_ns',0))
    field('RX blocked','rx_enter','rx_unblock')
    field('RX return','rx_unblock','rx_exit')
    field('decode','unmarshal_enter','unmarshal_exit')
    resolved=next((e['timestamp_ns'] for e in ev if e['event']==101),0)
    add('entry / classify',row.get('server_entry_enter_ns',0),resolved)
    if row['execution_kind'] & 1:
        resume=next((e['timestamp_ns'] for e in ev if e['event']==2),0)
        add('defer queue',row['server_defer_enqueue_ns'],resume)
    field('encode','marshal_enter','marshal_exit')
    field('final TX','tx_enter','tx_exit')
    active={}
    pairs={103:(102,'function setup'),104:(103,'process execution'),
           106:(105,'child submission'),108:(107,'await result'),109:(108,'collect result'),
           3:(2,'fiber active')}
    for e in ev:
        kind=e['event']; stamp=e['timestamp_ns']
        if kind in pairs:
            start,label=pairs[kind]
            if start in active: add(label,active.pop(start),stamp)
            elif kind not in (108,): raise ValueError(f'missing start for {label}')
        active[kind]=stamp
    return result

def tree(root,children):
    seen=set(); result=[]
    def visit(key):
        if key in seen: raise ValueError('cycle in request tree')
        seen.add(key); result.append(key)
        for child in children.get(key,[]): visit(child)
    visit(root); return result

def plot(path,root,client,requests,events,children):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.patches import Patch
    ids=tree(root,children); origin=requests[root]['server_rx_unblock_ns']
    end=requests[root]['server_tx_exit_ns']; duration=(end-origin)/1000
    prelude=min(50,max(5,duration*.025))
    colors={'RX blocked':'#c3cbd4','RX return':'#78a6c8','decode':'#58a5a1',
            'entry / classify':'#bb86c7','defer queue':'#bcbcbc','function setup':'#e4bb69',
            'process execution':'#df805d','child submission':'#749ad1','await result':'#e0e5ea',
            'collect result':'#8aad75','encode':'#48918b','final TX':'#5377a1'}
    fig,ax=plt.subplots(figsize=(13,max(4,1.0*len(ids)+1.8)))
    labels=[]; shown=set()
    for lane,key in enumerate(ids):
        r=requests[key]; ev=events[key]
        cpu=next((e['cpu'] for e in ev if e['event']==103),r['rx_cpu_id'])
        labels.append(('parent' if key==root else 'child')+f' {key}\nCPU {cpu}')
        a=(r['server_rx_unblock_ns']-origin)/1000; b=(r['server_tx_exit_ns']-origin)/1000
        ax.broken_barh([(a,b-a)],(lane-.32,.64),facecolors='#f1f2f4',edgecolor='#a4a4a4',linewidth=.5)
        for name,start,stop in spans(r,ev):
            if name=='fiber active':
                ax.plot([(start-origin)/1000,(stop-origin)/1000],[lane+.38]*2,color='#404040',lw=2)
                continue
            left=(start-origin)/1000; right=(stop-origin)/1000
            if right < -prelude: continue
            if name=='RX blocked' and left < -prelude:
                ax.annotate('',xy=(-prelude,lane),xytext=(-prelude+prelude*.35,lane),
                            arrowprops={'arrowstyle':'->','color':'#7a8591'})
            left=max(left,-prelude)
            ax.broken_barh([(left,max(0,right-left))],(lane-.27,.54),facecolors=colors[name])
            shown.add(name)
    lanes={key:i for i,key in enumerate(ids)}
    for parent in ids:
        for child in children.get(parent,[]):
            sent=next(e for e in events[parent] if e['event']==106 and e['arg0']==child)
            got=next(e for e in events[parent] if e['event']==108 and e['arg0']==child)
            for x0,y0,x1,y1 in [
                (sent['timestamp_ns'],lanes[parent],requests[child]['server_rx_unblock_ns'],lanes[child]),
                (requests[child]['server_tx_exit_ns'],lanes[child],got['timestamp_ns'],lanes[parent])]:
                ax.annotate('',xy=((x1-origin)/1000,y1),xytext=((x0-origin)/1000,y0),
                            arrowprops={'arrowstyle':'->','lw':.7,'color':'#777','alpha':.65})
    ax.set_yticks(range(len(ids)),labels); ax.invert_yaxis()
    ax.set_xlim(-prelude,max(duration,1)*1.025)
    ax.axvline(0,color='#555',lw=.7,ls='--')
    ax.set_xlabel('Time from parent RX unblock (µs; shared DUT MONOTONIC_RAW clock)')
    ax.set_title(f'Actual request nearest median RTT: {client["roundtrip_ns"]/1000:.2f} µs\n'
                 f'DUT RX-unblock → TX-return: {duration:.2f} µs; process spans include launch/wait')
    ax.grid(axis='x',alpha=.2)
    handles=[Patch(color=colors[n],label=n) for n in colors if n in shown]
    handles.append(Patch(color='#f1f2f4',label='other runtime / coordination'))
    ax.legend(handles=handles,ncol=4,loc='upper center',bbox_to_anchor=(.5,-.17),fontsize=8)
    fig.tight_layout(); fig.savefig(path.with_suffix('.svg'),bbox_inches='tight')
    fig.savefig(path.with_suffix('.png'),dpi=180,bbox_inches='tight'); plt.close(fig)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--server',type=Path,required=True); p.add_argument('--events',type=Path,required=True)
    p.add_argument('--client',type=Path,required=True); p.add_argument('--out',type=Path,required=True)
    p.add_argument('--no-plot',action='store_true'); args=p.parse_args()
    requests,events,children=load(args.server,args.events)
    measured=[r for r in rows(args.client) if r['warmup']=='false']
    if not measured: raise ValueError('no measured clients')
    components=defaultdict(list)
    for c in measured:
        root=c['request_id']; ids=tree(root,children)
        if root not in requests: raise ValueError(f'missing root {root}')
        for key in ids:
            for name,a,b in spans(requests[key],events[key]):
                if name!='RX blocked': components[name].append(b-a)
    selected=sorted(measured,key=lambda r:r['roundtrip_ns'])[(len(measured)-1)//2]
    result={'roundtrip_ns':distribution([r['roundtrip_ns'] for r in measured]),
            'components_ns_per_span':{k:distribution(v) for k,v in components.items()},
            'selected_request':selected,'selected_tree':tree(selected['request_id'],children),
            'limitations':['Client and DUT clocks are not directly subtracted.',
                'RX blocked may begin before the visible frame.',
                'Overlapping child spans and fiber-active spans are not additive E2E components.',
                'Process execution includes spawn, mapping, ptrace and wait costs.',
                'MAC/ECI clock correlation is not included in this software-only view.'],
            'inputs':{str(f):hashlib.file_digest(f.open('rb'),'sha256').hexdigest()
                      for f in (args.server,args.events,args.client)}}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.with_suffix('.json').write_text(json.dumps(result,indent=2)+'\n')
    if not args.no_plot: plot(args.out,selected['request_id'],selected,requests,events,children)
    print(json.dumps({'requests':len(measured),'selected':selected['request_id'],'out':str(args.out)}))

if __name__=='__main__': main()
