#!/usr/bin/env python3
"""Validate archived CX5 readiness observations without inventing wire totals."""
import argparse
import csv
import json
from pathlib import Path
from campaign.import_unloaded import dpdk
from campaign.import_loaded import digest, load
from campaign.policy import validate_activity


def validate(root):
    root=Path(root).resolve()
    summary=json.loads((root/'summary.json').read_text())
    expected={(size,mode,workers) for size in (8,128,1432) for mode,workers in
              (('off',1),('breakdown',1),('loaded',1),('loaded',4))}
    actual={(r['size'],r['mode'],r['workers']) for r in summary['results']}
    if actual!=expected or len(summary['results'])!=len(expected):raise ValueError('Incomplete readiness matrix')
    hashes=json.loads((root/'files.json').read_text())
    for name,sha in hashes.items():
        p=Path(name)
        if p.is_absolute() or '..' in p.parts or digest(root/p)!=sha:raise ValueError('Readiness evidence hash mismatch')
    output=[]
    for trial in summary['results']:
        if not trial['passed']:raise ValueError('Failed readiness case')
        case=root/trial['case']
        before=json.loads((case/'server-runner/before.json').read_text())
        if before['driver']!='mlx5_core' or before['pci']!='0005:90:00.0' or before['timestamp_provenance'] is not None:
            raise ValueError('Wrong baseline NIC or timestamping')
        for role in ('client','server'):
            runner=json.loads((case/(role+'-runner')/'summary.json').read_text())
            if runner['status']!='pass' or runner['restored'] is not True:raise ValueError('Failed NIC restoration')
        if trial['mode']=='loaded':
            data,_=load(case/'client.csv',case/'client.csv.open.json',collect_latencies=False)
            with (case/'server.csv').open() as f:workers=list(csv.DictReader(f))
            activity=[int(r['measured']) for r in workers]
            validate_activity(activity,trial['workers'])
            if any(n<=0 for n in activity) or data['on_time']==0:raise ValueError('Unexercised worker or no valid replies')
            output.append(dict(case=trial['case'],trial=data,worker_activity=activity))
        else:
            cohort=dpdk(case/'client.csv',case/'server.csv',count=256,warmup=16,
                        instrumentation=trial['mode'],payload_bytes=trial['size'])
            if cohort['paper_inputs'] or cohort['wire_available']:raise ValueError('Readiness promoted to paper input')
            output.append(dict(case=trial['case'],cohort=cohort))
    result=dict(passed=True,wire_available=False,paper_inputs=False,capacity_measurement=False,
                cases=output,files={str(root/name):sha for name,sha in hashes.items()})
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('root',type=Path);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();result=validate(a.root)
    with a.output.open('x') as f:json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps(dict(passed=True,cases=len(result['cases']),wire_available=False)))
