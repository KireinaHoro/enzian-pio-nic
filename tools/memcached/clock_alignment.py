"""DCS control-line anchors for native UDP; CPU clocks never match packets.

Uses the established selected-map decoder and trace source-delay correction.
Only after ID/lifetime joins are complete do CPU brackets constrain clock fit.
"""
from collections import defaultdict
import bisect
import json
from pathlib import Path
import numpy as np
from transport_anchors import latency, sample_timestamp, decode_address
from dandelion_hardware import clock_fit


def extract_controls(raw_path, map_path, address_map, lo, hi, cycle=5):
    config=json.loads(address_map.read_text())[0]['configuration']
    mp=latency.enrich_trace_map(latency.load_map(map_path));cfg=mp['sample']
    if cfg['sample_width']!=128 or cfg['payload_width']!=75:
        raise ValueError('unsupported trace sample envelope')
    sw=cfg['source_width']; source_shift=cfg['payload_width']-64;stamp_shift=source_shift+sw
    sources={int(s['source']) for s in mp['sources'] if s.get('type')=='eci'
        and s.get('clock_domain')=='sys' and s.get('channel') in ('req_wod_i','rsp_wd_o','fwd_wod_o','rsp_wd_i','rsp_wod_i')}
    raw=np.memmap(raw_path,dtype='<u8',mode='r').reshape(-1,2);out=[]
    for start in range(0,len(raw),1<<20):
        block=raw[start:start+(1<<20)];stamp=block[:,1]>>stamp_shift;src=(block[:,1]>>source_shift)&((1<<sw)-1)
        if np.any((src==cfg['lost_source']) & ((block[:,0]&0xffffffff)!=0)):
            raise ValueError('trace loss marker')
        for i in np.flatnonzero(np.isin(src,list(sources)) & (stamp>lo/cycle-100) & (stamp<hi/cycle+100)):
            sample=int(block[i,0])|(int(block[i,1])<<64)
            source=int(src[i]);info=latency.source_info(mp,source);payload=latency.sample_payload(sample,mp)
            if not latency._is_accepted_eci_payload(payload,mp):continue
            t=sample_timestamp(sample,mp)*cycle
            address,v=latency._eci_event_values(mp,info,source,t,latency._field(payload,mp,'eci_header'))
            if address is None or v.get('opcode_name') not in ('ECI_CMD_MREQ_RLDD','ECI_CMD_MRSP_PEMD'):continue
            try:worker,domain,offset=decode_address(config,address)
            except ValueError:continue
            direction=next((d for d in ('rx','tx') if offset in config['directions'][d]['control_offsets_bytes']),None)
            if direction is None:continue
            out.append(dict(time_ns=t,core=worker+1,address=address,domain=domain,
                direction=direction,parity=config['directions'][direction]['control_offsets_bytes'].index(offset),opcode=v['opcode_name']))
    return sorted(out,key=lambda e:e['time_ns'])


def align(requests, controls):
    by_core=defaultdict(list)
    for e in controls:by_core[e['core']].append(e)
    times={c:[e['time_ns'] for e in es] for c,es in by_core.items()}
    def unique(core,lo,hi,**match):
        es=by_core[core];ts=times[core]
        candidates=[e for e in es[bisect.bisect_left(ts,lo):bisect.bisect_right(ts,hi)]
            if all(e.get(k)==v for k,v in match.items())]
        if len(candidates)!=1:raise ValueError(f'DCS {match}: {len(candidates)} candidates in {lo}..{hi}')
        return candidates[0]
    bounds=[];owners={}
    for core in {r['core'] for r in requests}:
        rs=sorted([r for r in requests if r['core']==core],key=lambda r:r['hardware']['rx_descriptor'])
        commits=sorted(t['commit'] for r in rs for t in r['tx'])
        for i,r in enumerate(rs):
            h=r['hardware'];s=r['software'];d=h['rx_descriptor']
            next_rx=rs[i+1]['hardware']['rx_descriptor'] if i+1<len(rs) else d+2000
            rx=unique(core,d,min(d+2000,next_rx),direction='rx',opcode='ECI_CMD_MRSP_PEMD')
            domain=rx['domain'];source=r['source']
            if owners.setdefault(source,domain)!=domain:raise ValueError('instance changed domain within capture')
            h.update(rx_dcs_response=rx['time_ns'],domain=domain)
            bounds.append((rx['time_ns'],s['server_rx_enter_ns'],s['server_rx_unblock_ns']))
            for tx in r['tx']:
                commit=tx['commit'];position=bisect.bisect_right(commits,commit)
                next_commit=commits[position] if position<len(commits) else commit+1000000
                req=unique(core,commit-2000,commit,direction='tx',domain=domain,
                    parity=1-tx['parity'],opcode='ECI_CMD_MREQ_RLDD')
                rsp=unique(core,commit,next_commit,address=req['address'],opcode='ECI_CMD_MRSP_PEMD')
                tx.update(dcs_request=req['time_ns'],dcs_response=rsp['time_ns'])
                bounds.extend([(req['time_ns'],tx['doorbell'],tx['end']),
                               (rsp['time_ns'],tx['doorbell'],tx['end'])])
    model,convert=clock_fit(bounds)
    if model['slack_ns']>25:raise ValueError('clock fit requires more than 25 ns shared slack')
    model['anchors']='DCS RX response, TX request and TX response; CPU entry/return brackets'
    for r in requests:
        h=r['hardware'];s=r['software'];first=r['tx'][0];last=r['tx'][-1]
        points=[('NIC arrival',convert(h['nic_arrival'])),('RX decode/DMA',convert(h['enqueue'])),
            ('scheduler wait',convert(h['assignment'])),('dispatch to descriptor',convert(h['rx_descriptor'])),
            ('descriptor to DCS response',convert(h['rx_dcs_response'])),
            ('RX response to CPU',s['server_rx_unblock_ns']),
            ('runtime/libevent/parser/storage',first['prepare']),
            ('response preparation and earlier segment commits',last['doorbell']),
            ('TX request transit',convert(last['dcs_request'])),('DCS request to commit',convert(last['commit'])),
            ('commit to submission',convert(last['submit'])),('submission to final egress',convert(last['egress']))]
        r['timeline_cpu_ns']=dict(points)
        r['additive_ns']={name:time-points[i-1][1] for i,(name,time) in enumerate(points) if i}
        if any(v < -model['uncertainty_ns'] for v in r['additive_ns'].values()):
            raise ValueError('noncausal stage outside clock uncertainty')
        r['aligned_mac_to_mac_ns']=points[-1][1]-points[0][1]
    return model
