import json,bisect,collections
from pathlib import Path
b=Path('out/nullrpc-inline');out={}
for label in ('hot-on','cold-on','hot-off2','cold-off'):
 a=json.loads((b/(label+'-audit.json')).read_text());rows=a['rows']
 events=json.loads((b/(label+'-dump/trace/latency-events.json')).read_text())['events']
 names=('EciTxCommitRead','EciTxCtrlInvalidated','EciTxSubmit','EciTxDataLciStart','EciTxDataLciaDone')
 index={}
 for e in events:
  if e['event'] in names:index.setdefault(e['data'].get('CoreID'),[]).append(e)
 for core in index:index[core].sort(key=lambda e:e['time_ns'])
 times={c:[e['time_ns'] for e in es] for c,es in index.items()};counts=collections.Counter()
 for r in rows:
  c=r['datapath_core'];lo=r['trace_tx_commit_ns'];hi=r['trace_rx_cmac_entry_ns']+r['trace_lh_cmac_to_cmac_ns']
  es=index[c][bisect.bisect_left(times[c],lo):bisect.bisect_right(times[c],hi)]
  count=collections.Counter(e['event'] for e in es)
  assert all(count[n]==(0 if 'Data' in n else 1) for n in names),(label,r['request_id'],count)
  counts.update(count)
  assert abs(sum(r['trace_segments'].values())-r['trace_lh_cmac_to_cmac_ns'])<1e-6
  assert abs(sum(r['breakdown'].values())-r['e2e_ns'])<1e-6
  assert r['client_ok'] and r['server_ok'] and r['server_tx_doorbell_ns']
  assert r['trace_clock_offset_bounds_ns'][0]<=r['trace_cpu_offset_ns']<=r['trace_clock_offset_bounds_ns'][1]
 x=rows[0]
 out[label]={'measured':len(rows),'events':dict(counts),'clock_offset_ns':x['trace_cpu_offset_ns'],'causal_offset_bounds_ns':x['trace_clock_offset_bounds_ns'],'all_conservation_checks_passed':True,'rx_unblock_min_ns':min(r['server_rx_unblock_ns']-r['trace_cpu_offset_ns']-r['trace_rx_dcs_response_ns'] for r in rows),'tx_return_min_ns':min(r['server_tx_exit_ns']-r['trace_cpu_offset_ns']-r['trace_tx_dcs_response_ns'] for r in rows)}
 print(label,out[label],flush=True)
(b/'inline-verification.json').write_text(json.dumps(out,indent=2)+'\n')
