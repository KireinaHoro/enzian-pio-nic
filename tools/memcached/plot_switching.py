#!/usr/bin/env python3
"""Plot recorded assignments, not CPU utilization or request latency."""
import argparse
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Patch


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--analysis', type=Path, required=True)
    p.add_argument('--launch', type=Path, required=True)
    p.add_argument('--clock-hz', type=int, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.clock_hz <= 0:
        p.error('positive nominal trace clock required')
    result = json.loads(a.analysis.read_text())
    launch = json.loads(a.launch.read_text())
    if not result['passed']:
        p.error('passing switching analysis required')
    assignments = result['assignments']
    start = min(r['timestamp'] for r in assignments)
    end = (max(r['timestamp'] for r in assignments)-start)/a.clock_hz
    end += max(0.001, end*0.005)
    colors = {r['pid']:plt.get_cmap('tab10')(i) for i,r in enumerate(launch['processes'])}
    fig, axes = plt.subplots(2, 1, figsize=(11, 5.5), layout='constrained')
    for core in range(1, launch['case']['physical_workers']+1):
        rows = [r for r in assignments if r['datapath']==core]
        for index, row in enumerate(rows):
            left = (row['timestamp']-start)/a.clock_hz
            right = (rows[index+1]['timestamp']-start)/a.clock_hz if index+1<len(rows) else end
            for ax in axes:
                ax.broken_barh([(left,right-left)], (core-0.33,0.66), facecolors=colors[row['pid']])
    for ax in axes:
        ax.set_yticks(range(1, len(launch['worker_cpus'])+1),
                      [f'CPU {cpu}' for cpu in launch['worker_cpus']])
        ax.set_ylim(0.4,len(launch['worker_cpus'])+0.6)
        ax.set_xlabel('Seconds from first assignment (nominal FPGA clock)')
        ax.grid(axis='x',alpha=0.2)
        ax.set_axisbelow(True)
    axes[0].set_xlim(0,end)
    axes[0].set_title('Five memcached instances sharing four physical workers')
    axes[1].set_xlim(max(0,end-1),end)
    axes[1].set_title('Final second: recorded assignment changes')
    fig.legend(handles=[Patch(color=colors[r['pid']],label=f"UDP {r['port']} · PID {r['pid']}")
                        for r in launch['processes']],loc='outside lower center',ncol=3)
    fig.savefig(a.output.with_suffix('.png'),dpi=180)
    fig.savefig(a.output.with_suffix('.pdf'))


if __name__ == '__main__':
    main()
