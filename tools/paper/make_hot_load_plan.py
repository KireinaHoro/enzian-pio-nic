#!/usr/bin/env python3
"""Emit paced, bounded hot-load cohorts with finer spacing near saturation."""
import argparse
import json
from pathlib import Path

RATES = (20000, 40000, 60000, 80000, 90000, 100000, 110000, 120000,
         130000, 140000, 145000, 150000, 152000, 154000, 156000, 160000, 180000)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.prefix.replace('-', '').replace('_', '').isalnum():
        parser.error('unsafe label prefix')
    plan = [dict(label=f'{args.prefix}-r{rate}', count=10000, warmup=128,
                 window=16, gap_ns=round(1e9/rate), mode='hot', target_rpc_s=rate)
            for rate in RATES]
    plan.append(dict(label=f'{args.prefix}-unpaced', count=10000, warmup=128,
                     window=16, gap_ns=0, mode='hot', target_rpc_s=None))
    args.output.write_text(json.dumps(plan, indent=2) + '\n')


if __name__ == '__main__':
    main()
