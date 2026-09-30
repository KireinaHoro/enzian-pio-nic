#!/usr/bin/env python3
"""Independently enumerate an emitted fixed-worker/domain configuration."""
import argparse
import json
from pathlib import Path
from capacity import color, encode, decode, compatible


def verify_config(config):
    assert config['schema'] == 'lauberhorn.fixed-worker-domain-map.v1'
    stride, bits = config['block_stride_bytes'], config['color_bits']
    assert stride > 0 and stride & (stride-1) == 0
    assert stride >= 4096, 'worker mapping blocks must be page aligned'
    assert len(config['workers']) == config['worker_count']
    assert config['bypass']['full_duplex']
    offsets = config['directions']
    all_offsets = [x for direction in offsets.values()
                   for key in ('control_offsets_bytes', 'overflow_offsets_bytes') for x in direction[key]]
    assert len(set(all_offsets)) == len(all_offsets)
    assert all(0 <= x < stride and x % 128 == 0 for x in all_offsets)
    for d in offsets.values():
        assert len(d['control_offsets_bytes']) == 2
        lines = d['control_offsets_bytes'] + d['overflow_offsets_bytes']
        assert lines == list(range(lines[0], lines[0] + 128 * len(lines), 128))

    def signature(base, direction):
        d = offsets[direction]
        return (tuple(color(base + x, bits) for x in d['control_offsets_bytes']),
                set(color(base + x, bits) for x in d['overflow_offsets_bytes']))

    def valid_block(base):
        assert base % stride == 0 and 0 <= base <= (1 << config['usable_address_bits']) - stride
        assert all(base + stride <= lo or base >= hi for lo, hi in config['reserved_regions'])

    bypass_base = config['bypass']['base_bytes']
    valid_block(bypass_base)
    bypass = [signature(bypass_base, d) for d in ('rx', 'tx')]
    assert compatible(bypass)
    seen = {bypass_base}
    signatures = []
    for worker in range(config['worker_count']):
        ref = None
        for domain in range(config['domain_count']):
            base = encode(config, worker, domain)
            valid_block(base)
            assert base not in seen
            seen.add(base)
            sig = [signature(base, d) for d in ('rx', 'tx')]
            if ref is None:
                ref = sig
            assert sig == ref
            for offset in all_offsets:
                assert decode(config, base + offset) == (worker, domain, offset)
        assert ref is not None
        assert all(compatible(bypass + [s]) for s in ref)
        for previous in signatures:
            assert all(compatible([a,b]) for a in previous for b in ref)
        signatures.append(ref)
    return len(seen)-1


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('file', type=Path)
    a = p.parse_args()
    for result in json.loads(a.file.read_text()):
        configurations = [result['configuration']] if 'configuration' in result else result['configurations']
        for config in configurations:
            count = verify_config(config)
            print(f"verified {count} distinct worker/domain blocks plus full-duplex bypass")


if __name__ == '__main__':
    main()
