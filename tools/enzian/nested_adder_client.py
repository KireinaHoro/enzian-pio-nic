#!/usr/bin/env python3
"""Exercise Sven's nested adder from a peer Enzian with a real remote add service.

The remote responder is ordinary UDP ONC RPC, not a NIC or runtime mock.
Only packets delivered to its bound UDP socket are observed (not a wire capture).
"""
import argparse
import json
import secrets
import socket
import struct
import threading


def words(*values):
    return struct.pack('!' + 'I' * len(values), *[v & 0xffffffff for v in values])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nic', default='192.168.129.200')
    parser.add_argument('--bind', required=True, help='peer cpu40g1 IPv4 address')
    parser.add_argument('--timeout', type=float, default=10)
    args = parser.parse_args()
    seen = []
    stopped = threading.Event()
    responder = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    responder.bind((args.bind, 12345))
    responder.settimeout(0.2)

    def serve():
        while not stopped.is_set():
            try:
                packet, peer = responder.recvfrom(4096)
            except socket.timeout:
                continue
            row = {'peer': list(peer), 'bytes': len(packet), 'hex': packet.hex()}
            seen.append(row)
            # AUTH_NONE call: XID, CALL, RPCv2, program=1, version=1, proc=1,
            # credentials and verifier, followed by request_id, a, b.
            if len(packet) != 52 or packet[4:40] != words(0, 2, 1, 1, 1, 0, 0, 0, 0):
                row['error'] = 'unexpected call header or payload'
                continue
            xid, = struct.unpack('!I', packet[:4])
            request_id, a, b = struct.unpack('!Iii', packet[40:])
            responder.sendto(words(xid, 1, 0, 0, 0, 0, request_id, a + b), peer)
            row['sum'] = a + b

    thread = threading.Thread(target=serve)
    thread.start()
    result = {'status': 'fail', 'nested_requests': seen}
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.bind((args.bind, 0))
            client.settimeout(args.timeout)
            xid = secrets.randbits(32)
            # Program 2, version 2, procedure 2: add(add(1,2), add(3,4)).
            client.sendto(words(xid, 0, 2, 2, 2, 2, 0, 0, 0, 0, 7, 1, 2, 3, 4),
                          (args.nic, 12346))
            reply, peer = client.recvfrom(4096)
            result['reply_peer'] = list(peer)
            result['reply_hex'] = reply.hex()
            if peer != (args.nic, 12346) or len(reply) != 32 or reply[:24] != words(xid, 1, 0, 0, 0, 0):
                raise ValueError('invalid nested-adder RPC reply')
            request_id, total = struct.unpack('!Ii', reply[24:])
            result.update(request_id=request_id, total=total)
            if total != 10 or len(seen) != 3 or any('error' in r for r in seen):
                raise ValueError('incorrect sum or missing two future calls / callback call')
            result['status'] = 'pass'
    except (OSError, ValueError) as error:
        result['error'] = str(error)
    finally:
        stopped.set()
        thread.join()
        responder.close()
    print(json.dumps(result, indent=2))
    return int(result['status'] != 'pass')


if __name__ == '__main__':
    raise SystemExit(main())
