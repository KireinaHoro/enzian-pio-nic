"""Accepted nominal-ns MAC-duration boundary. Capture is in campaign/tofino.py.

Fixtures may exercise analysis, but cannot authorize acquisition. Durations are
supplied per flow/XID by the maintained pairer. Absolute calibration metadata
does not veto the accepted nominal-ns interval. Real-workload integration
acceptance remains required before long collection.
"""
METHOD = 'tofino-wire-v1'
COMPONENTS = ('rx_queue', 'rx_network', 'rpc_receive', 'handler',
              'rpc_transmit', 'tx_network', 'tx_submission', 'cpu_other')


def require_capture_backend():
    raise RuntimeError('Tofino MAC timestamp backend is not integrated; long rehearsal/full collection is held')


def join_durations(client, wire, software=None):
    """Join explicitly keyed observations without synchronizing clock epochs.

    Input rows use (flow_id, request_id); wire_ns and CPU component durations
    are integer nanoseconds. Warmup/setup selection belongs to the caller.
    """
    def keyed(rows):
        result = {}
        for row in rows:
            key = (row['flow_id'], row['request_id'])
            if key in result:
                raise ValueError('Duplicate flow/XID')
            result[key] = row
        return result
    clients, wires = keyed(client), keyed(wire)
    cpus = keyed(software) if software is not None else None
    if not clients or clients.keys() != wires.keys() or (cpus is not None and cpus.keys() != clients.keys()):
        raise ValueError('Incomplete wire/client/software pairing')
    output = []
    for key, cl in clients.items():
        total = wires[key]['wire_ns']
        if type(total) is not int or not 0 < total <= cl['rtt_ns']:
            raise ValueError('Invalid wire duration')
        row = dict(flow_id=key[0], request_id=key[1], wire_ns=total)
        if cpus is not None:
            cpu = cpus[key]
            values = cpu['components_ns']
            if set(values) != set(COMPONENTS) or any(type(v) is not int or v < 0 for v in values.values()):
                raise ValueError('Incomplete or invalid CPU partition')
            elapsed = cpu['cpu_duration_ns']
            if type(elapsed) is not int or elapsed <= 0 or sum(values.values()) != elapsed:
                raise ValueError('CPU components do not conserve CPU duration')
            if elapsed > total:
                raise ValueError('Negative CPU-wire residual')
            row['components_ns'] = dict(values, cpu_wire=total-elapsed)
        output.append(row)
    return output
