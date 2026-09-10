#!/usr/bin/env python3
"""Check Sven's standalone adder schema (hyper timestamp, two signed ints)."""
import argparse
import json
import secrets
import socket
import struct
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--nic', default='192.168.129.200')
    p.add_argument('--bind', required=True)
    p.add_argument('--calls', type=int, default=100)
    args = p.parse_args()
    if args.calls < 1:
        p.error('--calls must be positive')
    completed = 0
    result = {'status': 'fail'}
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind((args.bind, 0))
            sock.settimeout(10)
            for i in range(args.calls):
                xid = secrets.randbits(32)
                stamp = time.monotonic_ns()
                a, b = i - 50, i + 7
                call = struct.pack('!10I', xid, 0, 2, 1, 1, 1, 0, 0, 0, 0)
                sock.sendto(call + struct.pack('!qii', stamp, a, b), (args.nic, 12345))
                reply, peer = sock.recvfrom(4096)
                expected = struct.pack('!6Iqi', xid, 1, 0, 0, 0, 0, stamp, a + b)
                if peer != (args.nic, 12345) or reply != expected:
                    raise ValueError(f'incorrect reply from {peer}: {reply.hex()}')
                completed += 1
        result['status'] = 'pass'
    except (OSError, ValueError) as error:
        result['error'] = str(error)
    result.update(completed=completed, requested=args.calls)
    print(json.dumps(result, indent=2))
    return int(result['status'] != 'pass')


if __name__ == '__main__':
    raise SystemExit(main())
