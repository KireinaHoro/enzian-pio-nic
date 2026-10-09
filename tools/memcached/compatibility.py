#!/usr/bin/env python3
"""Run identically against pinned upstream and native UDP; preserve JSON evidence."""
import argparse
import json
import time
from pathlib import Path
from protocol import Client


def run(host, port):
    c = Client(host, port)
    checks = []
    before = c.stats()
    prefix = f'lh-{time.time_ns()}-'.encode()
    def expect(name, command, response, noreply=False):
        actual = c.request(command, noreply=noreply)
        if actual != response:
            raise AssertionError(f'{name}: response mismatch (actual {len(actual)} bytes, expected {len(response)} bytes)')
        checks.append(name)
        return actual
    key = prefix + b'key'
    expect('set', b'set '+key+b' 7 0 3\r\nabc\r\n', b'STORED\r\n')
    expect('get', b'get '+key+b'\r\n', b'VALUE '+key+b' 7 3\r\nabc\r\nEND\r\n')
    expect('append', b'append '+key+b' 0 0 1\r\nd\r\n', b'STORED\r\n')
    expect('prepend', b'prepend '+key+b' 0 0 1\r\nz\r\n', b'STORED\r\n')
    expect('append-prepend-value', b'get '+key+b'\r\n', b'VALUE '+key+b' 7 5\r\nzabcd\r\nEND\r\n')
    gets = c.request(b'gets '+key+b'\r\n')
    token = gets.split(b'\r\n')[0].split()[-1]
    expect('cas', b'cas '+key+b' 7 0 1 '+token+b'\r\nx\r\n', b'STORED\r\n')
    expect('stale-cas', b'cas '+key+b' 7 0 1 '+token+b'\r\ny\r\n', b'EXISTS\r\n')
    expect('noreply', b'set '+key+b' 0 0 1 noreply\r\nn\r\n', b'', True)
    expect('noreply-applied', b'get '+key+b'\r\n', b'VALUE '+key+b' 0 1\r\nn\r\nEND\r\n')
    exp = prefix+b'expire'
    expect('expiry-set', b'set '+exp+b' 0 1 1\r\ne\r\n', b'STORED\r\n')
    time.sleep(2.1)
    expect('expiration', b'get '+exp+b'\r\n', b'END\r\n')
    large = prefix+b'large'
    value = b'a'*1024
    expect('segment-set', b'set '+large+b' 0 0 1024\r\n'+value+b'\r\n', b'STORED\r\n')
    for i in range(3):
        expect(f'segment-append-{i}', b'append '+large+b' 0 0 1024\r\n'+value+b'\r\n', b'STORED\r\n')
    expected = b'VALUE '+large+b' 0 4096\r\n'+value*4+b'\r\nEND\r\n'
    expect('segmented-response', b'get '+large+b'\r\n', expected)
    segments = c.last_segments
    if segments < 2:
        raise AssertionError('segmented response test did not produce multiple datagrams')
    after = c.stats()
    for snapshot in (before, after):
        if snapshot.get('hash_is_expanding') != '0':
            raise AssertionError('hash expansion scope failure')
    if before.get('hash_power_level') != after.get('hash_power_level'):
        raise AssertionError('hash table expanded during acceptance')
    c.close()
    return dict(passed=True, host=host, port=port, checks=checks,
                segmented_datagrams=segments, before=before, after=after)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--host', required=True)
    p.add_argument('--port', type=int, default=11211)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if args.output.exists():
        p.error('output already exists')
    try:
        result = run(args.host, args.port)
    except Exception as exc:
        result = dict(passed=False, error=f'{type(exc).__name__}: {exc}')
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('before','after')}))
    raise SystemExit(0 if result['passed'] else 1)
