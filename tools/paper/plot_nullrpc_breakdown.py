#!/usr/bin/env python3
"""Validate paired stage observations and plot two additive median MAC bars."""
import argparse
import csv
import gzip
import hashlib
import json
from pathlib import Path
import paper_chart
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
from datasets import data_root, paper_directory
DATA = data_root() / 'paper/nullrpc'
CATEGORIES = ('handler', 'rpc', 'network', 'cpu_mac')
LABELS = ('Handler', 'RPC runtime', 'Network stack', 'CPU--MAC')

def read_rows(path):
    with gzip.open(path, 'rt') as stream:
        return list(csv.DictReader(stream))

def components(request_id, total, handler, rpc, network):
    values = dict(zip(CATEGORIES, (handler, rpc, network, total-handler-rpc-network)))
    if any(value < 0 for value in values.values()) or total <= 0:
        raise ValueError(f'Invalid additive partition: {request_id}: {values}')
    if sum(values.values()) != total:
        raise ValueError('Components do not conserve the MAC interval')
    return dict(request_id=int(request_id), total=total, **values)

def lauberhorn(meta):
    mapping = json.loads((DATA / 'w1-trace-map.json').read_text())
    sources = {s['source']: s for s in mapping['sources']}
    with gzip.open(DATA / meta['events'], 'rb') as stream:
        raw = np.frombuffer(stream.read(), dtype='<u8').reshape(-1, 2)
    events = {}
    for low, high in raw:
        low, high = int(low), int(high)
        source = sources[(high >> 11) & 127]
        if source['clock_domain'] != 'app':
            raise ValueError('Expected a common FPGA clock domain')
        stamp = ((high >> 18) - source['pipeline_stages']) * 5
        events.setdefault(low & 63, []).append(stamp)
    events = {eid: np.array(sorted(stamps)) for eid, stamps in events.items()}
    mac = [r for r in read_rows(DATA / 'w1-mac-latency.csv.gz')
           if r['label'] == 'w1-hot-w1' and r['warmup'] == '0']
    server = read_rows(DATA / 'w1-server.csv.gz')
    client = read_rows(DATA / 'w1-hot-w1.csv.gz')
    client_by_id = {int(r['request_id']): r for r in client if r['warmup'] == '0'}
    if len(mac) != meta['measured_count'] or len(client_by_id) != len(mac):
        raise ValueError('Incomplete Lauberhorn cohort')
    result = []
    for row in mac:
        rx, tx = int(row['rx_mac_ns']), int(row['tx_mac_ns'])
        stages = {}
        for eid in (3, 4, 9, 44, 45, 2, 5, 6):
            stamps = events[eid]
            window = stamps[np.searchsorted(stamps, rx):np.searchsorted(stamps, tx, side='right')]
            if len(window) != 1:
                raise ValueError(f'Nonunique/missing stage {eid} for {row["request_id"]}')
            stages[eid] = int(window[0])
        if list(stages.values()) != sorted(stages.values()) or stages[3] != rx or stages[6] != tx:
            raise ValueError('Invalid hardware stage ordering')
        # This is the first cohort after setup, so no cold preparation intervenes.
        sw = server[int(row['ordinal'])]
        request_id = int(row['request_id'])
        cl = client_by_id[request_id]
        if (int(row['ordinal']) != meta['setup_count'] + request_id - 1
                or int(sw['request_id']) != request_id or sw['ok'] != '1'
                or cl['ok'] != '1' or cl['rpc_status'] != '0'):
            raise ValueError('Lauberhorn software/hardware cohort identity mismatch')
        total = tx - rx
        if total != int(row['mac_latency_ns']) or total > int(cl['client_call_exit_ns'])-int(cl['client_call_enter_ns']):
            raise ValueError('Invalid Lauberhorn MAC/client interval')
        start, finish = int(sw['server_rx_exit_ns']), int(sw['server_tx_enter_ns'])
        enter, leave = int(sw['server_handler_enter_ns']), int(sw['server_handler_exit_ns'])
        if not start <= enter <= leave <= finish:
            raise ValueError('Invalid CPU stage ordering')
        handler = leave - enter
        rpc = finish-start-handler + stages[44]-stages[9] + stages[2]-stages[45]
        network = stages[9]-stages[4] + stages[5]-stages[2]
        record = components(request_id, total, handler, rpc, network)
        unmarshal = int(sw['server_unmarshal_exit_ns']) - int(sw['server_unmarshal_enter_ns'])
        marshal = int(sw['server_marshal_exit_ns']) - int(sw['server_marshal_enter_ns'])
        record['rpc_detail'] = dict(unmarshal_stage=unmarshal, marshal_stage=marshal,
                                  other_cpu_runtime=finish-start-handler-unmarshal-marshal,
                                  fpga_rpc=stages[44]-stages[9]+stages[2]-stages[45])
        if min(record['rpc_detail'].values()) < 0 or sum(record['rpc_detail'].values()) != rpc:
            raise ValueError('Invalid RPC substage partition')
        result.append(record)
    return result

def dpdk(meta):
    server = read_rows(DATA / meta['server'])
    client = read_rows(DATA / meta['client'])
    expected = meta['measured_count'] + meta['warmup_count']
    if len(server) != expected or len(client) != expected:
        raise ValueError('Incomplete DPDK cohort')
    for path in meta['run_summaries']:
        run = json.loads((DATA / path).read_text())
        if run['status'] != 'pass' or not run['restored'] or run['returncode'] != 0:
            raise ValueError('DPDK run or restoration failed')
    result = []
    for index, (sw, cl) in enumerate(zip(server, client), 1):
        warmup = str(int(index <= meta['warmup_count']))
        if (int(sw['request_id']) != index or int(cl['request_id']) != index
                or sw['status'] != '1' or cl['ok'] != '1' or cl['rpc_status'] != '0'
                or sw['warmup'] != warmup or cl['warmup'] != warmup
                or sw['profile'] != '1' or sw['injected_delay_ns'] != '0'):
            raise ValueError('DPDK request identity/status/profile mismatch')
        fields = ('cpu_rx_ns', 'network_rx_begin_ns', 'network_rx_end_ns',
                  'network_tx_begin_ns', 'network_tx_end_ns', 'cpu_tx_ns')
        start, rx_begin, rx_end, tx_begin, tx_end, finish = [int(sw[k]) for k in fields]
        if [start, rx_begin, rx_end, tx_begin, tx_end, finish] != sorted((start, rx_begin, rx_end, tx_begin, tx_end, finish)) or start <= 0:
            raise ValueError('Invalid DPDK CPU stage ordering')
        total = int(sw['tx_hw_ns']) - int(sw['rx_hw_ns'])
        if total != int(sw['mac_to_mac_ns']) or total > int(cl['client_call_exit_ns'])-int(cl['client_call_enter_ns']):
            raise ValueError('Invalid DPDK MAC/client interval')
        network = rx_end-rx_begin + tx_end-tx_begin
        # Fixed-input handler is folded into the encoded constant result.
        record = components(index, total, 0, finish-start-network, network)
        if warmup == '0':
            result.append(record)
    return result

def median_bar(rows):
    ordered = sorted(rows, key=lambda r: (r['total'], r['request_id']))
    n = len(ordered)
    chosen = ordered[(n-1)//2:n//2+1]
    values = {key: sum(row[key] for row in chosen)/len(chosen)
              for key in ('total', *CATEGORIES)}
    if values['total'] != float(np.median([r['total'] for r in rows])):
        raise ValueError('Representative rows do not give the median total')
    result = dict(count=n, request_ids=[r['request_id'] for r in chosen], ns=values)
    if 'rpc_detail' in chosen[0]:
        result['rpc_detail_ns'] = {key: sum(row['rpc_detail'][key] for row in chosen)/len(chosen)
                                   for key in chosen[0]['rpc_detail']}
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=DATA/'breakdown/manifest.json')
    parser.add_argument('--output', type=Path, default=paper_directory())
    parser.add_argument('--summary', type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    for name, digest in manifest['files'].items():
        if hashlib.sha256((DATA/name).read_bytes()).hexdigest() != digest:
            raise ValueError(f'Input hash mismatch: {name}')
    stats = {'LH': median_bar(lauberhorn(manifest['lauberhorn'])),
             'DPDK': median_bar(dpdk(manifest['dpdk']))}
    fig, ax = paper_chart.figure(height=1.8)
    fig.subplots_adjust(left=.25, right=.975, bottom=.25, top=.72)
    left = np.zeros(2)
    for category, label, color in zip(CATEGORIES, LABELS, paper_chart.COLORS):
        values = np.array([r['ns'][category]/1000 for r in stats.values()])
        ax.barh([1, 0], values, left=left, height=.45, label=label, color=color)
        for y, start, value in zip((1, 0), left, values):
            center = start + value/2
            if value >= .45:
                ax.text(center, y, f'{value:.3f}', ha='center', va='center', fontsize=7)
            else:
                # Keep adjacent small DPDK segments legible at column width.
                dx, align = ((-9, 'right') if value == 0 else
                             (-4, 'center') if y == 0 and category == 'rpc' else
                             (2, 'center') if y == 0 and category == 'network' else
                             (0, 'center'))
                ax.annotate('0' if value == 0 else f'{value:.3f}',
                            (center, y+.225), xytext=(dx, 3), textcoords='offset points',
                            ha=align, va='bottom', fontsize=6.5, color=color)
        left += values
    ax.set(yticks=[1, 0], yticklabels=list(stats), ylim=(-.55, 1.55), xlim=(0, max(left)*1.05),
           xlabel=r'MAC-to-MAC latency ($\mu$s)')
    ax.grid(False)
    ax.legend(loc='lower center', bbox_to_anchor=(.42, 1.08), ncol=2,
              fontsize=7, columnspacing=1.1, handlelength=1.3)
    paper_chart.save(fig, args.output/'nullrpc-hot-latency-breakdown.pdf')
    text = json.dumps(stats, indent=2)+'\n'
    if args.summary:
        args.summary.write_text(text)
    print(text, end='')

if __name__ == '__main__':
    main()
