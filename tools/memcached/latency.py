#!/usr/bin/env python3
"""Correlate a complete, loss-free native UDP trace window; reject ambiguous joins.

Uses the established Dandelion causal clock fit. No client/host clock subtraction.
Requires every server CSV in the capture and an explicit Linux CPU/datapath map.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools/enzian'))
from dandelion_hardware import clock_fit, lh
from dandelion_waterfall import rows, distribution


def analyze(server_paths, event_paths, hw, cpu_map):
    requests={}; rx=defaultdict(list); tx=defaultdict(list)
    for path in (*server_paths,*event_paths):
        if not json.loads(Path(str(path)+'.status.json').read_text())['loss_free']:
            raise ValueError(f'incomplete software trace: {path}')
    for path in server_paths:
        for row in rows(path):
            key=row['request_id']
            if not row['request_id_valid'] or key in requests or not row['ok']:
                raise ValueError('missing/reused request ID or failed request')
            requests[key]=row
            rx[cpu_map[row['rx_cpu_id']]].append(row)
    outstanding={}
    for path in event_paths:
        for e in sorted(rows(path),key=lambda r:r['timestamp_ns']):
            if e['event'] not in (200,201):continue
            identity=(str(path),e['tid'])
            if e['event']==200:
                if identity in outstanding:raise ValueError('nested UDP send')
                outstanding[identity]=e
            else:
                begin=outstanding.pop(identity)
                if begin['request_id']!=e['request_id'] or e['arg1']!=1:
                    raise ValueError('failed or mismatched UDP send')
                if e['request_id'] not in requests:raise ValueError('send without completed request row')
                if begin['cpu']!=e['cpu']:raise ValueError('migration within admitted TX')
                tx[cpu_map[e['cpu']]].append(dict(start=begin['timestamp_ns'],end=e['timestamp_ns'],key=e['request_id']))
    if outstanding:raise ValueError('incomplete UDP send')
    index=lh.build_event_index(hw); hrx=defaultdict(list);htx=defaultdict(list)
    for e in hw:
        core=e['data'].get('CoreID')
        if e['event']=='EciRxDescSent':hrx[core].append(e)
        if e['event']=='EciTxCommitRead':htx[core].append(e)
    bounds=[];rp=[];tp=[];counts={}
    for core in cpu_map.values():
        a=sorted(rx[core],key=lambda r:r['server_rx_unblock_ns']);b=hrx[core]
        c=sorted(tx[core],key=lambda r:r['start']);d=htx[core]
        counts[core]=dict(software_rx=len(a),hardware_rx=len(b),software_tx=len(c),hardware_tx=len(d))
        if len(a)!=len(b) or len(c)!=len(d):raise ValueError(f'packet count mismatch: {counts[core]}')
        rp.extend(zip(a,b));tp.extend(zip(c,d))
        bounds.extend((h['time_ns'],s['start'],s['end']) for s,h in zip(c,d))
        bounds.extend((h['time_ns'],s['server_rx_enter_ns'],s['server_rx_unblock_ns']) for s,h in zip(a,b))
    if not bounds:raise ValueError('empty capture')
    model,convert=clock_fit(bounds);result={key:dict(tx=[]) for key in requests}
    def require(e,name):
        if e is None:raise ValueError(f'missing or ambiguous {name}')
        return e
    for s,h in rp:
        rec=result[s['request_id']]
        dispatch=require(lh.last_event_before(index,'SchedulerRequestDispatched',h['time_ns']+1,{'CoreID':h['data']['CoreID']}),'dispatch')
        enqueue=require(lh.last_event_before(index,'RxRpcEnqueueToHost',dispatch['time_ns']+1,{'HostMsgID':dispatch['data']['HostMsgID']}),'enqueue')
        eth=require(lh.last_event_before(index,'EthernetDecoder',enqueue['time_ns']+1,{'PacketID':enqueue['data']['PacketID']}),'RX Ethernet')
        mac=require(lh.unique_nearby_event(index,'RxCmacEntry',eth['time_ns'],radius_ns=1000),'RX MAC')
        for name,e in [('nic_arrival',mac),('enqueue',enqueue),('assignment',dispatch),('rx_descriptor',h)]:rec[name]=convert(e['time_ns'])
        rec.update(host_receive=s['server_rx_unblock_ns'],execution_begin=s['server_handler_enter_ns'],
                   tid=s.get('server_tid'),activation_index=s.get('activation_index'),
                   activation_worker=s.get('activation_worker'),activation_ready=s.get('server_activation_ready_ns'),
                   execution_end=s['server_handler_exit_ns'],linux_cpu=s['rx_cpu_id'],datapath=h['data']['CoreID'])
    for s,h in tp:
        match={k:h['data'][k] for k in ('CoreID','HostMsgID')}
        submit=require(lh.first_event_after(index,'EciTxSubmit',h['time_ns'],match,max_delta_ns=1_000_000),'TX submit')
        udp=require(lh.first_event_after(index,'UdpHostPacket',submit['time_ns'],match,max_delta_ns=100_000),'native UDP encode')
        eth=require(lh.first_event_after(index,'EthernetEncoder',udp['time_ns'],{'PacketID':udp['data']['PacketID']},max_delta_ns=100_000),'TX Ethernet')
        mac=require(lh.unique_nearby_event(index,'TxCmacExit',eth['time_ns'],radius_ns=1000),'TX MAC')
        result[s['key']]['tx'].append(dict(begin=s['start'],end=s['end'],commit=convert(h['time_ns']),egress=convert(mac['time_ns'])))
    spans=defaultdict(list)
    for rec in result.values():
        stages=[('RX decode','nic_arrival','enqueue'),('scheduler queue','enqueue','assignment'),
                ('assignment to descriptor','assignment','rx_descriptor'),('host receive','rx_descriptor','host_receive')]
        for name,left,right in stages:
            delta=rec[right]-rec[left]
            if delta < -model['uncertainty_ns']:raise ValueError(f'noncausal {name}')
            spans[name].append(delta)
        if not rec['tx']:raise ValueError('bounded breakdown requires response-producing requests')
        spans['execution before first TX'].append(min(t['begin'] for t in rec['tx'])-rec['execution_begin'])
        for t in rec['tx']:
            spans['TX admission'].append(t['end']-t['begin'])
            spans['commit to NIC egress'].append(t['egress']-t['commit'])
    return dict(clock=model,counts=counts,requests=result,
        stages_ns={k:distribution(v) for k,v in spans.items()},
        scheduling_events=[e for e in hw if 'Scheduler' in e['event'] or 'Desched' in e['event']],
        limits=['Exact per-datapath packet-order matching; no missing packets or reused request IDs.',
                'MAC markers must be unique within 1 us; saturated ambiguous captures fail.',
                'Execution span includes TX; TX admission overlaps commit-to-egress. Do not sum overlapping stages.',
                'Clock uncertainty is conditional on validated ordering and causal brackets.'])


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--server',type=Path,nargs='+',required=True)
    p.add_argument('--events',type=Path,nargs='+',required=True)
    p.add_argument('--trace',type=Path,required=True);p.add_argument('--map',type=Path,required=True)
    p.add_argument('--cpu-map',required=True,help='Linux CPU:datapath pairs, e.g. 44:1,45:2,46:3,47:4')
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False)
    cpu_map=dict(tuple(map(int,pair.split(':'))) for pair in a.cpu_map.split(','))
    if len(set(cpu_map.values()))!=len(cpu_map):p.error('datapaths must be unique')
    lh.TRACE_EVENT_NAMES.update({'NativeUdpDecoder','UdpHostPacket'})
    lh.decode_relevant_eci_event=lambda *args:None
    hw,_=lh.decode_trace_events(a.trace,a.map,5,None)
    result=analyze(a.server,a.events,hw,cpu_map)
    result['inputs']={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in (*a.server,*a.events,a.trace,a.map)}
    (a.output/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    names=list(result['stages_ns']);v=result['stages_ns']
    fig,ax=plt.subplots(figsize=(9,5));ax.barh(names,[v[n]['p50']/1000 for n in names])
    ax.set_xlabel('Median stage duration (us); overlapping stages are not additive')
    ax.set_title(f"Clock uncertainty ±{result['clock']['uncertainty_ns']:.1f} ns")
    fig.tight_layout();fig.savefig(a.output/'latency.svg');plt.close(fig)
    print(json.dumps(dict(requests=len(result['requests']),clock=result['clock'])))


if __name__=='__main__':main()
