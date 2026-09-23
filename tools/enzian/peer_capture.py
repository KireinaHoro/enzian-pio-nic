#!/usr/bin/env python3
"""Record Ethernet frames involving one MAC in standard PCAP format (root)."""
import argparse
import json
import signal
import socket
import struct
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('interface')
    p.add_argument('mac')
    p.add_argument('output')
    args = p.parse_args()
    mac = bytes.fromhex(args.mac.replace(':', ''))
    if len(mac) != 6:
        p.error('expected a six-byte MAC')
    running = True

    def stop(_signum, _frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    packets = 0
    with socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(3)) as sock:
        sock.bind((args.interface, 0))
        sock.settimeout(0.2)
        # Exclusive creation protects earlier evidence. Times are userspace
        # receive timestamps, suitable for packet diagnosis, not latency claims.
        with open(args.output, 'xb') as output:
            output.write(struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1))
            output.flush()
            print('CAPTURE_READY', flush=True)
            while running:
                try:
                    packet = sock.recv(65535)
                except socket.timeout:
                    continue
                if len(packet) < 14 or mac not in (packet[:6], packet[6:12]):
                    continue
                stamp = time.time_ns()
                output.write(struct.pack('<IIII', stamp // 1_000_000_000,
                    (stamp // 1000) % 1_000_000, len(packet), len(packet)))
                output.write(packet)
                output.flush()
                packets += 1
    print(json.dumps({'packets': packets, 'timestamp': 'userspace_receive'}), flush=True)


if __name__ == '__main__':
    main()
