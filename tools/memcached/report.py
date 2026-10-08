#!/usr/bin/env python3
"""Plot recorded throughput/RTT curves; never synthesize missing experiment cases."""
import argparse
import csv
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import statistics


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('summaries', type=Path, nargs='+')
    p.add_argument('--output', type=Path, required=True)
    a=p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    groups=defaultdict(list); rows=[]; provenance={}
    for path in a.summaries:
        result=json.loads(path.read_text())
        provenance[str(path)]=hashlib.sha256(path.read_bytes()).hexdigest()
        if not result.get('completed'): raise ValueError(f'incomplete run: {path}')
        c=result['case']
        label=tuple(c[k] for k in ('pool_case','get_fraction','key_distribution','value_bytes','traffic'))
        groups[label].append(result)
        for i in result['instances']:
            rows.append(dict(source=str(path),pool_case=c['pool_case'],port=i['port'],
                offered=c['offered_rps_per_instance'],goodput=i['goodput_rps'],
                p50_us=i['rtt_us']['0.5'],p99_us=i['rtt_us']['0.99'],
                errors=i['errors'],timeouts=i['timeouts'],generator_limited=result['generator_limited']))
    with (a.output/'instances.csv').open('w') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    for number,(label,runs) in enumerate(sorted(groups.items())):
        by_rate=defaultdict(list)
        for r in runs: by_rate[r['case']['offered_rps_per_instance']].append(r)
        rates=sorted(by_rate); good=[]; low=[]; high=[]; latency=[]
        for rate in rates:
            values=[r['aggregate_goodput_rps'] for r in by_rate[rate]]
            median=statistics.median(values)
            good.append(median);low.append(median-min(values));high.append(max(values)-median)
            tails=[i['rtt_us']['0.99'] for r in by_rate[rate] for i in r['instances'] if i['rtt_us']['0.99'] is not None]
            latency.append(max(tails) if tails else float('nan'))
        fig,ax=plt.subplots(1,2,figsize=(10,4))
        ax[0].errorbar(rates,good,yerr=[low,high],marker='o',capsize=3)
        ax[0].set(xlabel='Offered requests/s per instance',ylabel='Aggregate goodput/s')
        ax[1].plot(rates,latency,marker='o');ax[1].set(xlabel='Offered requests/s per instance',ylabel='Worst instance p99 RTT (us)')
        for x in ax:x.grid(alpha=.3)
        limited=any(r['generator_limited'] for r in runs)
        fig.suptitle(str(label)+ (' [GENERATOR LIMITED]' if limited else ''))
        fig.tight_layout();fig.savefig(a.output/f'curve-{number}.svg');plt.close(fig)
    (a.output/'provenance.json').write_text(json.dumps(dict(inputs=provenance,groups=len(groups),
        runs=sum(map(len,groups.values())),limits='Error bars show observed min/max; missing repetitions and load points are not inferred. Packet loss requires NIC counters.'),indent=2)+'\n')


if __name__=='__main__':main()
