import csv,json,statistics
from pathlib import Path
stages=[('rx_unpack','server_rx_unblock_ns','server_rx_exit_ns'),('before_unmarshal','server_rx_exit_ns','server_unmarshal_enter_ns'),('unmarshal','server_unmarshal_enter_ns','server_unmarshal_exit_ns'),('before_handler','server_unmarshal_exit_ns','server_handler_enter_ns'),('handler','server_handler_enter_ns','server_handler_exit_ns'),('before_marshal','server_handler_exit_ns','server_marshal_enter_ns'),('marshal','server_marshal_enter_ns','server_marshal_exit_ns'),('before_tx','server_marshal_exit_ns','server_tx_enter_ns')]
out={}
for label,ap in [('old','out/baseline-latency-516232/bufhotoff1-audit.json'),('new','out/nullrpc-inline/hot-off2-audit.json')]:
 a=json.loads(Path(ap).read_text());ids={r['request_id'] for r in a['rows']}
 with open(a['provenance']['server_csv']) as f:rows=[{k:int(v) for k,v in r.items() if v and v.lstrip('-').isdigit()} for r in csv.DictReader(f) if int(r['request_id']) in ids]
 def d(v):return {'mean':statistics.mean(v),'median':statistics.median(v),'min':min(v),'max':max(v)}
 metrics={k:d([r[end]-r[start] for r in rows]) for k,start,end in stages}
 metrics['rx_unblock_to_tx_enter']=d([r['server_tx_enter_ns']-r['server_rx_unblock_ns'] for r in rows]);metrics['tx_call']=d([r['server_tx_exit_ns']-r['server_tx_enter_ns'] for r in rows]);metrics['probe_overhead']=d([r['timestamp_overhead_ns'] for r in rows])
 if label=='new':metrics['tx_prepare_raw']=d([r['server_tx_doorbell_ns']-r['server_tx_enter_ns'] for r in rows])
 assert all(sum(r[e]-r[s] for _,s,e in stages)==r['server_tx_enter_ns']-r['server_rx_unblock_ns'] for r in rows)
 out[label]={'count':len(rows),'metrics':metrics,'provenance':a['provenance']}
for k in out['old']['metrics']:
 x=out['old']['metrics'][k];y=out['new']['metrics'][k];print(k,'old',x['mean'],x['median'],'new',y['mean'],y['median'],'delta',y['mean']-x['mean'])
Path('out/nullrpc-sw-regression/comparison.json').write_text(json.dumps(out,indent=2)+'\n')
