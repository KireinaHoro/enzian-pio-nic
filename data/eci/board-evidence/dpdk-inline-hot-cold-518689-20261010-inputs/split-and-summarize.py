import csv,json,sys,collections
from pathlib import Path
sys.path.insert(0,'tools/enzian')
from activation_breakdown import distribution
b=Path('out/nullrpc-inline'); old=Path('out/nullrpc-inline')
source=b/'server/server.csv'; status=json.loads(Path(str(source)+'.status.json').read_text())
assert status['loss_free'] and status['dropped']==0
with source.open() as f: reader=csv.DictReader(f); fields=reader.fieldnames; rows=list(reader)
assert len(rows)==status['rows']
phases=[('hot-on',1016),('cold-on',1000),('hot-off2',1016),('cold-off',1000)]
assert len(rows)==sum(n for _,n in phases),(len(rows),sum(n for _,n in phases))
start=0; report={}
for label,count in phases:
 selected=rows[start:start+count];start+=count
 assert [int(r['request_id']) for r in selected]==list(range(1,count+1)),label
 with (old/(label+'-server.csv')).open('w') as f:
  w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(selected)
 with (old/(label+'.csv')).open() as f: client=list(csv.DictReader(f))
 report[label]={'runtime_rows':count,'first_runtime_row':start-count,'past_last_runtime_row':start,'workers':dict(collections.Counter(r['rx_cpu_id'] for r in selected)),'unique_activations':len({r['activation_index'] for r in selected}),'runtime_failed':sum(r['ok']!='1' for r in selected),'client_rows':len(client),'client_fields':list(client[0])}
 if 'cold' in label: assert report[label]['unique_activations']==1000,label
 assert not report[label]['runtime_failed'],label
(b/'phase-rows.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
