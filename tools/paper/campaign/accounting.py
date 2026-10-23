"""Versioned hot inline-RPC accounting. All values are raw CPU nanoseconds."""
SHARED = ('backend_enter', 'rpc_enter', 'alloc_enter', 'alloc_exit',
          'decode_enter', 'decode_exit', 'handler_enter', 'handler_exit',
          'outcome_enter', 'reserve_enter', 'reserve_exit', 'encode_enter',
          'encode_exit', 'reply_enter', 'reply_ready')


def partition(raw, system):
    from .import_unloaded import cpu_partition
    if int(raw.get('execution_kind',0)) != 0:
        raise ValueError('Versioned hot accounting requires synchronous inline replies')
    current = version([raw]) == 4
    t = lambda k: int(raw['server_' + k + '_ns'])
    stamps = {k: t(k) for k in SHARED}
    sequence = list(SHARED)
    if system != 'LH':
        sequence[1:1] = ['lookup_enter', 'lookup_exit']
        stamps.update({k: t(k) for k in ('lookup_enter', 'lookup_exit')})
    values = [stamps[k] for k in sequence]
    if not all(values) or values != sorted(values):
        raise ValueError('Incomplete or unordered versioned RPC timestamps')
    spans = {
        'rpc_receive': (t('decode_enter'), t('decode_exit')),
        'handler': (t('handler_enter'), t('handler_exit')),
        'rpc_transmit': (t('encode_enter'), t('encode_exit')),
        'reservation': (t('reserve_enter'), t('reserve_exit')),
    }
    if system == 'LH':
        start, end = t('rx_unblock'), t('tx_publish' if current else 'tx_exit')
        spans.update(rx_queue=(start, t('rx_exit')),
                     tx_submission=(t('tx_enter'), t('tx_publish' if current else 'tx_doorbell_return')))
        anchors = ('rx_unblock', 'rx_exit', 'backend_enter', 'reply_ready',
                   'tx_enter') + (('tx_publish',) if current else ('tx_doorbell', 'tx_doorbell_return', 'tx_exit'))
    elif system == 'DPDK':
        d = lambda k: int(raw[k + '_ns'])
        start, end = d('rx_cqe'), d('tx_publish' if current else 'tx_return')
        spans.update(rx_queue=(start, d('rx_return')),
                     rx_network=(d('network_rx_begin'), d('network_rx_end')),
                     tx_network=(d('network_tx_begin'), d('network_tx_end')),
                     tx_submission=(d('tx_submit_begin'), end))
        if current:
            queue = [d(k) for k in ('rx_cqe','rx_return','network_rx_begin','network_rx_end',
                      'network_tx_begin','network_tx_end','tx_submit_begin','tx_pmd_enter','tx_publish')]
            if not all(queue) or queue != sorted(queue):
                raise ValueError('Invalid DPDK queue/publication order')
        anchors = ()
        # Retain queue/network boundaries in their native namespace.
        stamps.update({k: int(v) for k, v in raw.items()
                       if k.endswith('_ns') and not k.startswith('server_')})
        if not (d('network_rx_end') <= t('backend_enter') and
                t('reply_ready') <= d('network_tx_begin')):
            raise ValueError('Invalid versioned DPDK adapter boundaries')
    elif system == 'CS-A64':
        start, end = t('rx_observed'), t('tx_publish' if current else 'submit_return')
        if not 0 < t('rx_queue_enter') <= t('rx_queue_exit'):
            raise ValueError('Invalid Caladan gather bounds')
        spans.update(rx_queue=(max(start,t('rx_queue_enter')), t('rx_queue_exit')),
                     rx_network=(t('network_rx_enter'), t('network_rx_exit')),
                     tx_network=(t('network_tx_enter'), t('network_tx_exit')),
                     tx_submission=(t('network_tx_exit'), end))
        anchors = (('rx_observed', 'rx_queue_exit') if current else ('rx_observed', 'rx_queue_enter', 'rx_queue_exit')) + (
                   'network_rx_enter', 'network_rx_exit', 'dispatch_ready',
                   'dispatch_enter', 'backend_enter', 'reply_ready',
                   'network_tx_enter', 'network_tx_exit', 'driver_enter',
                    ) + (('tx_publish',) if current else ('tx_doorbell', 'submit_return'))
    else:
        raise ValueError('Unknown versioned backend')
    anchor_values = [t(k) for k in anchors]
    if any(v <= 0 for v in anchor_values) or anchor_values != sorted(anchor_values):
        raise ValueError('Invalid versioned transport boundaries')
    stamps.update({k: t(k) for k in anchors})
    # cpu_partition validates all spans jointly, including reservation overlap.
    result = cpu_partition(start, end, spans)
    result['components_ns']['tx_submission'] += result['components_ns'].pop('reservation')
    details = {
        'allocation': (t('alloc_enter'), t('alloc_exit')),
        'backend_dispatch': (t('backend_enter'), t('rpc_enter')),
        'request_setup': (t('rpc_enter'), t('alloc_enter')),
        'decode_setup': (t('alloc_exit'), t('decode_enter')),
        'handler_setup': (t('decode_exit'), t('handler_enter')),
        'handler_return': (t('handler_exit'), t('outcome_enter')),
        'outcome_dispatch': (t('outcome_enter'), t('reserve_enter')),
        'encode_setup': (t('reserve_exit'), t('encode_enter')),
        'reply_dispatch': (t('encode_exit'), t('reply_enter')),
        'reply_preparation': (t('reply_enter'), t('reply_ready')),
    }
    if system != 'LH':
        details.pop('backend_dispatch')
        details.update(rpc_header=(t('backend_enter'), t('lookup_enter')),
                       lookup=(t('lookup_enter'), t('lookup_exit')),
                       backend_setup=(t('lookup_exit'), t('rpc_enter')))
    if system == 'LH':
        details.update(rx_dispatch=(t('rx_exit'),t('backend_enter')),
                       tx_handoff=(t('reply_ready'),t('tx_enter')))
        if not current:
            details['transport_return']=(t('tx_doorbell_return'),t('tx_exit'))
    elif system == 'DPDK':
        details.update(rx_handoff=(d('rx_return'),d('network_rx_begin')),
                       rpc_handoff=(d('network_rx_end'),t('backend_enter')),
                       tx_handoff=(t('reply_ready'),d('network_tx_begin')),
                       tx_mbuf_preparation=(d('network_tx_end'),d('tx_submit_begin')))
    else:
        details.update(rx_service_wait=(t('rx_observed'),max(t('rx_observed'),t('rx_queue_enter'))),
                       rx_handoff=(t('rx_queue_exit'),t('network_rx_enter')),
                       request_thread_setup=(t('network_rx_exit'),t('dispatch_ready')),
                       dispatch_wait=(t('dispatch_ready'),t('dispatch_enter')),
                       tx_handoff=(t('reply_ready'),t('network_tx_enter')))
    # Named details must be wholly in the complement, never double counted.
    for a, b in details.values():
        if not start <= a <= b <= end or any(max(a,c) < min(b,d) for c,d in spans.values()):
            raise ValueError('Runtime detail overlaps a paid component')
    ordered = sorted(details.values())
    if any(b > c for (_,b),(c,_) in zip(ordered, ordered[1:])):
        raise ValueError('Overlapping runtime details')
    durations = {k: b-a for k,(a,b) in details.items()}
    durations['other_runtime'] = result['components_ns']['cpu_other'] - sum(durations.values())
    if durations['other_runtime'] < 0:
        raise ValueError('Runtime details exceed runtime component')
    result['accounting'] = dict(version=4 if current else 3, cpu_start_ns=start, cpu_end_ns=end,
                                timestamps_ns=stamps, runtime_details_ns=durations)
    return result


def version(rows):
    versions = {int(r.get('accounting_version',3 if 'server_rpc_enter_ns' in r else 2)) for r in rows}
    if not versions <= {2,3,4}:
        raise ValueError('Unsupported CPU timestamp schema')
    if len(versions) != 1:
        raise ValueError('Mixed CPU timestamp schemas')
    return versions.pop()
