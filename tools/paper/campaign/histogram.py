"""Mergeable RTT counts with weighted NumPy-linear percentiles, without expansion."""
from collections import Counter
import math


class Latencies:
    def __init__(self, counts=()):
        self.counts = Counter(dict(counts))

    def __len__(self):
        return sum(self.counts.values())

    def extend(self, other):
        self.counts.update(other.counts if isinstance(other, Latencies) else other)

    def percentile(self, percentile):
        if not 0 <= percentile <= 100 or not len(self):
            raise ValueError('Percentile requires nonempty observations and 0..100')
        rank = (len(self)-1)*percentile/100
        lower, upper = math.floor(rank), math.ceil(rank)
        cumulative = 0
        low = None
        for value, count in sorted(self.counts.items()):
            cumulative += count
            if low is None and cumulative > lower:
                low = value
            if cumulative > upper:
                return low+(value-low)*(rank-lower)
        raise AssertionError('Histogram count mismatch')


def validate_aggregate(summary):
    s = summary
    if (s.get('observation_mode') != 'aggregate' or s.get('histogram_bin_ns') != 100
            or s.get('tx_phase_boundary') != 'admission-entry'
            or s.get('arrival_model') != 'paced-batch'
            or s.get('timestamp_retention_ns') != 30_000_000
            or s.get('rx_forward_limit_ns') != 10_000_000):
        raise ValueError('Unknown aggregate observation contract')
    cores = len(s['generator_cpus'])
    if not 1 <= cores <= 32 or len(set(s['generator_cpus'])) != cores:
        raise ValueError('Invalid aggregate placement')
    batch = max(1,min(s['batch_max'],s['rate']*s['batch_target_ns']//(cores*1_000_000_000)))
    if not 1 < s['batch_max'] <= 64 or not 0 < s['batch_target_ns'] <= 1_000_000 or s['batch_size'] != batch:
        raise ValueError('Invalid aggregate batch configuration')
    if s['send_end_ns']-s['send_start_ns'] != s['duration_ns']:
        raise ValueError('Aggregate send interval mismatch')
    fields = ('attempted','transmitted','on_time','late','duplicates','generator_misses',
              'tx_admission_failures','malformed','server_rejections','lag_count',
              'lag_sum_ns','lag_max_ns','schedule_violations')
    if len(s['phases']) != 2:
        raise ValueError('Aggregate needs warmup and measurement')
    for phase, duration in zip(s['phases'], (s['warmup_ns'],s['duration_ns'])):
        if any(type(phase[k]) is not int or phase[k] < 0 for k in fields):
            raise ValueError('Invalid aggregate counters')
        if (duration*s['rate'] % 1_000_000_000 or phase['attempted'] != duration*s['rate']//1_000_000_000
                or phase['attempted'] != phase['transmitted']
                or any(phase[k] for k in ('generator_misses','tx_admission_failures','malformed','schedule_violations'))
                or phase['on_time']+phase['late'] > phase['transmitted']
                or phase['server_rejections'] > phase['transmitted']+phase['duplicates']
                or phase['lag_count'] != phase['attempted']
                or not phase['lag_max_ns'] <= phase['lag_sum_ns'] <= phase['lag_count']*phase['lag_max_ns']):
            raise ValueError('Aggregate phase accounting or schedule violation')
    if any(s['phases'][1][k] != s[k] for k in fields[1:9]):
        raise ValueError('Aggregate summary counters disagree')
    counts = []
    previous = -1
    for bucket, count in s['latency_histogram']:
        if (type(bucket) is not int or type(count) is not int or count <= 0
                or not previous < bucket <= s['deadline_ns']//100):
            raise ValueError('Invalid aggregate histogram')
        previous = bucket
        counts.append((min(bucket*100+50,s['deadline_ns']),count))
    values = Latencies(counts)
    if len(values) != s['on_time']:
        raise ValueError('Histogram count differs from on-time replies')
    return values
