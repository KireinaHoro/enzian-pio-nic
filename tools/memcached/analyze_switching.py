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
    active = {}
    assignment_time = {}
    peak = Counter()
    queued = {}
    queue_latencies = []
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
        if name == 'YieldAccepted':
            active.pop(core, None)
        if name == 'SchedulerRequestQueued':
            token = fields['HostMsgID']
            if token in queued:
                raise ValueError('request ID reused before dispatch')
            queued[token] = (timestamp, fields['ProcessID'], fields['ProcessID'] in active.values())
        if name == 'YieldAccepted' and not fields.get('DeschedOutOfIdle', 0):
            yielded[core] += 1
        if name not in ('SchedulerProcessRun', 'SchedulerRequestDispatched'):
            continue
        pid = fields['ProcessID']
        if core not in range(1, physical+1) or pid not in expected:
            raise ValueError(f'unexpected dispatch/assignment identity: core={core}, pid={pid}')
        if name == 'SchedulerRequestDispatched':
            dispatch[pid][core] += 1
            if 'HostMsgID' in fields:
                start, queued_pid, resident = queued.pop(fields['HostMsgID'])
                if queued_pid != pid:
                    raise ValueError('queue/dispatch process mismatch')
                queue_latencies.append(dict(timestamp=start, dispatch_timestamp=timestamp, pid=pid,
                    datapath=core, cycles=timestamp-start, resident_at_enqueue=resident,
                    worker_assigned_at_enqueue=assignment_time[core] <= start))
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
        active[core] = pid
        assignment_time[core] = timestamp
        peak[pid] = max(peak[pid], sum(p == pid for p in active.values()))
        consumed_yields[core] = yielded[core]
    if any(peak[p['pid']] > p['workers'] for p in launch['processes']):
        raise ValueError('assignment exceeds declared parallelism')
    if queued:
        raise ValueError('queued requests without dispatch')
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
    return dict(passed=True, peak_assigned_workers=dict(peak), queue_latencies=queue_latencies, process_worker_dispatches=dict(dispatch),
                ordinary_yields=dict(yielded), transitions=transitions,
                assignments=assignments, event_counts=dict(counts), activation_audit=audit,
                datapath_to_linux_cpu={i+1:cpu for i,cpu in enumerate(launch['worker_cpus'])})


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--trace', type=Path, required=True)
    p.add_argument('--map', type=Path, required=True)
    p.add_argument('--launch', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--start-cycle', type=int, default=0, help='Explicit inclusive evidence window start')
    p.add_argument('--end-cycle', type=int, help='Explicit exclusive evidence window end')
    a = p.parse_args()
    if a.start_cycle < 0 or (a.end_cycle is not None and a.end_cycle <= a.start_cycle):
        p.error('invalid trace window')
    records = read_records(a.trace, dma.load_map(a.map))
    losses = [dict(timestamp=t, **f) for t,n,f in records if n == 'Lost' and f['count']]
    window = [r for r in records if r[0] >= a.start_cycle and (a.end_cycle is None or r[0] < a.end_cycle)]
    result = analyze(window, json.loads(a.launch.read_text()))
    result['trace_window'] = dict(start_cycle=a.start_cycle,end_cycle=a.end_cycle,
                                  full_capture_loss_markers=losses,
                                  scope='Only the selected window is audited; excluded history is not validated')
    result['trace_sha256'] = hashlib.file_digest(a.trace.open('rb'), 'sha256').hexdigest()
    result['map_sha256'] = hashlib.sha256(a.map.read_bytes()).hexdigest()
    a.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ('passed','process_worker_dispatches','ordinary_yields')}))
    print('Verified process changes:', len(result['transitions']))
