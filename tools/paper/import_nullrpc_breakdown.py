#!/usr/bin/env python3
"""Archive app-clock stage markers from the validated one-worker hot capture."""
import argparse
import csv
import shutil
import gzip
import hashlib
import json
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'data/paper/nullrpc'
EVENTS = (3, 4, 5, 6, 9, 44, 45, 2)

def archive_dpdk(source):
    destination = DATA / 'breakdown'
    destination.mkdir(exist_ok=True)
    for label, count, warmup in [('pilot2', 100, 16), ('hot', 10000, 128), ('control', 10000, 128)]:
        for role, folder in [('server', 'dut'), ('client', 'peer')]:
            base = source / folder
            run = json.loads((base/f'{label}-{role}/summary.json').read_text())
            if run['status'] != 'pass' or not run['restored'] or run['returncode'] != 0:
                raise ValueError(f'Failed run: {label}-{role}')
            path = base/f'{label}-{role}.csv'
            with path.open() as stream:
                rows = list(csv.DictReader(stream))
            if len(rows) != count+warmup:
                raise ValueError('Incomplete DPDK cohort')
            for index, row in enumerate(rows, 1):
                if (int(row['request_id']) != index or row['warmup'] != str(int(index <= warmup))
                        or row['status' if role == 'server' else 'ok'] != '1'):
                    raise ValueError('Invalid DPDK row')
            prefix = f'dpdk-{label}-{role}'
            (destination/f'{prefix}.csv.gz').write_bytes(gzip.compress(path.read_bytes(), mtime=0))
            for name, suffix in [('summary.json', 'run'), ('before.json', 'before')]:
                shutil.copyfile(base/f'{label}-{role}'/name, destination/f'{prefix}-{suffix}.json')
            shutil.copyfile(base/f'{label}-{role}-manifest.json', destination/f'{prefix}-manifest.json')
            if role == 'server':
                counters = [json.loads(line) for line in (base/f'{label}-{role}/client.log').read_text().splitlines()
                            if line.startswith('{')]
                if (len(counters) != 1 or not counters[0]['passed'] or counters[0]['completed'] != count+warmup
                        or any(counters[0][k] for k in ('rx_errors', 'tx_errors', 'rx_missed'))):
                    raise ValueError('DPDK hardware counters failed')
                (destination/f'{prefix}-counters.json').write_text(json.dumps(counters[0], indent=2)+'\n')
            else:
                shutil.copyfile(base/f'{label}-{role}.csv.load.json', destination/f'{prefix}-load.json')
    print('DPDK cohorts validated and archived')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--trace', type=Path)
    parser.add_argument('--dpdk', type=Path, help='downloaded dut/ and peer/ acquisition directories')
    args = parser.parse_args()
    if not args.trace and not args.dpdk:
        parser.error('provide --trace and/or --dpdk')
    if args.dpdk:
        archive_dpdk(args.dpdk)
    if not args.trace:
        return
    provenance = json.loads((DATA / 'mac-provenance.json').read_text())
    digest = hashlib.sha256()
    with args.trace.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    if digest.hexdigest() != provenance['source_sha256']:
        raise ValueError('Capture differs from validated MAC provenance')
    with gzip.open(DATA / 'w1-mac-latency.csv.gz', 'rt') as stream:
        rows = [r for r in csv.DictReader(stream) if r['label'] == 'w1-hot-w1']
    lo = min(int(r['rx_mac_ns']) for r in rows)
    hi = max(int(r['tx_mac_ns']) for r in rows)
    map_path = DATA / 'w1-trace-map.json'
    if hashlib.sha256(map_path.read_bytes()).hexdigest() != provenance['files'][map_path.name]:
        raise ValueError('Trace map hash mismatch')
    mapping = json.loads(map_path.read_text())
    sources = [s['source'] for s in mapping['sources']
               if s['type'] == 'lauberhorn_event' and s['clock_domain'] == 'app']
    margin = max(s['pipeline_stages'] for s in mapping['sources'] if s['source'] in sources) * 5 + 100
    raw = np.memmap(args.trace, dtype='<u8', mode='r').reshape(-1, 2)
    selected = []
    for start in range(0, len(raw), 1 << 20):
        block = raw[start:start + (1 << 20)]
        timestamps = (block[:, 1] >> 18) * 5
        keep = ((timestamps >= lo - margin) & (timestamps <= hi + margin)
                & np.isin((block[:, 1] >> 11) & 127, sources)
                & np.isin(block[:, 0] & 63, EVENTS))
        selected.append(block[keep].copy())
    destination = DATA / 'breakdown/lauberhorn-events.bin.gz'
    destination.parent.mkdir(exist_ok=True)
    destination.write_bytes(gzip.compress(np.concatenate(selected).tobytes(), mtime=0))
    print(json.dumps(dict(file=str(destination.relative_to(DATA)),
                         sha256=hashlib.sha256(destination.read_bytes()).hexdigest(),
                         source_sha256=provenance['source_sha256'], events=EVENTS), indent=2))

if __name__ == '__main__':
    main()
