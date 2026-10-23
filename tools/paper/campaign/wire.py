"""Accepted nominal-ns MAC-duration boundary. Capture is in campaign/tofino.py.

Fixtures may exercise analysis, but cannot authorize acquisition. Durations are
supplied per flow/XID by the maintained pairer. Absolute calibration metadata
does not veto the accepted nominal-ns interval. Real-workload integration
acceptance remains required before long collection.
"""
METHOD = 'tofino-wire-v1'
COMPONENTS = ('rx_queue', 'rx_network', 'rpc_receive', 'handler',
              'rpc_transmit', 'tx_network', 'tx_submission', 'cpu_other')


def require_capture_backend(config=None):
    import json
    from pathlib import Path
    from .import_loaded import digest
    from .import_unloaded import validate_matched
    proof=(config or {}).get('wire_acceptance',{})
    if not proof:raise RuntimeError('Validated unloaded switch rehearsal required before long collection')
    if isinstance(proof, list):
        from .policy import selected_systems
        covered=[s for item in proof for s in item['systems']]
        if len(covered)!=len(set(covered)) or set(covered)!=set(selected_systems(config)):
            raise ValueError('Wire acceptance proofs must cover each selected system exactly once')
        for item in proof:
            require_capture_backend(dict(config, systems=item['systems'], wire_acceptance=item))
        return

    root=Path(proof['root'])
    if digest(root/'rehearsal-unloaded.json')!=proof['cohorts_sha256']:
        raise ValueError('Wire acceptance evidence changed')
    cohorts=json.loads((root/'rehearsal-unloaded.json').read_text())
    from .policy import selected_systems
    validate_matched(cohorts,rehearsal=True,systems=selected_systems(config or {}))
    receipt=Path(proof['validation_receipt'])
    if digest(receipt)!=proof['validation_sha256']:raise ValueError('Wire validation receipt changed')
    saved=json.loads(receipt.read_text())
    if not saved['result'].get('passed') or not saved['result'].get('unloaded_only'):
        raise ValueError('Unloaded integration validation did not pass')
    for path,sha in saved['hashes'].items():
        if digest(path)!=sha:raise ValueError('Wire validation artifact changed')
    for cohort in cohorts:
        for path,sha in cohort['files'].items():
            if digest(path)!=sha:raise ValueError('Wire rehearsal input changed')


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
            # The CPU return path and NIC transmission proceed concurrently
            # after submission. This signed difference is not a transport stage.
            row['cpu_duration_ns'] = elapsed
            row['components_ns'] = dict(values, cpu_wire=total-elapsed)
        output.append(row)
    return output
