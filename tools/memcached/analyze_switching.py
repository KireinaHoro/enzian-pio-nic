#!/usr/bin/env python3
"""Prove finite worker sharing from a complete loss-free cohort trace."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools/enzian'))
from analyze_rx_holds import activation_audit, read_records
import dma_decode as dma


def analyze(records, launch):
    expected = {p['pid'] for p in launch['processes']}
    physical = launch['case']['physical_workers']
    if sum(p['workers'] for p in launch['processes']) <= physical:
        raise ValueError('cohort is not oversubscribed')
    owners = {}
    yielded = Counter()
    consumed_yields = Counter()
    dispatch = defaultdict(Counter)
    transitions = []
    assignments = []
    counts = Counter()
    for timestamp, name, fields in sorted(records, key=lambda row: row[0]):
        counts[name] += 1
        core = fields.get('CoreID')
        if name == 'Lost' and fields['count']:
            raise ValueError('trace contains loss')
        if name == 'YieldAccepted' and not fields.get('PreemptOutOfIdle', 0):
            yielded[core] += 1
        if name not in ('SchedulerProcessRun', 'SchedulerRequestDispatched'):
            continue
        pid = fields['ProcessID']
        if core not in range(1, physical+1) or pid not in expected:
            raise ValueError(f'unexpected dispatch/assignment identity: core={core}, pid={pid}')
        if name == 'SchedulerRequestDispatched':
            dispatch[pid][core] += 1
            if owners.get(core) != pid:
                raise ValueError('dispatch without matching recorded assignment')
            continue
        previous = owners.get(core)
        row = dict(timestamp=timestamp, datapath=core, pid=pid)
        assignments.append(row)
        if previous is not None and previous != pid:
            if yielded[core] <= consumed_yields[core]:
                raise ValueError('process changed without an ordinary yield')
            transitions.append(dict(row, previous_pid=previous))
        owners[core] = pid
        consumed_yields[core] = yielded[core]
    if set(dispatch) != expected:
        raise ValueError('not all instances received dispatched requests')
    used = set().union(*(set(c) for c in dispatch.values()))
    if used != set(range(1, physical+1)):
        raise ValueError('not all physical workers were exercised')
    if not transitions:
        raise ValueError('no observed worker reassignment')
    if any(counts[name] != len(assignments) for name in ('ActivationCommitted', 'ActivationDelivered')):
        raise ValueError('incomplete activation/assignment observations')
    audit = activation_audit(sorted(records, key=lambda row: row[0]))
    if not all(audit['first_access_parity_checks'].get(direction) for direction in ('rx','tx')):
        raise ValueError('missing first-access parity evidence')
    if audit['parity_mismatches']:
        raise ValueError('activation parity mismatch')
    return dict(passed=True, process_worker_dispatches=dict(dispatch),
                ordinary_yields=dict(yielded), transitions=transitions,
                assignments=assignments, event_counts=dict(counts), activation_audit=audit,
                datapath_to_linux_cpu={i+1:cpu for i,cpu in enumerate(launch['worker_cpus'])})


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--trace', type=Path, required=True)
    p.add_argument('--map', type=Path, required=True)
    p.add_argument('--launch', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    result = analyze(read_records(a.trace, dma.load_map(a.map)), json.loads(a.launch.read_text()))
    result['trace_sha256'] = hashlib.file_digest(a.trace.open('rb'), 'sha256').hexdigest()
    result['map_sha256'] = hashlib.sha256(a.map.read_bytes()).hexdigest()
    a.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ('passed','process_worker_dispatches','ordinary_yields')}))
    print('Verified process changes:', len(result['transitions']))
