"""Four real memcached domains share four device workers and retain streams."""
import concurrent.futures
import json
import statistics
import socket
import struct
import subprocess
import threading
import time
from memcached_tcp import Client

PORTS = range(11220, 11224)
streams = [[Client(port) for _ in range(32)] for port in PORTS]
values = [bytes([97 + domain]) * 4096 for domain in range(4)]
replies = [b'VALUE shared 0 4096\r\n' + value + b'\r\nEND\r\n' for value in values]
for domain, clients in enumerate(streams):
    value = values[domain]
    clients[0].command(b'set shared 0 0 4096\r\n' + value + b'\r\n', b'STORED\r\n')
    clients[0].command(b'set expires 0 1 1\r\nx\r\n', b'STORED\r\n')

stop = threading.Event()
samples = []
burst_rates = []

def states():
    return [json.load(open('/tmp/mc-%d.json' % port)) for port in PORTS]

def observe():
    while not stop.is_set():
        samples.append((time.monotonic(), states()))
        time.sleep(.002)

def wait(predicate, description):
    end = time.monotonic() + 10
    while not predicate():
        assert time.monotonic() < end, (description, states())
        time.sleep(.002)


def parked_retransmission(pool):
    probe = streams[0][0]
    client_port = probe.sock.getsockname()[1]
    capture = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0800))
    capture.bind(('lo', 0))
    capture.settimeout(.1)
    captured = []
    done = threading.Event()

    def collect():
        while not done.is_set():
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
            source, dest, seq = struct.unpack('!HHI', tcp[:8])
            if (source, dest) == (11220, client_port) and tcp[(tcp[12] >> 4) * 4:] == b'END\r\n':
                captured.append((seq, time.monotonic()))

    thread = threading.Thread(target=collect)
    thread.start()
    subprocess.run(['nft', '-f', '-'], input=f"""
    table ip memcached_timer {{
      chain input {{
        type filter hook input priority -100; policy accept;
        tcp dport 11220 tcp sport {client_port} tcp flags == ack counter drop
      }}
    }}
    """, text=True, check=True)
    try:
        probe.command(b'get timer_missing\r\n', b'END\r\n')
        wait(lambda: len(captured) > 0, 'initial memcached response captured')
        futures = [pool.submit(c.command, b'get shared\r\n' * 64, replies[1] * 64)
                   for c in streams[1]]
        wait(lambda: states()[0]['assigned'] == states()[0]['reserved'] == 0,
             'unacknowledged memcached stream fully preempted')
        parked = time.monotonic()
        activations = states()[0]['deadline_activations']
        wait(lambda: states()[0]['deadline_activations'] > activations and
             any(seq == captured[0][0] and when > parked for seq, when in captured[1:]),
             'memcached timer-only wakeup and response retransmission')
        for future in futures:
            future.result()
    finally:
        subprocess.run(['nft', 'delete', 'table', 'ip', 'memcached_timer'], check=True)
        done.set()
        thread.join()
        capture.close()
    probe.command(b'get shared\r\n', replies[0])
    print('MEMCACHED_PARKED_RETRANSMISSION_PASS', flush=True)


observer = threading.Thread(target=observe)
observer.start()
try:
    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
        parked_retransmission(pool)
        # Every domain bursts alone, twice; all requests reuse original streams.
        for round_ in range(2):
            for domain, clients in enumerate(streams):
                def burst(c):
                    c.command(b'get shared\r\n' * 32, replies[domain] * 32)
                started = time.monotonic()
                list(pool.map(burst, clients))
                burst_rates.append(32 * len(clients) / (time.monotonic() - started))
                # After each burst, probe every other domain on an old stream.
                for other_domain, other in enumerate(streams):
                    other[0].command(b'get shared\r\n', replies[other_domain])
                print('MEMCACHED_BURST_PASS', round_, domain, flush=True)
    snapshot = states()
    assert all(s['registered'] == 4 for s in snapshot), snapshot
    assert all(s['workers'] == 15 for s in snapshot), snapshot
    parked = [any(s[i]['assigned'] == s[i]['reserved'] == 0 for _, s in samples)
              for i in range(4)]
    assert all(parked), ('all four domains must lose every worker', parked)

    # Fixed offered cadence: 20 requests/s/domain, concurrent across domains.
    # Verify this run was below saturation using measured response times and
    # queue occupancy, rather than labeling a closed-loop test underloaded.
    assert 4 * 20 < min(burst_rates), ('low offered load exceeds measured burst service', burst_rates)
    low_start = time.monotonic()
    def low_load(domain):
        latencies = []
        start = time.monotonic()
        for n in range(100):
            due = start + n * .05
            time.sleep(max(0, due - time.monotonic()))
            sent = time.monotonic()
            assert sent - due < .1, ('offered load fell behind', domain, n)
            streams[domain][n % 32].command(b'get shared\r\n', replies[domain])
            latencies.append(time.monotonic() - sent)
        return latencies
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        latencies = list(pool.map(low_load, range(4)))
    low_samples = [s for t, s in samples if t >= low_start]
    for i, latency in enumerate(latencies):
        assert sorted(latency)[94] < .05, ('not below saturation', i, latency)
        assert sum(2 * s[i]['queued'] <= s[i]['capacity'] for s in low_samples) / len(low_samples) > .95
    # Persistence includes a fully idle interval and another operation on every
    # original connection, not merely creating replacement sockets.
    time.sleep(1)
    for domain, clients in enumerate(streams):
        clients[0].command(b'get expires\r\n', b'END\r\n')
        for c in clients:
            c.command(b'get shared\r\n', replies[domain])
            c.sock.sendall(b'quit\r\n')
            assert not c.sock.recv(1)
            c.close()
    print('MEMCACHED_COLOCATION_PASS', json.dumps({
        'domains': 4, 'threads_per_domain': 4, 'device_workers': 4,
        'persistent_connections': 128, 'fully_parked': parked,
        'low_rate_per_domain': 20, 'low_requests_per_domain': 100,
        'burst_requests_per_second': burst_rates,
        'latency_mean_ms': [statistics.mean(x) * 1000 for x in latencies],
        'latency_p95_ms': [sorted(x)[94] * 1000 for x in latencies],
    }), flush=True)
finally:
    stop.set()
    observer.join()
