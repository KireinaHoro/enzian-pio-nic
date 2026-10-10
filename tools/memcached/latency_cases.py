#!/usr/bin/env python3
"""Separate assignment state and older requests waiting for dispatch.

This measures scheduler enqueue-to-dispatch delay, not client RTT. No older
request waiting is not proof of an idle worker: earlier work may be executing.
"""
import argparse
from collections import defaultdict
import heapq
import json
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

LABELS = {
    'hot_empty': 'Hot worker; no older request waiting',
    'hot_backlog': 'Hot worker; older requests waiting',
    'cold_empty': 'Cold tenant; no older request waiting',
    'cold_backlog': 'Cold tenant; older requests waiting',
    'scale_up': 'Resident tenant; newly assigned worker',
}


def classify(full, workload):
    wanted = {(r['pid'],r['timestamp'],r['dispatch_timestamp']) for r in workload}
    if len(wanted) != len(workload):
        raise ValueError('ambiguous workload identities')
    waiting = defaultdict(list)
    result = []
    for r in sorted(full, key=lambda r:r['timestamp']):
        pending = waiting[r['pid']]
        while pending and pending[0] <= r['timestamp']:
            heapq.heappop(pending)
        ahead = len(pending)
        heapq.heappush(pending,r['dispatch_timestamp'])
        if (r['pid'],r['timestamp'],r['dispatch_timestamp']) not in wanted:
            continue
        if not r['resident_at_enqueue']:
            case = 'cold_backlog' if ahead else 'cold_empty'
        elif not r['worker_assigned_at_enqueue']:
            case = 'scale_up'
        else:
            case = 'hot_backlog' if ahead else 'hot_empty'
        result.append(dict(r,case=case,older_waiting=ahead))
    if len(result) != len(workload):
        raise ValueError('workload requests missing from full trace')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--full',type=Path,required=True)
    p.add_argument('--workload',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--clock-hz',type=int,required=True)
    a = p.parse_args()
    full=json.loads(a.full.read_text());work=json.loads(a.workload.read_text())
    if not full['passed'] or full['trace_window']['full_capture_loss_markers']:
        p.error('complete loss-free audit required')
    if full['trace_sha256'] != work['trace_sha256'] or a.clock_hz<=0:
        p.error('matching traces and positive clock required')
    rows=classify(full['queue_latencies'],work['queue_latencies'])
    stats=[]
    fig,axes=plt.subplots(1,2,figsize=(12,4.5),layout='constrained')
    for key,label in LABELS.items():
        group=[r for r in rows if r['case']==key]
        values=np.sort([r['cycles']*1e6/a.clock_hz for r in group])
        if not len(values): continue
        stat=dict(case=key,label=label,n=len(values),
                  **{f'p{v}_us':float(np.percentile(values,v)) for v in [50,90,99,99.9,100]})
        stats.append(stat)
        ax=axes[0] if key.startswith('hot') else axes[1]
        ax.step(values,np.arange(1,len(values)+1)/len(values),label=f'{label} (n={len(values):,})')
    for ax,title in zip(axes,['Already-assigned worker','Activation / scale-up required']):
        ax.set_title(title);ax.set_xlabel('Scheduler enqueue → dispatch (µs)')
        ax.set_xscale('log');ax.set_ylim(0,1);ax.set_ylabel('Fraction of requests completed')
        ax.grid(alpha=.2);ax.legend(fontsize=8,loc='upper left')
    fig.suptitle('Backlog = an older request for this tenant still awaiting dispatch\nNo backlog does not prove the worker was idle; these are not request RTTs',fontsize=10)
    fig.savefig(a.output.with_suffix('.png'),dpi=180);fig.savefig(a.output.with_suffix('.pdf'))
    fast=[r for r in rows if r['case']=='hot_empty' and r['cycles']<=3]
    summary=dict(metric='scheduler enqueue-to-dispatch',clock_hz=a.clock_hz,
        scope=__doc__,trace_sha256=full['trace_sha256'],cases=stats,
        hot_empty_2_or_3_cycle_dispatches=len(fast),request_count=len(rows))
    a.output.with_suffix('.json').write_text(json.dumps(summary,indent=2)+'\n')
    a.output.with_name(a.output.name+'-requests.json').write_text(json.dumps(rows)+'\n')
    print(json.dumps(summary,indent=2))


if __name__=='__main__': main()
