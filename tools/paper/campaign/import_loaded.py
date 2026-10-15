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
                  generator_misses=0, tx_admission_failures=0, malformed=0, server_rejections=0)
    latencies = []
    previous = 0
    measured = 0
    strict=summary.get('schema')==2
    if strict:
        if summary['send_end_ns']-summary['send_start_ns']!=summary['duration_ns']:
            raise ValueError('Send interval differs from duration')
        start=summary['send_start_ns']-summary['warmup_ns']
        warmup_slots=summary['rate']*summary['warmup_ns']//1_000_000_000
    opener = gzip.open if csv_path.suffix == '.gz' else open
    with opener(csv_path, 'rt') as stream:
        for row in csv.DictReader(stream):
            xid = int(row['request_id'])
            if xid != previous+1:
                raise ValueError('Missing/repeated XID observation')
            previous = xid
            status = 1 if row['ok'] == '1' else int(row['rpc_status'])
            if status not in range(1, 8):
                raise ValueError('Unclassified request')
            rejections=int(row.get('server_rejections',0))
            duplicates=int(row['duplicates'])
            if (rejections<0 or duplicates<0 or rejections>duplicates+1
                    or (status==7 and not rejections)
                    or (rejections and status not in (1,4,7))):
                raise ValueError('Invalid server rejection accounting')
            if status==7 and int(row['client_rx_burst_ns'])<int(row['client_call_enter_ns']):
                raise ValueError('Rejection precedes transmission')
            if strict:
                scheduled=start+(xid-1)*1_000_000_000//summary['rate']
                enter=int(row['client_call_enter_ns']);submitted=int(row['client_tx_submit_ns'])
                warmup=xid<=warmup_slots
                if (int(row['scheduled_ns'])!=scheduled or int(row['warmup'])!=int(warmup)
                        or row['benchmark']!=summary['benchmark']):
                    raise ValueError('Absolute schedule, phase or workload mismatch')
                end=summary['send_start_ns'] if warmup else summary['send_end_ns']
                if submitted and not scheduled<=enter<=submitted<end:
                    raise ValueError('Transmission outside its scheduled phase')
                if int(row['start_lag_ns'])!=max(0,enter-scheduled):
                    raise ValueError('Scheduled-start lag mismatch')
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
            counts['duplicates'] += duplicates
            counts['server_rejections'] += rejections
            if status in (1, 4):
                rtt = rx-enter
                if rtt < 0 or (rtt <= summary['deadline_ns']) != (status == 1):
                    raise ValueError('Deadline classification mismatch')
                if status == 1 and collect_latencies:
                    latencies.append(rtt)
    if measured != summary['rate'] * summary['duration_ns'] // 1_000_000_000:
        raise ValueError('Observation count differs from fixed schedule')
    if strict and previous!=warmup_slots+measured:
        raise ValueError('Warmup observation count differs from schedule')
    if any(summary.get(k,0) != v for k, v in counts.items()):
        raise ValueError('Raw observations disagree with counters')
    return dict(summary, files={str(csv_path): digest(csv_path), str(summary_path): digest(summary_path)}), latencies
