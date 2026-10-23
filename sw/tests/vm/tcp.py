"""Functional Linux-peer test of the shared FreeBSD stack, not host TCP echo."""
import concurrent.futures
import os
import socket
import struct
import threading

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

# A small receive window forces FreeBSD to retain unsent echo data. Observe an
# actual zero-window advertisement before draining; sleeping alone is not proof
# that flow control was exercised. Capture only observes traffic, never injects it.
with socket.socket() as stream:
    stream.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
    stream.setsockopt(socket.IPPROTO_TCP, socket.TCP_WINDOW_CLAMP, 1024)
    stream.settimeout(10)
    stream.connect((host, 19501))
    client_port = stream.getsockname()[1]
    zero_window = threading.Event()
    finished = threading.Event()
    # ETH_P_ALL includes outgoing peer advertisements on its Ethernet interface;
    # ETH_P_IP alone observes incoming packets (and would only work on loopback).
    capture = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0003))
    capture.settimeout(.1)

    def observe_window():
        while not finished.is_set():
            try:
                frame = capture.recv(65536)
            except socket.timeout:
                continue
            if len(frame) < 54 or frame[12:14] != b'\x08\x00':
                continue
            packet = frame[14:]
            if packet[0] >> 4 != 4 or packet[9] != 6:
                continue
            tcp = packet[(packet[0] & 15) * 4:]
            if len(tcp) >= 20 and struct.unpack('!HH', tcp[:4]) == (client_port, 19501):
                if tcp[14:16] == b'\0\0':
                    zero_window.set()

    observer = threading.Thread(target=observe_window)
    observer.start()
    payload = bytes(range(251)) * 1024
    sender = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    sent = sender.submit(stream.sendall, payload)
    try:
        assert zero_window.wait(5), 'peer never advertised a zero window'
        exchange(31)  # another connection progresses while this one is blocked
        received = bytearray()
        while len(received) < len(payload):
            part = stream.recv(len(payload) - len(received))
            assert part, 'EOF while reopening receive window'
            received.extend(part)
        assert received == payload
        sent.result(timeout=10)
        stream.shutdown(socket.SHUT_WR)
        assert stream.recv(1) == b''
    finally:
        finished.set()
        observer.join()
        capture.close()
        sender.shutdown(wait=True)
print('TCP_ZERO_WINDOW_RECOVERY_PASS')

# Abort with unread echo data, then reuse server capacity with new connections.
for index in range(4):
    with socket.create_connection((host, 19501), timeout=10) as stream:
        stream.sendall(b'reset-with-data' * 100)
        stream.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
    exchange(index)
print('TCP_RESET_RECOVERY_PASS')
print("VM_TCP_ECHO_PASS")
