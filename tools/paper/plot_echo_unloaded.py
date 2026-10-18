#!/usr/bin/env python3
"""Plot validated unloaded echo cohorts; instrumented totals remain separate."""
import argparse
import json
from pathlib import Path
import paper_chart as chart
import numpy as np
from campaign.import_unloaded import validate_matched
from campaign.import_loaded import digest
from campaign.policy import SIZES


def render(source,output, *, rehearsal=False):
    count,warmup=(100,128) if rehearsal else (10000,128)
    source=Path(source).resolve()
    cohorts=json.loads(source.read_text()); validate_matched(cohorts, rehearsal=rehearsal)
    for cohort in cohorts:
        cohort['files']={str((source.parent/Path(p)).resolve()):sha for p,sha in cohort['files'].items()}
    for c in cohorts:
        if c['count']!=count or c['warmup']!=warmup: raise ValueError('Not a full unloaded paper cohort')
        if not c['files'] or any(digest(p)!=sha for p,sha in c['files'].items()):
            raise ValueError('Unloaded raw evidence hash mismatch')
    output=Path(output)
    def panels():
        fig,original=chart.figure(columns=2,height=2.5)
        original.remove()
        axes=fig.subplots(1,len(SIZES),sharey=True)
        fig.subplots_adjust(left=.09,right=.985,bottom=.24,top=.84,wspace=.22)
        return fig,axes
    statistics={}
    fig,axes=panels()
    for ax,size in zip(axes,SIZES):
        statistics[str(size)]={}
        for i,system in enumerate(('LH','DPDK')):
            c=next(c for c in cohorts if c['system']==system and c['payload_bytes']==size and c['instrumentation']=='off')
            values=np.sort([r['wire_ns']/1000 for r in c['rows'] if not r['warmup']])
            if len(values)!=count or np.any(values<=0): raise ValueError('Invalid CDF observations')
            ax.step(values,np.arange(1,len(values)+1)/len(values),where='post',label=system,color=chart.COLORS[i])
            statistics[str(size)][system]=dict(p50_us=float(np.percentile(values,50)),p99_us=float(np.percentile(values,99)),max_us=float(values[-1]))
        ax.set_xlabel(str(size)+' B\nWire-to-wire latency ($\\mu$s)')
    axes[0].set_ylabel('CDF')
    fig.legend(*axes[0].get_legend_handles_labels(),loc='upper center',ncol=2)
    chart.save(fig,output/'echo-unloaded-hot-latency-cdf.pdf')
    fig,axes=panels()
    fig.subplots_adjust(top=.68)
    categories=('rx_queue','rx_network','rpc_receive','handler','rpc_transmit','tx_network','tx_submission','cpu_other','cpu_wire')
    labels=('RX queue','RX network','RPC receive','Handler','RPC transmit','TX network','TX submission','Other CPU','CPU–wire residual')
    for ax,size in zip(axes,SIZES):
        for i,system in enumerate(('LH','DPDK')):
            c=next(c for c in cohorts if c['system']==system and c['payload_bytes']==size and c['instrumentation']=='breakdown')
            measured=[r for r in c['rows'] if not r['warmup']]
            bottom=0
            for j,(category,label) in enumerate(zip(categories,labels)):
                mean=float(np.mean([r['components_ns'][category] for r in measured]))/1000
                ax.bar(i,mean,bottom=bottom,color=(chart.COLORS[0],chart.COLORS[1],chart.COLORS[2],chart.COLORS[3],'#77c9b5','#efa96d','#75b2d8','#888888','#cccccc')[j],label=label if not i else None)
                bottom+=mean
            statistics[str(size)][system]['instrumented_mean_us']=bottom
        ax.set_xticks([0,1],['LH','DPDK']);ax.set_xlabel(str(size)+' B')
    axes[0].set_ylabel('Mean elapsed time ($\\mu$s)')
    fig.legend(*axes[0].get_legend_handles_labels(),loc='upper center',ncol=3,fontsize=7)
    chart.save(fig,output/'echo-unloaded-hot-latency-breakdown.pdf')
    (output/'echo-unloaded-statistics.json').write_text(json.dumps(statistics,indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,required=True)
    p.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[3]/'paper/plots')
    p.add_argument('--rehearsal',action='store_true',help='Render short non-paper validation cohorts')
    a=p.parse_args();render(a.input,a.output,rehearsal=a.rehearsal)
