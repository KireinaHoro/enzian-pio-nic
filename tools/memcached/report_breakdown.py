#!/usr/bin/env python3
"""Compare joined native-UDP RTTs with tracing-off controls; retain all samples."""
import argparse
from collections import defaultdict
import csv
import json
from pathlib import Path
import numpy as np


def stats(values):
    a=np.asarray(values,dtype=float)
    return dict(n=len(a),mean=float(a.mean()),**{f'p{p}':float(np.percentile(a,p)) for p in (50,90,95,99,100)})


def report(joined, controls, output):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    source=json.loads(joined.read_text());groups=defaultdict(list)
    for r in source['requests']:
        if 'client' in r:groups[r['client']['case']].append(r)
    control={}
    for path in controls:
        rows=list(csv.DictReader(path.open()))
        if not rows or any(r['status']!='ok' for r in rows):raise ValueError('failed/empty control')
        if path.parent.name in control:raise ValueError('duplicate control case')
        control[path.parent.name]=[float(r['rtt_us']) for r in rows]
    if groups.keys()!=control.keys():raise ValueError('control case mismatch')
    order=sorted(groups,key=lambda k:(int(k.split('-')[0][1:]),k.endswith('batch')))
    labels={k:f"Window {k.split('-')[0][1:]}, {'batched' if k.endswith('batch') else 'individual'} sends" for k in order}
    result={}
    for k in order:
        rs=groups[k];stages=list(rs[0]['additive_ns'])
        result[k]=dict(label=labels[k],requests=len(rs),
            rtt_us=stats([r['rtt_ns']/1000 for r in rs]),control_rtt_us=stats(control[k]),
            mac_us=stats([r['hardware']['mac_to_mac_ns']/1000 for r in rs]),
            outside_mac_us=stats([r['outside_mac_ns']/1000 for r in rs]),
            stage_us={stage:stats([r['additive_ns'][stage]/1000 for r in rs]) for stage in stages},
            cpu_rx_to_prepare_us=stats([(r['tx'][0]['prepare']-r['software']['server_rx_unblock_ns'])/1000 for r in rs]),
            cpu_tx_prepare_us=stats([(t['doorbell']-t['prepare'])/1000 for r in rs for t in r['tx']]),
            cpu_commit_call_us=stats([(t['end']-t['doorbell'])/1000 for r in rs for t in r['tx']]),
            linux_cpus=sorted({r['software']['rx_cpu_id'] for r in rs}),
            hardware_cores=sorted({r['core'] for r in rs}),
            cleared_descriptor_ids=sum(r['hardware']['descriptor_id_cleared'] for r in rs))
    output.mkdir(parents=True,exist_ok=False)
    (output/'summary.json').write_text(json.dumps(dict(cases=result,clock=source['clock'],
        inputs=source['inputs'],limits=source['limits'],recorder_timestamp_point=source.get('recorder_timestamp_point','legacy_batch_queue_dequeue')),indent=2)+'\n')
    colors=plt.get_cmap('tab10').colors
    fig,axes=plt.subplots(1,2,figsize=(12,4.5))
    for i,k in enumerate(order):
        for values,style in [([r['rtt_ns']/1000 for r in groups[k]],'-'),(control[k],'--')]:
            x=np.sort(values);axes[0].step(x,np.arange(1,len(x)+1)/len(x),where='post',color=colors[i],linestyle=style,label=labels[k] if style=='-' else None)
        x=np.sort([r['hardware']['mac_to_mac_ns']/1000 for r in groups[k]])
        axes[1].step(x,np.arange(1,len(x)+1)/len(x),where='post',color=colors[i],label=labels[k])
    axes[0].set(title='Client RTT: solid traced, dashed tracing off',xlabel='RTT (µs)',ylabel='Fraction of requests')
    axes[1].set(title='Recorder-observed MAC-to-MAC (timestamp caveat)' if source.get('recorder_timestamp_point')!='trace_dma_input_before_batch_queue' else 'Matched MAC-to-MAC: traced requests',xlabel='MAC-to-MAC duration (µs)',ylabel='Fraction of requests')
    for ax in axes:ax.grid(alpha=.2);ax.legend(fontsize=8)
    fig.tight_layout()
    for ext in ('svg','png'):fig.savefig(output/f'distributions.{ext}',dpi=170)
    plt.close(fig)
    # Means are additive; independently chosen medians are not.
    fig,axes=plt.subplots(1,2,figsize=(13,5))
    ys=np.arange(len(order));nic=[result[k]['mac_us']['mean'] for k in order]
    axes[0].barh(ys,nic,label='MAC-to-MAC')
    axes[0].barh(ys,[result[k]['outside_mac_us']['mean'] for k in order],left=nic,label='Outside MAC: client/kernel/network',color='#aaaaaa')
    left=np.zeros(len(order))
    for stage in result[order[0]]['stage_us']:
        values=np.array([result[k]['stage_us'][stage]['mean'] for k in order]);axes[1].barh(ys,values,left=left,label=stage);left+=values
    for ax in axes:ax.set_yticks(ys,[labels[k] for k in order]);ax.set_xlabel('Mean duration (µs)');ax.invert_yaxis();ax.legend(fontsize=8,loc='upper center',bbox_to_anchor=(.5,-.12))
    axes[0].set_title('Where client RTT is spent');axes[1].set_title('Recorder-observed stages (timestamp caveat)' if source.get('recorder_timestamp_point')!='trace_dma_input_before_batch_queue' else 'Inside MAC-to-MAC (FPGA-clock stages)')
    fig.tight_layout()
    for ext in ('svg','png'):fig.savefig(output/f'breakdown.{ext}',dpi=170,bbox_inches='tight')
    plt.close(fig)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--joined',type=Path,required=True)
    p.add_argument('--control',type=Path,nargs='+',required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();r=report(a.joined,a.control,a.output)
    print(json.dumps({k:{name:v[name]['p50'] for name in ('rtt_us','control_rtt_us','mac_us','outside_mac_us')} for k,v in r.items()}))
