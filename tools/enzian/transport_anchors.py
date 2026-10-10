#!/usr/bin/env python3
"""Join DCS control responses/requests to an isolated, one-outstanding RPC audit.

The seed audit supplies hardware identities/times, never its fitted CPU epoch.
Only unwrapped 128-bit captures and the supplied bounded address map are supported.
"""
import argparse
import bisect
import hashlib
import json
from pathlib import Path
import sys
import numpy as np
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'data/eci/apps'))
sys.path.insert(0, str(ROOT/'tools/progress'))
import plot_latency as latency
from dma_decode import sample_timestamp
from capacity import decode as decode_address


def unique(events, low, high, predicate, label):
    selected = [e for e in events if low <= e['time_ns'] <= high and predicate(e)]
    if len(selected) != 1:
        raise ValueError(f'{label}: expected one event, found {len(selected)}')
    return selected[0]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('audit','events','trace','map','output'):
        p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--address-map',type=Path,default=ROOT/'model/data/selected-8k.json')
    p.add_argument('--capture-summary', type=Path, help='bounded capture summary (default: trace/../summary.json)')
    a=p.parse_args();audit=json.loads(a.audit.read_text());rows=audit['rows']
    summary_path = a.capture_summary or a.trace.parent.parent/'summary.json'
    summary = json.loads(summary_path.read_text())
    if not summary.get('bounded_window') or any(summary[phase]['trace_wrapped_1'] or summary[phase]['trace_dma_error_1'] for phase in ('start', 'end')):
        raise ValueError('requires bounded, unwrapped, DMA-error-free capture')
    raw_hash=hashlib.file_digest(a.trace.open('rb'),'sha256').hexdigest()
    if summary['sha256'] != raw_hash or summary['bytes'] != a.trace.stat().st_size:
        raise ValueError('capture summary does not match trace')
    map_hash=hashlib.sha256(a.map.read_bytes()).hexdigest()
    if (audit['provenance']['trace_dump_sha256'] != raw_hash or
        audit['provenance']['trace_map_sha256'] != map_hash):
        raise ValueError('seed audit does not match trace/map')
    cache=json.loads(a.events.read_text())
    if cache['key']['raw_sha256'] != raw_hash or cache['key']['map_sha256'] != map_hash:
        raise ValueError('event cache does not match trace/map')
    commits=[e for e in cache['events'] if e['event']=='EciTxCommitRead']
    commit_index={}
    for e in commits:
        d=e['data'];commit_index.setdefault((d['CoreID'],d['HostMsgID']),[]).append(e)
    config=json.loads(a.address_map.read_text())[0]['configuration']
    mp=latency.enrich_trace_map(latency.load_map(a.map));cfg=mp['sample']
    if cfg['sample_width']!=128 or cfg['payload_width']!=75 or cfg['source_width']!=7:
        raise ValueError('unsupported sample envelope')
    cycle=audit['provenance']['trace_cycle_ns']
    lo=min(r['trace_rx_cmac_entry_ns'] for r in rows)-1000
    hi=max(r['trace_rx_cmac_entry_ns']+r['trace_lh_cmac_to_cmac_ns'] for r in rows)+10000
    sources={int(s['source']) for s in mp['sources'] if s.get('type')=='eci'
             and s.get('clock_domain')=='sys' and s.get('channel') in ('req_wod_i','rsp_wd_o','fwd_wod_o','rsp_wd_i','rsp_wod_i')}
    raw=np.memmap(a.trace,dtype='<u8',mode='r').reshape(-1,2)
    by_core={}
    for start in range(0,len(raw),1<<20):
        block=raw[start:start+(1<<20)];stamps=block[:,1]>>18;src=(block[:,1]>>11)&127
        # Full bounded capture is checked for loss, including outside the ROI.
        if np.any((src==cfg['lost_source']) & ((block[:,0]&0xffffffff)!=0)):
            raise ValueError('trace loss marker')
        # Recorder interleaves pipeline-delayed sources; small reordering is normal.
        selected=np.flatnonzero(np.isin(src,list(sources)) & (stamps>lo/cycle-100) & (stamps<hi/cycle+100))
        for i in selected:
            sample=int(block[i,0])|(int(block[i,1])<<64)
            source=int(src[i]);info=latency.source_info(mp,source);payload=latency.sample_payload(sample,mp)
            if not latency._is_accepted_eci_payload(payload,mp):continue
            t=sample_timestamp(sample,mp)*cycle
            address,v=latency._eci_event_values(mp,info,source,t,latency._field(payload,mp,'eci_header'))
            if address is None or v.get('opcode_name') not in ('ECI_CMD_MREQ_RLDD','ECI_CMD_MRSP_PEMD','ECI_CMD_MFWD_FLDX_EH','ECI_CMD_MRSP_HAKD'):continue
            try:worker,domain,offset=decode_address(config,address)
            except ValueError:continue # bypass/unallocated addresses are not RPC workers
            direction=next((d for d in ('rx','tx') if offset in config['directions'][d]['control_offsets_bytes']),None)
            if direction is None:continue
            e=dict(time_ns=t,address=address,domain=domain,direction=direction,opcode=v['opcode_name'])
            by_core.setdefault(worker+1,[]).append(e)
    index={core:(sorted(es,key=lambda e:e['time_ns'])) for core,es in by_core.items()}
    times={core:[e['time_ns'] for e in es] for core,es in index.items()}
    out={}
    for r in rows:
        core=r['datapath_core'];rid=r['request_id'];delivered=r['trace_core_delivery_ns']
        commit=unique(commit_index[(core,r['trace_tx_host_msg_id'])],r['trace_rx_cmac_entry_ns'],r['trace_rx_cmac_entry_ns']+r['trace_lh_cmac_to_cmac_ns'],lambda e:True,f'{rid}: commit')
        ct=commit['time_ns'];es=index[core];ts=times[core]
        near=es[bisect.bisect_left(ts,delivered):bisect.bisect_right(ts,ct+5000)]
        rx=unique(near,delivered,delivered+2000,lambda e:e['direction']=='rx' and e['opcode']=='ECI_CMD_MRSP_PEMD',f'{rid}: RX response')
        offset=config['directions']['tx']['control_offsets_bytes'][1-commit['data']['CacheLineIndex']]
        req=unique(near,ct-2000,ct,lambda e:e['direction']=='tx' and e['domain']==rx['domain'] and e['address']%config['block_stride_bytes']==offset and e['opcode']=='ECI_CMD_MREQ_RLDD',f'{rid}: TX request')
        rsp=unique(near,ct,ct+5000,lambda e:e['address']==req['address'] and e['opcode']=='ECI_CMD_MRSP_PEMD',f'{rid}: TX response')
        forward=unique(near,ct,rsp['time_ns'],lambda e:e['direction']=='tx' and e['domain']==rx['domain'] and e['opcode']=='ECI_CMD_MFWD_FLDX_EH',f'{rid}: TX control forward')
        ack=unique(near,forward['time_ns'],rsp['time_ns'],lambda e:e['address']==forward['address'] and e['opcode']=='ECI_CMD_MRSP_HAKD',f'{rid}: TX control ack')
        out[str(rid)]=dict(tx_control_forward_ns=forward['time_ns'],tx_control_ack_ns=ack['time_ns'],rx_dcs_response_ns=rx['time_ns'],tx_dcs_request_ns=req['time_ns'],tx_dcs_response_ns=rsp['time_ns'],tx_commit_ns=ct,domain=rx['domain'],rx_address=rx['address'],tx_address=req['address'])
    result=dict(rows=out,raw_sha256=raw_hash,map_sha256=map_hash,seed_audit_sha256=hashlib.sha256(a.audit.read_bytes()).hexdigest(),server_sha256=audit['provenance']['server_sha256'],client_sha256=audit['provenance']['client_sha256'],address_map_sha256=hashlib.sha256(a.address_map.read_bytes()).hexdigest(),scope='Unique bounded joins of control-line events; no inferred CPU epoch used for matching.')
    result.update(extractor_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), capture_summary_sha256=hashlib.sha256(summary_path.read_bytes()).hexdigest())
    a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(dict(anchors=len(out),output=str(a.output))))

if __name__=='__main__':main()
