#!/usr/bin/env python3
"""Hardware enqueue-to-dispatch delays, classified by observed worker residency."""
import argparse
import json
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--analysis',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--clock-hz',type=int,required=True);a=p.parse_args()
    d=json.loads(a.analysis.read_text());rows=d['queue_latencies'];origin=min(r['timestamp'] for r in rows)
    fig,axes=plt.subplots(2,1,figsize=(12,7),layout='constrained');stats=[]
    for resident,label,color in [(False,'No assigned worker at enqueue (cold)','tab:red'),(True,'Worker assigned at enqueue (resident)','tab:blue')]:
        selected=[r for r in rows if r['resident_at_enqueue']==resident]
        values=np.array([r['cycles']/a.clock_hz*1e6 for r in selected])
        axes[0].scatter([(r['timestamp']-origin)/a.clock_hz for r in selected],values,s=5,alpha=.65,c=color,label=label)
        if len(values):
            axes[1].step(np.sort(values),np.arange(1,len(values)+1)/len(values),c=color,label=label)
            stats.append(dict(resident=resident,n=len(values),**{f'p{v}_us':float(np.percentile(values,v)) for v in [50,90,99,100]}))
    axes[0].set_yscale('log');axes[0].set_ylabel('Enqueue → dispatch (µs)');axes[0].set_xlabel('Seconds from first enqueue (nominal FPGA clock)')
    axes[1].set_xscale('log');axes[1].set_xlabel('Enqueue → dispatch (µs)');axes[1].set_ylabel('Empirical CDF')
    for ax in axes: ax.legend();ax.grid(alpha=.2)
    axes[0].set_title('Every hardware request: scheduler delay and residency at enqueue\nResident does not imply cache-hot; these are not end-to-end RTTs')
    fig.savefig(a.output.with_suffix('.png'),dpi=180);fig.savefig(a.output.with_suffix('.pdf'))
    a.output.with_suffix('.json').write_text(json.dumps(stats,indent=2)+'\n')


if __name__=='__main__': main()
