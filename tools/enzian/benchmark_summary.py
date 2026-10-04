#!/usr/bin/env python3
"""Summarize all client samples without dropping failures or hiding warmup.

Optionally compare two plot_latency.py audit reports using per-request external
residuals and FPGA-clock TX durations. Percentile differences are descriptive,
not causal attribution or subtraction of percentiles to construct residuals.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path


def distribution(values):
    values = sorted(values)
    if not values:
        return None
    def pct(p):
        i = (len(values)-1)*p
        lo = math.floor(i)
        hi = math.ceil(i)
        return values[lo] + (values[hi]-values[lo])*(i-lo)
    return dict(count=len(values), minimum=values[0], p50=pct(.5), p90=pct(.9),
                p99=pct(.99), maximum=values[-1], p99_minus_p50=pct(.99)-pct(.5))


def summarize(path):
    with path.open() as f:
        rows = list(csv.DictReader(f))
    ids = [int(r['request_id']) for r in rows]
    if len(ids) != len(set(ids)):
        raise ValueError('duplicate request IDs')
    measured = [r for r in rows if not int(r.get('warmup', 0))]
    failures = [int(r['request_id']) for r in rows if int(r['ok']) != 1]
    result = dict(source=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                  rows=len(rows), warmup=len(rows)-len(measured), failures=failures,
                  measured_request_ids=[int(r['request_id']) for r in measured],
                  valid=bool(measured) and not failures,
                  metrics_ns={})
    fields = [('e2e_raw', 'client_call_enter_ns', 'client_call_exit_ns'),
              ('encode', 'client_call_enter_ns', 'client_tx_ready_ns'),
              ('tx_submit', 'client_tx_ready_ns', 'client_tx_submit_ns'),
              ('poll_to_receive', 'client_tx_submit_ns', 'client_rx_burst_ns'),
              ('validate', 'client_rx_burst_ns', 'client_call_exit_ns')]
    for name, start, end in fields:
        values = []
        for row in measured:
            if int(row['ok']) != 1 or not row.get(start) or not row.get(end):
                continue
            delta = int(row[end])-int(row[start])
            if delta < 0:
                raise ValueError(f"request {row['request_id']}: reversed {name} interval")
            values.append(delta)
        if values:
            result['metrics_ns'][name] = distribution(values)
    client_work = [int(r['client_tx_submit_ns'])-int(r['client_call_enter_ns'])+
                   int(r['client_call_exit_ns'])-int(r['client_rx_burst_ns'])
                   for r in measured if int(r['ok']) == 1 and
                   r.get('client_tx_submit_ns') and r.get('client_rx_burst_ns')]
    if client_work:
        result['metrics_ns']['client_software'] = distribution(client_work)
    corrected = [int(r['client_call_exit_ns'])-int(r['client_call_enter_ns'])-
                 int(r['timestamp_overhead_ns']) for r in measured if int(r['ok']) == 1]
    if corrected:
        if min(corrected) < 0:
            raise ValueError('timestamp overhead exceeds E2E')
        result['metrics_ns']['e2e_corrected'] = distribution(corrected)
        # Keep first/last quartiles in request order to expose drift.
        width = max(1,len(corrected)//4)
        result['drift_ns'] = dict(first_quarter=distribution(corrected[:width]),
                                 last_quarter=distribution(corrected[-width:]))
    return result


def validate_audit(result, audit):
    if audit['provenance']['client_sha256'] != result['sha256']:
        raise ValueError('audit belongs to a different client CSV')
    if audit['provenance'].get('allow_partial_trace'):
        raise ValueError('diagnostic partial-trace audit is not a performance result')
    if sorted(r['request_id'] for r in audit['rows']) != sorted(result['measured_request_ids']):
        raise ValueError('audit request IDs do not match measured client requests')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('client_csv', type=Path)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--audit', type=Path)
    p.add_argument('--baseline-audit', type=Path)
    args = p.parse_args()
    result = summarize(args.client_csv)
    if args.audit:
        audit = json.loads(args.audit.read_text())
        validate_audit(result, audit)
        result['trace_metrics_ns'] = audit['metrics']
        residuals = [r['external_residual_ns'] for r in audit['rows'] if 'external_residual_ns' in r]
        result['trace_coverage'] = dict(matched=len(residuals), expected=result['rows']-result['warmup'])
        result['valid'] &= len(residuals) == result['trace_coverage']['expected']
        if residuals:
            width = max(1,len(residuals)//4)
            result['external_residual_drift_ns'] = dict(
                first_quarter=distribution(residuals[:width]), last_quarter=distribution(residuals[-width:]))
        if args.baseline_audit:
            baseline = json.loads(args.baseline_audit.read_text())
            result['comparison_ns'] = {key: {p: values[p]-baseline['metrics'][key][p]
                for p in ('p50','p90','p99')} for key,values in audit['metrics'].items()
                if key in baseline['metrics']}
            result['comparison_limit'] = 'Descriptive distributions; verify matched workload, maps, clocks, artifacts and topology before causal claims.'
    elif args.baseline_audit:
        p.error('--baseline-audit requires --audit')
    with args.output.open('x') as f:
        json.dump(result,f,indent=2); f.write('\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('drift_ns', 'measured_request_ids')},indent=2))
    return 0 if result['valid'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
