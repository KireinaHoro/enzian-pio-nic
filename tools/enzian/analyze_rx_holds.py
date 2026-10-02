#!/usr/bin/env python3
"""Measure finite RX request-to-NACK intervals using the matching trace map.

These intervals include protocol/response overhead, not just timer cycles.
Transport event counts are not application RPC completion counts.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'data/eci/sys_trace'))
import dma_decode as dma


def event(sample, trace_map):
    source = dma.source_info(trace_map, dma.sample_source(sample, trace_map))
    if source.get('type') != 'lauberhorn_event':
        return None
    fmt = trace_map['payload_formats']['lauberhorn_event']
    payload = dma.sample_payload(sample, trace_map)
    field = fmt['fields']['event_id']
    code = str((payload >> field['offset']) & ((1 << field['width']) - 1))
    name = fmt['events'].get(code)
    offset = max(v['offset'] + v['width'] for k, v in fmt['fields'].items()
                 if k in ('event_id', 'reserved_event_id'))
    values = {}
    for key in fmt['event_data'].get(code, []):
        if isinstance(key, dict):
            width, offset, key = key['width'], key['offset'], key['name']
        else:
            width = fmt['trace_data_keys'][key]['width']
        values[key] = (payload >> offset) & ((1 << width) - 1)
        offset += width
    return name, values


def summarize(records, clock_hz):
    pending, preempt = {}, set()
    holds, unmatched = [], Counter()
    counts, losses = Counter(), 0
    peer_events = []
    for timestamp, name, fields in sorted(records, key=lambda row: row[0]):
        counts[name] += 1
        core = fields.get('CoreID')
        if name == 'Lost':
            losses += fields['count']
            unmatched['interrupted_by_loss'] += len(pending)
            pending.clear()
            preempt.clear()
        elif name == 'SchedulerPreemptCore' and not fields.get('PreemptOutOfIdle', 0):
            preempt.add(core)
        elif name == 'YieldAccepted':
            preempt.discard(core)
        elif name in ('EciRxReadFirst', 'EciRxReadNew'):
            if core in pending:
                unmatched['replaced_read'] += 1
            pending[core] = timestamp
        elif name == 'EciRxDescSent':
            pending.pop(core, None)
        elif name == 'EciRxNackSent':
            if core not in pending:
                unmatched['nack_without_read'] += 1
            else:
                start = pending.pop(core)
                holds.append(dict(core=core, start_cycle=start, end_cycle=timestamp,
                                  cycles=timestamp-start, microseconds=(timestamp-start)*1e6/clock_hz,
                                  handoff_pending=core in preempt))
        if name in ('EciRxDescSent', 'EciTxSubmit', 'TxCmacExit', 'ActivationDelivered'):
            peer_events.append((timestamp, core, name))
    unmatched['open_read_at_capture_end'] = len(pending)
    # Aggregate overlap evidence without constructing a quadratic event product.
    from bisect import bisect_left, bisect_right
    indexed = defaultdict(list)
    for timestamp, core, name in peer_events:
        indexed[(core, name)].append(timestamp)
    for hold in holds:
        overlap = {}
        for (core, name), times in indexed.items():
            if core == hold['core']:
                continue
            count = bisect_right(times, hold['end_cycle']) - bisect_left(times, hold['start_cycle'])
            if count:
                overlap[f'{name}:core={core}'] = count
        hold['other_transport_events_during_hold'] = overlap
    return dict(clock_hz=clock_hz, lost_samples=losses, event_counts=dict(counts),
                unmatched=dict(unmatched), holds=holds,
                scope='Finite request-to-NACK intervals include transport overhead; event counts do not prove application completion or infinite-stall progress.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--map', type=Path, required=True)
    parser.add_argument('--clock-hz', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.clock_hz <= 0:
        parser.error('clock frequency must be positive')
    trace_map = dma.load_map(args.map)
    def selected(sample):
        source = dma.source_info(trace_map, dma.sample_source(sample, trace_map))
        return source.get('type') == 'lauberhorn_event' or dma.sample_source(sample, trace_map) == trace_map['sample']['lost_source']
    rows = dma.filtered_adjusted_chronological_samples(args.capture, trace_map, 0, selected)
    records = []
    for _, _, sample, _, raw_timestamp in rows:
        source = dma.sample_source(sample, trace_map)
        timestamp = raw_timestamp - dma.source_pipeline_stages(source, trace_map)
        if source == trace_map['sample']['lost_source']:
            count = dma.sample_lost_count(sample, trace_map)
            if count:
                records.append((timestamp, 'Lost', {'count': count}))
        else:
            decoded = event(sample, trace_map)
            if decoded:
                records.append((timestamp, *decoded))
    result = summarize(records, args.clock_hz)
    result['capture_sha256'] = hashlib.file_digest(args.capture.open('rb'), 'sha256').hexdigest()
    result['map_sha256'] = hashlib.sha256(args.map.read_bytes()).hexdigest()
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ('lost_samples', 'unmatched', 'event_counts')}))
    print(f"Measured {len(result['holds'])} finite request-to-NACK intervals; details: {args.output}")


if __name__ == '__main__':
    main()
