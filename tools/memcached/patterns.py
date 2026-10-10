#!/usr/bin/env python3
"""Finite tenant bursts with every client RTT retained; not a saturation benchmark.

One nonblocking socket per tenant, no request retries, bounded in-flight window.
RTT includes client/socket/network overhead. Entry labels describe traffic only,
not confirmed worker residency. Hardware traces establish actual assignments.
"""
import argparse
import csv
import json
from pathlib import Path
import selectors
import socket
import time
from protocol import Client, HEADER
from ready import wait_ready
from batch import send_batch


def schedule(ports, repeats):
    for repeat in range(repeats):
        for port in ports:
            yield repeat, 'single', [port]
        for index, port in enumerate(ports):
            yield repeat, 'pair', [port, ports[(index+1) % len(ports)]]
        yield repeat, 'all', ports


def run(host, ports, output, count=256, window=16, repeats=3, idle=0.1, timeout=2, batch=False):
    output.mkdir(parents=True, exist_ok=False)
    result = dict(passed=False, count=count, window=window, repeats=repeats,
                  idle_seconds=idle, batch_sendmmsg=batch, ports=ports, phases=[], errors=[],
                  latency_scope=('Client batch-call preparation start to receive; includes ctypes/kernel batch transmission'
                                 if batch else 'Client monotonic send-to-receive RTT, including generator/socket/network overhead'),
                  classification='first window is burst-entry; later requests sustained; neither proves hardware hot/cold')
    def save():
        (output/'summary.json').write_text(json.dumps(result, indent=2)+'\n')
    values = {p:(f'tenant-{p}-'.encode()*128)[:1024] for p in ports}
    selector = selectors.DefaultSelector()
    sockets = {}
    try:
        result['readiness'] = wait_ready(host, ports)
        if not result['readiness']['passed']:
            raise RuntimeError('cohort not ready')
        assert len({s['pid'] for s in result['readiness']['stats'].values()}) == len(ports)
        for port in ports:
            c = Client(host, port)
            try:
                assert c.request(b'set pattern-key 0 0 1024\r\n'+values[port]+b'\r\n') == b'STORED\r\n'
            finally:
                c.close()
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4*1024*1024)
            s.connect((host, port)); s.setblocking(False)
            sockets[port] = s
            selector.register(s, selectors.EVENT_READ, port)
        next_id = dict.fromkeys(ports, 0)
        origin = time.monotonic_ns()
        result['origin_monotonic_ns'] = origin
        with (output/'requests.csv').open('w', newline='') as raw:
            writer = csv.DictWriter(raw, fieldnames=['phase','repeat','pattern','port','index','request_id',
                'class','operation','sent_ns','received_ns','rtt_us','status'])
            writer.writeheader()
            for phase_id, (repeat, pattern, active) in enumerate(schedule(ports, repeats)):
                time.sleep(idle)
                phase = dict(id=phase_id, repeat=repeat, pattern=pattern, active_ports=active,
                             start_ns=time.monotonic_ns(), sent=0, completed=0)
                result['phases'].append(phase)
                pending = {}; sent = dict.fromkeys(active, 0); inflight = dict.fromkeys(active, 0)
                while any(sent[p] < count for p in active) or pending:
                    prepared = {p:[] for p in active}
                    # Individual sends are round-robin; sendmmsg submits one tenant burst per call.
                    for _ in range(window):
                        for port in active:
                            if sent[port] >= count or inflight[port] >= window:
                                continue
                            rid = next_id[port]
                            if rid >= 65536:
                                raise RuntimeError('request ID reuse prohibited; reduce bounds')
                            index = sent[port]; is_set = index % 20 == 19
                            command = (b'set pattern-key 0 0 1024\r\n'+values[port]+b'\r\n'
                                       if is_set else b'get pattern-key\r\n')
                            expected = (b'STORED\r\n' if is_set else
                                        b'VALUE pattern-key 0 1024\r\n'+values[port]+b'\r\nEND\r\n')
                            stamp = time.monotonic_ns()
                            payload = HEADER.pack(rid,0,1,0)+command
                            if not batch:
                                sockets[port].send(payload)
                            row = dict(phase=phase_id,repeat=repeat,pattern=pattern,port=port,index=index,
                                request_id=rid,operation='SET' if is_set else 'GET',sent_ns=stamp,
                                received_ns='',rtt_us='',status='pending')
                            row['class'] = 'entry' if index < window else 'sustained'
                            if batch:
                                prepared[port].append((payload,row))
                            pending[port,rid] = (row,expected)
                            sent[port] += 1; inflight[port] += 1; next_id[port] += 1; phase['sent'] += 1
                    for port, burst in prepared.items():
                        if burst:
                            # Shared call-start timestamp includes ctypes preparation and
                            # kernel batch transmission, not a per-packet wire timestamp.
                            stamp = time.monotonic_ns()
                            for _, row in burst: row['sent_ns'] = stamp
                            send_batch(sockets[port], [payload for payload,_ in burst])
                    for event, _ in selector.select(0.001):
                        port = event.data
                        for _ in range(window):
                            try:
                                data = event.fileobj.recv(65536)
                            except BlockingIOError:
                                break
                            received = time.monotonic_ns()
                            if len(data) < 8:
                                raise ValueError('truncated response')
                            rid, seq, total, reserved = HEADER.unpack_from(data)
                            row, expected = pending.pop((port,rid))
                            inflight[port] -= 1
                            row.update(received_ns=received, rtt_us=(received-row['sent_ns'])/1000,
                                       status='ok' if (seq,total,reserved)==(0,1,0) and data[8:]==expected else 'corrupt')
                            writer.writerow(row)
                            if row['status'] != 'ok':
                                raise ValueError(f'corrupt response on {port}')
                            phase['completed'] += 1
                    now = time.monotonic_ns()
                    expired = [token for token,(row,_) in pending.items() if now-row['sent_ns'] > timeout*1e9]
                    if expired:
                        for token in expired:
                            row,_ = pending.pop(token); row['status']='timeout'; writer.writerow(row)
                        raise TimeoutError(f'{len(expired)} unanswered requests; no retries')
                phase['end_ns'] = time.monotonic_ns()
                raw.flush(); save()
        result['stats_after'] = {}
        for port in ports:
            c = Client(host,port)
            try:
                after = c.stats(); before = result['readiness']['stats'][port]
                assert after['hash_is_expanding']=='0' and after['hash_power_level']==before['hash_power_level']
                result['stats_after'][port] = after
            finally:
                c.close()
        result['passed'] = True
    except Exception as exc:
        result['errors'].append(f'{type(exc).__name__}: {exc}')
    finally:
        for s in sockets.values(): s.close()
        selector.close(); save()
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host', required=True)
    p.add_argument('--ports', type=int, nargs='+', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--count', type=int, default=256)
    p.add_argument('--window', type=int, default=16)
    p.add_argument('--repeats', type=int, default=3)
    p.add_argument('--idle', type=float, default=0.1)
    p.add_argument('--batch', action='store_true', help='Linux sendmmsg bursts; RTT starts before batch preparation')
    a = p.parse_args()
    if len(a.ports)<2 or len(set(a.ports))!=len(a.ports) or not 1<=a.window<=a.count or a.repeats<1 or a.idle<0 or a.count*a.repeats*4>=65536:
        p.error('invalid pattern bounds or request ID budget')
    result = run(a.host,a.ports,a.output,a.count,a.window,a.repeats,a.idle,batch=a.batch)
    print(json.dumps(dict(passed=result['passed'], errors=result['errors'],
                         completed=sum(p['completed'] for p in result['phases']))))
    raise SystemExit(0 if result['passed'] else 1)
