"""Mergeable RTT counts: conditional linear or all-transmission inverse CDF."""
from collections import Counter
import math


class Latencies:
    def __init__(self, counts=()):
        self.counts = Counter(dict(counts))

    def __len__(self):
        return sum(self.counts.values())

    def extend(self, other):
        self.counts.update(other.counts if isinstance(other, Latencies) else other)

    def population_percentile(self, percentile, transmitted):
        """Inverse empirical CDF over all transmissions; deadline failures are +inf.

        Nearest rank keeps P99 finite at exactly 1% deadline loss. No fabricated
        timeout RTTs or interpolation between a finite observation and infinity.
        """
        from fractions import Fraction
        if (type(transmitted) is not int or transmitted <= 0 or
                transmitted < len(self) or not 0 < percentile <= 100):
            raise ValueError('Invalid transmitted population or percentile')
        fraction = Fraction(str(percentile))/100
        rank = (transmitted*fraction.numerator + fraction.denominator-1)//fraction.denominator
        if rank > len(self): return math.inf
        cumulative = 0
        for value, count in sorted(self.counts.items()):
            cumulative += count
            if cumulative >= rank: return value
        raise AssertionError('Histogram count mismatch')

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
    poisson=s.get('arrival_model')=='slotted-poisson'
    if poisson:
        from .poisson import validate
        validate(s)
    if (s.get('observation_mode') != 'aggregate' or s.get('histogram_bin_ns') != 100
            or s.get('tx_phase_boundary') != 'admission-entry'
            or s.get('arrival_model') not in ('paced-batch','slotted-poisson')
            or s.get('timestamp_retention_ns') != 30_000_000
            or s.get('rx_forward_limit_ns') != 10_000_000):
        raise ValueError('Unknown aggregate observation contract')
    cores = len(s['generator_cpus'])
    if not 1 <= cores <= 32 or len(set(s['generator_cpus'])) != cores:
        raise ValueError('Invalid aggregate placement')
    if s['send_end_ns']-s['send_start_ns'] != s['duration_ns']:
        raise ValueError('Aggregate send interval mismatch')
    fields = ('attempted','transmitted','on_time','late','duplicates','generator_misses',
              'tx_admission_failures','malformed','server_rejections','lag_count',
              'lag_sum_ns','lag_max_ns','schedule_violations')
    if len(s['phases']) != 2:
        raise ValueError('Aggregate needs warmup and measurement')
    for index,(phase,duration) in enumerate(zip(s['phases'], (s['warmup_ns'],s['duration_ns']))):
        expected=(s['warmup_count'] if index==0 else s['scheduled_count']) if poisson else None
        if any(type(phase[k]) is not int or phase[k] < 0 for k in fields):
            raise ValueError('Invalid aggregate counters')
        if ((poisson and phase['attempted'] != expected)
                or phase['attempted'] != phase['transmitted']
                or any(phase[k] for k in ('generator_misses','tx_admission_failures','malformed','schedule_violations'))
                or phase['on_time']+phase['late'] > phase['transmitted']
                or phase['server_rejections'] > phase['transmitted']+phase['duplicates']
                or phase['lag_count'] != phase['attempted']
                or not phase['lag_max_ns'] <= phase['lag_sum_ns'] <= phase['lag_count']*phase['lag_max_ns']):
            raise ValueError('Aggregate phase accounting or schedule violation')
        if poisson and phase['lag_max_ns']>s['max_send_lag_ns']:
            raise ValueError('Poisson release lag exceeds bound')
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
