import csv,json,sys,collections
from pathlib import Path
sys.path.insert(0,'tools/enzian')
from activation_breakdown import distribution
from ipg_breakdown import intervals,partition
b=Path('out/nullrpc-inline'); old=Path('out/nullrpc-inline'); result={}; table=[]
def add(condition,scope,component,values):
 d=distribution(values);table.append(dict(condition=condition,scope=scope,component=component,count=d.pop('count'),**{k+'_us':v/1000 for k,v in d.items()}));return distribution(values)
for label,condition in [('hot-on','hot'),('cold-on','cold')]:
 audit=json.loads((old/(label+'-audit.json')).read_text()); rows=audit['rows'];assert len(rows)==1000
 assert all(r['client_ok']==1 and r['server_ok']==1 for r in rows)
 result[condition]={'roundtrip_ns':add(condition,'raw_roundtrip','roundtrip',[r['e2e_raw_ns'] for r in rows]),'components_ns':{},'workers':dict(collections.Counter(r['rx_cpu_id'] for r in rows))}
 for k in rows[0]['breakdown']:
  result[condition]['components_ns'][k]=add(condition,'additive_e2e',k,[r['breakdown'][k] for r in rows])
 capture=old/(label+'-kernel');meta=json.loads((capture/'capture.json').read_text());assert meta['loss_free'] and meta['returncode']==0
 spans,incomplete=intervals((capture/'trace.txt').read_text())
 with (old/(label+'-server.csv')).open() as f: server=list(csv.DictReader(f))
 byid={int(r['request_id']):r for r in server};diag=[]
 for r in rows:
  s=byid[r['request_id']]; offset=r['trace_cpu_offset_ns']
  queue=r['trace_request_queued_ns']+offset; dispatch=r['trace_request_dispatched_ns']+offset
  end=max(queue,min(float(s['server_rx_enter_ns']),dispatch))
  parts=partition(queue,end,int(s['server_tid']),spans)
  diag.append(dict(request_id=r['request_id'],queue_before_rx_ns=end-queue,kernel_components_ns=parts))
 result[condition]['kernel_queue_before_rx_ns']={k:add(condition,'nested_pre_rx_queue',k,[d['kernel_components_ns'][k] for d in diag]) for k in diag[0]['kernel_components_ns']}
 result[condition]['pre_rx_queue_ns']=add(condition,'nested_pre_rx_queue','total',[d['queue_before_rx_ns'] for d in diag])
 result[condition]['kernel_incomplete_boundary_intervals']=incomplete
 (b/(condition+'-ipg-overlap.json')).write_text(json.dumps(diag,indent=2)+'\n')
 activation=json.loads((old/(label+'-activation.json')).read_text())
 if condition=='cold':
  a=activation['rows'];assert len(a)==1000
  result[condition]['activation_ns']={k:add(condition,'nested_activation',k,[r['activation_components_ns'][k] for r in a if k in r['activation_components_ns']]) for k in a[0]['activation_components_ns']}
result['limitations']=['Kernel IPG intervals use microsecond text timestamps and the FPGA/runtime clock fit; diagnostics, not extra E2E terms.','Incomplete boundary pairs receive no attribution.','A single-thread measured domain, separate four-thread flush domain; not four concurrent measured requests.','Pipeline 518689 has contiguous RX/TX and length-limited TX invalidation; timing remains open.','No traces are exported during each timed request sequence.']
(b/'report.json').write_text(json.dumps(result,indent=2)+'\n')
with (b/'percentiles.csv').open('w') as f:
 w=csv.DictWriter(f,fieldnames=list(table[0]));w.writeheader();w.writerows(table)
print(json.dumps({k:v['roundtrip_ns'] for k,v in result.items() if isinstance(v,dict)},indent=2))
