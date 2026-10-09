#!/usr/bin/env python3
"""Emit required experiment cases; this is a manifest, never a result."""
import argparse
import itertools
import json
from pathlib import Path


def cases(workers, rates):
    pools = [(f'single-{n}', [n]) for n in range(1, workers+1)]
    pools += [(f'homogeneous-{n}', [n]*(workers+1)) for n in range(1, workers+1)]
    pools += [('mixed', [i % workers+1 for i in range(workers+1)])]
    for ((name, sizes), get_fraction, distribution, value_bytes, shape, rate, repeat) in itertools.product(
            pools, [1.0, 0.95], ['uniform', 'skewed'], [64, 1024],
            ['steady', 'burst', 'idle-active'], rates, range(3)):
        yield dict(pool_case=name, workers=sizes, declared_workers=sum(sizes),
                   oversubscribed=sum(sizes)>workers, physical_workers=workers,
                   get_fraction=get_fraction, key_distribution=distribution,
                   value_bytes=value_bytes, traffic=shape, offered_rps_per_instance=rate,
                   warmup_seconds=10, measurement_seconds=30, repetition=repeat,
                   seed=20261008+repeat, ports=list(range(11211,11211+len(sizes))))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--workers', type=int, default=4, choices=range(1,8))
    p.add_argument('--rates', type=int, nargs='+', required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if any(rate <= 0 for rate in args.rates) or args.output.exists():
        p.error('rates must be positive and output must be new')
    manifest = dict(schema=1, status='unrun', cases=list(cases(args.workers, args.rates)))
    args.output.write_text(json.dumps(manifest, indent=2)+'\n')
    print(json.dumps(dict(cases=len(manifest['cases']), status='unrun')))
