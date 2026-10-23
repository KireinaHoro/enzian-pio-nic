import os
import socket
import struct

host = os.environ.get('LH_VM_HOST', '127.0.0.1')
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
    s.settimeout(2)
    request_id = 0

    def request(command):
        global request_id
        request_id += 1
        s.sendto(struct.pack('!4H', request_id, 0, 1, 0) + command, (host, 11211))
        parts = {}
        while True:
            packet, _ = s.recvfrom(2048)
            rid, seq, total, reserved = struct.unpack('!4H', packet[:8])
            assert rid == request_id and not reserved and seq < total <= 64
            parts[seq] = packet[8:]
            if len(parts) == total:
                return b''.join(parts[n] for n in range(total))

    assert request(b'set alpha 0 0 3\r\nabc\r\n') == b'STORED\r\n'
    assert request(b'get alpha\r\n') == b'VALUE alpha 0 3\r\nabc\r\nEND\r\n'
    assert request(b'set number 0 0 2\r\n10\r\n') == b'STORED\r\n'
    assert request(b'incr number 5\r\n') == b'15\r\n'
    value = b'x' * 1024
    assert request(b'set large 0 0 1024\r\n' + value + b'\r\n') == b'STORED\r\n'
    for _ in range(3):
        assert request(b'append large 0 0 1024\r\n' + value + b'\r\n') == b'STORED\r\n'
    expected = b'VALUE large 0 4096\r\n' + value * 4 + b'\r\nEND\r\n'
    assert request(b'get large\r\n') == expected
    assert request(b'delete alpha\r\n') == b'DELETED\r\n'
    assert request(b'get alpha\r\n') == b'END\r\n'
print('VM_MEMCACHED_PASS', host, flush=True)
