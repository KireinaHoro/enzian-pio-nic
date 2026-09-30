#!/usr/bin/env python3
"""Exhaust the shared-offset family for 0x20000 blocks and 12 overflow lines.

Six-bit colors repeat every 256 CLs within a 1024-CL block. Each pair of
residues can be placed without physical overlap using different 256-CL regions.
With full-duplex bypass and independently selectable worker directions,
compatibility of two workers is exactly compatibility of their union signatures.
XOR translation and direction/parity permutation therefore preserve capacity.
This sweep covers every offset pair in the block, not other strides/footprints.
"""
import argparse
from concurrent.futures import ProcessPoolExecutor
import json
from pathlib import Path
from capacity import Layout, compatible, frontier, configuration


def representatives():
    signatures = [Layout(i, i+256, 12).signature(0, 0, 6) for i in range(256)]
    unique = {}
    valid_pairs = 0
    for rx, (rc, ro) in enumerate(signatures):
        for tx, (tc, to) in enumerate(signatures):
            if not compatible([(rc, ro), (tc, to)]):
                continue
            valid_pairs += 1
            controls, overflow = rc + tc, ro | to
            key = min((tuple(sorted(c ^ anchor for c in controls)),
                       tuple(sorted(c ^ anchor for c in overflow))) for anchor in controls)
            # Three regions are enough even when RX crosses a 256-CL boundary.
            offset = tx + (512 if set(range(rx, rx+14)) & set(range(tx+256, tx+270)) else 256)
            unique.setdefault(key, (rx, offset))
    return list(unique.values()), valid_pairs


def solve(offsets):
    rx, tx = offsets
    result = frontier(Layout(rx, tx, 12), stride=17)
    return result['frontier'][-1]['workers'], result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jobs', type=int, default=1)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    offsets, pairs = representatives()
    best = None
    checked = 0
    with ProcessPoolExecutor(max_workers=a.jobs) as pool:
        for workers, result in pool.map(solve, offsets, chunksize=8):
            checked += 1
            if best is None or workers > best['frontier'][-1]['workers']:
                best = result
                print(f'best: {workers} workers + bypass; RX {result["layout"]["rx"]}, TX {result["layout"]["tx"]}', flush=True)
            if checked % 500 == 0:
                print(f'checked {checked}/{len(offsets)} equivalence classes', flush=True)
    best['configurations'] = [configuration(best, r['workers'], r['domains']) for r in best['frontier'] if r['workers']]
    best['sweep'] = dict(status='exhaustive', color_bits=6, address_bits=38, stride_bits=17,
                         overflow=12, rx_and_tx_start_range=[0, 1010],
                         valid_residue_pairs=pairs, equivalence_classes=checked,
                         excluded='other strides, footprints, reserved regions, noncanonical worker placements')
    a.output.write_text(json.dumps([best], indent=2)+'\n')
    print(f'complete: maximum {best["frontier"][-1]["workers"]} workers plus full-duplex bypass', flush=True)


if __name__ == '__main__':
    main()
