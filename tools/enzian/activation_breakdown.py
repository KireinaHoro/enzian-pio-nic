#!/usr/bin/env python3
"""Join embedded raw-clock kernel activation records to runtime request rows.
Kernel components form a separate, non-overlapping activation partition. They
are nested diagnostics, NOT extra terms to add to the existing FPGA/E2E stack.
"""
import argparse
import csv
import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path

STAGES = ['irq', 'dispatch', 'read_begin', 'read_end', 'claim', 'affinity',
          'wake', 'resume', 'return', 'ioctl_exit', 'ready', 'rx_enter']
COMPONENTS = ['irq_to_dispatch', 'dispatch_to_mmio', 'preempt_info_mmio',
              'claim_wait', 'affinity_setup', 'activation_publish',
              'wake_to_wait_return', 'yield_copyout', 'kernel_to_userspace',
              'runtime_activation_setup', 'runtime_before_rx']


def distribution(values):
    v = sorted(values)
    if not v:
        raise ValueError('empty distribution')
    def percentile(p):
        position = (len(v)-1)*p/100
        lo = int(position)
        hi = min(lo+1, len(v)-1)
        return v[lo] + (v[hi]-v[lo])*(position-lo)
    return dict(count=len(v), **{'p'+str(p): percentile(p) for p in (50,90,95,99,100)})


def parse_trace(text):
    if re.search(r'LOST.*EVENTS', text, re.I):
        raise ValueError('lost kernel trace events')
    records = []
    seen = set()
    for line in text.splitlines():
        if not re.search(r'\b(?:lauberhorn:)?activation:', line):
            continue
        d = {k:int(v) for k,v in re.findall(r'\b(\w+)=([0-9]+)', line)}
        if not set(STAGES[:9]+['worker','sequence','domain','tid','cold']) <= d.keys():
            raise ValueError('incomplete kernel activation record')
        key = (d['worker'],d['sequence'])
        if key in seen:
            raise ValueError('duplicate activation identity')
        seen.add(key)
        chain = STAGES[:9] if d['cold'] else STAGES[2:9]
        if any(d[k] <= 0 for k in chain) or any(d[b]<d[a] for a,b in zip(chain,chain[1:])):
            raise ValueError('missing/reversed activation stamps')
        records.append(d)
    return records


def join(server_rows, records, audit, condition, margin_ns=1000):
    # The audit is made with all warmup rows retained for correlation; its output
    # already excludes warmup. Do not silently analyze just surviving RPCs.
    by_request = {}
    for s in server_rows:
        key = int(s['request_id'])
        if key in by_request:
            raise ValueError('duplicate runtime request ID')
        by_request[key] = s
    by_tid = defaultdict(list)
    for r in records:
        by_tid[r['tid']].append(r)
    if len({r['request_id'] for r in audit['rows']}) != len(audit['rows']):
        raise ValueError('duplicate audit request IDs')
    result = []
    cold_used = set()
    for row in audit['rows']:
        if row.get('client_ok', 1) != 1 or row.get('server_ok', 1) != 1:
            raise ValueError('failed RPC in performance audit')
        s = by_request[int(row['request_id'])]
        tid = int(s['server_tid'])
        end = int(s['server_activation_ioctl_exit_ns'])
        ready = int(s['server_activation_ready_ns'])
        worker = int(s['activation_worker'])
        if not (0 < end <= ready <= int(s['server_rx_enter_ns'])):
            raise ValueError('invalid runtime activation stamps')
        # Epoch alignment is inferred by the existing trace audit. Require a
        # margin and disclose it; kernel component durations do not use this fit.
        arrival = row['trace_rx_cmac_entry_ns'] + row['trace_cpu_offset_ns']
        if abs(ready-arrival) <= margin_ns:
            raise ValueError('activation/arrival boundary ambiguous within clock-fit margin')
        actual = 'hot' if ready < arrival else 'cold'
        if actual != condition:
            raise ValueError('request does not satisfy requested '+condition+' condition')
        if int(s['rx_cpu_id']) != row['rx_cpu_id'] or worker != row['datapath_core']-1:
            raise ValueError('worker identity mismatch')
        output = {'request_id':row['request_id'], 'worker':worker, 'tid':tid,
                  'condition':actual, 'roundtrip_ns':row['e2e_raw_ns'],
                  'e2e_breakdown_ns':row['breakdown']}
        if actual == 'cold':
            candidates = [r for r in by_tid[tid] if r['worker']==worker and
                          arrival <= r['return'] <= end]
            if len(candidates) != 1:
                raise ValueError('missing or ambiguous kernel activation for cold request')
            r = dict(candidates[0], ioctl_exit=end, ready=ready, rx_enter=int(s['server_rx_enter_ns']))
            key = (worker,r['sequence'])
            if key in cold_used:
                raise ValueError('multiple cold requests attributed to one activation')
            cold_used.add(key)
            start = 0 if r['cold'] else 2
            if any(r[b]<r[a] for a,b in zip(STAGES[start:],STAGES[start+1:])):
                raise ValueError('reversed kernel/userspace boundary')
            parts = {COMPONENTS[i]:r[STAGES[i+1]]-r[STAGES[i]] for i in range(start,len(STAGES)-1)}
            assert sum(parts.values()) == r['rx_enter']-r[STAGES[start]]
            output.update(activation_sequence=r['sequence'], domain=r['domain'],
                          activation_path='idle_dispatch' if r['cold'] else 'cooperative',
                          activation_components_ns=parts,
                          activation_total_ns=sum(parts.values()))
        result.append(output)
    return result


def summarize(rows):
    groups = defaultdict(list)
    for row in rows:
        groups['all'].append(row)
        groups[str(row['worker'])].append(row)
    result = {}
    for worker, group in groups.items():
        metrics = {'roundtrip':distribution([r['roundtrip_ns'] for r in group])}
        for field in ('e2e_breakdown_ns','activation_components_ns'):
            names = sorted(set().union(*(r.get(field,{}).keys() for r in group)))
            metrics[field] = {n:distribution([r[field][n] for r in group if n in r.get(field,{})]) for n in names}
        result[worker] = metrics
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--capture', type=Path, required=True)
    p.add_argument('--server', type=Path, required=True)
    p.add_argument('--audit', type=Path, required=True)
    p.add_argument('--condition', choices=['hot','cold'], required=True)
    p.add_argument('--margin-ns', type=int, default=1000)
    p.add_argument('--expected-per-worker', type=int, default=1000)
    p.add_argument('--expected-workers', type=int, nargs='+', default=list(range(4)),
                   help='physical worker IDs required in this campaign (default: 0 1 2 3)')
    p.add_argument('--expected-total', type=int,
                   help='require this total across observed workers instead of per-worker counts')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.margin_ns < 0 or a.expected_per_worker <= 0:
        p.error('nonnegative margin and positive expected sample count required')
    if len(set(a.expected_workers)) != len(a.expected_workers) or any(w not in range(4) for w in a.expected_workers):
        p.error('expected workers must be distinct physical IDs in 0..3')
    metadata = json.loads((a.capture/'capture.json').read_text())
    if not metadata.get('loss_free') or metadata.get('returncode') != 0:
        raise ValueError('kernel capture is incomplete or its command failed')
    audit = json.loads(a.audit.read_text())
    digest = hashlib.sha256(a.server.read_bytes()).hexdigest()
    if audit['provenance']['server_sha256'] != digest or audit['provenance'].get('allow_partial_trace'):
        raise ValueError('unmatched/partial trace audit')
    records = parse_trace((a.capture/'trace.txt').read_text())
    with a.server.open() as f:
        rows = join(list(csv.DictReader(f)),records,audit,a.condition,a.margin_ns)
    counts = {w:sum(r['worker']==w for r in rows) for w in sorted(set(r['worker'] for r in rows))}
    if a.expected_total is not None:
        if a.expected_total <= 0 or len(rows) != a.expected_total:
            raise ValueError('expected total measured request count not met: '+str(counts))
    elif set(counts) != set(a.expected_workers) or any(v != a.expected_per_worker for v in counts.values()):
        raise ValueError('expected measured request count per physical worker not met: '+str(counts))
    output = {'condition':a.condition,'metrics_ns':summarize(rows),'rows':rows,
              'kernel_clock':'embedded CLOCK_MONOTONIC_RAW; no cross-clock subtraction for components',
              'classification':'runtime readiness vs FPGA arrival with inferred clock offset',
              'classification_margin_ns':a.margin_ns,
              'accounting':'activation components partition activation only; do NOT add to E2E breakdown',
              'capture':metadata,'server_sha256':digest,
              'audit_sha256':hashlib.sha256(a.audit.read_bytes()).hexdigest()}
    a.output.write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps({'condition':a.condition,'counts':counts,'output':str(a.output)}))


if __name__ == '__main__':
    main()
