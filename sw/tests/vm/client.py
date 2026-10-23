import os
import concurrent.futures
import socket
import struct
import time


def echo(port, payload):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(3)
        s.sendto(payload, (os.environ.get('LH_VM_HOST', '127.0.0.1'), port))
        got, _ = s.recvfrom(65536)
        assert got == payload, (len(got), len(payload))


for size in (0, 1, 24, 64, 65, 256, 1472):
    echo(19000, bytes(i % 128 for i in range(size)))

# A burst crosses the hardware queue watermark to scale beyond one core.
# Four isolated arrivals do not request four workers under that policy.
# Simultaneous sleeping handlers occupy all four assignments; syscall blocking
# must retain those assignments. A second domain must receive DESCHED handoffs.
with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
    futures = [pool.submit(echo, 19000, b'\xd0' + bytes([i])) for i in range(12)]
    time.sleep(0.05)
    futures += [pool.submit(echo, 19001, b'handoff' + bytes([i])) for i in range(4)]
    for f in futures:
        f.result()

# Unmatched packets retain the normal host socket path.
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as host:
    host.bind(('127.0.0.1', 19002))
    host.settimeout(3)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
        sender.sendto(b'bypass', host.getsockname())
    assert host.recv(32) == b'bypass'

print('VM_UDP_PASS', flush=True)
