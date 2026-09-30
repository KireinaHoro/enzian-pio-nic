#!/usr/bin/env python3
"""Conservative RTG occupancy bound, not a protocol or shared-path progress proof."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from capacity import color, alias
from verify_config import verify_config

ROOT = Path(__file__).resolve().parents[2]
DEFS = Path('vivado/eci/directory-controller-slice/dcs/eci_dcs_defs/rtl/eci_dcs_defs.sv')


def resident_line_bound(config):
    # Assume every line from both directions can remain resident simultaneously.
    # Prefix-invariant colors make this independent of the current domain choice.
    bits = config['color_bits']
    assert all(color(k, bits) == 0 for k in config['domain_encoding']['kernel_basis_bytes'])
    offsets = {o for d in config['directions'].values()
               for field in ('control_offsets_bytes', 'overflow_offsets_bytes') for o in d[field]}
    counts = Counter(color(base + offset, bits)
                     for base in [config['bypass']['base_bytes']] + [w['base_bytes'] for w in config['workers']]
                     for offset in offsets)
    return max(counts.values()), dict(sorted(Counter(counts.values()).items()))


def retained_set_pressure(config):
    """Potential distinct tags across all domain aliases, before CPU replacement.

    XOR-prefix projection is linear. Count each 13-bit directory-index image
    with multiplicity without enumerating every physical domain/worker line.
    This is offered footprint pressure, not a claim all lines can be resident.
    """
    vectors = [alias(v >> 7) & 8191
               for v in config['domain_encoding']['kernel_basis_bytes']]
    count = config['domain_count']
    indices = config['domain_encoding'].get('prefix_indices')
    def project(i):
        result = 0
        for bit, vector in enumerate(vectors):
            if i >> bit & 1:
                result ^= vector
        return result
    if indices is not None:
        images = Counter(project(i) for i in indices)
    else:
        # Decompose [0,count) into aligned power-of-two ranges.
        images, lower = Counter(), Counter({0: 1})
        for bit in range(len(vectors) + 1):
            if count >> bit & 1:
                fixed = project((count >> (bit + 1)) << (bit + 1))
                for image, multiplicity in lower.items():
                    images[image ^ fixed] += multiplicity
            if bit < len(vectors):
                expanded = lower.copy()
                for image, multiplicity in lower.items():
                    expanded[image ^ vectors[bit]] += multiplicity
                lower = expanded
    offsets = {o for d in config['directions'].values()
               for field in ('control_offsets_bytes', 'overflow_offsets_bytes') for o in d[field]}
    counts = Counter()
    for worker in config['workers']:
        for offset in offsets:
            base = alias((worker['base_bytes'] + offset) >> 7) & 8191
            for image, multiplicity in images.items():
                counts[base ^ image] += multiplicity
    for offset in offsets:
        counts[alias((config['bypass']['base_bytes'] + offset) >> 7) & 8191] += 1
    return max(counts.values()), dict(sorted(Counter(counts.values()).items()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('configuration', type=Path)
    a = p.parse_args()
    config = json.loads(a.configuration.read_text())[0]['configuration']
    verify_config(config)
    bound, histogram = resident_line_bound(config)
    retained, retained_histogram = retained_set_pressure(config)
    source = (ROOT / DEFS).read_text()
    sets = int(re.search(r'parameter\s+DS_NUM_SETS_PER_DCU\s*=\s*(\d+)', source).group(1))
    assert sets > 0 and sets & (sets - 1) == 0
    assert config['color_bits'] == 13 - (sets.bit_length() - 1), 'configuration must match audited DCS geometry'
    ways = int(re.search(r'parameter\s+DS_NUM_WAYS_PER_SET\s*=\s*(\d+)', source).group(1))
    print(json.dumps(dict(
        active_assignment_lines_per_dcu=bound, active_assignment_dcus_by_line_count=histogram,
        ways_per_set=ways,
        current_assignment_only_bound=bound,
        all_domain_potential_tags_per_set=retained,
        sets_by_potential_tags=retained_histogram,
        replacement_required_for_full_alias_footprint=retained > ways,
        arbitrary_background_can_fill_set=True,
        clean_cache_required=False,
        progress_conclusion="Requires CPU eviction/downgrade service; occupancy arithmetic is not progress",
        source=str(DEFS), source_sha256=hashlib.sha256(source.encode()).hexdigest(),
        assumptions=['potential footprint counts all configured domain aliases, not just installed owners',
                     'one RTG entry per distinct line; CPU residency is finite and replacement is allowed',
                     'current-assignment-only six-line bound is descriptive, not an entry precondition'],
        excluded=['RTG transition progress', 'background pressure beyond the configured alias footprint',
                  'write/ECI slots, data/CDC queues, crossbar and THX credits']), indent=2))


if __name__ == '__main__':
    main()
