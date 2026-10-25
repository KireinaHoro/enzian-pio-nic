"""Measurement and selection policy shared by acquisition and plotting."""
import math
SYSTEMS = ('LH', 'DPDK')
SUPPORTED_SYSTEMS = (*SYSTEMS, 'CS-A64')

def selected_systems(config):
    systems = tuple(config.get('systems', SYSTEMS))
    if not systems or len(set(systems)) != len(systems) or any(s not in SUPPORTED_SYSTEMS for s in systems):
        raise ValueError('Invalid campaign systems')
    return systems

def observed_systems(records):
    names = {r['system'] for r in records}
    selected_systems({'systems': list(names)})
    return tuple(s for s in SUPPORTED_SYSTEMS if s in names)


def caladan_execution_model(record):
    # All legacy CS-A64 acquisitions used udp_create_spawner. Preserve system
    # identity and acquisition receipts; distinguish the execution model in views.
    model=record.get('execution_model','udp-spawner')
    if model not in ('udp-spawner','resident-workers'):
        raise ValueError('Unknown Caladan execution model')
    return model


def system_label(system, records, *, multiline=False):
    if system!='CS-A64':return system
    models={caladan_execution_model(r) for r in records if r['system']==system}
    if len(models)!=1:raise ValueError('Cannot combine Caladan execution models in one series')
    if models=={'resident-workers'}:return 'CS-A64'
    return 'CS-A64'+('\n' if multiline else ' ')+'spawner'


SIZES = (8, 128, 1432)
WORKERS = (1, 2, 3, 4)
LOSS_LIMIT = .01


def validate_trial(t):
    for key in ('transmitted', 'on_time', 'late', 'duplicates', 'generator_misses',
                'tx_admission_failures', 'malformed', 'duration_ns'):
        if type(t[key]) is not int or t[key] < 0:
            raise ValueError(f'Invalid {key}')
    if type(t.get('server_rejections',0)) is not int or t.get('server_rejections',0)<0:
        raise ValueError('Invalid server rejection count')
    if t.get('server_rejections',0)>t['transmitted']+t['duplicates']:
        raise ValueError('Rejection replies exceed received/transmitted requests')
    if any(t.get(k,0) for k in ('nic_rx_missed','nic_rx_no_buffer','nic_rx_errors','nic_tx_errors')):
        raise ValueError('Client NIC resource/health failure')
    if t['valid'] is not True or t['generator_misses'] or t['tx_admission_failures'] or t['malformed']:
        raise ValueError('Generator/resource/corruption failure is not server loss')
    if t['deadline_ns'] != 10_000_000 or not t['duration_ns'] or not t['transmitted']:
        raise ValueError('Invalid measurement boundary')
    if t['on_time'] + t['late'] > t['transmitted']:
        raise ValueError('Reply counts exceed transmissions')
    return t


def loss(t):
    validate_trial(t)
    return (t['transmitted'] - t['on_time']) / t['transmitted']


def aggregate(trials):
    if not trials or len({t['duration_ns'] for t in trials}) != 1:
        raise ValueError('Pooling requires equal-duration repetitions')
    if len({(t.get('arrival_model'),t.get('pipeline'),t.get('tx_phase_boundary')) for t in trials})!=1:
        raise ValueError('Cannot pool different loaded arrival or RTT boundary contracts')
    for t in trials:
        validate_trial(t)
    seconds = sum(t['duration_ns'] for t in trials) / 1e9
    return dict(offered=sum(t['transmitted'] for t in trials)/seconds,
                goodput=sum(t['on_time'] for t in trials)/seconds,
                worst_loss=max(map(loss, trials)), trials=trials)


def qualifying(points):
    result = []
    for point in sorted(points, key=lambda p: p['rate']):
        if aggregate(point['trials'])['worst_loss'] > LOSS_LIMIT:
            break
        result.append(point)
    return result


def candidates(points):
    return sorted(qualifying(points), key=lambda p: aggregate(p['trials'])['goodput'], reverse=True)


def grid(boundary):
    if not math.isfinite(boundary) or boundary <= 0:
        raise ValueError('Positive finite rehearsal boundary required')
    return sorted({max(1, math.ceil(boundary*f)) for f in
                   (.1, .25, .5, .7, .8, .85, .9, .95, 1)})


def next_rate(rate):
    return max(rate+1, math.ceil(rate*1.5))



def validate_activity(counts, workers, *, allow_zero=False):
    """Validate counter shape; loaded loss may leave every worker inactive."""
    if (len(counts)!=workers or any(type(n) is not int or n<0 for n in counts)
            or (not allow_zero and not any(counts))):
        raise ValueError('Invalid worker activity coverage')


def validate_loaded_activity(counts, workers, trial):
    validate_activity(counts, workers, allow_zero=True)
    validate_trial(trial)
    if not any(counts) and trial['on_time'] + trial['late']:
        raise ValueError('Client replies contradict zero server activity')


def validate_activity_coverage(groups):
    """Each worker configuration must exercise every worker somewhere in its grid."""
    for key,records in groups.items():
        workers=key[-1]
        for counts in records:validate_activity(counts,workers,allow_zero=True)
        if not records or any(not any(row[i]>0 for row in records) for i in range(workers)):
            raise ValueError('Workers never exercised in collected grid: '+str(key))
