#!/usr/bin/env python3
"""Validate the paired CPU-NIC baseline samples and reproduce statistics."""
import csv
import gzip
import json
from pathlib import Path
from statistics import mean, median

root = Path(__file__).resolve().parent

def percentile(values, p):
    ordered = sorted(values)
    position = (len(ordered) - 1) * p
    index = int(position)
    return ordered[index] + (ordered[min(index + 1, len(ordered) - 1)] - ordered[index]) * (position - index)

results = {}
for label, count, warmup in [('pilot', 100, 16), ('delay20us', 100, 16), ('hot', 10000, 128)]:
    samples = {}
    for role in ('server', 'client'):
        with gzip.open(root / f'{label}-{role}.csv.gz', 'rt') as f:
            samples[role] = list(csv.DictReader(f))
        assert len(samples[role]) == count + warmup
        run = json.loads((root / f'{label}-{role}-run.json').read_text())
        assert run['status'] == 'pass' and run['restored'] and run['returncode'] == 0
    for index, (server, client) in enumerate(zip(samples['server'], samples['client']), 1):
        assert int(server['request_id']) == int(client['request_id']) == index
        assert int(server['warmup']) == int(client['warmup']) == (index <= warmup)
        assert server['status'] == client['ok'] == '1' and client['rpc_status'] == '0'
        rx, tx, elapsed = (int(server[k]) for k in ('rx_hw_ns', 'tx_hw_ns', 'mac_to_mac_ns'))
        assert 0 < rx < tx and tx - rx == elapsed
        assert elapsed <= int(client['client_call_exit_ns']) - int(client['client_call_enter_ns'])
        if index > 1:
            assert rx > int(samples['server'][index - 2]['rx_hw_ns'])
    values = [int(row['mac_to_mac_ns']) for row in samples['server'] if row['warmup'] == '0']
    results[label] = dict(measured=count, warmup=warmup, min_ns=min(values), mean_ns=mean(values),
                          p50_ns=median(values), p90_ns=percentile(values, .9),
                          p95_ns=percentile(values, .95), p99_ns=percentile(values, .99),
                          p999_ns=percentile(values, .999), max_ns=max(values),
                          first_quarter_p50_ns=median(values[:count//4]),
                          last_quarter_p50_ns=median(values[-count//4:]))
results['delay_control_shift_ns'] = results['delay20us']['p50_ns'] - results['pilot']['p50_ns']
assert abs(results['delay_control_shift_ns'] - 20000) < 1000
print(json.dumps(results, indent=2))
