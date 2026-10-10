#!/usr/bin/env python3
"""Plot every diagnostic RTT, with traffic-defined entry/sustained labels."""
import argparse
import csv
import json
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def plot(source, output):
    summary=json.loads((source/'summary.json').read_text())
    rows=list(csv.DictReader((source/'requests.csv').open()))
    good=[r for r in rows if r['status']=='ok']
    ports=summary['ports']; origin=summary['origin_monotonic_ns']
    fig,axes=plt.subplots(len(ports),1,figsize=(13,10),sharex=True,layout='constrained')
    stats=[]
    for ax,port in zip(axes,ports):
        for label,color in [('entry','tab:red'),('sustained','tab:blue')]:
            selected=[r for r in good if int(r['port'])==port and r['class']==label]
            ax.scatter([(int(r['sent_ns'])-origin)/1e9 for r in selected],
                       [float(r['rtt_us']) for r in selected],s=4,alpha=.65,label=label,color=color)
        for phase in summary['phases']:
            ax.axvline((phase['start_ns']-origin)/1e9,color='gray',alpha=.2,lw=.5)
        ax.set_yscale('log'); ax.set_ylabel(f'{port}\nRTT (µs)'); ax.grid(alpha=.15)
    for phase in summary['phases']:
        x=(phase['start_ns']-origin)/1e9
        label=phase['pattern']+':'+','.join(str(ports.index(p)) for p in phase['active_ports'])
        axes[0].text(x,1.02,label,rotation=90,fontsize=6,transform=axes[0].get_xaxis_transform(),va='bottom')
    axes[0].legend(loc='upper right'); axes[-1].set_xlabel('Client monotonic seconds from workload start')
    axes[0].set_title('Every request RTT: five tenants × four declared workers on four physical workers\nEntry = first in-flight window; traffic labels do not establish hardware residency',pad=65)
    fig.savefig(output.with_suffix('.png'),dpi=180);fig.savefig(output.with_suffix('.pdf'));plt.close(fig)
    fig,axes=plt.subplots(1,3,figsize=(13,4),layout='constrained')
    for ax,pattern in zip(axes,['single','pair','all']):
        for label,color in [('entry','tab:red'),('sustained','tab:blue')]:
            values=np.sort([float(r['rtt_us']) for r in good if r['pattern']==pattern and r['class']==label])
            if len(values):
                ax.step(values,np.arange(1,len(values)+1)/len(values),color=color,label=label)
                stats.append(dict(pattern=pattern,classification=label,n=len(values),
                                  **{f'p{p}_us':float(np.percentile(values,p)) for p in [50,90,99,100]}))
        ax.set_xscale('log');ax.set_title(pattern);ax.set_xlabel('RTT (µs)');ax.grid(alpha=.2);ax.legend()
    axes[0].set_ylabel('Empirical CDF')
    fig.savefig(output.parent/(output.name+'-cdf.png'),dpi=180)
    fig.savefig(output.parent/(output.name+'-cdf.pdf'));plt.close(fig)
    per_phase=[]
    for phase in summary['phases']:
        for port in phase['active_ports']:
            values=[float(r['rtt_us']) for r in good if int(r['phase'])==phase['id'] and int(r['port'])==port]
            if values:
                per_phase.append(dict(phase=phase['id'],pattern=phase['pattern'],port=port,n=len(values),
                    **{f'p{p}_us':float(np.percentile(values,p)) for p in [50,90,99,100]}))
    (output.parent/(output.name+'-summary.json')).write_text(json.dumps(dict(passed=summary['passed'],
        samples=len(rows),failed_samples=len(rows)-len(good),groups=stats,per_phase=per_phase),indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args();plot(a.source,a.output)
