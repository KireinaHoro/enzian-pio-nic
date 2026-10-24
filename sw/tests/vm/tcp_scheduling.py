"""Bounded concurrency checks on persistent Linux/FreeBSD TCP connections."""
import json
import socket
import struct
import subprocess
import threading
import time


def state(name):
    with open('/tmp/tcp-' + name + '.json') as f:
        return json.load(f)


def wait(predicate, description):
    end = time.monotonic() + 8
    while not predicate():
        assert time.monotonic() < end, (description, state('a'), state('b'))
        time.sleep(.002)


def receive(s, payload):
    result = bytearray()
    while len(result) < len(payload):
        part = s.recv(len(payload) - len(result))
        assert part
        result.extend(part)
    assert result == payload


gate_tickets = {'a': 0, 'b': 0}
streams = {
    name: [socket.create_connection(('192.168.1.1', port), timeout=10)
           for _ in range(24)]
    for name, port in [('a', 19502), ('b', 19503)]
}
# Prevent the Linux peer's pure ACKs from reaching the stack, after handshake.
# Data still reaches FreeBSD and its echo acknowledges the request, so the peer
# has no outstanding data to retransmit and wake the server accidentally.
probe = streams['a'][0]
port = probe.getsockname()[1]
payload = b'timer-only-retransmission'
seen = []
stop = threading.Event()
capture = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0800))
capture.bind(('lo', 0))
capture.settimeout(.1)


def observe():
    while not stop.is_set():
        try:
            packet, address = capture.recvfrom(65536)
        except socket.timeout:
            continue
        if address[2] == socket.PACKET_OUTGOING:
            continue
        ip = packet[14:]
        if len(ip) < 40 or ip[0] >> 4 != 4 or ip[9] != 6:
            continue
        tcp = ip[(ip[0] & 15) * 4:struct.unpack('!H', ip[2:4])[0]]
        if len(tcp) < 20:
            continue
        source, dest, sequence = struct.unpack('!HHI', tcp[:8])
        if (source, dest) == (19502, port) and tcp[(tcp[12] >> 4) * 4:] == payload:
            seen.append((sequence, time.monotonic()))


observer = threading.Thread(target=observe)
observer.start()
subprocess.run(['nft', '-f', '-'], input='''
table ip tcp_test {
 chain input {
  type filter hook input priority -100; policy accept;
  tcp dport 19502 tcp flags == ack counter drop
 }
}
''', text=True, check=True)
try:
    probe.sendall(payload)
    receive(probe, payload)
    wait(lambda: len(seen) > 0, 'initial echo captured')
    for index, s in enumerate(streams['b']):
        s.sendall(bytes([0xd0, index]))
    wait(lambda: state('a')['assigned'] == state('a')['reserved'] == 0,
         'unacknowledged connection fully preempted')
    parked = time.monotonic()
    activations = state('a')['deadline_activations']
    wait(lambda: state('a')['deadline_activations'] > activations and
         any(seq == seen[0][0] and when > parked for seq, when in seen[1:]),
         'timer-only wakeup and retransmission after full preemption')
    for index, s in enumerate(streams['b']):
        receive(s, bytes([0xd0, index]))
    print('TCP_PARKED_RETRANSMISSION_PASS', flush=True)
finally:
    subprocess.run(['nft', 'delete', 'table', 'ip', 'tcp_test'], check=True)
    stop.set()
    observer.join()
    capture.close()
probe.sendall(b'after-loss')
receive(probe, b'after-loss')

for group in streams.values():
    for s in group:
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

# Reuse the same established connections throughout both directions of reclaim.
for name, other in [('a', 'b'), ('b', 'a'), ('a', 'b')]:
    for index, s in enumerate(streams[name]):
        s.sendall(bytes([0xd0, index]))
    wait(lambda: state(name)['workers'] == 15 and
         state(other)['assigned'] == state(other)['reserved'] == 0,
         'four workers and fully preempted competing TCP application')
    for index, s in enumerate(streams[name]):
        receive(s, bytes([0xd0, index]))
    streams[name][0].sendall(b'\xd1held')
    wait(lambda: state(name)['holding'] == 1, 'one busy application handler')
    # Cross the packet watermark so parallelism does not depend on cores
    # retained after the preceding burst (other domains may have timers due).
    for s in streams[name][1:]:
        s.sendall(b'independent')
    receive(streams[name][1], b'independent')
    assert state(name)['holding'] == 1, 'independent reply waited for slow handler'
    gate_tickets[name] += 1
    with open('/tmp/tcp-' + name + '.json.release', 'w') as release:
        release.write(str(gate_tickets[name]))
    receive(streams[name][0], b'\xd1held')
    for s in streams[name][2:]:
        receive(s, b'independent')
    # The parked application's original connection must resume normally.
    streams[other][0].sendall(b'resume')
    receive(streams[other][0], b'resume')
    print('TCP_RECLAIM_PASS', name, other, flush=True)

for group in streams.values():
    for s in group:
        s.close()
print('VM_TCP_SCHEDULING_PASS', flush=True)
