"""Network-only multi-domain acceptance, shared by VM and board peers.

Checks progress on persistent streams across alternating heavy bursts. It does
not infer core ownership; the VM's separate observed test checks full parking.
"""
import concurrent.futures
import os
import socket

host = os.environ.get('LH_TEST_HOST', os.environ.get('LH_VM_HOST', '192.168.1.1'))
ports = range(11220, 11224)
streams = [[socket.create_connection((host, port), timeout=10) for _ in range(16)]
           for port in ports]
values = [bytes([97 + domain]) * 4096 for domain in range(4)]
replies = [b'VALUE phase4 0 4096\r\n' + value + b'\r\nEND\r\n' for value in values]


def exchange(stream, request, expected):
    stream.sendall(request)
    received = bytearray()
    while len(received) < len(expected):
        part = stream.recv(min(65536, len(expected) - len(received)))
        assert part, 'unexpected EOF'
        received.extend(part)
    assert received == expected, 'stream contents changed across competing bursts'


try:
    for domain, clients in enumerate(streams):
        exchange(clients[0], b'set phase4 0 0 4096\r\n' + values[domain] + b'\r\n', b'STORED\r\n')
    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
        for round_ in range(2):
            for domain, clients in enumerate(streams):
                pending = [pool.submit(exchange, stream, b'get phase4\r\n' * 32,
                                       replies[domain] * 32) for stream in clients]
                # Old connections in all other domains must progress while
                # this domain's burst is outstanding.
                probes = [pool.submit(exchange, streams[other][0], b'get phase4\r\n', replies[other])
                          for other in range(4) if other != domain]
                for future in pending + probes:
                    future.result(timeout=20)
        for domain, clients in enumerate(streams):
            for stream in clients:
                exchange(stream, b'get phase4\r\n', replies[domain])
    print('MEMCACHED_PERSISTENT_BURSTS_PASS: four domains, four workers each, 64 persistent streams, eight bursts')
finally:
    for clients in streams:
        for stream in clients:
            stream.close()
