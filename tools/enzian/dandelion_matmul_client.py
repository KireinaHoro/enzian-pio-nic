#!/usr/bin/env python3
"""External single-function smoke test matching Sven's test_matmul fixture.

Requires pymongo's bson module. HTTP registration uses the CPU management IP;
ONC RPC invocation uses the FPGA NIC IP. Run on the second Enzian, not the DUT.
"""
import argparse
import json
import secrets
import socket
import struct
import urllib.request

from bson import BSON


def opaque(payload):
    return struct.pack('!I', len(payload)) + payload + b'\0' * (-len(payload) % 4)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--management', default='192.168.192.14')
    p.add_argument('--nic', default='192.168.129.200')
    p.add_argument('--bind', required=True)
    p.add_argument('--binary', required=True, help='matmul ELF path visible on the DUT')
    args = p.parse_args()
    registration = dict(name='matmul', context_size=0x8020000, engine_type='Process',
                        local_path=args.binary, binary=[], input_sets=[['', None]], output_sets=[''])
    result = {'status': 'fail', 'stage': 'registration'}
    try:
        req = urllib.request.Request(f'http://{args.management}:6000/register/function',
            data=BSON.encode(registration), headers={'Content-Type': 'application/octet-stream'})
        with urllib.request.urlopen(req, timeout=20) as response:
            if response.status != 200:
                raise ValueError(f'HTTP registration status {response.status}')
        result['registration'] = 'pass'
        result['stage'] = 'rpc'
        data = struct.pack('<qq', 1, 1)  # dimension=1 and one matrix element
        expected = {'sets': [{'identifier': '', 'items': [
            {'identifier': '', 'key': 0, 'data': data}]}]}
        body = opaque(b'matmul') + opaque(BSON.encode(expected))
        xid = secrets.randbits(32)
        call = struct.pack('!10I', xid, 0, 2, 1, 1, 1, 0, 0, 0, 0) + body
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind((args.bind, 0))
            sock.settimeout(10)
            sock.sendto(call, (args.nic, 12345))
            packet, peer = sock.recvfrom(4096)
        if peer != (args.nic, 12345) or packet[:24] != struct.pack('!6I', xid, 1, 0, 0, 0, 0):
            raise ValueError('unexpected ONC RPC reply header/source')
        size, = struct.unpack('!I', packet[24:28])
        if len(packet) != 28 + size + (-size % 4):
            raise ValueError('invalid XDR opaque reply length')
        actual = BSON(packet[28:28+size]).decode()
        if actual != expected:
            raise ValueError(f'incorrect matmul result: {actual!r}')
        result.update(status='pass', response_bytes=len(packet), matrix=[1])
    except Exception as error:
        result['error'] = str(error)
    print(json.dumps(result, indent=2))
    return int(result['status'] != 'pass')


if __name__ == '__main__':
    raise SystemExit(main())
