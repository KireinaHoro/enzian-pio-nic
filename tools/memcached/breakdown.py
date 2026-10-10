#!/usr/bin/env python3
"""Bounded native-UDP request joins using wrapping RX references and TX annotations.

Scope: complete quiet-edge captures, successful response-producing memcached
requests, no asynchronous responses past the worker's next RX. IDs are not unique:
Queue lifetimes, per-core delivery order and software context validate every join.
"""
import argparse
import csv
from collections import defaultdict, deque
import hashlib
import ipaddress
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools/enzian'))
from dandelion_hardware import lh
from dandelion_waterfall import rows, distribution


def require(condition, message):
    if not condition:
        raise ValueError(message)


def one(items, label):
    require(len(items) == 1, f'{label}: expected one candidate, found {len(items)}')
    return items[0]


def hardware_paths(events):
    events = sorted(events, key=lambda e:e['time_ns'])
    by_name = defaultdict(list)
    for e in events:
        by_name[e['event']].append(e)
    # Wire order is preserved between each MAC and its Ethernet stage. This is
    # not a global RX/reply pairing: it includes unrelated Ethernet traffic too.
    mac = {}
    for left, right in [('RxCmacEntry','EthernetDecoder'), ('EthernetEncoder','TxCmacExit')]:
        a, b = by_name[left], by_name[right]
        require(len(a) == len(b), f'incomplete MAC/Ethernet window: {left}/{right}')
        for x,y in zip(a,b):
            require(0 <= y['time_ns']-x['time_ns'] <= 5000, 'noncausal/ambiguous MAC order')
            mac[id(y if left=='RxCmacEntry' else x)] = x if left=='RxCmacEntry' else y
    def predecessor(name, e, match, limit=2000):
        return one([x for x in by_name[name]
            if 0 <= e['time_ns']-x['time_ns'] <= limit
            and all(x['data'].get(k)==v for k,v in match.items())], name)
    pending_dma=defaultdict(deque); pending_pid=defaultdict(deque)
    dispatched={}; active={}; commits={}; submissions=defaultdict(deque)
    result=[]; used_ethernet=set(); used_tx_ethernet=set()
    for e in events:
        n,d,t=e['event'],e['data'],e['time_ns']
        if n=='RxRpcEnqueueToHost':
            require(not d.get('PacketDropped',0), 'RX enqueue dropped')
            native=predecessor('NativeUdpDecoder',e,{'PacketID':d['PacketID']})
            eth=predecessor('EthernetDecoder',native,{'PacketID':d['PacketID']})
            require(id(eth) not in used_ethernet, 'reused RX Ethernet occurrence')
            used_ethernet.add(id(eth))
            r=dict(rx_id=d['HostMsgID'],nic_arrival=mac[id(eth)]['time_ns'],
                   enqueue=t,tx=[])
            pending_dma[d['HostMsgID']].append(r)
        elif n=='SchedulerRequestQueued':
            q=pending_dma[d['HostMsgID']]
            require(bool(q),'scheduler queue without RX enqueue')
            r=q.popleft();r.update(queued=t,pid=d['ProcessID'])
            pending_pid[d['ProcessID']].append(r)
        elif n=='SchedulerRequestDispatched':
            q=pending_pid[d['ProcessID']]
            require(bool(q),'dispatch without queued request')
            r=one([r for r in q if r['rx_id']==d['HostMsgID']], 'live process RX ID')
            q.remove(r) # different workers may complete dispatch out of FIFO order
            core=d['CoreID'];require(core not in dispatched,'overlapping core dispatch')
            r.update(assignment=t,core=core);dispatched[core]=r
        elif n=='EciRxDescSent':
            core=d['CoreID'];require(core in dispatched,'RX without dispatch')
            r=dispatched.pop(core)
            require(r['rx_id']==d['HostMsgID'],'RX descriptor ID mismatch')
            if core in active:
                require(bool(active[core]['tx']),'next RX before any response')
            r['rx_descriptor']=t;active[core]=r;result.append(r)
        elif n=='EciTxCommitRead':
            core=d['CoreID'];require(core not in commits,'overlapping TX commit')
            commits[core]=e
        elif n=='EciTxSubmit':
            core=d['CoreID'];require(core in commits,'TX submit without commit')
            commit=commits.pop(core);require(commit['data']['HostMsgID']==d['HostMsgID'],'TX ID mismatch')
            ref=d.get('RelatedRxTrace',0)
            if not ref & 0x8000: continue # control-only/unrelated TX
            require(ref & ~0x803f == 0,'malformed RX trace reference')
            require(core in active and active[core]['rx_id']==ref&63,'TX does not name active RX')
            r=active[core]
            tx=dict(commit=commit['time_ns'],submit=t,tx_id=d['HostMsgID'],
                    parity=commit['data']['CacheLineIndex'],core=core,related_rx=ref)
            r['tx'].append(tx);submissions[core,d['HostMsgID']].append(tx)
        elif n=='UdpHostPacket':
            q=submissions[d['CoreID'],d['HostMsgID']]
            require(bool(q),'UDP packet without related TX submission')
            tx=q.popleft();tx['udp_encode']=t;tx['packet_id']=d['PacketID']
    require(not dispatched and not commits,'incomplete RX/TX at capture end')
    require(not any(pending_dma.values()) and not any(pending_pid.values()),'undrained request queues')
    require(not any(submissions.values()),'TX has no UDP encoding event')
    for r in result:
        require(bool(r['tx']),'request has no response')
        for tx in r['tx']:
            eth=one([e for e in by_name['EthernetEncoder']
                if e['data']['PacketID']==tx['packet_id'] and 0<=e['time_ns']-tx['udp_encode']<=2000], 'TX Ethernet')
            require(id(eth) not in used_tx_ethernet,'reused TX Ethernet occurrence')
            used_tx_ethernet.add(id(eth));tx['egress']=mac[id(eth)]['time_ns']
        r['mac_to_mac_ns']=max(t['egress'] for t in r['tx'])-r['nic_arrival']
    return result


def software_rows(server_paths, event_paths, cpu_map):
    require(len(server_paths)==len(event_paths),'pair each server CSV with its event CSV')
    result=[]
    for instance,(server,event_path) in enumerate(zip(server_paths,event_paths)):
        for p in (server,event_path):
            require(json.loads(Path(str(p)+'.status.json').read_text())['loss_free'],f'loss in {p}')
        ev=rows(event_path); consumed=set()
        for seq,r in enumerate(sorted(rows(server),key=lambda r:r['server_rx_unblock_ns'])):
            require(r['ok'] and r['request_id_valid'],'failed request or missing wire ID')
            ref=r['request_context'];require(ref&0xffc0==0x8000,'missing RX trace reference')
            lo,hi=r['server_rx_unblock_ns'],r['server_handler_exit_ns']
            matches=[(i,e) for i,e in enumerate(ev) if e['tid']==r['server_tid'] and lo<=e['timestamp_ns']<=hi]
            require(not (consumed & {i for i,_ in matches}),'overlapping worker request intervals')
            consumed.update(i for i,_ in matches)
            packet=one([e for _,e in matches if e['event']==6],'software RX event')
            require(packet['request_context']==ref,'RX event context mismatch')
            request=dict(key=f'{instance}:{seq}',source=str(server),software=r,
                core=cpu_map[r['rx_cpu_id']],rx_id=ref&63,
                port=packet['arg0']&65535,wire_key=(packet['arg0']&65535,
                    r['request_id']>>32,(r['request_id']>>16)&65535,r['request_id']&65535),tx=[])
            pending=None
            for _,e in sorted(matches,key=lambda v:v[1]['timestamp_ns']):
                if e['event'] not in (200,201,202,203):continue
                require(e['request_context']==ref and e['request_id']==r['request_id'],'TX context mismatch')
                if e['event']==203:
                    require(pending is None,'nested preparation')
                    framing=e['arg1']
                    pending=dict(prepare=e['timestamp_ns'],length=e['arg0'],
                        wire_id=framing>>32,sequence=(framing>>16)&65535,segments=framing&65535)
                    require(pending['wire_id']==r['request_id']&65535,'response wire ID mismatch')
                elif e['event']==200:
                    require(pending is not None and 'begin' not in pending,'send without preparation')
                    require(e['arg1']>>16==ref,'TX reference differs from request')
                    require(pending['length']==e['arg0'],'preparation length mismatch')
                    pending.update(begin=e['timestamp_ns'],cpu=e['cpu'])
                elif e['event']==202:
                    require(pending is not None and 'doorbell' not in pending,'orphan/duplicate doorbell')
                    pending.update(doorbell=e['arg0'],end=e['arg1'])
                else:
                    require(pending is not None and 'doorbell' in pending,'incomplete send timestamps')
                    require(e['arg1']==1 and e['arg0']==pending['length'],'rejected/failed UDP send')
                    require(e['cpu']==pending['cpu']==r['rx_cpu_id'],'migration during request')
                    require(pending['end']<=e['timestamp_ns'],'return after completion event')
                    require(pending['prepare']<=pending['begin']<=pending['doorbell']<=pending['end'],'noncausal software TX')
                    request['tx'].append(pending);pending=None
            require(pending is None and request['tx'],'unfinished/response-less request')
            total=len(request['tx'])
            require(all(t['segments']==total and t['sequence']==i for i,t in enumerate(request['tx'])),
                    'missing, duplicated or reordered response segment')
            result.append(request)
        require(all(i in consumed for i,e in enumerate(ev) if e['event'] in (6,200,201,202,203)),
                'unpublished request or out-of-window software event')
    return result


def join_requests(software,hardware):
    result=[]
    cores={r['core'] for r in software}|{r['core'] for r in hardware}
    for core in sorted(cores):
        sw=sorted([r for r in software if r['core']==core],key=lambda r:r['software']['server_rx_unblock_ns'])
        hw=sorted([r for r in hardware if r['core']==core],key=lambda r:r['rx_descriptor'])
        require(len(sw)==len(hw),f'RX count mismatch on core {core}')
        for s,h in zip(sw,hw):
            require(s['rx_id']==h['rx_id'],'RX wrapping ID sequence mismatch')
            require(len(s['tx'])==len(h['tx']),'response segment count mismatch')
            for a,b in zip(s['tx'],h['tx']):
                require(b['related_rx']==0x8000|s['rx_id'],'TX/RX relation mismatch')
                a.update(b)
            s.update(hardware={k:v for k,v in h.items() if k!='tx'})
            result.append(s)
    owners={};instances={}
    for r in result:
        pid=r['hardware']['pid'];source=r['source']
        require(owners.setdefault(pid,source)==source and instances.setdefault(source,pid)==pid,
                'software instance/hardware owner mismatch')
    return result


def join_clients(requests, client_paths):
    groups=defaultdict(list)
    for r in requests: groups[r['wire_key']].append(r)
    client_groups=defaultdict(list)
    for path in client_paths:
        for c in rows(path):
            require(c['status']=='ok','client error/timeout')
            key=(c['port'],int(ipaddress.IPv4Address(c['source_ip'])),c['source_port'],c['request_id'])
            client_groups[key].append(c)
    for key,clients in client_groups.items():
        server=sorted(groups[key],key=lambda r:r['software']['server_rx_unblock_ns'])
        clients.sort(key=lambda c:c['sent_ns'])
        require(len(server)==len(clients),'client/server wire identity count mismatch')
        for a,b in zip(clients,clients[1:]):
            require(a['received_ns']<b['sent_ns'],'ambiguous concurrent reuse of client wire ID')
        for r,c in zip(server,clients):
            r['client']=c
            r['rtt_ns']=c['received_ns']-c['sent_ns']
            r['outside_mac_ns']=r['rtt_ns']-r['hardware']['mac_to_mac_ns']
            require(r['outside_mac_ns']>=0,'RTT shorter than matched MAC interval')
    require(bool(client_groups),'empty client workload')
    # Setup/stats requests may exist in the complete HW/software window. Retain
    # them but report workload distributions only for explicitly joined clients.


def summarize(requests):
    groups=defaultdict(list)
    for r in requests:
        if 'client' not in r:continue
        c=r['client'];label=f"{c.get('pattern','single')}/port-{r['port']}/{c.get('class','all')}/{c.get('operation','request')}"
        groups[label].append(r)
    return {label:{'requests':len(rs),
        'rtt_ns':distribution([r['rtt_ns'] for r in rs]),
        'mac_to_mac_ns':distribution([r['hardware']['mac_to_mac_ns'] for r in rs]),
        'outside_mac_ns':distribution([r['outside_mac_ns'] for r in rs]),
        'scheduler_queue_ns':distribution([r['hardware']['assignment']-r['hardware']['enqueue'] for r in rs]),
        'cpu_rx_to_first_tx_ns':distribution([r['tx'][0]['prepare']-r['software']['server_rx_unblock_ns'] for r in rs]),
        'tx_software_ns':distribution([t['doorbell']-t['prepare'] for r in rs for t in r['tx']]),
        'commit_read_ns':distribution([t['end']-t['doorbell'] for r in rs for t in r['tx']])}
        for label,rs in groups.items()}


def plot(requests,clock,output):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    groups=defaultdict(list)
    for r in requests:
        if 'client' in r:
            c=r['client'];groups[f"{c.get('pattern','single')}, port {r['port']}, {c.get('class','all')}, {c.get('operation','request')}"].append(r)
    for index,(label,rs) in enumerate(groups.items()):
        fig,axes=plt.subplots(1,2,figsize=(13,4))
        for key,title in [('rtt_ns','Client RTT'),('mac_to_mac_ns','MAC-to-MAC')]:
            values=sorted((r['hardware'][key] if key=='mac_to_mac_ns' else r[key])/1000 for r in rs)
            axes[0].step(values,[(i+1)/len(values) for i in range(len(values))],where='post',label=title)
        axes[0].set(xlabel='Latency (µs)',ylabel='Empirical CDF',title=label);axes[0].legend()
        selected=sorted(rs,key=lambda r:r['rtt_ns'])[len(rs)//2]
        spans={**selected['additive_ns'], 'Outside MAC (client/kernel/network)':selected['outside_mac_ns']}
        axes[1].barh(list(spans),[v/1000 for v in spans.values()])
        axes[1].set(xlabel='Duration (µs)',title=f"Actual median-RTT request; clock ±{clock['uncertainty_ns']:.0f} ns")
        fig.tight_layout();fig.savefig(output/f'case-{index:02d}.svg');plt.close(fig)
    with (output/'requests.csv').open('w',newline='') as f:
        records=[dict(key=r['key'],port=r['port'],pattern=r['client'].get('pattern','single'),
            rtt_us=r['rtt_ns']/1000,mac_to_mac_us=r['hardware']['mac_to_mac_ns']/1000,
            outside_mac_us=r['outside_mac_ns']/1000,core=r['core'],pid=r['hardware']['pid'],
            segments=len(r['tx'])) for r in requests if 'client' in r]
        if records:
            w=csv.DictWriter(f,fieldnames=list(records[0]));w.writeheader();w.writerows(records)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('server','events','client'):p.add_argument('--'+name,type=Path,nargs='+',required=True)
    for name in ('trace','map','capture-summary','output'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--address-map',type=Path,default=ROOT/'model/data/selected-8k.json')
    p.add_argument('--cpu-map',default='44:1,45:2,46:3,47:4')
    a=p.parse_args();cpu_map=dict(tuple(map(int,x.split(':'))) for x in a.cpu_map.split(','))
    meta=json.loads(a.capture_summary.read_text())
    digest=hashlib.file_digest(a.trace.open('rb'),'sha256').hexdigest()
    require(meta.get('bounded_window') and meta['sha256']==digest and meta['bytes']==a.trace.stat().st_size,'unverified capture window')
    require(not any(meta[phase][field] for phase in ('start','end') for field in ('trace_wrapped_1','trace_dma_error_1')),'wrapped/failed capture')
    lh.TRACE_EVENT_NAMES.update({'NativeUdpDecoder','UdpHostPacket'})
    lh.decode_relevant_eci_event=lambda *args:None
    hw,_=lh.decode_trace_events(a.trace,a.map,5,None) # scans loss markers too
    requests=join_requests(software_rows(a.server,a.events,cpu_map),hardware_paths(hw))
    join_clients(requests,a.client)
    from clock_alignment import extract_controls, align
    controls=extract_controls(a.trace,a.map,a.address_map,
        min(r['hardware']['nic_arrival'] for r in requests)-2000,
        max(t['egress'] for r in requests for t in r['tx'])+10000)
    clock=align(requests,controls)
    result=dict(requests=requests,clock=clock,distributions=summarize(requests),
        inputs={str(path):hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
            for path in [*a.server,*a.events,*a.client,a.trace,a.map,a.capture_summary,a.address_map]},
        limits=['Complete successful native-UDP window with quiet edges and in-order per-worker replies.',
                '6-bit IDs resolved by stage lifetimes, live process queues and per-core sequences; mismatches fail.',
                'RTT and MAC are durations on separate clocks; outside-MAC includes both network and client overhead.',
                'Additive timelines use DCS-constrained affine clock alignment; report its conditional uncertainty.',
                'Negative stages within uncertainty remain visible; per-segment commit-read spans overlap NIC work.',
                'Entry/sustained are traffic labels, not proven hot/cold worker classifications.'])
    a.output.mkdir(parents=True,exist_ok=False)
    (a.output/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    plot(requests,clock,a.output)
    print(json.dumps(dict(requests=len(requests),measured=sum('client' in r for r in requests))))


if __name__=='__main__':main()
