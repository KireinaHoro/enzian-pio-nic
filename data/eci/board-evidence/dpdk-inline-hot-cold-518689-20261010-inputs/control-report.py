import csv,json,sys,hashlib,collections
from pathlib import Path
sys.path.insert(0,'tools/enzian')
from activation_breakdown import distribution
b=Path('out/nullrpc-inline');p=Path('out/nullrpc-inline')
report=json.loads((b/'report.json').read_text());report['cold_kernel_on']=report.pop('cold');report['hot_kernel_on']=report.pop('hot')
with (b/'percentiles.csv').open() as f:table=list(csv.DictReader(f))
for row in table:row['condition'] += '_kernel_on'
for label,condition in [('hot-off2','hot_kernel_off'),('cold-off','cold_kernel_off')]:
 a=json.loads((p/(label+'-audit.json')).read_text());rows=a['rows'];assert len(rows)==1000 and all(r['client_ok']==1 and r['server_ok']==1 for r in rows)
 with (p/(label+'-server.csv')).open() as f:s=list(csv.DictReader(f))
 byid={int(r['request_id']):r for r in s};identities=set()
 for r in rows:
  sr=byid[r['request_id']];arrival=r['trace_rx_cmac_entry_ns']+r['trace_cpu_offset_ns'];ready=int(sr['server_activation_ready_ns']);d=ready-arrival
  assert abs(d)>1000,(label,r['request_id'],'ambiguous activation boundary')
  assert (d>0)==('cold' in condition),(label,r['request_id'],'condition mismatch')
  assert int(sr['rx_cpu_id'])==r['rx_cpu_id'] and int(sr['activation_worker'])==r['datapath_core']-1
  identities.add(sr['activation_index'])
 assert len(identities)==(1000 if 'cold' in condition else 1)
 r={'roundtrip_ns':distribution([x['e2e_raw_ns'] for x in rows]),'components_ns':{},'workers':dict(collections.Counter(x['rx_cpu_id'] for x in rows)),'unique_activations':len(identities),'condition_validated':True}
 for k in rows[0]['breakdown']:r['components_ns'][k]=distribution([x['breakdown'][k] for x in rows])
 report[condition]=r
 for scope,metrics in [('raw_roundtrip',{'roundtrip':r['roundtrip_ns']}),('additive_e2e',r['components_ns'])]:
  for component,values in metrics.items():
   table.append(dict(condition=condition,scope=scope,component=component,count=values['count'],**{q+'_us':values[q]/1000 for q in ('p50','p90','p95','p99','p100')}))
# Supplemental intervals overlap the additive stack; never sum these with it.
for label,condition in [('hot-on','hot_kernel_on'),('cold-on','cold_kernel_on'),('hot-off2','hot_kernel_off'),('cold-off','cold_kernel_off')]:
 rows=json.loads((p/(label+'-audit.json')).read_text())['rows']
 nested={k:distribution([r[k] for r in rows]) for k in ('client_tx_submit_observed_ns','client_poll_wait_observed_ns','trace_lh_cmac_to_cmac_ns','trace_tx_commit_to_submit_ns','trace_tx_commit_to_cmac_ns')}
 report[condition]['nested_observations_ns']=nested
 for component,values in nested.items():
  table.append(dict(condition=condition,scope='nested_observation',component=component,count=values['count'],**{q+'_us':values[q]/1000 for q in ('p50','p90','p95','p99','p100')}))
 if condition.startswith('hot'):
  with (p/(label+'-server.csv')).open() as f:sr=list(csv.DictReader(f))
  gaps={int(y['request_id']):int(y['server_rx_enter_ns'])-int(x['server_tx_exit_ns']) for x,y in zip(sr,sr[1:])}
  values=distribution([gaps[r['request_id']] for r in rows]);report[condition]['ipg_ns']=values
  table.append(dict(condition=condition,scope='nested_observation',component='tx_exit_to_next_rx_enter',count=values['count'],**{q+'_us':values[q]/1000 for q in ('p50','p90','p95','p99','p100')}))
report['kernel_tracing_comparison']={c:{'median_delta_ns':report[c+'_kernel_on']['roundtrip_ns']['p50']-report[c+'_kernel_off']['roundtrip_ns']['p50'],'scope':'one sequential pair, runtime buffering and FPGA tracing retained; not a per-request correction'} for c in ('hot','cold')}
report['provenance']=json.loads((b/'provenance.json').read_text())
report['provenance']['runtime_export_sha256']=hashlib.sha256((b/'server/server.csv').read_bytes()).hexdigest()
report['export']=json.loads((b/'server/server.csv.status.json').read_text())
report['final_health']=json.loads((b/'final-health.json').read_text())['passed']
report['phase_ranges']=json.loads((b/'phase-rows.json').read_text())
report['artifacts']={}
for label in ('hot-on','cold-on','hot-off2','cold-off'):
 a=json.loads((p/(label+'-audit.json')).read_text())
 report['artifacts'][label]={'audit':str(p/(label+'-audit.json')),'audit_provenance':a['provenance'],'trace':json.loads((p/(label+'-dump/summary.json')).read_text()),'health':json.loads((p/(label+'-assessment.json')).read_text())['passed']}
report['outliers']={}
for label,condition in [('hot-on','hot_kernel_on'),('cold-on','cold_kernel_on'),('hot-off2','hot_kernel_off'),('cold-off','cold_kernel_off')]:
 rows=json.loads((p/(label+'-audit.json')).read_text())['rows']
 worst=max(rows,key=lambda r:r['e2e_raw_ns'])
 report['outliers'][condition]={k:worst[k] for k in ('request_id','e2e_raw_ns','breakdown')}
report['limitations']+=['Kernel on/off comparison is sequential; use kernel-off cohorts for main E2E values.','One measured thread; the separate flush domain has four threads.','External residual combines client NIC/network/host contributions, not independently measured one-way delays.']
report['status']='four-cohorts-correlated-and-condition-validated'
(b/'final-report.json').write_text(json.dumps(report,indent=2)+'\n')
with (b/'final-percentiles.csv').open('w') as f:
 w=csv.DictWriter(f,fieldnames=list(table[0]));w.writeheader();w.writerows(table)
print(json.dumps({c:report[c]['roundtrip_ns'] for c in ('hot_kernel_off','cold_kernel_off','hot_kernel_on','cold_kernel_on')},indent=2))
