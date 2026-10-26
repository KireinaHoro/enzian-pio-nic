#!/usr/bin/env python3
"""Finalize a trace map from Tcl's final-netlist FF-chain measurements."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def sources(mapping):
    if mapping.get('pipeline_resolution') != 'implementation_required':
        raise ValueError('expected unresolved auto-pipeline map')
    return {int(s['source']): s for s in mapping['sources'] if s['type'] != 'lost'}


def resolve(mapping, rows, revision, checkpoint_hash):
    expected = sources(mapping)
    observed = {key: {} for key in expected}
    for row in rows:
        key, bit, stages = (int(row[k]) for k in ('source', 'bit', 'stages'))
        if key not in expected:
            raise ValueError(f'unexpected source {key}')
        spec = expected[key]['autopipeline']
        if row['instance'] != spec['instance'] or not 0 <= bit < spec['width']:
            raise ValueError(f'wrong endpoint for source {key}')
        if bit in observed[key] or not spec['base_stages'] <= stages <= spec['base_stages'] + spec['limit']:
            raise ValueError(f'duplicate bit or illegal depth for source {key}')
        observed[key][bit] = stages
    for key, src in expected.items():
        spec, bits = src['autopipeline'], observed[key]
        if spec['width'] - 1 not in bits or len(set(bits.values())) != 1:
            raise ValueError(f'missing valid or unequal payload/valid depth for source {key}')
        src['pipeline_stages'] = next(iter(bits.values()))
        spec['measured_bits'] = sorted(bits)
        # Constant payload bits can be optimized away; every surviving sink FF
        # and the mandatory valid bit must have the same measured depth.
    mapping['pipeline_resolution'] = 'implemented'
    mapping['implementation'] = {'revision': revision, 'checkpoint_sha256': checkpoint_hash}
    return mapping


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=['plan', 'resolve'])
    p.add_argument('template', type=Path)
    p.add_argument('rest', nargs='*')
    a = p.parse_args()
    mapping = json.loads(a.template.read_text())
    if a.mode == 'plan':
        for key, src in sources(mapping).items():
            spec = src['autopipeline']
            print(f"{key}\t{spec['instance']}\t{spec['limit']}\t{spec['width']}")
    else:
        evidence, output, revision, checkpoint = a.rest
        with open(evidence) as f:
            result = resolve(mapping, list(csv.DictReader(f, delimiter='\t')), revision, digest(checkpoint))
        result['implementation']['depths_sha256'] = digest(evidence)
        result['implementation']['template_sha256'] = digest(a.template)
        bitstream = Path(checkpoint).with_name('shell_lauberhorn-eci.bit')
        if bitstream.is_file():
            result['implementation']['bitstream_sha256'] = digest(bitstream)
        target = Path(output)
        tmp = target.with_suffix(target.suffix + '.tmp')
        tmp.write_text(json.dumps(result, indent=2) + '\n')
        tmp.replace(target)


if __name__ == '__main__':
    main()
