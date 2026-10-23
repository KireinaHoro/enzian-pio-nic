import socket
import struct
import threading
import time


def words(*values):
    return struct.pack('!' + 'I' * len(values), *values)


held = []
saturating = threading.Event()
peer_socket = None


def peer():
    global peer_socket
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(('127.0.0.1', 23456))
        peer_socket = s
        ready.set()
        while True:
            packet, addr = s.recvfrom(4096)
            header = struct.unpack('!10I', packet[:40])
            assert header[1:3] == (0, 2)
            op = struct.unpack('!4I', packet[40:56])[1]
            if op in (100, 101):
                if op == 100 and saturating.is_set():
                    held.append((packet, addr))
                continue  # cancellation or timeout; no network arrival wakes it
            response = words(header[0], 1, 0, 0, 0, 3 if op == 102 else 0)
            if op != 102:
                response += packet[40:]
            s.sendto(response, addr)


ready = threading.Event()
threading.Thread(target=peer, daemon=True).start()
assert ready.wait(3)
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
    client.settimeout(6)
    for xid, op in enumerate((0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 0), 1):
        body = words(xid, op, 32 if op == 7 else 4, 123)
        request = words(xid, 0, 2, 0x10234567, 0x12345678, 0x23456789, 0, 0, 0, 0) + body
        client.sendto(request, ('127.0.0.1', 22345))
        reply, _ = client.recvfrom(4096)
        header = struct.unpack('!6I', reply[:24])
        assert header[:5] == (xid, 1, 0, 0, 0), header
        expected = {9: 3, 10: 5, 11: 5, 12: 5}.get(op, 0)
        assert header[5] == expected, (op, header)
        if not expected:
            if op in (3, 4):
                body = words(xid, 0, 4, 123)
            elif op == 7:
                body = words(xid, op, 16, 16)
            assert reply[24:] == body, (op, reply[24:], body)
        print('RPC_CASE_PASS', op, flush=True)
    # Hold sixteen accepted calls in the emulator's incoming-context table.
    # The remaining calls must get SYSTEM_ERR without blocking RX. Then release
    # the peer replies and prove both context retirement and subsequent progress.
    saturating.set()
    for xid in range(100, 132):
        request = words(xid, 0, 2, 0x10234567, 0x12345678, 0x23456789, 0, 0, 0, 0)
        client.sendto(request + words(xid, 15, 1, 123), ('127.0.0.1', 22345))
    rejected = set()
    for _ in range(16):
        reply, _ = client.recvfrom(4096)
        xid, kind, accepted, flavor, length, status = struct.unpack('!6I', reply[:24])
        assert status == 5 and 100 <= xid < 132
        rejected.add(xid)
    assert len(rejected) == 16
    deadline = time.monotonic() + 3
    while len(held) < 16 and time.monotonic() < deadline:
        time.sleep(0.01)
    assert len(held) == 16
    for packet, addr in held:
        xid = struct.unpack('!I', packet[:4])[0]
        peer_socket.sendto(words(xid, 1, 0, 0, 0, 0) + packet[40:], addr)
    completed = set()
    for _ in range(16):
        reply, _ = client.recvfrom(4096)
        header = struct.unpack('!6I', reply[:24])
        assert header[5] == 0 and header[0] not in rejected
        assert reply[24:] == words(header[0], 15, 1, 123)
        completed.add(header[0])
    assert completed | rejected == set(range(100, 132))
    client.sendto(words(200, 0, 2, 0x10234567, 0x12345678, 0x23456789, 0, 0, 0, 0)
                  + words(200, 0, 1, 123), ('127.0.0.1', 22345))
    reply, _ = client.recvfrom(4096)
    assert reply == words(200, 1, 0, 0, 0, 0) + words(200, 0, 1, 123)
    print('RPC_SATURATION_RECOVERY_PASS', flush=True)
print('VM_RPC_PASS', flush=True)
