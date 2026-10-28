#!/usr/bin/env python3
"""Measure finite RX request-to-NACK intervals using the matching trace map.

These intervals include protocol/response overhead, not just timer cycles.
Transport event counts are not application RPC completion counts.
"""
import argparse
from collections import Counter, defaultdict, deque
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/trace/eci/sys_trace'))
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


def activation_audit(records):
    """Check only complete, loss-free activation-to-first-access observations."""
    expected, owners, departed = {}, {}, {}
    locations = defaultdict(set)
    history = defaultdict(lambda: deque(maxlen=3))
    checks, pairs, migrations, reuse = Counter(), Counter(), Counter(), Counter()
    mismatches = []
    for timestamp, name, fields in records:
        core = fields.get('CoreID')
        if name == 'Lost' and fields['count']:
            expected.clear()
            locations.clear()
            owners.clear()
            departed.clear()
            history.clear()
        elif name == 'ActivationCommitted':
            expected[core] = dict(rx=fields['RxNext'], tx=fields['TxNext'],
                                  domain=fields['DomainCode'])
            pairs[f"rx={fields['RxNext']}:tx={fields['TxNext']}"] += 1
        elif name == 'ActivationDelivered':
            domain = fields['DomainCode']
            previous = owners.get(core)
            if previous is not None and previous != domain:
                locations[previous].discard(core)
                if not locations[previous]:
                    departed[previous] = core
            # Multiple workers can serve one domain concurrently. Expansion is
            # not migration: require a fully observed departure from its last
            # worker, then activation on a different worker without trace loss.
            if not locations[domain] and domain in departed and departed[domain] != core:
                migrations[f'domain={domain}:{departed[domain]}->{core}'] += 1
            locations[domain].add(core)
            owners[core] = domain
            history[core].append(domain)
            if len(history[core]) == 3 and history[core][0] == domain and history[core][1] != domain:
                reuse[str(core)] += 1
        elif name in ('EciRxReadFirst', 'EciRxReadNew', 'EciTxAcquire'):
            direction = 'tx' if name == 'EciTxAcquire' else 'rx'
            if core in expected and direction in expected[core]:
                value = expected[core].pop(direction)
                actual = fields['CacheLineIndex']
                checks[direction] += 1
                if value != actual:
                    mismatches.append(dict(cycle=timestamp, core=core,
                                           domain=expected[core]['domain'],
                                           direction=direction, expected=value, observed=actual))
    return dict(first_access_parity_checks=dict(checks), parity_mismatches=mismatches,
                committed_parity_pairs=dict(pairs), observed_domain_migrations=dict(migrations),
                observed_aba_by_core=dict(reuse),
                scope='CoreID is worker_index + 1, not Linux CPU ID. Only fully observed pairs are checked; migration and A-B-A events alone do not prove cache freshness.')


def summarize(records, clock_hz, minimum_hold_cycles=0):
    records = sorted(records, key=lambda row: row[0])
    pending, preempt = {}, set()
    holds, unmatched = [], Counter()
    counts, losses = Counter(), 0
    activations, dispatches = Counter(), Counter()
    peer_events = []
    for timestamp, name, fields in records:
        counts[name] += 1
        core = fields.get('CoreID')
        if name == 'Lost':
            losses += fields['count']
            unmatched['interrupted_by_loss'] += len(pending)
            pending.clear()
            preempt.clear()
        elif name == 'SchedulerDeschedCore' and not fields.get('DeschedOutOfIdle', 0):
            preempt.add(core)
        elif name == 'YieldAccepted':
            preempt.discard(core)
            kind = 'idle' if fields.get('DeschedOutOfIdle', 0) else 'ordinary'
            activations[f"{kind}:core={core}:domain={fields.get('DomainCode')}"] += 1
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
        if name == 'SchedulerRequestDispatched':
            dispatches[f"process={fields.get('ProcessID')}:core={core}"] += 1
        if name in ('EciRxDescSent', 'EciTxSubmit', 'TxCmacExit', 'ActivationDelivered') or (name == 'YieldAccepted' and not fields.get('DeschedOutOfIdle', 0)):
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
    # Count events occurring while N distinct worker reads overlap. Bypass is
    # datapath zero and is never included among stalled application workers.
    import heapq
    qualifying = sorted((h for h in holds if h['core'] and not h['handoff_pending']
                         and h['cycles'] >= minimum_hold_cycles), key=lambda h: h['start_cycle'])
    active, ending, cursor = Counter(), [], 0
    concurrency = defaultdict(Counter)
    for timestamp, core, name in peer_events:
        while cursor < len(qualifying) and qualifying[cursor]['start_cycle'] <= timestamp:
            hold = qualifying[cursor]
            heapq.heappush(ending, (hold['end_cycle'], cursor, hold['core']))
            active[hold['core']] += 1
            cursor += 1
        while ending and ending[0][0] < timestamp:
            _, _, worker = heapq.heappop(ending)
            active[worker] -= 1
            if not active[worker]:
                del active[worker]
        if active and core not in active:
            concurrency[len(active)][f'{name}:core={core}'] += 1
    return dict(clock_hz=clock_hz, minimum_hold_cycles=minimum_hold_cycles,
                peer_events_by_simultaneously_stalled_workers={str(n): dict(events) for n, events in concurrency.items()},
                lost_samples=losses, event_counts=dict(counts),
                accepted_activations=dict(activations), process_worker_dispatches=dict(dispatches),
                activation_audit=activation_audit(records),
                unmatched=dict(unmatched), holds=holds,
                scope='Finite request-to-NACK intervals include transport overhead; event counts do not prove application completion or infinite-stall progress.')


def read_records(capture, trace_map):
    def selected(sample):
        source = dma.source_info(trace_map, dma.sample_source(sample, trace_map))
        return source.get('type') == 'lauberhorn_event' or dma.sample_source(sample, trace_map) == trace_map['sample']['lost_source']
    rows = dma.filtered_adjusted_chronological_samples(capture, trace_map, 0, selected)
    records = []
    for _, _, sample, timestamp, _ in rows:
        source = dma.sample_source(sample, trace_map)
        if source == trace_map['sample']['lost_source']:
            count = dma.sample_lost_count(sample, trace_map)
            if count:
                records.append((timestamp, 'Lost', {'count': count}))
        else:
            decoded = event(sample, trace_map)
            if decoded:
                records.append((timestamp, *decoded))
    # The decoder's adjusted timestamp is source-delay corrected already.
    # Its internal unwrapped raw timestamp can acquire false epochs at bubble
    # markers. Never derive durations from that value. This analyzer supports
    # short captures away from a real counter wrap; fail closed otherwise.
    if records and max(r[0] for r in records) - min(r[0] for r in records) >= dma.timestamp_modulus(trace_map) // 2:
        raise ValueError('capture crosses timestamp wrap or spans half the counter period')
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--map', type=Path, required=True)
    parser.add_argument('--clock-hz', type=int, required=True)
    parser.add_argument('--minimum-hold-cycles', type=int, default=0,
                        help='only count simultaneous stalls at least this long; use the programmed hold')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.clock_hz <= 0 or args.minimum_hold_cycles < 0:
        parser.error('positive clock frequency and nonnegative minimum hold required')
    trace_map = dma.load_map(args.map)
    records = read_records(args.capture, trace_map)
    result = summarize(records, args.clock_hz, args.minimum_hold_cycles)
    result['capture_sha256'] = hashlib.file_digest(args.capture.open('rb'), 'sha256').hexdigest()
    result['map_sha256'] = hashlib.sha256(args.map.read_bytes()).hexdigest()
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ('lost_samples', 'unmatched', 'event_counts')}))
    print(f"Measured {len(result['holds'])} finite request-to-NACK intervals; details: {args.output}")


if __name__ == '__main__':
    main()
