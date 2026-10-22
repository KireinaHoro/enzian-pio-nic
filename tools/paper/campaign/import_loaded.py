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
    if summary.get('schema') == 3:
        from .histogram import validate_aggregate
        values = validate_aggregate(summary)
        return dict(summary, files={str(csv_path): digest(csv_path), str(summary_path): digest(summary_path)}), values if collect_latencies else []
    counts = dict(transmitted=0, on_time=0, late=0, duplicates=0,
                  generator_misses=0, tx_admission_failures=0, malformed=0, server_rejections=0)
    latencies = []
    previous = 0
    measured = 0
    batch_key=None;batch_index=0;batch_call=None
    if summary.get('arrival_model') not in (None,'fixed-spacing','paced-batch','slotted-poisson'):
        raise ValueError('Unknown arrival model')
    poisson=summary.get('arrival_model')=='slotted-poisson'
    if poisson:
        from .poisson import schedule
        arrivals=iter(schedule(summary))
        if summary.get('tx_phase_boundary') not in ('admission-entry','prepared-slot-release'):
            raise ValueError('Poisson requires TX admission timestamps')
    if summary['send_end_ns']-summary['send_start_ns']!=summary['duration_ns']:
        raise ValueError('Send interval differs from duration')
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
            enter=int(row['client_call_enter_ns']);submitted=int(row['client_tx_submit_ns'])
            scheduled=int(row['scheduled_ns']);warmup=bool(int(row['warmup']))
            if poisson:
                try:due,expected_warmup=next(arrivals)
                except StopIteration:raise ValueError('Too many Poisson observations')
                if scheduled!=due or warmup!=expected_warmup or row['benchmark']!=summary['benchmark']:
                    raise ValueError('Poisson schedule, phase or workload mismatch')
                if submitted:
                    admitted=int(row['client_tx_admit_enter_ns'])
                    if not scheduled<=enter<=admitted<=submitted<=scheduled+summary['max_send_lag_ns']:
                        raise ValueError('TX admission outside Poisson slot lag bound')
                    if summary.get('pipeline')=='prepared-dedicated-v1' and not int(row['client_tx_ready_ns'])<=enter==admitted:
                        raise ValueError('Prepared TX timestamp boundary mismatch')
                    key=(warmup,scheduled)
                    if key!=batch_key:batch_key=key;batch_index=0
                    call=(admitted,submitted)
                    if batch_index%64==0:batch_call=call
                    elif call!=batch_call:raise ValueError('Slot batch members do not share a TX call')
                    batch_index+=1
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
    if poisson:
        if next(arrivals,None) is not None:raise ValueError('Missing Poisson observations')
    if poisson and (measured!=summary['scheduled_count'] or previous!=summary['warmup_count']+measured):
        raise ValueError('Observation count differs from Poisson schedule')
    if any(summary.get(k,0) != v for k, v in counts.items()):
        raise ValueError('Raw observations disagree with counters')
    return dict(summary, files={str(csv_path): digest(csv_path), str(summary_path): digest(summary_path)}), latencies
