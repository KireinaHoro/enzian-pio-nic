#!/usr/bin/env python3
"""Bounded checkpoint digest; exact pins remain in paths.tsv for targeted follow-up."""
import argparse
import collections
import csv
import json
import pathlib
import re


def family(pin):
    # Preserve plugin/instance identity while collapsing register bits and replicas.
    pin = re.sub(r'\[\d+\]', '[]', pin)
    return '/'.join(pin.split('/')[:3])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=pathlib.Path)
    p.add_argument('--limit', type=int, default=6)
    p.add_argument('--baseline', type=pathlib.Path, help='previous summary.json for metric deltas')
    args = p.parse_args()
    if args.limit < 1:
        p.error('--limit must be positive')
    paths_file = args.directory / 'paths.tsv'
    rows = []
    if paths_file.exists():
        with paths_file.open() as source:
            rows = list(csv.DictReader(source, delimiter='\t'))
    groups = collections.defaultdict(list)
    for row in rows:
        groups[(row['type'], family(row['STARTPOINT_PIN']), family(row['ENDPOINT_PIN']))].append(row)
    result = []
    for (kind, src, dst), paths in groups.items():
        worst = min(paths, key=lambda r: float(r['SLACK']))
        result.append(dict(type=kind, source=src, destination=dst, sampled_paths=len(paths),
                           sampled_failing=sum(float(r['SLACK']) < 0 for r in paths),
                           worst_slack_ns=float(worst['SLACK']), representative=worst))
    result.sort(key=lambda r: (r['type'], r['worst_slack_ns']))
    summary = dict(complete=(args.directory / 'COMPLETE').exists(), metadata=(args.directory / 'metadata.txt').read_text().splitlines(),
                   note='Bounded worst-path sample, not total endpoint counts or TNS.',
                   families=result)
    print('status=' + ('complete' if summary['complete'] else 'PARTIAL: inspect Vivado log'))
    if not paths_file.exists():
        print('paths.tsv missing: only retained timing-summary representatives are available')
    print('\n'.join(summary['metadata']))
    timing = (args.directory / 'timing.rpt').read_text()
    match = re.search(r'WNS\(ns\).*?\n\s*-+.*?\n([^\n]+)', timing, re.S)
    if match:
        print('WNS TNS failing total | WHS THS failing total | WPWS TPWS failing total')
        print(match.group(1).strip())
        keys = ('wns', 'tns', 'setup_failing', 'setup_total', 'whs', 'ths', 'hold_failing', 'hold_total', 'wpws', 'tpws', 'pulse_failing', 'pulse_total')
        summary['timing'] = dict(zip(keys, map(float, match.group(1).split())))
    clock_paths = []
    for match in re.finditer(r'Slack \((?:MET|VIOLATED)\).*?(?=\n\s*Location)', timing, re.S):
        block = match.group(0)
        if 'Path Type:              Setup' not in block:
            continue
        fields = {}
        for label in ('Source', 'Destination', 'Path Group', 'Data Path Delay', 'Logic Levels'):
            value = re.search(r'^\s*' + label + r':\s*(.*)$', block, re.M)
            fields[label] = value.group(1) if value else ''
        fields['slack'] = float(re.search(r':\s*([+-]?[\d.]+)ns', block).group(1))
        clock_paths.append(fields)
    summary['clock_group_representatives'] = clock_paths
    cdc = args.directory / 'cdc.rpt'
    summary['cdc_counts'] = {}
    if cdc.exists():
        for line in cdc.read_text().splitlines():
            row = re.match(r'^(CDC-\d+)\s+(Critical|Warning|Info)\s+(\d+)\s+(.*)$', line)
            if row:
                summary['cdc_counts'][row[1]] = dict(severity=row[2], count=int(row[3]), description=row[4])
    print('CDC counts (structural diagnostics, not proved functional bugs): ' +
          ', '.join(f"{k}={v['count']}" for k, v in summary['cdc_counts'].items() if v['severity'] != 'Info'))
    if args.baseline:
        old = json.loads(args.baseline.read_text())
        summary['baseline'] = str(args.baseline)
        summary['timing_delta'] = {k: v - old.get('timing', {}).get(k, v)
                                   for k, v in summary.get('timing', {}).items()
                                   if k in old.get('timing', {})}
        print('Timing deltas (new minus baseline): ' + json.dumps(summary['timing_delta']))
    (args.directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('Worst reported setup clock-pair representatives:')
    for path in sorted(clock_paths, key=lambda x: x['slack'])[:args.limit]:
        print(f"  {path['slack']:.3f} {path['Path Group']} {path['Source']} -> {path['Destination']}")
    print(summary['note'])
    for kind in ('max', 'min'):
        for row in [r for r in result if r['type'] == kind][:args.limit]:
            rep = row['representative']
            print(f"{kind} slack={row['worst_slack_ns']:.3f} sampled={row['sampled_paths']} "
                  f"fail={row['sampled_failing']} levels={rep['LOGIC_LEVELS']} "
                  f"delay={rep['DATAPATH_DELAY']} {row['source']} -> {row['destination']}")


if __name__ == '__main__':
    main()
