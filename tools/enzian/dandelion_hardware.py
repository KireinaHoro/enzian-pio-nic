#!/usr/bin/env python3
"""Join a loss-free measurement window to software packet order and TX bounds.
The affine clock fit is constrained by software TX and RX entry/return intervals,
not by assuming a fixed receive latency. Fails closed on packet-count mismatch.
"""
import argparse
from collections import defaultdict
import importlib.util
import hashlib
import json
from pathlib import Path
import sys
import numpy as np
from scipy.optimize import linprog
import dandelion_waterfall as sw

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('latency',ROOT/'data/eci/apps/plot_latency.py')
lh=importlib.util.module_from_spec(spec);spec.loader.exec_module(lh)

def clock_fit(bounds):
    x0=bounds[0][0]
    base=float(np.median([((lo+hi)//2)-x for x,lo,hi in bounds]))
    xs=np.array([(x-x0)/1e9 for x,_,_ in bounds])
    lo=np.array([(a-x-base)/1000 for x,a,_ in bounds])
    hi=np.array([(b-x-base)/1000 for x,_,b in bounds])
    # Variables: offset correction in us, rate error in ppm, shared slack in us.
    a=np.vstack([np.column_stack([np.ones(len(xs)),xs,-np.ones(len(xs))]),
                 np.column_stack([-np.ones(len(xs)),-xs,-np.ones(len(xs))])])
    b=np.concatenate([hi,-lo])
    fit=linprog([0,0,1],A_ub=a,b_ub=b,bounds=[(None,None),(-1000,1000),(0,None)],method='highs')
    if not fit.success: raise ValueError('no clock fit')
    offset,ppm,slack=fit.x
    if slack>1: raise ValueError(f'clock fit requires {slack*1000:.1f} ns slack; inspect matching/drift')
    # Include two FPGA cycles of timestamp/pipeline quantization.
    slack+=.010
    extrema=[]
    for x in (min(xs),max(xs)):
        for sign in (-1,1):
            r=linprog([sign,sign*x],A_ub=a[:,:2],b_ub=b+slack,
                      bounds=[(None,None),(-1000,1000)],method='highs')
            if not r.success: raise ValueError('unbounded clock interval')
            extrema.append(r.x)
    # Center the feasible extrema; convexity preserves every causal bound.
    offset,ppm=np.mean(extrema,axis=0)
    uncertainty=max(abs(v[0]+v[1]*x-(offset+ppm*x))
                    for v in extrema for x in (min(xs),max(xs)))
    model={'anchor_hw_ns':x0,'base_offset_ns':base,'offset_correction_us':offset,
           'rate_error_ppm':ppm,'slack_ns':slack*1000,'uncertainty_ns':uncertainty*1000,
           'tx_bounds':len(bounds)}
    def convert(x): return x+base+1000*(offset+ppm*(x-x0)/1e9)
    return model,convert

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--server',type=Path,required=True);p.add_argument('--events',type=Path,required=True)
    p.add_argument('--clients',type=Path,nargs='+',required=True)
    p.add_argument('--trace',type=Path,required=True);p.add_argument('--map',type=Path,required=True)
    p.add_argument('--decoded',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    args=p.parse_args()
    requests,ev,children=sw.load(args.server,args.events)
    if any(int(r.get('accounting_version',3))>=4 for r in requests.values()):
        raise ValueError('This archived clock-fit method requires post-publication TX return bounds; version 4 has no such probe')
    roots=[r['request_id'] for path in args.clients for r in sw.rows(path)]
    if len(set(roots))!=len(roots):raise ValueError('duplicate client request IDs')
    selected={key for root in roots for key in sw.tree(root,children)}
    req={key:requests[key] for key in selected}
    tokens={r['request_context']:key for key,r in req.items()}
    if len(tokens)!=len(req):raise ValueError('request context reused within selected window')
    flat=[e for key in selected for e in ev[key]]
    submissions={(e['tid'],e['arg0']):e for e in flat if e['event']==4}
    completed={(e['tid'],e['arg0']):e for e in flat if e['event']==5}
    if submissions.keys()!=completed.keys():raise ValueError('unpaired nested submission')
    # A definite TX-capacity rejection still has a commit-read bracket,
    # but cannot produce a completion packet. Receive-side SYSTEM_ERR replies
    # belong to accepted TX submissions and do remain in this cookie set.
    cookies={e['arg0'] for identity,e in submissions.items()
             if completed[identity]['arg1']==sw.CORE_TX_ACCEPTED}
    swrx=defaultdict(list);swtx=defaultdict(list)
    for e in sw.rows(args.events):
        if e['event']!=6:continue
        call=e['arg0']==3 and e['arg1'] in tokens
        reply=e['arg0']==6 and e['arg1'] in cookies
        if call or reply:swrx[e['cpu']-43].append(e)
    for key,r in req.items():
        swtx[r['tx_cpu_id']-43].append({'start':r['server_tx_enter_ns'],
            'end':r['server_tx_exit_ns'],'request_id':key,'reply':True})
    for identity,e in submissions.items():
        done=completed[identity]
        if done['arg1'] not in (sw.CORE_TX_ACCEPTED, 2):
            raise ValueError('indeterminate submission in measurement')
        swtx[e['cpu']-43].append({'start':e['timestamp_ns'],'end':done['timestamp_ns'],
            'request_id':e['request_id'],'reply':False})
    provenance={str(path):hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
                for path in (args.trace,args.map)}
    cache_meta=Path(str(args.decoded)+'.provenance.json')
    if args.decoded.exists():
        if not cache_meta.exists() or json.loads(cache_meta.read_text())!=provenance:
            raise ValueError('decoded cache provenance mismatch; use a fresh --decoded path')
        hw=json.loads(args.decoded.read_text())
    else:
        # ECI RTT estimation is unnecessary: use observed TX timestamp brackets.
        lh.decode_relevant_eci_event=lambda *a:None
        hw,_=lh.decode_trace_events(args.trace,args.map,5,None)
        args.decoded.write_text(json.dumps(hw))
        cache_meta.write_text(json.dumps(provenance,indent=2))
    index=lh.build_event_index(hw)
    hwrx=defaultdict(list);hwtx=defaultdict(list)
    for e in hw:
        core=e['data'].get('CoreID',0)
        if core not in range(1,5):continue
        if e['event']=='EciRxDescSent':hwrx[core].append(e)
        if e['event']=='EciTxCommitRead':hwtx[core].append(e)
    bounds=[];rxpairs=[];txpairs=[]
    counts={}
    for core in range(1,5):
        a=sorted(swrx[core],key=lambda e:e['timestamp_ns']);b=hwrx[core]
        c=sorted(swtx[core],key=lambda e:e['start']);d=hwtx[core]
        counts[core]={'software_rx':len(a),'hardware_rx':len(b),'software_tx':len(c),'hardware_tx':len(d)}
        if len(a)!=len(b) or len(c)!=len(d):
            raise ValueError(f'core {core} packet-order mismatch: {counts[core]}')
        rxpairs.extend(zip(a,b));txpairs.extend(zip(c,d))
        bounds.extend((h['time_ns'],s['start'],s['end']) for s,h in zip(c,d))
    tx_count=len(bounds)
    for s,h in rxpairs:
        if s['arg0']==3:
            r=req[tokens[s['arg1']]]
            bounds.append((h['time_ns'],r['server_rx_enter_ns'],r['server_rx_unblock_ns']))
    model,convert=clock_fit(bounds)
    model['tx_bounds']=tx_count
    model['rx_bounds']=len(bounds)-tx_count
    result={key:{} for key in selected}
    for s,h in rxpairs:
        delta=s['timestamp_ns']-convert(h['time_ns'])
        if delta < -model['uncertainty_ns']:raise ValueError('RX delivery follows software receipt')
        if s['arg0']!=3:continue
        key=tokens[s['arg1']]; rec=result[key]
        rec['rx_desc_ns']=convert(h['time_ns']);rec['rx_desc_hw_ns']=h['time_ns']
        core=h['data']['CoreID']
        dispatch=lh.last_event_before(index,'SchedulerRequestDispatched',h['time_ns']+1,{'CoreID':core})
        if not dispatch:raise ValueError('missing dispatch')
        rec['dispatch_ns']=convert(dispatch['time_ns'])
        host_id=dispatch['data']['HostMsgID']
        enqueue=lh.last_event_before(index,'RxRpcEnqueueToHost',dispatch['time_ns']+1,{'HostMsgID':host_id})
        if not enqueue:raise ValueError('missing enqueue')
        rec['enqueue_ns']=convert(enqueue['time_ns'])
        if key not in roots:continue
        decoder=lh.last_event_before(index,'OncRpcCallDecoder',enqueue['time_ns']+1,{'RpcID':enqueue['data']['RpcID']})
        if not decoder:raise ValueError('missing root decoder')
        eth=lh.last_event_before(index,'EthernetDecoder',decoder['time_ns']+1,{'PacketID':decoder['data']['PacketID']})
        if not eth:raise ValueError('missing root Ethernet decode')
        mac=lh.unique_nearby_event(index,'RxCmacEntry',eth['time_ns'],radius_ns=1000)
        if not mac:raise ValueError('missing/ambiguous root RX MAC')
        rec['rx_mac_ns']=convert(mac['time_ns']);rec['rx_mac_hw_ns']=mac['time_ns']
    for s,h in txpairs:
        if not s['reply']:continue
        key=s['request_id'];rec=result[key]
        rec['tx_commit_ns']=convert(h['time_ns']);rec['tx_commit_hw_ns']=h['time_ns']
        if key not in roots:continue
        match={k:h['data'][k] for k in ('CoreID','HostMsgID')}
        submit=lh.first_event_after(index,'EciTxSubmit',h['time_ns'],match,max_delta_ns=1_000_000)
        if not submit:raise ValueError('missing TX submit')
        encode=lh.first_event_after(index,'OncRpcReplyEncode',submit['time_ns'],max_delta_ns=100_000)
        if not encode:raise ValueError('missing root reply encode')
        udp=lh.first_event_after(index,'UdpEncoder',encode['time_ns'],{'RpcID':encode['data']['RpcID']},max_delta_ns=100_000)
        if not udp:raise ValueError('missing root UDP encode')
        eth=lh.first_event_after(index,'EthernetEncoder',udp['time_ns'],{'PacketID':udp['data']['PacketID']},max_delta_ns=10_000)
        if not eth:raise ValueError('missing root Ethernet encode')
        # MAC events carry no packet ID; require a unique nearby event.
        mac=lh.unique_nearby_event(index,'TxCmacExit',eth['time_ns'],radius_ns=1000)
        if not mac:raise ValueError('missing root TX MAC')
        rec['tx_mac_ns']=convert(mac['time_ns']);rec['tx_mac_hw_ns']=mac['time_ns']
    args.out.write_text(json.dumps({'clock':model,'counts':counts,'requests':result,'inputs':provenance,
        'software_inputs':{str(path):hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
                           for path in (args.server,args.events,*args.clients)},
        'assumptions':['Decoded window contains no loss markers; exactly one descriptor/commit per software packet.',
                       'Linux CPUs 44–47 map to datapaths 1–4.',
                       'Clock uncertainty is conditional on the validated ordered TX correspondence.']},indent=2)+'\n')
    print(json.dumps({'requests':len(result),'roots':len(roots),'clock':model,'counts':counts}))

if __name__=='__main__':main()
