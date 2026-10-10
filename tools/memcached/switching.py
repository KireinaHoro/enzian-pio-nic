#!/usr/bin/env python3
"""Finite multi-instance correctness traffic; hardware traces must prove switching.

Startup probes may retry. Compatibility and workload requests never retry.
This is not a throughput/saturation or latency measurement.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import threading
import time
from compatibility import run as compatibility
from protocol import Client
from ready import wait_ready


def run(host, ports, output, rounds=40):
    output.mkdir(parents=True, exist_ok=False)
    result = dict(passed=False, ports=ports, readiness=None, compatibility=[], phases=[])
    def save():
        (output/'summary.json').write_text(json.dumps(result, indent=2)+'\n')
    save()
    try:
        result['readiness'] = wait_ready(host, ports)
        save()
        if not result['readiness']['passed']:
            raise RuntimeError('cohort not ready')
        pids = [result['readiness']['stats'][p]['pid'] for p in ports]
        if len(set(pids)) != len(ports):
            raise AssertionError('ports do not belong to distinct instances')
        for port in ports:
            result['compatibility'].append(compatibility(host, port))
            save()
        # Distinct contents expose cross-domain payload/state confusion.
        values = {p: (f'port-{p}-'.encode()*128)[:1024] for p in ports}
        for port in ports:
            c = Client(host, port)
            try:
                assert c.request(b'set switch-key 0 0 1024\r\n'+values[port]+b'\r\n') == b'STORED\r\n'
            finally:
                c.close()
        def requests(port, count, barrier=None):
            c = Client(host, port)
            completed = 0
            try:
                if barrier:
                    barrier.wait(timeout=10)
                for index in range(count):
                    if index % 20 == 19:
                        command = b'set switch-key 0 0 1024\r\n'+values[port]+b'\r\n'
                        expected = b'STORED\r\n'
                    else:
                        command = b'get switch-key\r\n'
                        expected = b'VALUE switch-key 0 1024\r\n'+values[port]+b'\r\nEND\r\n'
                    if c.request(command) != expected:
                        raise AssertionError(f'port {port} corrupt response')
                    completed += 1
                return dict(port=port, completed=completed)
            finally:
                c.close()
        phase = dict(name='alternating', completed={p:0 for p in ports})
        result['phases'].append(phase)
        for _ in range(rounds):
            for port in ports:
                phase['completed'][port] += requests(port, 1)['completed']
            save()
        for name, count in [('concurrent', 200), ('idle-to-active', 40)]:
            if name == 'idle-to-active':
                time.sleep(0.25)
            phase = dict(name=name, completed=[])
            result['phases'].append(phase)
            barrier = threading.Barrier(len(ports))
            with ThreadPoolExecutor(max_workers=len(ports)) as pool:
                futures = [pool.submit(requests, p, count, barrier) for p in ports]
                for future in futures:
                    phase['completed'].append(future.result())
                    save()
        result['stats_after'] = {}
        for port in ports:
            c = Client(host, port)
            try:
                after = c.stats()
                before = result['readiness']['stats'][port]
                if after['hash_is_expanding'] != '0' or after['hash_power_level'] != before['hash_power_level']:
                    raise AssertionError('hash expansion scope failure')
                result['stats_after'][port] = after
            finally:
                c.close()
        result['passed'] = True
    except Exception as exc:
        result['error'] = f'{type(exc).__name__}: {exc}'
    save()
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host', required=True)
    p.add_argument('--ports', type=int, nargs='+', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--rounds', type=int, default=40)
    a = p.parse_args()
    if a.rounds < 1 or len(set(a.ports)) != len(a.ports):
        p.error('positive rounds and distinct ports required')
    result = run(a.host, a.ports, a.output, a.rounds)
    print(json.dumps({k:v for k,v in result.items() if k in ('passed','error','phases')}))
    raise SystemExit(0 if result['passed'] else 1)
