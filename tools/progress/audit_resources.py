#!/usr/bin/env python3
"""Conservative RTG occupancy bound, not a protocol or shared-path progress proof."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from capacity import color
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


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('configuration', type=Path)
    a = p.parse_args()
    config = json.loads(a.configuration.read_text())[0]['configuration']
    verify_config(config)
    bound, histogram = resident_line_bound(config)
    source = (ROOT / DEFS).read_text()
    ways = int(re.search(r'parameter\s+DS_NUM_WAYS_PER_SET\s*=\s*(\d+)', source).group(1))
    print(json.dumps(dict(
        max_resident_lines_per_dcu=bound, dcus_by_resident_line_count=histogram,
        ways_per_set=ways, footprint_alone_cannot_exhaust_set=bound < ways,
        source=str(DEFS), source_sha256=hashlib.sha256(source.encode()).hexdigest(),
        assumptions=['only the selected transport footprint is resident',
                     'one current domain per worker; prior-owner cache state retired',
                     'one RTG entry per distinct resident line; protocol correctness assumed'],
        excluded=['RTG transition progress', 'other traffic and stale domain generations',
                  'write/ECI slots, data/CDC queues, crossbar and THX credits']), indent=2))


if __name__ == '__main__':
    main()
