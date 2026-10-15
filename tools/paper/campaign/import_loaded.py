"""Validate raw client observations; loaded cases have no trace input."""
import csv
import gzip
import hashlib
import json
from pathlib import Path
from .policy import validate_trial


def digest(path):
    with Path(path).open('rb') as stream:
        sha = hashlib.sha256()
        for block in iter(lambda: stream.read(1024*1024), b''):
            sha.update(block)
        return sha.hexdigest()


def load(csv_path, summary_path, *, collect_latencies=True):
    csv_path, summary_path = Path(csv_path), Path(summary_path)
    summary = validate_trial(json.loads(summary_path.read_text()))
    counts = dict(transmitted=0, on_time=0, late=0, duplicates=0,
                  generator_misses=0, tx_admission_failures=0, malformed=0)
    latencies = []
    previous = 0
    measured = 0
    opener = gzip.open if csv_path.suffix == '.gz' else open
    with opener(csv_path, 'rt') as stream:
        for row in csv.DictReader(stream):
            xid = int(row['request_id'])
            if xid != previous+1:
                raise ValueError('Missing/repeated XID observation')
            previous = xid
            status = 1 if row['ok'] == '1' else int(row['rpc_status'])
            if status not in range(1, 7):
                raise ValueError('Unclassified request')
            if int(row['warmup']):
                if status in (3, 5, 6):
                    raise ValueError('Invalid warmup generator or payload')
                continue
            measured += 1
            enter, rx = int(row['client_call_enter_ns']), int(row['client_rx_burst_ns'])
            scheduled = int(row['scheduled_ns'])
            if enter and enter < scheduled:
                raise ValueError('Sent before scheduled time')
            counts['transmitted'] += bool(int(row['client_tx_submit_ns'])) and status != 6
            counts['on_time'] += status == 1
            counts['late'] += status == 4
            counts['malformed'] += status == 3
            counts['generator_misses'] += status == 5
            counts['tx_admission_failures'] += status == 6
            counts['duplicates'] += int(row['duplicates'])
            if status in (1, 4):
                rtt = rx-enter
                if rtt < 0 or (rtt <= summary['deadline_ns']) != (status == 1):
                    raise ValueError('Deadline classification mismatch')
                if status == 1 and collect_latencies:
                    latencies.append(rtt)
    if measured != summary['rate'] * summary['duration_ns'] // 1_000_000_000:
        raise ValueError('Observation count differs from fixed schedule')
    if any(summary[k] != v for k, v in counts.items()):
        raise ValueError('Raw observations disagree with counters')
    return dict(summary, files={str(csv_path): digest(csv_path), str(summary_path): digest(summary_path)}), latencies
