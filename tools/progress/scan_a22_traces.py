#!/usr/bin/env python3
"""Count retained HAKS/A22 observations; absence in captures is not unreachability."""
import argparse
import csv
import hashlib
import json
import re
import struct
import subprocess
import tarfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    result = {'scope': __doc__, 'exports': [], 'legacy_csv': [], 'dma': [], 'thx_history': []}
    for path in sorted((ROOT / 'data/eci').rglob('events.tar.gz')):
        rows, members, hits = 0, 0, []
        with tarfile.open(path, 'r|gz') as archive:
            for member in archive:
                if not member.isfile() or not member.name.endswith('.csv'):
                    continue
                members += 1
                stream = archive.extractfile(member)
                header = next(stream).decode().strip().split(',')
                for index, line in enumerate(stream, 2):
                    rows += 1
                    if b'HAKS' in line:
                        hits.append({'member': member.name, 'line': index,
                                     'row': dict(zip(header, next(csv.reader([line.decode()]))))})
        record = {'path': str(path.relative_to(ROOT)), 'rows': rows, 'members': members, 'hits': hits}
        result['exports'].append(record)
        print(record['path'], rows, 'rows;', len(hits), 'HAKS', flush=True)
    for path in sorted((ROOT / 'data/eci').glob('dcs_trace/*/parsed/*.csv')):
        rows, hits = 0, []
        with path.open() as stream:
            header = next(stream)
            for index, line in enumerate(stream, 2):
                rows += 1
                if re.search(r'HAKS|\bA22\b', line):
                    hits.append({'line': index, 'text': line.strip()})
        result['legacy_csv'].append({'path': str(path.relative_to(ROOT)), 'rows': rows, 'hits': hits})
    base = ROOT / 'out/cmac-trace-archive'
    cases = [('cmac-fmac1-vivado-inputs', 'received'), ('cmac-vivado-inputs', 'recover-old/received')]
    for artifacts, capture in cases:
        map_path = base / artifacts / 'generated/lauberhorn_trace_dma_map.json'
        path = base / 'hardware-tests/cmac-fmac1-20260928' / capture / 'trace-full.bin'
        if not path.exists() or not map_path.exists():
            result['dma'].append({'path': str(path), 'skipped': 'capture or matching map unavailable'})
            continue
        mapping = json.loads(map_path.read_text())
        sample = mapping['sample']; fields = mapping['payload_formats']['eci']['fields']
        assert sample['sample_width'] == 128 and sample['byte_order'] == 'little'
        source_bits, payload_bits = sample['source_width'], sample['payload_width']
        incoming = {s['source']: s for s in mapping['sources'] if s.get('type') == 'eci'
                    and s.get('channel') in ('rsp_wod_i', 'rsp_wd_i')}
        counts, accepted, opcodes, hits = Counter(), Counter(), Counter(), []
        lost, lost_samples, bubbles, samples, digest = 0, 0, 0, 0, hashlib.sha256()
        def field(raw, name):
            f = fields[name]
            return (raw >> f['offset']) & ((1 << f['width']) - 1)
        with path.open('rb') as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
                assert len(chunk) % 16 == 0
                for low, high in struct.iter_unpack('<QQ', chunk):
                    value = low | (high << 64)
                    src = (value >> payload_bits) & ((1 << source_bits) - 1)
                    if src == sample['lost_source']:
                        count = value & ((1 << sample.get('lost_count_width', 32)) - 1)
                        if count:
                            lost += 1
                            lost_samples += count
                        else:
                            bubbles += 1
                    if src in incoming:
                        counts[src] += 1
                        accept = field(value, 'accepted')
                        header = field(value, 'eci_header')
                        opcode = (header >> 59) & 31
                        if accept:
                            accepted[src] += 1
                            opcodes[opcode] += 1
                        if opcode == 7:  # ECI MRSP_HAKS; inspect presented and accepted frames.
                            hits.append({'sample': samples, 'source': src, 'accepted': accept,
                                         'header': f'0x{header:016x}', 'vc': field(value, 'vc')})
                    samples += 1
        record = {'path': str(path.relative_to(ROOT)), 'map': str(map_path.relative_to(ROOT)),
                  'sha256': digest.hexdigest(), 'map_sha256': hashlib.sha256(map_path.read_bytes()).hexdigest(),
                  'samples': samples, 'lost_markers': lost, 'lost_samples': lost_samples,
                  'bubble_markers': bubbles, 'response_samples_by_source': dict(counts),
                  'accepted_by_source': dict(accepted), 'accepted_response_opcodes': dict(opcodes), 'hits': hits}
        result['dma'].append(record)
        print(record['path'], samples, 'samples;', len(hits), 'HAKS', flush=True)
    repo = ROOT / 'deps/thx-fsms'
    def git(*args):
        return subprocess.check_output(['git', '-C', str(repo), *args]).decode()
    for rev in git('rev-list', '--all').split():
        files = git('ls-tree', '-r', '--name-only', rev).splitlines()
        entry = {'revision': rev, 'commit': git('show', '-s', '--format=%ad %an %s', '--date=short', rev).strip(), 'files': {}}
        for file in files:
            if file.endswith(('remote_fsm.txt', 'remote_fsm.json', 'eci_stmc_auto_corrected.pl')):
                text = git('show', rev + ':' + file)
                if file.endswith('.json'):
                    data = json.loads(text)
                    count = sum(o['msg'] == 'FAS' for t in data['fsmTransitions'] for o in t['outputs'])
                    entry['files'][file] = {'FAS_output_transitions': count}
                elif file.endswith('.txt'):
                    entry['files'][file] = {'FAS_output_transitions': len(re.findall(r'\}\) FAS$', text, re.M))}
                else:
                    entry['files'][file] = {'A22_occurrences': len(re.findall(r'\bA22\b', text)),
                                           'sha256': hashlib.sha256(text.encode()).hexdigest()}
        result['thx_history'].append(entry)
    (args.output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print('summary:', args.output / 'summary.json', flush=True)


if __name__ == '__main__':
    main()
