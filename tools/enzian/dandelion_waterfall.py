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

CORE_TX_ACCEPTED = 1  # sw/core/eci/core.h

EVENT = {1:'entry_return',2:'fiber_resume',3:'fiber_suspend',4:'call_submit',
         5:'call_submitted',6:'rx_packet',100:'entry',101:'resolved',
         102:'prepare',103:'execute',104:'executed',105:'child_begin',
         106:'child_sent',107:'collect_begin',108:'collected',109:'collect_end',110:'done',111:'child_retry'}

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
        if not r['ok'] or r['reply_status'] or r['transport_status'] != CORE_TX_ACCEPTED:
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
            if not child: raise ValueError('missing child ID')
            if child in owners: raise ValueError(f'duplicate child submission {child}')
            owners[child]=parent
            retry=[r for r in ev if r['event']==111 and r['arg0']==child]
            if retry:
                if len(retry)!=1 or retry[0]['timestamp_ns']<e['timestamp_ns']:
                    raise ValueError(f'ambiguous retry {child}')
                if child in requests or any(r['event']==108 and r['arg0']==child for r in ev):
                    raise ValueError(f'retried child also executed or collected: {child}')
                continue
            if child not in requests: raise ValueError(f'missing child {child}')
            children[parent].append(child)
            collected=[c for c in ev if c['event']==108 and c['arg0']==child]
            if len(collected)!=1: raise ValueError(f'child not collected exactly once: {child}')
    for parent,ev in by_id.items():
        for e in ev:
            if e['event']==111 and owners.get(e['arg0'])!=parent:
                raise ValueError(f'retry without submission: {e["arg0"]}')
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
        add('return to runtime',resolved,row.get('server_entry_exit_ns',0))
        add('fiber admission',row.get('server_entry_exit_ns',0),row['server_defer_enqueue_ns'])
        resume=next((e['timestamp_ns'] for e in ev if e['event']==2),0)
        add('defer queue',row['server_defer_enqueue_ns'],resume)
        first_child=next((e['timestamp_ns'] for e in ev if e['event']==105),0)
        add('fiber preparation',resume,first_child)
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

def plot(path,root,client,requests,events,children,hardware=None,worker_lanes=False):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.patches import Patch
    from matplotlib.lines import Line2D
    ids=tree(root,children); origin=requests[root]['server_rx_unblock_ns']
    hw=hardware['requests'][str(root)] if hardware else None
    if hw: origin=hw['rx_mac_ns']
    end=requests[root]['server_tx_exit_ns']; duration=(end-origin)/1000
    prelude=min(50,max(5,duration*.025))
    rightmost=max(duration,(hw['tx_mac_ns']-origin)/1000) if hw else duration
    colors={'RX blocked':'#c3cbd4','RX return':'#78a6c8','decode':'#58a5a1',
            'entry / classify':'#bb86c7','return to runtime':'#a979a2',
            'fiber admission':'#927fb1','defer queue':'#bcbcbc',
            'fiber preparation':'#c5abd7','function setup':'#e4bb69',
            'process execution':'#df805d','child submission':'#749ad1','await result':'#e0e5ea',
            'collect result':'#8aad75','encode':'#48918b','final TX':'#5377a1'}
    cpu_by_id={key:next((e['cpu'] for e in events[key] if e['event']==103),requests[key]['rx_cpu_id']) for key in ids}
    cpus=sorted({cpu_by_id[key] for key in ids})
    lanes=({root:0,**{key:cpus.index(cpu_by_id[key])+1 for key in ids if key!=root}}
           if worker_lanes else {key:i for i,key in enumerate(ids)})
    labels=([f'parent fiber {root}\nCPU {cpu_by_id[root]}']+[f'children on CPU {cpu}' for cpu in cpus]
            if worker_lanes else [])
    lane_count=len(cpus)+1 if worker_lanes else len(ids)
    fig,ax=plt.subplots(figsize=(15 if worker_lanes else 13,max(4,1.0*lane_count+1.8)))
    shown=set()
    for key in ids:
        lane=lanes[key]
        r=requests[key]; ev=events[key]
        cpu=next((e['cpu'] for e in ev if e['event']==103),r['rx_cpu_id'])
        if not worker_lanes:labels.append(('parent' if key==root else 'child')+f' {key}\nCPU {cpu}')
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
            if worker_lanes and name=='process execution' and key!=root:
                ax.text((left+right)/2,lane,f'C{ids.index(key)}',ha='center',va='center',fontsize=6,clip_on=True)
    for parent in ids:
        for child in children.get(parent,[]):
            launched=next(e for e in events[parent] if e['event']==105 and e['arg0']==child)
            got=next(e for e in events[parent] if e['event']==108 and e['arg0']==child)
            for x0,y0,x1,y1 in [
                (launched['timestamp_ns'],lanes[parent],requests[child]['server_rx_unblock_ns'],lanes[child]),
                (requests[child]['server_tx_enter_ns'],lanes[child],got['timestamp_ns'],lanes[parent])]:
                ax.annotate('',xy=((x1-origin)/1000,y1),xytext=((x0-origin)/1000,y0),
                            arrowprops={'arrowstyle':'->','lw':.7,'color':'#777','alpha':.13 if worker_lanes else .65})
    ticks=list(range(lane_count))
    hardware_handles=[]
    if hw:
        ticks.insert(0,-1);labels.insert(0,'FPGA / ECI')
        for name,a,b,color in [
            ('MAC → enqueue','rx_mac_ns','enqueue_ns','#58a5a1'),
            ('queue → dispatch','enqueue_ns','dispatch_ns','#bb86c7'),
            ('dispatch → descriptor','dispatch_ns','rx_desc_ns','#78a6c8'),
            ('TX commit → MAC','tx_commit_ns','tx_mac_ns','#5377a1')]:
            hardware_handles.append(Patch(color=color,label='FPGA: '+name))
            if hw[b]<hw[a]:raise ValueError(f'reversed hardware span {name}')
            ax.broken_barh([((hw[a]-origin)/1000,(hw[b]-hw[a])/1000)],(-1.27,.54),facecolors=color)
    ax.set_yticks(ticks,labels); ax.invert_yaxis()
    ax.set_xlim(-prelude,max(rightmost,1)*1.025)
    ax.axvline(0,color='#555',lw=.7,ls='--')
    anchor='RX MAC arrival' if hw else 'parent RX unblock'
    ax.set_xlabel(f'Time from {anchor} (µs; process spans include launch/wait)')
    title=f'Actual request nearest median RTT: {client["roundtrip_ns"]/1000:.2f} µs'
    if hw:
        title+=f'\nMAC → MAC: {(hw["tx_mac_hw_ns"]-hw["rx_mac_hw_ns"])/1000:.2f} µs; clock alignment ±{hardware["clock"]["uncertainty_ns"]:.0f} ns'
    else:title+=f'\nDUT RX-unblock → TX-return: {duration:.2f} µs'
    ax.set_title(title)
    ax.grid(axis='x',alpha=.2)
    handles=[Patch(color=colors[n],label=n) for n in colors if n in shown]
    handles.append(Patch(color='#f1f2f4',label='other runtime / coordination'))
    handles.append(Line2D([0],[0],color="#404040",lw=2,label="fiber running"))
    handles.extend(hardware_handles)
    ax.legend(handles=handles,ncol=4,loc='upper center',bbox_to_anchor=(.5,-.17),fontsize=8)
    fig.tight_layout(); fig.savefig(path.with_suffix('.svg'),bbox_inches='tight')
    fig.savefig(path.with_suffix('.png'),dpi=180,bbox_inches='tight')
    views=[('launch',(-5,160)),('return',(rightmost-100,rightmost+5))]
    first_collection=next((e['timestamp_ns'] for e in events[root] if e['event']==108),None)
    if first_collection is not None:
        x=(first_collection-origin)/1000
        views.append(('collection',(x-30,x+200)))
    for name,limits in views:
        ax.set_xlim(*limits);ax.set_title(title+'\n'+name.capitalize()+' detail')
        for extension in ('svg','png'):
            fig.savefig(path.parent/(path.name+'-'+name+'.'+extension),dpi=180,bbox_inches='tight')
    plt.close(fig)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--server',type=Path,required=True); p.add_argument('--events',type=Path,required=True)
    p.add_argument('--client',type=Path,required=True); p.add_argument('--out',type=Path,required=True)
    p.add_argument('--hardware',type=Path,help='validated dandelion_hardware.py output')
    p.add_argument('--worker-lanes',action='store_true',help='Group children by execution CPU, keeping parent fiber separate')
    p.add_argument('--no-plot',action='store_true'); args=p.parse_args()
    requests,events,children=load(args.server,args.events)
    measured=[r for r in rows(args.client) if r['warmup']=='false']
    if not measured: raise ValueError('no measured clients')
    components=defaultdict(list)
    peaks=[]
    for c in measured:
        root=c['request_id']; ids=tree(root,children)
        if root not in requests: raise ValueError(f'missing root {root}')
        edges=[]
        for key in ids:
            for name,a,b in spans(requests[key],events[key]):
                if name!='RX blocked': components[name].append(b-a)
                if key!=root and name=='process execution':edges.extend([(a,1),(b,-1)])
        concurrent=peak=0
        for _,delta in sorted(edges):
            concurrent+=delta;peak=max(peak,concurrent)
        peaks.append(peak)
    selected=sorted(measured,key=lambda r:r['roundtrip_ns'])[(len(measured)-1)//2]
    result={'roundtrip_ns':distribution([r['roundtrip_ns'] for r in measured]),
            'child_execution_peak':distribution(peaks),
            'components_ns_per_span':{k:distribution(v) for k,v in components.items()},
            'selected_request':selected,'selected_tree':tree(selected['request_id'],children),
            'selected_spans':{key:{'rx_cpu':requests[key]['rx_cpu_id'],
                                  'spans':[{'name':n,'start_ns':a,'end_ns':b}
                                           for n,a,b in spans(requests[key],events[key])]}
                              for key in tree(selected['request_id'],children)},
            'limitations':['Client and DUT clocks are not directly subtracted.',
                'RX blocked may begin before the visible frame.',
                'Overlapping child spans and fiber-active spans are not additive E2E components.',
                'Process execution includes spawn, mapping, ptrace and wait costs.',
                'MAC/ECI clock correlation is not included in this software-only view.'],
            'inputs':{str(f):hashlib.file_digest(f.open('rb'),'sha256').hexdigest()
                      for f in (args.server,args.events,args.client)}}
    hardware=json.loads(args.hardware.read_text()) if args.hardware else None
    if hardware:
        for name,digest in result['inputs'].items():
            if hardware.get('software_inputs',{}).get(name)!=digest:
                raise ValueError(f'hardware/software provenance mismatch: {name}')
        result['hardware_clock']=hardware['clock']
        result['selected_hardware']={str(key):hardware['requests'][str(key)]
                                     for key in result['selected_tree']}
        result['limitations'].remove('MAC/ECI clock correlation is not included in this software-only view.')
        result['limitations'].extend(hardware['assumptions'])
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.with_suffix('.json').write_text(json.dumps(result,indent=2)+'\n')
    if not args.no_plot: plot(args.out,selected['request_id'],selected,requests,events,children,hardware,args.worker_lanes)
    print(json.dumps({'requests':len(measured),'selected':selected['request_id'],'out':str(args.out)}))

if __name__=='__main__': main()
