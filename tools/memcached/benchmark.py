#!/usr/bin/env python3
"""Bounded open-loop UDP load generator. Report pacing misses as generator limits.

One process per traffic-generator core can run a disjoint subset of instances.
Run matrix cases only after the hardware/maintenance gates pass. This program
never resets a board, starts servers, or treats client timeouts as proven loss.
"""
import argparse
from array import array
import heapq
import json
import itertools
import sys
import random
import selectors
import socket
import time
from pathlib import Path
from protocol import Client, HEADER


def key(index):
    return f'lhbench-{index:08d}'.encode()


def active_rate(shape, elapsed, rate):
    if shape == 'steady':
        return rate
    if shape == 'burst':
        return rate*2 if elapsed % 0.2 < 0.1 else 0
    return rate*5 if elapsed % 10 >= 8 else 0


def percentile(values, p):
    return values[min(len(values)-1, int((len(values)-1)*p))]/1000 if values else None


def run(args, case):
    rng = random.Random(case['seed'])
    value = b'x'*case['value_bytes']
    peers = [(args.host, port) for port in case['ports']]
    stats_before = []
    for host, port in peers:
        c = Client(host, port)
        if args.preload:
            for index in range(args.keys):
                if c.request(b'set '+key(index)+b' 0 0 '+str(len(value)).encode()+b'\r\n'+value+b'\r\n') != b'STORED\r\n':
                    raise RuntimeError('preload failed')
        snapshot = c.stats()
        if snapshot.get('hash_is_expanding') != '0':
            raise RuntimeError('hash expansion before measurement')
        stats_before.append(snapshot)
        c.close()
    selector = selectors.DefaultSelector()
    sockets = []
    identities = itertools.count()
    def new_socket(instance):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4*1024*1024)
        s.connect(peers[instance])
        s.setblocking(False)
        state = dict(socket=s, identity=next(identities), instance=instance, next_id=0, pending=0)
        selector.register(s, selectors.EVENT_READ, state)
        return state
    for i in range(len(peers)):
        sockets.append([new_socket(i) for _ in range(args.flows)])
    counters = [dict(sent=0, completed=0, goodput_completions=0, timeouts=0, errors=0,
                     pacing_misses=0, admission_misses=0, late_or_duplicate=0,
                     received_datagrams=0) for _ in peers]
    rtts = [array('Q') for _ in peers]
    pending = {}
    deadlines = []
    next_flow = [0]*len(peers)
    start = time.monotonic()
    measure_start = start + case['warmup_seconds']
    end = measure_start + case['measurement_seconds']
    next_send = [start]*len(peers)
    try:
        while time.monotonic() < end or pending:
            now = time.monotonic()
            measured = measure_start <= now < end
            for instance, flows in enumerate(sockets):
                rate = active_rate(case['traffic'], now-start, case['offered_rps_per_instance'])
                if not rate or now >= end:
                    next_send[instance] = now + 0.001
                    continue
                interval = 1/rate
                due = int((now-next_send[instance])/interval)+1 if now >= next_send[instance] else 0
                # Never turn an overloaded generator into an unlimited catch-up burst.
                if due > 32:
                    if measured: counters[instance]['pacing_misses'] += due-32
                    next_send[instance] += (due-32)*interval
                    due = 32
                for _ in range(due):
                    state = flows[next_flow[instance] % len(flows)]
                    next_flow[instance] += 1
                    next_send[instance] += interval
                    if state['next_id'] == 65536:
                        if state['pending']:
                            if measured: counters[instance]['admission_misses'] += 1
                            continue
                        selector.unregister(state['socket'])
                        state['socket'].close()
                        replacement = new_socket(instance)
                        state.clear(); state.update(replacement)
                        # Ensure selector holds the shared state object.
                        selector.modify(state['socket'], selectors.EVENT_READ, state)
                    if len(pending) >= args.max_pending:
                        if measured: counters[instance]['admission_misses'] += 1
                        continue
                    index = rng.randrange(args.keys)
                    if case['key_distribution'] == 'skewed' and rng.random() < 0.9:
                        index = rng.randrange(max(1, args.keys//100))
                    k = key(index)
                    get = rng.random() < case['get_fraction']
                    command = b'get '+k+b'\r\n' if get else b'set '+k+b' 0 0 '+str(len(value)).encode()+b'\r\n'+value+b'\r\n'
                    expected = b'VALUE '+k+b' 0 '+str(len(value)).encode()+b'\r\n'+value+b'\r\nEND\r\n' if get else b'STORED\r\n'
                    rid = state['next_id']; state['next_id'] += 1
                    stamp = time.monotonic_ns()
                    measured = int(measure_start*1e9) <= stamp < int(end*1e9)
                    try:
                        state['socket'].send(HEADER.pack(rid,0,1,0)+command)
                    except (BlockingIOError, OSError):
                        if measured: counters[instance]['admission_misses'] += 1
                        continue
                    token = (state['identity'],rid)
                    pending[token] = (stamp, measured, state, expected)
                    heapq.heappush(deadlines,(stamp+int(args.timeout*1e9),token))
                    state['pending'] += 1
                    if measured: counters[instance]['sent'] += 1
            for event, _ in selector.select(0 if any(x <= now for x in next_send) and now < end else 0.0001):
                state = event.data
                counter = counters[state['instance']]
                for _ in range(64):
                    try:
                        data = state['socket'].recv(65536)
                    except BlockingIOError:
                        break
                    except OSError:
                        counter['errors'] += 1
                        break
                    received_ns = time.monotonic_ns()
                    counter['received_datagrams'] += 1
                    if len(data) < HEADER.size:
                        counter['errors'] += 1
                        continue
                    rid, seq, total, reserved = HEADER.unpack_from(data)
                    request = pending.pop((state['identity'],rid),None)
                    if request is None:
                        counter['late_or_duplicate'] += 1
                        continue
                    stamp, in_window, owner, expected = request
                    owner['pending'] -= 1
                    if in_window:
                        if seq or total != 1 or reserved or data[8:] != expected:
                            counter['errors'] += 1
                        else:
                            counter['completed'] += 1
                            rtts[state['instance']].append(received_ns-stamp)
                            if received_ns < int(end*1e9): counter['goodput_completions'] += 1
            now_ns = time.monotonic_ns()
            while deadlines and deadlines[0][0] <= now_ns:
                _, token = heapq.heappop(deadlines)
                request = pending.pop(token,None)
                if request:
                    _, in_window, owner, _ = request
                    owner['pending'] -= 1
                    if in_window: counters[owner['instance']]['timeouts'] += 1
    finally:
        for states in sockets:
            for state in states: state['socket'].close()
        selector.close()
    instances = []
    for i, (host, port) in enumerate(peers):
        c = Client(host,port); after = c.stats(); c.close()
        if after.get('hash_is_expanding') != '0' or after.get('hash_power_level') != stats_before[i].get('hash_power_level'):
            raise RuntimeError(f'hash expansion scope failure on port {port}')
        values = sorted(rtts[i])
        with (args.output/f'rtt-{port}.u64-{sys.byteorder}').open('wb') as raw:
            rtts[i].tofile(raw)
        counter = counters[i]
        instances.append(dict(port=port, declared_workers=case['workers'][i], **counter,
                              goodput_rps=counter['goodput_completions']/case['measurement_seconds'],
                              cohort_goodput_rps=counter['completed']/case['measurement_seconds'],
                              rtt_us={str(p):percentile(values,p) for p in (0.5,0.9,0.99,0.999)},
                              before=stats_before[i],after=after))
    return dict(schema=1, case=case, instances=instances,
                aggregate_goodput_rps=sum(i['goodput_rps'] for i in instances),
                generator_limited=any(i['pacing_misses'] or i['admission_misses'] for i in instances),
                packet_loss=None,
                packet_loss_note='Timeouts are unanswered requests; attribute packet loss only with independently recorded NIC counters.',
                actual_core_assignments='Join server activation traces and CPU samples; declared workers are not assignments.')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--case', type=int, required=True)
    p.add_argument('--host', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--keys', type=int, default=10000)
    p.add_argument('--flows', type=int, default=32)
    p.add_argument('--max-pending', type=int, default=32768)
    p.add_argument('--timeout', type=float, default=1.0)
    p.add_argument('--preload', action='store_true')
    args = p.parse_args()
    if not 0 < args.keys < 65536 or args.flows < 1 or args.timeout <= 0 or args.max_pending < 1:
        p.error('invalid load-generator bounds')
    args.output.mkdir(parents=True, exist_ok=False)
    case = json.loads(args.manifest.read_text())['cases'][args.case]
    try:
        result = run(args,case)
        result['completed'] = True
    except Exception as exc:
        result = dict(completed=False, case=case, error=f'{type(exc).__name__}: {exc}')
    (args.output/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('instances','case')}))
    raise SystemExit(0 if result['completed'] else 1)
