#!/usr/bin/env python3
"""Finite peer RPC/bypass load with per-completion evidence and no retries.

Run only against matched applications on a reserved board. This does not program,
reset, start NIC domains, change RX hold, or certify trace/TAD state.
"""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import secrets
import socket
import struct
import time


def words(*values):
    return struct.pack('!' + 'I' * len(values), *values)


def request(kind, ident):
    if kind == 'bypass':
        payload = b'LHBP' + words(ident) + bytes(range(56))
        return 32345, payload, payload
    if kind == 'echo':
        port, program, version, procedure = 22345, 0x10234567, 0x12345678, 0x23456789
        payload = words(ident, 0, 1, 0x13579bdf) + bytes(range(32))
        result = payload
    else:
        port, program = (12345, 0x23451111) if kind == 'add' else (12346, 0x23451112)
        version, procedure = 1, 1
        a, b = ident % 1000 + 1, ident % 97 + 1
        payload = words(ident, a, b)
        result = words(ident, a + b if kind == 'add' else a * b)
    return port, words(ident, 0, 2, program, version, procedure, 0, 0, 0, 0) + payload, words(ident, 1, 0, 0, 0, 0) + result


def load(nic, bind, kinds, seconds, window, timeout, output):
    output.mkdir(parents=True, exist_ok=False)
    pending, counts, active = {}, Counter(), Counter()
    ident = secrets.randbelow(0x7fffffff) + 1
    started = time.monotonic_ns()
    end = started + int(seconds * 1e9)
    failure = None
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock, (output/'completions.csv').open('w') as log:
        sock.bind((bind, 0))
        rows = csv.writer(log)
        rows.writerow(['stream', 'id', 'send_monotonic_ns', 'receive_monotonic_ns', 'bytes'])
        try:
            while time.monotonic_ns() < end or pending:
                now = time.monotonic_ns()
                if now < end:
                    for kind in kinds:
                        while active[kind] < window:
                            ident = (ident + 1) & 0xffffffff
                            if ident in pending:
                                raise RuntimeError('request identity collision')
                            port, packet, expected = request(kind, ident)
                            stamp = time.monotonic_ns()
                            sock.sendto(packet, (nic, port))
                            pending[ident] = (kind, stamp, port, expected)
                            active[kind] += 1
                if not pending:
                    continue
                earliest = min(value[1] for value in pending.values())
                left = timeout - (time.monotonic_ns() - earliest) / 1e9
                if left <= 0:
                    raise TimeoutError('oldest request exceeded response deadline')
                sock.settimeout(left)
                packet, peer = sock.recvfrom(65536)
                stamp = time.monotonic_ns()
                if len(packet) < 8:
                    raise ValueError('short response')
                key = struct.unpack('!I', packet[4:8] if packet[:4] == b'LHBP' else packet[:4])[0]
                if key not in pending:
                    raise ValueError('unsolicited or duplicate response')
                kind, sent, port, expected = pending[key]
                if peer != (nic, port) or packet != expected:
                    raise ValueError(f'{kind}: response source or bytes mismatch')
                del pending[key]
                active[kind] -= 1
                counts[kind] += 1
                rows.writerow([kind, key, sent, stamp, len(packet)])
        except (OSError, ValueError, RuntimeError) as exc:
            failure = str(exc)
        finally:
            log.flush()
    result = dict(passed=failure is None and all(counts[k] for k in kinds), error=failure,
                  nic=nic, bind=bind, streams=kinds, window=window, requested_seconds=seconds,
                  elapsed_seconds=(time.monotonic_ns()-started)/1e9, completions=dict(counts),
                  pending=len(pending), retries=0,
                  scope='Finite application and bypass completions; verify simultaneous holds, handoffs and all TADs independently.')
    (output/'summary.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result), flush=True)
    return 0 if result['passed'] else 1


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--nic', default='192.168.129.200')
    p.add_argument('--bind', required=True)
    p.add_argument('--streams', default='add,bypass')
    p.add_argument('--seconds', type=float, default=30)
    p.add_argument('--window', type=int, default=1)
    p.add_argument('--timeout', type=float, default=5)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--bypass-server', action='store_true', help='DUT kernel UDP echo on port 32345; no NIC-domain registration')
    a = p.parse_args()
    kinds = a.streams.split(',')
    if a.seconds <= 0 or a.timeout <= 0 or not 1 <= a.window <= 256 or len(set(kinds)) != len(kinds) or set(kinds) - {'add', 'mul', 'echo', 'bypass'}:
        p.error('positive durations, window 1..256 and distinct supported streams required')
    socket.inet_aton(a.nic)
    socket.inet_aton(a.bind)
    if a.bypass_server:
        a.output.mkdir(parents=True, exist_ok=False)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind((a.bind, 32345))
            print('BYPASS_READY', flush=True)
            while True:
                packet, peer = sock.recvfrom(65536)
                if len(packet) == 64 and packet[:4] == b'LHBP':
                    sock.sendto(packet, peer)
    return load(a.nic, a.bind, kinds, a.seconds, a.window, a.timeout, a.output)


if __name__ == '__main__':
    raise SystemExit(main())
