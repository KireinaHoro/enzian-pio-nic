"""Import Caladan stage traces using the existing unloaded wire partition."""
import argparse
import json
from pathlib import Path
from .import_unloaded import client_rows, rows, cpu_partition, finish
from .import_loaded import digest


def caladan(client_path, server_path, status_path, *, count, warmup,
            payload_bytes, wire_path=None):
    client = client_rows(client_path, count, warmup, payload_bytes)
    server = rows(server_path)
    status = json.loads(Path(status_path).read_text())
    if (status.get('loss_free') is not True or status.get('dropped') != 0
            or status.get('rows') != len(server)):
        raise ValueError('Incomplete Caladan trace export')
    by_id = {int(r['request_context']): r for r in server}
    if len(by_id) != len(server) or set(by_id) != {int(c['request_id']) for c in client}:
        raise ValueError('Duplicate or mismatched Caladan XID sets')
    software = []
    for cl in client:
        sw = by_id[int(cl['request_id'])]
        if (sw['ok'] != '1' or sw['reply_status'] != '0'
                or sw['transport_status'] != '0' or sw['request_id_valid'] != '1'
                or int(sw['request_id']) != int(cl['request_id'])
                or int(sw['request_bytes']) != payload_bytes
                or int(sw['response_bytes']) != payload_bytes):
            raise ValueError('Invalid Caladan reply metadata')
        t = lambda k: int(sw['server_' + k + '_ns'])
        stages = ('rx_observed', 'rx_queue_enter', 'rx_queue_exit',
                  'network_rx_enter', 'network_rx_exit', 'dispatch_ready',
                  'dispatch_enter', 'unmarshal_enter', 'unmarshal_exit',
                  'handler_enter', 'handler_exit', 'marshal_enter', 'marshal_exit',
                  'network_tx_enter', 'network_tx_exit', 'driver_enter',
                  'tx_doorbell', 'submit_return')
        times = [t(k) for k in stages]
        if not times[0] or times != sorted(times):
            raise ValueError('Invalid Caladan queue/software stage order')
        part = cpu_partition(t('rx_observed'), t('submit_return'), {
            'rx_queue': (t('rx_queue_enter'), t('rx_queue_exit')),
            'rx_network': (t('network_rx_enter'), t('network_rx_exit')),
            'rpc_receive': (t('dispatch_enter'), t('handler_enter')),
            'handler': (t('handler_enter'), t('handler_exit')),
            'rpc_transmit': (t('handler_exit'), t('marshal_exit')),
            'tx_network': (t('network_tx_enter'), t('network_tx_exit')),
            'tx_submission': (t('network_tx_exit'), t('submit_return')),
        })
        part.update(flow_id=0, request_id=int(cl['request_id']))
        software.append(part)
    result = finish(client_path, client, software, system='SH-A64', count=count,
                    warmup=warmup, instrumentation='breakdown', payload_bytes=payload_bytes,
                    wire_path=wire_path, server_path=server_path,
                    correlation='Exact Caladan flow/XID; shared client validation')
    result['files'][str(status_path)] = digest(status_path)
    result['cpu_partition_version'] = 2
    result['clock'] = 'CLOCK_MONOTONIC_RAW'
    result['timestamp_count_scope'] = 'Shared RPC probes only; packet boundaries are additional'
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('client', 'server', 'status', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--wire', type=Path)
    p.add_argument('--count', type=int, required=True)
    p.add_argument('--warmup', type=int, required=True)
    p.add_argument('--payload-bytes', type=int, required=True)
    a = p.parse_args()
    result = caladan(a.client, a.server, a.status, count=a.count, warmup=a.warmup,
                     payload_bytes=a.payload_bytes, wire_path=a.wire)
    with a.output.open('x') as f:
        json.dump(result, f, indent=2)


if __name__ == '__main__':
    main()
