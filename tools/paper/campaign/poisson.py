"""Slotted-Poisson schedule shared by acquisition provenance and replay checks."""
import math

VERSION = 'splitmix64-knuth-slots-v1'
MASK = (1 << 64)-1
WARMUP_STREAM = 0xd1b54a32d192ed03


def batches(rate, duration, slot_ns, seed):
    def uniform():
        nonlocal seed
        seed = (seed+0x9e3779b97f4a7c15) & MASK
        z = seed
        z = ((z ^ (z >> 30))*0xbf58476d1ce4e5b9) & MASK
        z = ((z ^ (z >> 27))*0x94d049bb133111eb) & MASK
        z ^= z >> 31
        return ((z >> 12)+0.5)*2.0**-52
    for due in range(0, duration, slot_ns):
        mean = rate*min(slot_ns, duration-due)/1e9
        count = 0
        while mean > 0:
            part = min(mean, 16)
            limit, product, n = math.exp(-part), 1.0, 0
            while True:
                product *= uniform()
                n += 1
                if product <= limit:
                    break
            count += n-1
            mean -= part
        yield due, count


def validate(summary):
    s = summary
    cores = len(s['generator_cpus'])
    if (s.get('arrival_model') != 'slotted-poisson' or s.get('schedule_version') != VERSION
            or type(s.get('seed')) is not int or not 1 <= s['seed'] <= 0xffffffff
            or type(s.get('slot_ns')) is not int or not 1 <= s['slot_ns'] <= 1_000_000
            or type(s.get('max_send_lag_ns')) is not int or not 1 <= s['max_send_lag_ns'] <= 1_000_000
            or s.get('tx_burst_max') != 64
            or type(s.get('warmup_count')) is not int or s['warmup_count'] < 0
            or type(s.get('scheduled_count')) is not int or s['scheduled_count'] <= 0
            or s['scheduled_count']+s['warmup_count'] > 0xffffffff
            or not 1 <= cores <= 32 or len(set(s['generator_cpus']))!=cores
            or any(type(c) is not int or c<0 for c in s['generator_cpus'])
            or type(s.get('rate')) is not int or not 1<=s['rate']<=100000000
            or type(s.get('duration_ns')) is not int or not 0<s['duration_ns']<=60000000000
            or type(s.get('warmup_ns')) is not int or not 0<=s['warmup_ns']<=60000000000):
        raise ValueError('Invalid slotted-Poisson schedule contract')
    counts = s.get('phase_core_counts')
    if (not isinstance(counts,list) or len(counts)!=2
            or any(not isinstance(p,list) or len(p)!=cores or any(type(n) is not int or n<0 for n in p) for p in counts)
            or list(map(sum,counts))!=[s['warmup_count'],s['scheduled_count']]):
        raise ValueError('Invalid Poisson core/phase counts')
    return s


def schedule(summary):
    s = validate(summary)
    cores = len(s['generator_cpus'])
    start = s['send_start_ns']-s['warmup_ns']
    for phase,(duration,seed,base) in enumerate((
            (s['warmup_ns'],s['seed'] ^ WARMUP_STREAM,start),
            (s['duration_ns'],s['seed'],s['send_start_ns']))):
        # Detailed observations already require per-request storage. Aggregate
        # imports validate the online counters without materializing this ledger.
        owners = [[] for _ in range(cores)]
        for slot,(due,count) in enumerate(batches(s['rate'],duration,s['slot_ns'],seed)):
            owners[slot%cores].extend([base+due]*count)
        if list(map(len,owners))!=s['phase_core_counts'][phase]:
            raise ValueError('Poisson phase count differs from seeded schedule')
        for owner in owners:
            for due in owner:
                yield due, phase==0
