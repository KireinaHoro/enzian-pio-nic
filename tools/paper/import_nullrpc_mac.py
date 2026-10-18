#!/usr/bin/env python3
"""Extract and correlate a preserved one-worker null-RPC MAC trace.

With --trace/--map, archive relevant raw samples and matched map; otherwise
regenerate the per-request MAC CSV from those archived samples. No board access.
"""
import argparse
import bisect
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import numpy as np
from scipy.optimize import linprog

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'data/paper/nullrpc'
EVENTS = {0, 2, 3, 6, 7, 12, 51}
CAMPAIGN = "w1"


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def compressed(path, data):
    with path.open('wb') as stream:
        with gzip.GzipFile(filename='', mode='wb', fileobj=stream, mtime=0) as archive:
            archive.write(data)


def rows(name):
    with gzip.open(DATA / name, 'rt') as stream:
        return list(csv.DictReader(stream))


def extract(trace, map_path, *, data=None, campaign=None, events=EVENTS):
    data = DATA if data is None else Path(data)
    campaign = CAMPAIGN if campaign is None else campaign
    check = json.loads((data / f'acquisition/{campaign}-trace-check.json').read_text())
    if digest(trace) != check['sha256'] or digest(map_path) != check['map_sha256']:
        raise ValueError('Capture/map differs from validated campaign evidence')
    m = json.loads(map_path.read_text())
    if m['sample']['payload_width'] != 75 or m['sample']['source_width'] != 7:
        raise ValueError('Unsupported trace layout')
    sources = [s['source'] for s in m['sources'] if s['type'] == 'lauberhorn_event']
    values = np.memmap(trace, dtype='<u8', mode='r').reshape(-1, 2)
    selected = []
    for start in range(0, len(values), 1 << 20):
        block = values[start:start + (1 << 20)]
        src = (block[:, 1] >> 11) & 127
        if np.any((src == 127) & ((block[:, 0] & 0xffffffff) != 0)):
            raise ValueError('Stored trace loss')
        keep = np.isin(src, sources) & np.isin(block[:, 0] & 63, list(events))
        selected.append(block[keep].copy())
    compressed(data / f'{campaign}-mac-events.bin.gz', np.concatenate(selected).tobytes())
    (data / f'{campaign}-trace-map.json').write_bytes(map_path.read_bytes())


def correlate(*, data=None, campaign=None, include_workers=False):
    data = DATA if data is None else Path(data)
    campaign = CAMPAIGN if campaign is None else campaign
    m = json.loads((data / f'{campaign}-trace-map.json').read_text())
    fmt = m['payload_formats']['lauberhorn_event']
    with gzip.open(data / f'{campaign}-mac-events.bin.gz', 'rb') as stream:
        raw = np.frombuffer(stream.read(), dtype='<u8').reshape(-1, 2)
    sources = {s['source']: s for s in m['sources']}
    events = []
    for low, high in raw:
        low, high = int(low), int(high)
        src = (high >> 11) & 127
        info = sources[src]
        if info['clock_domain'] != 'app':
            raise ValueError('MAC correlation requires one FPGA clock domain')
        stamp = (high >> 18) - info['pipeline_stages']
        if stamp < 0:
            raise ValueError('Timestamp wrap requires explicit unwrapping')
        eid = low & 63
        fields = {}; offset = 6
        for key in fmt.get('event_data', {}).get(str(eid), []):
            width = fmt['trace_data_keys'][key]['width']
            fields[key] = (low >> offset) & ((1 << width) - 1)
            offset += width
        events.append((stamp * 5, eid, fields))
    events.sort(key=lambda event: event[0])
    # Packet/RPC identifiers wrap. Match only their current lifetime, never
    # globally join on a small identifier. MAC markers have no packet ID.
    mac = {eid: [t for t, e, _ in events if e == eid] for eid in (3, 6)}
    eth = {eid: {} for eid in (7, 12)}
    for t, eid, fields in events:
        if eid in eth:
            eth[eid].setdefault(fields['PacketID'], []).append(t)
    # The MAC and Ethernet streams preserve packet order, including non-RPC
    # traffic. Pair their complete loss-free streams; proximity alone is
    # ambiguous during cold eviction bursts.
    markers = {}
    for mac_id, eth_id in ((3, 7), (6, 12)):
        ethernet = [t for t, eid, _ in events if eid == eth_id]
        if len(ethernet) != len(mac[mac_id]) or len(set(ethernet)) != len(ethernet):
            raise ValueError('MAC/Ethernet stream count or timestamp mismatch')
        markers[mac_id] = dict(zip(ethernet, mac[mac_id]))
        for e, t in markers[mac_id].items():
            delta = e - t if mac_id == 3 else t - e
            if not 0 <= delta < 10000:
                raise ValueError(f'MAC/Ethernet ordering failed: {delta} ns')
    def marker(eid, timestamp):
        return markers[eid][timestamp]
    calls = []; pending = {}; replies = {}; queued = {}
    for t, eid, f in events:
        if eid == 0:
            rid = f['RpcID']
            if rid in pending:
                raise ValueError('RPC identifier reused before reply')
            a = eth[7][f['PacketID']]; i = bisect.bisect_right(a, t) - 1
            if i < 0 or not 0 <= t - a[i] < 1000:
                raise ValueError('Missing ingress Ethernet event')
            record = dict(ordinal=len(calls), rx_mac_ns=marker(3, a[i]), tx_mac_ns=None)
            calls.append(record); pending[rid] = record
        elif include_workers and eid == 44:
            if f['PacketDropped'] or f['RpcID'] not in pending or f['HostMsgID'] in queued:
                raise ValueError('Invalid RPC worker enqueue lifetime')
            queued[f['HostMsgID']] = pending[f['RpcID']]
        elif include_workers and eid == 42:
            record = queued.pop(f['HostMsgID'], None)
            if record is None or f['CoreID'] == 0:
                raise ValueError('Unmatched application worker dispatch')
            record['worker'] = f['CoreID'] - 1
        elif eid == 51:
            rid = f['RpcID']
            if rid not in pending or rid in replies:
                raise ValueError('Unmatched/duplicate RPC reply')
            replies[rid] = t
        elif eid == 2:
            rid = f['RpcID']
            if rid not in replies or not 0 <= t - replies.pop(rid) < 1000:
                raise ValueError('UDP encoder does not follow its RPC reply')
            record = pending.pop(rid)
            a = eth[12].get(f['PacketID'], []); i = bisect.bisect_left(a, t)
            # A setup request and the first warmup used the bypass path. They must not
            # be mistaken for later uses of the same packet identifier.
            if i < len(a) and 0 <= a[i] - t < 10000:
                record['tx_mac_ns'] = marker(6, a[i])
    expected = json.loads((data / f'acquisition/{campaign}-trace-check.json').read_text())['expected_rpcs']
    if len(calls) != expected or pending or replies:
        raise ValueError('Incomplete whole-capture RPC accounting')
    if include_workers and (queued or any("worker" not in c for c in calls)):
        raise ValueError("Incomplete worker dispatch evidence")
    return calls


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--campaign', default='w1')
    parser.add_argument('--trace', type=Path)
    parser.add_argument('--map', type=Path)
    args = parser.parse_args()
    global CAMPAIGN
    CAMPAIGN = args.campaign
    if not CAMPAIGN.replace('-', '').replace('_', '').isalnum():
        parser.error('unsafe campaign name')
    if bool(args.trace) != bool(args.map):
        parser.error('--trace and --map must be supplied together')
    if args.trace:
        extract(args.trace, args.map)
    else:
        provenance = json.loads((DATA / ('mac-provenance.json' if CAMPAIGN == 'w1' else f'{CAMPAIGN}-mac-provenance.json')).read_text())
        for name in (f'{CAMPAIGN}-mac-events.bin.gz', f'{CAMPAIGN}-trace-map.json'):
            if digest(DATA / name) != provenance['files'][name]:
                raise ValueError('Archived MAC evidence hash mismatch: ' + name)
    calls = correlate()
    plan = json.loads((DATA / f'{CAMPAIGN}-provenance.json').read_text())
    cursor = plan['setup_count']; output = []; selected = []; unmatched_warmups = []
    for run in plan['runs']:
        client = rows(run['label'] + '.csv.gz')
        meta = json.loads((DATA / 'acquisition' / run['label'] / 'client.csv.load.json').read_text())
        flush = rows(run['label'] + '-flush.csv.gz') if run['condition'] == 'cold' else []
        for index, row in enumerate(client):
            if flush:
                rounds = meta['flush_rounds']
                preparation = flush[index * rounds:(index + 1) * rounds]
                if len(preparation) != rounds or any(r['ok'] != '1' or r['expected'] != r['received'] for r in preparation):
                    raise ValueError('Incomplete cold preparation')
                cursor += sum(int(r['received']) for r in preparation)
            rec = calls[cursor]; cursor += 1
            if rec['tx_mac_ns'] is None and row['warmup'] == '1':
                unmatched_warmups.append(dict(label=run['label'], request_id=row['request_id'], ordinal=rec['ordinal']))
                continue
            if rec['tx_mac_ns'] is None:
                raise ValueError(f'Measured/warmup request lacks MAC reply: {run["label"]} {row["request_id"]} {rec}')
            latency = rec['tx_mac_ns'] - rec['rx_mac_ns']
            if not 0 < latency <= int(row['client_rx_burst_ns']) - int(row['client_call_enter_ns']):
                raise ValueError('Invalid MAC interval or exceeds client RTT')
            output.append(dict(label=run['label'], request_id=row['request_id'], warmup=row['warmup'],
                               **rec, mac_latency_ns=latency))
            selected.append((rec['rx_mac_ns'], rec['tx_mac_ns'],
                             int(row['client_call_enter_ns']), int(row['client_rx_burst_ns'])))
    if cursor != len(calls):
        raise ValueError('Unattributed hardware calls')
    # Independent causal check on the cohort-order join: one affine clock map
    # must place *every* MAC interval inside its client call interval. No fit
    # or clock correction is used in MAC-to-MAC latency itself.
    a = np.array(selected, dtype=np.float64); x0 = a[0, 0]
    base = a[0, 2] - x0; rx = (a[:, 0] - x0) / 1e9; tx = (a[:, 1] - x0) / 1e9
    fit = linprog([0, 0], A_ub=np.vstack([np.column_stack([-np.ones(len(a)), -rx]),
                                        np.column_stack([np.ones(len(a)), tx])]),
                  b_ub=np.r_[(a[:, 0] + base - a[:, 2]) / 1000,
                             (a[:, 3] - a[:, 1] - base) / 1000],
                  bounds=[(None, None), (-1000, 1000)], method='highs')
    if not fit.success:
        raise ValueError('Cohort order fails client/MAC causal clock bounds')
    stream = io.StringIO(); writer = csv.DictWriter(stream, fieldnames=list(output[0]))
    writer.writeheader(); writer.writerows(output)
    target = DATA / f'{CAMPAIGN}-mac-latency.csv.gz'; compressed(target, stream.getvalue().encode())
    provenance = dict(pipeline=plan['pipeline'], cycle_ns=5, interval='RxCmacEntry -> TxCmacExit',
        timestamp_point=m_point(), source_capture=plan.get('trace_source', 'out/paper-microbenchmarks/w1-dump/trace/trace-dram.bin'),
        source_sha256=json.loads((DATA / f'acquisition/{CAMPAIGN}-trace-check.json').read_text())['sha256'],
        whole_capture_rpcs=len(calls), archived_client_rows=len(output),
        unmatched_warmups=unmatched_warmups,
        measured_rows=sum(r['warmup'] == '0' for r in output),
        causal_clock_offset_us=float(fit.x[0]), causal_clock_rate_ppm=float(fit.x[1]),
        files={name: digest(DATA / name) for name in
               (f'{CAMPAIGN}-mac-events.bin.gz', f'{CAMPAIGN}-trace-map.json', target.name)})
    (DATA / ('mac-provenance.json' if CAMPAIGN == 'w1' else f'{CAMPAIGN}-mac-provenance.json')).write_text(json.dumps(provenance, indent=2) + '\n')
    manifest_path = DATA / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    evidence = dict(file=target.name, sha256=digest(target), unit='ns',
        interval='RxCmacEntry -> TxCmacExit', provenance=('mac-provenance.json' if CAMPAIGN == 'w1' else f'{CAMPAIGN}-mac-provenance.json'))
    if CAMPAIGN == 'w1':
        manifest['mac_latency'] = evidence
    else:
        labels = {run['label'] for run in plan['runs']}
        for entry in [*manifest['load_runs'], *manifest['latency'].values()]:
            if entry['label'] in labels:
                entry['mac_latency'] = evidence
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(provenance, indent=2))


def m_point():
    return json.loads((DATA / f'{CAMPAIGN}-trace-map.json').read_text())['sample']['timestamp_point']


if __name__ == '__main__':
    main()
