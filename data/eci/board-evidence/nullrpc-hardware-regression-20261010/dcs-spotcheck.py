import json,sys
from pathlib import Path
import numpy as np
sys.path.insert(0,'data/eci/apps')
import plot_latency as m
from dma_decode import sample_timestamp
b=Path('out/nullrpc-hw-regression')
for label,audit,target in [('old','out/baseline-latency-516232/bufhotoff1-audit.json',3700),('new','out/nullrpc-inline/hot-off2-audit.json',4165)]:
 a=json.loads(Path(audit).read_text());row=min(a['rows'],key=lambda r:(abs(r['trace_lh_cmac_to_cmac_ns']-target),r['request_id']))
 mp=m.enrich_trace_map(m.load_map(Path(a['provenance']['trace_map'])));zero=row['trace_rx_cmac_entry_ns'];raw=Path(a['provenance']['trace_dump']);data=np.memmap(raw,dtype='<u8',mode='r').reshape(-1,2)
 stamps=data[:,1]>>82-64;idx=np.flatnonzero((stamps>zero/5-10)&(stamps<(zero+row['trace_lh_cmac_to_cmac_ns']+600)/5));decode=m.event_decoder(mp);events=[]
 for i in idx:
  sample=int(data[i,0])|(int(data[i,1])<<64);src=m.sample_source(sample,mp);info=m.source_info(mp,src);p=m.sample_payload(sample,mp);t=sample_timestamp(sample,mp)*5;e=None
  if m._is_sys_eci_source(info) and m._is_accepted_eci_payload(p,mp):
   addr,v=m._eci_event_values(mp,info,src,t,m._field(p,mp,'eci_header'));e=dict(time_ns=t,delta_ns=t-zero,source=info.get('name'),address=addr,data=v)
  elif info.get('type')=='dcs_event':
   fields=mp['payload_formats']['dcs_event']['fields'];v={}
   for key,field in fields.items():
    value=(p>>field['offset'])&((1<<field['width'])-1);v[key]=field.get('enum',{}).get(str(value),value)
   e=dict(time_ns=t,delta_ns=t-zero,event='DCS',data=v,source=info)
  elif info.get('type')=='lauberhorn_event':
   name,v=decode(p)
   if v.get('CoreID')==row['datapath_core'] or name.startswith('Tx') or name.endswith('Encoder'):e=dict(time_ns=t,delta_ns=t-zero,event=name,data=v)
  if e:events.append(e)
 events.sort(key=lambda e:e['time_ns']);(b/(label+'-dcs-spotcheck.json')).write_text(json.dumps(dict(request_id=row['request_id'],row=row,events=events),indent=2)+'\n')
 print(label,'request',row['request_id'])
 for e in events:
  name=e.get('event',e['data'].get('opcode_name'))
  if name=='DCS' and (2300<e['delta_ns']<2600 if label=='old' else 2750<e['delta_ns']<3050):print(e['delta_ns'],name,e.get('address'),e.get('source'),e['data'])
