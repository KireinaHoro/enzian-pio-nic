import json,statistics,hashlib
from pathlib import Path
b=Path('out/nullrpc-hw-regression')
inputs={'old':('out/baseline-latency-516232/bufhotoff1-audit.json',str(b/'old-hot-anchors.json')),'new':('out/nullrpc-inline/hot-off2-audit.json','out/nullrpc-inline/hot-off2-anchors-v2.json')}
result={}
for name,(audit,anchor) in inputs.items():
 a=json.loads(Path(audit).read_text());anchors=json.loads(Path(anchor).read_text())
 assert anchors['raw_sha256']==a['provenance']['trace_dump_sha256']
 rows=[]
 for r in a['rows']:
  q=anchors['rows'][str(r['request_id'])];rx=r['trace_rx_cmac_entry_ns'];desc=r['trace_core_delivery_ns'];c=q['tx_commit_ns'];done=c+r['breakdown']['lh_tx_2f2f_hw'];submit=c+r['trace_tx_commit_to_submit_ns'];mac=rx+r['trace_lh_cmac_to_cmac_ns']
  segments={'mac_to_descriptor':desc-rx,'descriptor_to_dcs_response':q['rx_dcs_response_ns']-desc,'dcs_rx_response_to_tx_request':q['tx_dcs_request_ns']-q['rx_dcs_response_ns'],'dcs_tx_request_to_commit':c-q['tx_dcs_request_ns'],'commit_to_invalidation_done':done-c,'invalidation_done_to_submit':submit-done,'submit_to_mac':mac-submit}
  assert min(segments.values())>=0
  assert sum(segments.values())==r['trace_lh_cmac_to_cmac_ns']
  rows.append({'request_id':r['request_id'],'segments_ns':segments,'mac_ns':mac-rx,'nested_ns':{'forward_to_ack':q['tx_control_ack_ns']-q['tx_control_forward_ns'],'commit_to_forward':q['tx_control_forward_ns']-c,'ack_to_invalidation_done':done-q['tx_control_ack_ns'],'commit_to_dcs_response':q['tx_dcs_response_ns']-c}})
 def dist(vals):
  v=sorted(vals);return dict(count=len(v),min=v[0],median=statistics.median(v),mean=statistics.mean(v),max=v[-1])
 result[name]={'provenance':a['provenance'],'anchor_sha256':hashlib.sha256(Path(anchor).read_bytes()).hexdigest(),'segments_ns':{k:dist([r['segments_ns'][k] for r in rows]) for k in rows[0]['segments_ns']},'nested_ns':{k:dist([r['nested_ns'][k] for r in rows]) for k in rows[0]['nested_ns']},'mac_ns':dist([r['mac_ns'] for r in rows]),'rows':rows}
for k in result['old']['segments_ns']:
 x=result['old']['segments_ns'][k];y=result['new']['segments_ns'][k];print(k,x['median'],y['median'],'delta',y['median']-x['median'],'mean delta',y['mean']-x['mean'])
for k in result['old']['nested_ns']:print(k,result['old']['nested_ns'][k]['median'],result['new']['nested_ns'][k]['median'])
(b/'comparison.json').write_text(json.dumps(result,indent=2)+'\n')
