#!/usr/bin/env python3
"""Native memcached UDP framing; no ONC-RPC or private chunking."""
import socket
import struct
import time

HEADER = struct.Struct('!HHHH')


class Client:
    def __init__(self, host, port, timeout=1.0):
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.connect((host, port))
        self.timeout = timeout
        self.request_id = 0
        self.last_segments = 0

    def close(self):
        self.socket.close()

    def request(self, command, noreply=False):
        if len(command) + HEADER.size > 1472:
            raise ValueError('request exceeds a single supported UDP datagram')
        # The sequential compatibility harness never wraps IDs on one socket.
        if self.request_id == 65536:
            raise RuntimeError('open a new client before request ID reuse')
        ident = self.request_id
        self.request_id += 1
        self.socket.send(HEADER.pack(ident, 0, 1, 0) + command)
        deadline = time.monotonic() + (0.05 if noreply else self.timeout)
        pieces = {}
        total = None
        self.last_segments = 0
        while True:
            self.socket.settimeout(max(0.000001, deadline - time.monotonic()))
            try:
                packet = self.socket.recv(65536)
            except socket.timeout:
                if noreply:
                    return b''
                raise
            if len(packet) < HEADER.size:
                raise ValueError('truncated response framing')
            rid, sequence, count, reserved = HEADER.unpack_from(packet)
            if rid != ident:
                raise ValueError('unexpected request ID in sequential test')
            if noreply:
                raise AssertionError('response to noreply command')
            if not count or sequence >= count or reserved or (total is not None and count != total):
                raise ValueError('invalid segmented response framing')
            total = count
            body = packet[HEADER.size:]
            if sequence in pieces and pieces[sequence] != body:
                raise ValueError('conflicting duplicate response segment')
            pieces[sequence] = body
            if len(pieces) == total:
                self.last_segments = total
                return b''.join(pieces[i] for i in range(total))
            if time.monotonic() >= deadline:
                raise TimeoutError('incomplete segmented response')

    def stats(self):
        raw = self.request(b'stats\r\n')
        return {line.split()[1].decode(): line.split()[2].decode()
                for line in raw.splitlines() if line.startswith(b'STAT ')}
