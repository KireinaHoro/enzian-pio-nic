#!/usr/bin/env python3
"""Plot validated unloaded echo cohorts; instrumented totals remain separate."""
import argparse
import json
from pathlib import Path
import paper_chart as chart
import numpy as np
from campaign.import_unloaded import validate_matched, correct_cpu_submission
from campaign.import_loaded import digest
from campaign.policy import SIZES


def render(source,output, *, rehearsal=False):
    count,warmup=(100,128) if rehearsal else (10000,128)
    source=Path(source).resolve()
    cohorts=json.loads(source.read_text()); validate_matched(cohorts, rehearsal=rehearsal)
    for cohort in cohorts:
        cohort['files']={str((source.parent/Path(p)).resolve()):sha for p,sha in cohort['files'].items()}
    for c in cohorts:
        correct_cpu_submission(c)
        if c['count']!=count or c['warmup']!=warmup*len(c.get('chunks',[None])): raise ValueError('Not a full unloaded paper cohort')
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
    fig,original=chart.figure(columns=1,height=5.4)
    original.remove()
    axes=fig.subplots(3,1,sharex=False)
    fig.subplots_adjust(left=.25,right=.975,bottom=.09,top=.88,hspace=.85)
    categories=(('handler',),('rpc_receive','rpc_transmit','cpu_other'),
                ('rx_network','tx_network'),('rx_queue','tx_submission'),('cpu_wire',))
    labels=('Handler','RPC runtime','Network stack','CPU--NIC','NIC--wire')
    colors=(*chart.COLORS,'#888888')
    for ax,size in zip(axes,SIZES):
        panel_max=max(float(np.median([r['wire_ns'] for r in c['rows'] if not r['warmup']]))/1000
                      for c in cohorts if c['payload_bytes']==size and c['instrumentation']=='breakdown')
        for i,system in enumerate(('LH','DPDK')):
            c=next(c for c in cohorts if c['system']==system and c['payload_bytes']==size and c['instrumentation']=='breakdown')
            measured=[r for r in c['rows'] if not r['warmup']]
            # As in the original chart, use the central wire-latency observation(s),
            # retaining a conserved decomposition of the median total.
            measured.sort(key=lambda r:(r['wire_ns'],r['request_id']))
            n=len(measured);selected=measured[(n-1)//2:n//2+1]
            bottom=0
            combined={}
            for j,(category,label) in enumerate(zip(categories,labels)):
                mean=float(np.mean([sum(r['components_ns'][k] for k in category) for r in selected]))/1000
                combined[label]=mean
                ax.barh(1-i,mean,left=bottom,height=.45,color=colors[j],label=label if not i else None)
                center=bottom+mean/2
                if mean>=.1*panel_max:
                    ax.text(center,1-i,f'{mean:.3f}',ha='center',va='center',fontsize=7)
                else:
                    dx,align=((-9,'right') if mean==0 else
                              (-4,'center') if i==1 and j==1 else
                              (2,'center') if i==1 and j==2 else (0,'center'))
                    if size==1432 and i==1 and j<3: dx,align=((-10,'center'),(0,'center'),(10,'center'))[j]
                    ax.annotate('0' if mean==0 else f'{mean:.3f}',(center,1-i+.225),
                                xytext=(dx,14 if size==1432 and i==1 and j==3 else 3),textcoords='offset points',ha=align,
                                va='bottom',fontsize=6.5,color=colors[j])
                bottom+=mean
            statistics[str(size)][system]['instrumented_median_us']=bottom
            statistics[str(size)][system]['breakdown_us']=combined
            if not np.isclose(bottom,np.median([r['wire_ns'] for r in measured])/1000):
                raise ValueError('Combined components do not conserve median wire latency')
        ax.set_yticks([1,0],['LH','DPDK'])
        ax.set_ylim(-.55,1.55)
        ax.set_xlim(0,max(statistics[str(size)][system]['instrumented_median_us'] for system in ('LH','DPDK'))*1.05)
        ax.grid(False)
        ax.set_xlabel(str(size)+' B\nWire-to-wire latency ($\\mu$s)')

    fig.legend(*axes[0].get_legend_handles_labels(),loc='upper center',ncol=2,fontsize=7,columnspacing=1.1,handlelength=1.3)
    chart.save(fig,output/'echo-unloaded-hot-latency-breakdown.pdf')
    (output/'echo-unloaded-statistics.json').write_text(json.dumps(statistics,indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,required=True)
    p.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[3]/'paper/plots')
    p.add_argument('--rehearsal',action='store_true',help='Render short non-paper validation cohorts')
    a=p.parse_args();render(a.input,a.output,rehearsal=a.rehearsal)
