import json,sys,bisect,statistics
from pathlib import Path
import numpy as np
sys.path.insert(0,'data/eci/apps');import plot_latency as m
from dma_decode import sample_timestamp
b=Path('out/nullrpc-hw-regression');out={}
for label,ap,qp in [('old','out/baseline-latency-516232/bufhotoff1-audit.json',b/'old-hot-anchors.json'),('new','out/nullrpc-inline/hot-off2-audit.json',Path('out/nullrpc-inline/hot-off2-anchors-v2.json'))]:
 a=json.loads(Path(ap).read_text());anchors=json.loads(qp.read_text())['rows'];mp=m.enrich_trace_map(m.load_map(Path(a['provenance']['trace_map'])));raw=np.memmap(a['provenance']['trace_dump'],dtype='<u8',mode='r').reshape(-1,2)
 lo=min(r['trace_rx_cmac_entry_ns'] for r in a['rows'])-1000;hi=max(r['trace_rx_cmac_entry_ns']+r['trace_lh_cmac_to_cmac_ns'] for r in a['rows'])+5000
 sources={int(s['source']) for s in mp['sources'] if s.get('type')=='eci' and s.get('clock_domain')=='app' and s.get('channel') in ('req_wod_i','rsp_wd_o')}
 found={}
 for start in range(0,len(raw),1<<20):
  block=raw[start:start+(1<<20)];stamps=block[:,1]>>18;src=(block[:,1]>>11)&127
  idx=np.flatnonzero(np.isin(src,list(sources)) & (stamps>lo/5-100) & (stamps<hi/5+100))
  for i in idx:
   sample=int(block[i,0])|(int(block[i,1])<<64);source=int(src[i]);info=m.source_info(mp,source);p=m.sample_payload(sample,mp)
   if not m._is_accepted_eci_payload(p,mp):continue
   t=sample_timestamp(sample,mp)*5;addr,v=m._eci_event_values(mp,info,source,t,m._field(p,mp,'eci_header'));name=v.get('opcode_name')
   if name in ('ECI_CMD_MREQ_RLDD','ECI_CMD_MRSP_PEMD'):found.setdefault((name,addr),[]).append(t)
 for times in found.values():times.sort()
 def unique(name,addr,low,high):
  ts=found[(name,addr)];v=ts[bisect.bisect_left(ts,low):bisect.bisect_right(ts,high)]
  assert len(v)==1,(name,addr,low,high,v)
  return v[0]
 rows=[]
 for r in a['rows']:
  q=anchors[str(r['request_id'])];tx=unique('ECI_CMD_MREQ_RLDD',q['tx_address'],q['tx_dcs_request_ns'],q['tx_commit_ns']);rx=unique('ECI_CMD_MRSP_PEMD',q['rx_address'],r['trace_core_delivery_ns'],q['rx_dcs_response_ns']);rsp=unique('ECI_CMD_MRSP_PEMD',q['tx_address'],q['tx_commit_ns'],q['tx_dcs_response_ns'])
  rows.append({'request_id':r['request_id'],'descriptor_to_app_response':rx-r['trace_core_delivery_ns'],'app_to_sys_rx_response':q['rx_dcs_response_ns']-rx,'sys_to_app_tx_request':tx-q['tx_dcs_request_ns'],'app_tx_request_to_commit':q['tx_commit_ns']-tx,'app_to_sys_tx_response':q['tx_dcs_response_ns']-rsp})
 stats={k:dict(median=statistics.median(r[k] for r in rows),mean=statistics.mean(r[k] for r in rows),min=min(r[k] for r in rows),max=max(r[k] for r in rows)) for k in rows[0] if k!='request_id'}
 out[label]={'rows':rows,'stats_ns':stats};print(label,stats,flush=True)
(b/'app-boundaries.json').write_text(json.dumps(out,indent=2)+'\n')
