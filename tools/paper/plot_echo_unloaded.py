#!/usr/bin/env python3
"""Plot validated unloaded echo cohorts; instrumented totals remain separate."""
import argparse
import json
from pathlib import Path
import paper_chart as chart
import numpy as np
from campaign.import_unloaded import validate_matched, correct_cpu_submission
from campaign.import_loaded import digest
from campaign.policy import SIZES, observed_systems


def render(source,output, *, rehearsal=False, sizes=SIZES):
    count,warmup=(100,128) if rehearsal else (10000,128)
    source=Path(source).resolve()
    cohorts=json.loads(source.read_text()); systems=observed_systems(cohorts)
    validate_matched(cohorts, rehearsal=rehearsal, systems=systems, sizes=sizes)
    for cohort in cohorts:
        cohort['files']={str((source.parent/Path(p)).resolve()):sha for p,sha in cohort['files'].items()}
    for c in cohorts:
        correct_cpu_submission(c)
        if c['count']!=count or c['warmup']!=warmup*len(c.get('chunks',[None])): raise ValueError('Not a full unloaded paper cohort')
        if not c['files'] or any(digest(p)!=sha for p,sha in c['files'].items()):
            raise ValueError('Unloaded raw evidence hash mismatch')
    output=Path(output)
    def panels():
        fig,original=chart.figure(columns=1,height=3.5 if len(sizes)>1 else 2.2)
        original.remove()
        axes=fig.subplots(len(sizes),1,sharex=True,sharey=True,squeeze=False)
        fig.subplots_adjust(left=.14,right=.98,bottom=.25 if len(sizes)==1 else .15,top=.88,hspace=.20)
        return fig,axes[:,0]
    statistics={}
    fig,axes=panels()
    for ax,size in zip(axes,sizes):
        statistics[str(size)]={}
        for i,system in enumerate(systems):
            c=next(c for c in cohorts if c['system']==system and c['payload_bytes']==size and c['instrumentation']=='off')
            values=np.sort([r['wire_ns']/1000 for r in c['rows'] if not r['warmup']])
            if len(values)!=count or np.any(values<=0): raise ValueError('Invalid CDF observations')
            ax.step(values,np.arange(1,len(values)+1)/len(values),where='post',label=system,color=chart.COLORS[i])
            statistics[str(size)][system]=dict(p50_us=float(np.percentile(values,50)),p99_us=float(np.percentile(values,99)),max_us=float(values[-1]))
            median,p99=np.percentile(values,[50,99])
            ax.plot(median,.5,'o',color=chart.COLORS[i],markersize=3)
            ax.plot(p99,.99,'D',color=chart.COLORS[i],markersize=3)
            positions = {
                8: {'LH': ((.20,.54),(.22,.70)),
                    'DPDK': ((.32,.20),(.43,.87)),
                    'CS-A64': ((.70,.38),(.70,.84))},
                128: {'LH': ((.43,.38),(.43,.82)),
                      'DPDK': ((.17,.18),(.17,.66)),
                      'CS-A64': ((.70,.38),(.70,.82))},
                1432: {'LH': ((.49,.28),(.49,.80)),
                       'DPDK': ((.29,.18),(.29,.70)),
                       'CS-A64': ((.72,.40),(.72,.84))},
            }
            for label,value,probability,position in zip(
                    ('P50','P99'),(median,p99),(.5,.99),positions[size][system]):
                ax.annotate(f'{label} {value:.3f}',xy=(value,probability),
                            xytext=position,textcoords='axes fraction',
                            ha='center',va='center',fontsize=6,color=chart.COLORS[i],
                            arrowprops=dict(arrowstyle='->',color=chart.COLORS[i],
                                            lw=.65,shrinkA=2,shrinkB=3))

        ax.set_ylim(-.04,1.06)
        ax.set_xscale('log')
        ax.set_yticks([0,.5,1],['0','.5','1'])
        ax.text(.03 if len(systems)==1 else .97,.12,str(size)+' B',transform=ax.transAxes,
                ha='left' if len(systems)==1 else 'right',fontsize=8)
    from matplotlib.ticker import NullFormatter
    ticks=[3,5,10] if len(sizes)==1 else [3,5,10,20,40]
    axes[-1].set_xticks(ticks,list(map(str,ticks)))
    axes[-1].xaxis.set_minor_formatter(NullFormatter())
    axes[len(axes)//2].set_ylabel('CDF',labelpad=2)
    axes[-1].set_xlabel('Wire-to-wire latency ($\\mu$s)')
    fig.legend(*axes[0].get_legend_handles_labels(),loc='upper center',ncol=min(3,len(systems)))
    chart.save(fig,output/'echo-unloaded-hot-latency-cdf.pdf')
    fig,original=chart.figure(columns=1,height=5.4 if len(sizes)>1 else 2.6)
    original.remove()
    axes=fig.subplots(len(sizes),1,sharex=True,squeeze=False)[:,0]
    fig.subplots_adjust(left=.25,right=.975,bottom=.19 if len(sizes)==1 else .09,top=.78 if len(sizes)==1 else .88,hspace=.30)
    categories=(('handler',),('rpc_receive','rpc_transmit'),('cpu_other',),
                ('rx_network','tx_network'),('rx_queue','tx_submission'),('cpu_wire',))
    labels=('Handler','RPC (un)marshal','Runtime/dispatch','Network stack','CPU--NIC','NIC--wire')
    colors=(chart.COLORS[0],chart.COLORS[1],'#E6AB02',chart.COLORS[2],chart.COLORS[3],'#888888')
    for ax,size in zip(axes,sizes):
        panel_max=max(float(np.median([r['wire_ns'] for r in c['rows'] if not r['warmup']]))/1000
                      for c in cohorts if c['payload_bytes']==size and c['instrumentation']=='breakdown')
        for i,system in enumerate(systems):
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
                ax.barh(len(systems)-1-i,mean,left=bottom,height=.45,color=colors[j],label=label if not i else None)
                center=bottom+mean/2
                if mean>=max(1.8,panel_max*.12):
                    ax.text(center,len(systems)-1-i,f'{mean:.3f}',ha='center',va='center',fontsize=7)
                elif mean>=.5:
                    ax.text(center,len(systems)-1-i,f'{mean:.3f}',ha='center',va='center',fontsize=6.5,rotation=90)
                else:
                    below=label=='Network stack'
                    ax.annotate('0' if mean==0 else f'{mean:.3f}',
                                (center,len(systems)-1-i+(-.225 if below else .225)),
                                xytext=(0,-3 if below else 3),textcoords='offset points',ha='center',
                                va='top' if below else 'bottom',fontsize=6.5,color=colors[j])
                bottom+=mean
            statistics[str(size)][system]['instrumented_median_us']=bottom
            statistics[str(size)][system]['breakdown_us']=combined
            if not np.isclose(bottom,np.median([r['wire_ns'] for r in measured])/1000):
                raise ValueError('Combined components do not conserve median wire latency')
        ax.set_yticks(list(reversed(range(len(systems)))),systems)
        ax.set_ylim(-.55,len(systems)-.45)
        ax.grid(False)
        ax.text(.97,.03,str(size)+' B',transform=ax.transAxes,ha='right',fontsize=8)

    axes[-1].set_xlim(0,max(statistics[str(size)][system]['instrumented_median_us'] for size in sizes for system in systems)*1.05)
    axes[-1].set_xlabel('Wire-to-wire latency ($\\mu$s)')
    fig.legend(*axes[0].get_legend_handles_labels(),loc='upper center',ncol=2,fontsize=7,columnspacing=1.1,handlelength=1.3)
    chart.save(fig,output/'echo-unloaded-hot-latency-breakdown.pdf')
    (output/'echo-unloaded-statistics.json').write_text(json.dumps(statistics,indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,required=True)
    p.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[3]/'paper/plots')
    p.add_argument('--rehearsal',action='store_true',help='Render short non-paper validation cohorts')
    p.add_argument('--payload',type=int,choices=SIZES,help='Render one complete payload comparison')
    a=p.parse_args();render(a.input,a.output,rehearsal=a.rehearsal,sizes=(a.payload,) if a.payload else SIZES)
