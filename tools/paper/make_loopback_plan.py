#!/usr/bin/env python3
"""Emit the finite loopback workload matrix; achieved load is measured, not assumed."""
import argparse
import json
from pathlib import Path

SIZES = (8, 64, 128, 512, 1432)
LINES = (8, 128, 1432)
RATES = (20000, 40000, 60000, 80000, 100000, 120000, 140000, 160000,
         180000, 200000, 250000, 300000, 400000, 500000, 750000, 1000000, 1500000, 2000000, 3000000, 4000000, 5000000, 6000000, 8000000, 16000000, 32000000, 0)


RATES_BY_SYSTEM = {'DPDK': RATES, 'LH': tuple(r for r in RATES if r <= 1000000)}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--system', choices=('LH', 'DPDK'), required=True)
    p.add_argument('--prefix', required=True)
    p.add_argument('--pilot', action='store_true')
    p.add_argument('--generator-cores', type=int, choices=(1,2,4,8), default=1)
    p.add_argument('--window',type=int,choices=range(1,257),default=16)
    p.add_argument('--latency-window',type=int,choices=range(1,257))
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if not a.prefix.replace('-', '').replace('_', '').isalnum():
        p.error('unsafe label')
    if not a.pilot and a.window<a.generator_cores:p.error('window must cover all generator cores')
    plan = []
    def add(size, workers, kind, rate, trial):
        generators=a.generator_cores
        window=a.latency_window if kind=='latency' and a.latency_window else a.window
        if not a.pilot and window<generators:p.error('window must cover all generator cores')
        plan.append(dict(label=f'{a.prefix}-{kind}-s{size}-w{workers}-r{rate}-t{trial}',
                         system=a.system, payload_bytes=size, workers=workers, kind=kind,
                         count=100 if a.pilot else 10000, warmup=16 if a.pilot else 128,
                         window=max(1,generators) if a.pilot else window, gap_ns=round(1e9/rate) if rate else 0,
                         target_rpc_s=rate or None, software_tracing=False,
                         ptp=False,
                         flows=64 if generators>1 or a.system=='DPDK' and workers>1 else 1,
                         generator_lcores=list(range(6,6+generators))))
    if a.pilot:
        for size in SIZES: add(size, 1, 'latency', 0, 0)
    else:
        for size in LINES:
            for workers in (1,2,3,4):
                for rate in RATES_BY_SYSTEM[a.system]: add(size, workers, 'latency', rate, 0)
        for workers in (4,):
            for size in SIZES:
                for trial in range(3): add(size, workers, 'workers', 0, trial)
    with a.output.open('x') as f:
        json.dump(plan, f, indent=2); f.write('\n')


if __name__ == '__main__':
    main()
