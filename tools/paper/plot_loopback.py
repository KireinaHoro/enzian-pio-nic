#!/usr/bin/env python3
"""Render loopback charts from validated client RTT samples and completion windows."""
import argparse
import csv
import gzip
import hashlib
import json
from pathlib import Path
import paper_chart
import numpy as np

ROOT = Path(__file__).resolve().parents[2]


def rows(root, entry):
    path = root / entry['file']
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
        raise ValueError(f'Hash mismatch: {path}')
    with (gzip.open(path, 'rt') if path.suffix == '.gz' else path.open()) as stream:
        return list(csv.DictReader(stream))


def load(manifest_path):
    manifest = json.loads(manifest_path.read_text())
    if not manifest['runs']:
        raise ValueError('No loopback measurements; refusing to render placeholder results')
    if len({r['label'] for r in manifest['runs']}) != len(manifest['runs']):
        raise ValueError('Duplicate cohort labels')
    points = []
    for run in manifest['runs']:
        if run['software_tracing'] or run['system'] not in manifest['systems']:
            raise ValueError('Invalid instrumentation or system')
        if (run['measured_count'] != manifest.get('measured_per_cohort',10000) or
            run['warmup_count'] != manifest.get('warmup_per_cohort',128)):
            raise ValueError('Cohort sample count differs from campaign contract')
        client = rows(manifest_path.parent, run['client'])
        ids = [int(r['request_id']) for r in client]
        if ids != list(range(1, run['warmup_count'] + run['measured_count'] + 1)):
            raise ValueError('Incomplete or duplicate request identities')
        for i, row in enumerate(client):
            if (row['ok'] != '1' or row['rpc_status'] != '0' or
                row['benchmark'] != f"loopback{run['payload_bytes']}" or
                int(row['warmup']) != int(i < run['warmup_count'])):
                raise ValueError('Invalid reply, payload or warmup')
        measured = client[run['warmup_count']:]
        window = json.loads((manifest_path.parent / run['window']['file']).read_text())
        path = manifest_path.parent / run['window']['file']
        if hashlib.sha256(path.read_bytes()).hexdigest() != run['window']['sha256']:
            raise ValueError('Observation window hash mismatch')
        start, end = window['observation_start_ns'], window['observation_end_ns']
        if (window['failed'] or end <= start or window['expected_measured'] != len(measured) or
            any(not start <= int(r['client_call_enter_ns']) < int(r['client_call_exit_ns']) <= end for r in measured)):
            raise ValueError('Invalid completion observation window')
        point = dict(run, throughput=len(measured)*1e9/(end-start),max_outstanding=window['window'],generator_cores=window.get('generator_cores',1))
        if (point['generator_cores'] != manifest['generator_cores'] or
            point['max_outstanding'] != manifest['max_outstanding'][run['system']]):
            raise ValueError('Generator configuration differs from campaign contract')
        if run.get('ptp', False):
            raise ValueError('RTT campaign requires the uninstrumented batched server')
        evidence = rows(manifest_path.parent, run['worker_evidence'])
        active = {r['worker'] for r in evidence if int(r['measured']) > 0}
        if not 0 < len(active) <= run['workers'] or sum(int(r['measured']) for r in evidence) != len(measured):
            raise ValueError('Worker activity evidence does not match cohort')
        if not run.get('target_rpc_s') and len(active) != run['workers']:
            raise ValueError('Unpaced cohort did not exercise every configured worker')
        point['observed_workers'] = len(active)
        if run['kind'] == 'latency':
            if manifest['latency_boundary'] != 'client call enter -> client RX burst observation':
                raise ValueError('Unexpected RTT boundary')
            values = np.asarray([int(r['client_rx_burst_ns'])-int(r['client_call_enter_ns']) for r in measured])
            if np.any(values <= 0) or any(int(r['client_rx_burst_ns']) > int(r['client_call_exit_ns']) for r in measured):
                raise ValueError('Invalid RTT')
            point['latency'], point['p99'] = np.percentile(values, [50, 99])/1000
        points.append(point)
    return manifest, points


def render(manifest, points, output, charts="all"):
    if charts in ('all', 'latency'):
        for size in manifest['line_payload_bytes']:
            fig, ax = paper_chart.figure(height=3.0)
            palettes = {'LH': ('#73b5d8', '#3995c5', '#0072b2', '#004b76'),
                        'DPDK': ('#eeaa76', '#e38640', '#d55e00', '#8c3e00')}
            for system in manifest['systems']:
                style = {'LH': '-', 'DPDK': '--'}[system]
                for workers, color in zip(manifest['workers'], palettes[system]):
                    selected = [p for p in points if p['kind'] == 'latency' and p['system'] == system
                                and p['payload_bytes'] == size and p['workers'] == workers]
                    expected = manifest['latency_rates_rpc_s'][system]
                    actual = [p['target_rpc_s'] or 0 for p in selected]
                    if sorted(actual) != sorted(expected):
                        raise ValueError(f'Missing or duplicate load points: {system}, {size}, {workers}')
                    selected.sort(key=lambda p: p['target_rpc_s'] or float('inf'))
                    x = [p['throughput']/1000 for p in selected]
                    y = [p['latency'] for p in selected]
                    upper = [p['p99'] for p in selected]
                    # Adjacent strips retain the band when achieved throughput
                    # reverses slightly at saturation (a single polygon can fold).
                    for i in range(len(x)-1):
                        ax.fill_between(x[i:i+2], y[i:i+2], upper[i:i+2],
                                        color=color, alpha=.13, linewidth=0)
                    ax.plot(x, y, linestyle=style, color=color, label=f'{system} {workers}W')
            ax.set(xlabel='Throughput (kRPC/s)', ylabel=r'RTT ($\mu$s)')
            ax.set_ylim(bottom=0)
            ax.legend(ncol=2, fontsize=7, loc='upper right')
            paper_chart.save(fig, output / f'loopback-hot-throughput-latency-{size}B.pdf')
    if charts not in ('all', 'peak'):
        return
    fig, ax = paper_chart.figure()
    x = np.arange(len(manifest['payload_bytes']))
    for index, system in enumerate(manifest['systems']):
        peaks = []
        for size in manifest['payload_bytes']:
            selected = [p['throughput']/1000 for p in points if p['kind'] == 'workers' and p['system'] == system and p['payload_bytes'] == size and p['workers'] == manifest['peak_workers']]
            if len(selected) != 3:
                raise ValueError(f'Missing peak trials: {system}, {size}')
            peaks.append(max(selected))
        ax.bar(x + (index-(len(manifest['systems'])-1)/2)*.36, peaks, width=.36,
               color={'LH': paper_chart.COLORS[0], 'DPDK': paper_chart.COLORS[1]}[system], label=system)
    ax.set(xlabel='Payload (bytes)', ylabel='Peak throughput (kRPC/s)', xticks=x, xticklabels=manifest['payload_bytes'])
    ax.legend()
    paper_chart.save(fig, output / 'loopback-hot-size-peak-throughput.pdf')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT/'data/paper/loopback/manifest.json')
    parser.add_argument('--output', type=Path, default=ROOT.parent/'paper/plots')
    parser.add_argument('--charts', choices=('all', 'latency', 'peak'), default='all')
    args = parser.parse_args()
    manifest, points = load(args.manifest)
    render(manifest, points, args.output, args.charts)
    print(json.dumps(points, indent=2))


if __name__ == '__main__':
    main()
