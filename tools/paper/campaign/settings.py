"""Manifest measurement settings, with defaults for historical campaigns."""
import copy

DEFAULTS = {
    'unloaded': {'warmup_requests': 128, 'measurement_requests': 10000},
    'loaded': {'warmup_ms': 2000, 'measurement_ms': 10000,
               'repetitions': 1, 'confirmation_ms': 60000,
               'confirmation_repetitions': 1,
               'step_rpc_s': {'LH': {'8': 20000, '128': 20000, '1432': 5000},
                              'DPDK': {'8': 50000, '128': 50000, '1432': 20000},
                              'CS-A64': {'8': 50000, '128': 50000, '1432': 20000}}},
    'rehearsal': {'unloaded_requests': 100, 'loaded_warmup_ms': 1000,
                  'loaded_measurement_ms': 1000},
}


def measurement_settings(config):
    result = copy.deepcopy(DEFAULTS)
    def merge(target, source):
        if not isinstance(source, dict): raise ValueError('Measurement settings must be objects')
        for key, value in source.items():
            if key not in target: raise ValueError('Unknown measurement setting: '+key)
            if isinstance(target[key], dict): merge(target[key], value)
            else:
                if type(value) is not int or value < (0 if 'warmup' in key else 1):
                    raise ValueError('Invalid measurement setting: '+key)
                target[key] = value
    merge(result, config.get('measurement', {}))
    if result['unloaded']['warmup_requests'] >= 1024:
        raise ValueError('Unloaded warmup must leave room in a 1024-request capture')
    return result


def validate_unloaded_counts(cohorts, settings):
    for c in cohorts:
        if (c['count'] != settings['measurement_requests'] or
                c['warmup'] != settings['warmup_requests'] * len(c.get('chunks', [None]))):
            raise ValueError('Wrong full unloaded sample count')
