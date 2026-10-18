#!/usr/bin/env python3
"""Render loaded echo figures from validated raw observations and frozen cohorts.

Rehearsal fixtures must stay in ignored output directories. Active paper figures
are replaced only after a real full campaign and the final visual review.
"""
import argparse
import json
from pathlib import Path
import paper_chart as chart
import numpy as np
from campaign.policy import SYSTEMS, SIZES, WORKERS, aggregate, qualifying
from campaign.import_loaded import load
from campaign.histogram import Latencies


TRIAL_STATS={}


def observations(trials, *, collect=True):
    pooled=Latencies()
    for trial in trials:
        csvs=[p for p in trial['files'] if p.endswith(('.csv','.csv.gz'))]
        summaries=[p for p in trial['files'] if p.endswith('.open.json')]
        if len(csvs)!=1 or len(summaries)!=1: raise ValueError('Raw observation provenance ambiguous')
        checked,values=load(csvs[0],summaries[0],collect_latencies=collect)
        if checked!=trial: raise ValueError('Trial provenance changed')
        trial_stats=dict(checked)
        if collect:
            trial_stats.update(median_ns=(values.percentile(50) if isinstance(values,Latencies) else float(np.percentile(values,50))) if values else None,
                               p99_ns=(values.percentile(99) if isinstance(values,Latencies) else float(np.percentile(values,99))) if values else None)
        TRIAL_STATS.setdefault(summaries[0],{}).update(trial_stats)
        pooled.extend(values)
    return pooled


def panels(columns=3):
    fig,ax=chart.figure(columns=2,height=4.5 if columns==3 else 2.5)
    ax.remove()
    axes=fig.subplots(2 if columns==3 else 1,columns,squeeze=False)
    fig.subplots_adjust(left=.085,right=.98,bottom=.13,top=.95,hspace=.38,wspace=.34)
    return fig,axes


def render(source,destination, *, rehearsal=False):
    TRIAL_STATS.clear()
    workers=(1,4) if rehearsal else WORKERS
    source=Path(source).resolve()
    records=json.loads(source.read_text())
    for record in records:
        for point in record['points']+record.get('confirmations',[])+([record['confirmed']] if 'confirmed' in record else []):
            for trial in point['trials']:
                trial['files']={str((source.parent/Path(p)).resolve()):sha for p,sha in trial['files'].items()}
    expected={(s,n,w) for s in SYSTEMS for n in SIZES for w in workers}
    actual=[(r['system'],r['size'],r['workers']) for r in records]
    if len(actual)!=len(expected) or set(actual)!=expected:
        raise ValueError('Incomplete or duplicate loaded matrix')
    for record in records:
        for point in record['points']+([] if rehearsal else [record['confirmed']]):
            observations(point['trials'],collect=False)
        if not rehearsal and aggregate(record['confirmed']['trials'])['worst_loss']>.01:
            raise ValueError('Confirmed peak exceeds loss threshold')
    destination=Path(destination); destination.mkdir(parents=True,exist_ok=True)
    stats=[]
    for kind in ('latency-goodput','overload-loss'):
        fig,axes=panels()
        for si,system in enumerate(SYSTEMS):
            for ni,size in enumerate(SIZES):
                ax=axes[si,ni]
                for worker in workers:
                    record=next(r for r in records if (r['system'],r['size'],r['workers'])==(system,size,worker))
                    points=qualifying(record['points']) if kind=='latency-goodput' else sorted(record['points'],key=lambda p:p['rate'])
                    aggregates=[aggregate(p['trials']) for p in points]
                    color=chart.COLORS[worker-1]
                    if kind=='latency-goodput':
                        values=[observations(p['trials']) for p in points]
                        if any(not len(v) for v in values): raise ValueError('Empty qualifying latency point')
                        x=[a['goodput']/1000 for a in aggregates]
                        for percentile,style in ((50,'-'),(99,'--')):
                            y=[v.percentile(percentile)/1000 for v in values]
                            ax.plot(x,y,style,color=color,label=f'{worker} workers' if percentile==50 else None)
                            if x: ax.plot(x[-1],y[-1],'o',color=color)
                        stats.append(dict(system=system,size=size,workers=worker,
                            rates=[p['rate'] for p in points],goodput=x,
                            median_us=[v.percentile(50)/1000 for v in values],
                            p99_us=[v.percentile(99)/1000 for v in values]))
                    else:
                        x=[a['offered']/1000 for a in aggregates]
                        # Retain each trial; rate-order connection uses pooled count ratio.
                        y=[100*sum(t['transmitted']-t['on_time'] for t in p['trials'])/sum(t['transmitted'] for t in p['trials']) for p in points]
                        ax.plot(x,y,'o-',color=color,label=f'{worker} workers')
                        for a,p in zip(x,points):
                            ax.plot([a]*len(p['trials']),[100*(1-t['on_time']/t['transmitted']) for t in p['trials']],'.',color=color)
                if kind=='overload-loss': ax.axhline(1,color='.4',linestyle=':',linewidth=.7)
                ax.set_xlabel('Goodput (kRPC/s)' if kind=='latency-goodput' else 'Offered load (kRPC/s)')
                ax.set_ylabel(f'{system}: '+('Client RTT ($\\mu$s)' if kind=='latency-goodput' else 'Loss (\\%)'))
                ax.set_title(f'{size} application bytes',fontsize=9)
                if si==0 and ni==0:
                    if kind=='latency-goodput':
                        ax.plot([],[],color='.25',linestyle='-',label='Median')
                        ax.plot([],[],color='.25',linestyle='--',label='P99')
                    ax.legend(fontsize=6,ncol=2)
        filename='echo-loaded-hot-latency-goodput.pdf' if kind=='latency-goodput' else 'echo-overload-loss.pdf'
        chart.save(fig,destination/filename)
    if rehearsal:
        (destination/'echo-trials.json').write_text(json.dumps(TRIAL_STATS,indent=2)+'\n')
        (destination/'echo-statistics.json').write_text(json.dumps(stats,indent=2)+'\n')
        return
    fig,axes=panels(2)
    for si,system in enumerate(SYSTEMS):
        ax=axes[0,si]
        for ni,size in enumerate(SIZES):
            trials=[]
            for worker in workers:
                r=next(r for r in records if (r['system'],r['size'],r['workers'])==(system,size,worker))
                if any(t['duration_ns']!=60_000_000_000 for t in r['confirmed']['trials']): raise ValueError('Peak requires sustained trials')
                trials.append([t['on_time']/(t['duration_ns']/1e9)/1000 for t in r['confirmed']['trials']])
            values=np.asarray(trials); middle=np.median(values,axis=1)
            ax.errorbar(WORKERS,middle,yerr=[middle-values.min(axis=1),values.max(axis=1)-middle],
                        fmt='o-',color=chart.COLORS[ni],label=f'{size} bytes',capsize=2)
        ax.set_xlabel('Workers'); ax.set_ylabel('Confirmed goodput (kRPC/s)'); ax.set_title(system)
        ax.set_xticks(WORKERS); ax.legend(fontsize=7)
    chart.save(fig,destination/'echo-loaded-hot-peak-throughput-workers.pdf')
    (destination/'echo-statistics.json').write_text(json.dumps(stats,indent=2)+'\n')
    (destination/'echo-trials.json').write_text(json.dumps(TRIAL_STATS,indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,required=True)
    p.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[3]/'paper/plots')
    p.add_argument('--rehearsal',action='store_true',help='Validate and render the smaller non-paper matrix')
    a=p.parse_args(); render(a.input,a.output,rehearsal=a.rehearsal)
