"""Functional Linux-peer test of the shared FreeBSD stack, not host TCP echo."""
import concurrent.futures
import os
import socket

host = os.environ.get("LH_VM_HOST", "192.168.1.1")


def exchange(index):
    with socket.create_connection((host, 19501), timeout=10) as stream:
        for size in (1, 17, 1400, 4096, 16384):
            payload = bytes((i + index) % 251 for i in range(size))
            stream.sendall(payload)
            reply = bytearray()
            while len(reply) < len(payload):
                part = stream.recv(len(payload) - len(reply))
                assert part, (index, size, "unexpected EOF")
                reply.extend(part)
            assert reply == payload, (index, size)
        stream.shutdown(socket.SHUT_WR)
        assert stream.recv(1) == b""


with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
    list(pool.map(exchange, range(16)))
print("VM_TCP_ECHO_PASS")
