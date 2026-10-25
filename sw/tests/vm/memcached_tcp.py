"""Native/upstream stream contract checks, using ordinary Linux TCP clients."""
import concurrent.futures
import os
import socket
import struct
import time

HOST = os.environ.get('LH_TEST_HOST', os.environ.get('LH_VM_HOST', '192.168.1.1'))
PORT = int(os.environ.get('LH_MC_PORT', '11212'))

class Client:
    def __init__(self, port=PORT):
        self.sock = socket.create_connection((HOST, port), timeout=10)
        self.buffer = b''

    def expect(self, expected):
        while len(self.buffer) < len(expected):
            part = self.sock.recv(max(65536, len(expected) - len(self.buffer)))
            assert part, ('early EOF', len(self.buffer), len(expected))
            self.buffer += part
        actual, self.buffer = self.buffer[:len(expected)], self.buffer[len(expected):]
        assert actual == expected, (actual[:200], expected[:200])

    def command(self, command, expected):
        self.sock.sendall(command)
        self.expect(expected)

    def close(self):
        self.sock.close()


def exercise(index):
    c = Client()
    key = ('key%d' % index).encode()
    value = bytes(range(251)) * 100
    store = b'set ' + key + b' 7 0 ' + str(len(value)).encode() + b'\r\n' + value + b'\r\n'
    # Split both command line and body. Keep the stream alive across idle gaps.
    for end in [2, 9, 31, 4079]:
        c.sock.sendall(store[:end])
        store = store[end:]
        time.sleep(.002)
    c.command(store, b'STORED\r\n')
    response = b'VALUE ' + key + b' 7 ' + str(len(value)).encode() + b'\r\n' + value + b'\r\nEND\r\n'
    c.command((b'get ' + key + b'\r\n') * 8, response * 8)
    c.command(b'set ' + key + b' 0 0 2\r\n10\r\nincr ' + key + b' 5\r\ndecr ' + key + b' 3\r\n',
              b'STORED\r\n15\r\n12\r\n')
    c.command(b'delete ' + key + b'\r\nget ' + key + b'\r\n', b'DELETED\r\nEND\r\n')
    c.command(b'set ' + key + b' 0 0 1 noreply\r\nx\r\nget ' + key + b'\r\n',
              b'VALUE ' + key + b' 0 1\r\nx\r\nEND\r\n')
    c.sock.sendall(b'quit\r\n')
    assert not c.sock.recv(1)
    c.close()

def binary_protocol():
    c = Client()
    opaque = 0

    def request(opcode, key=b'', value=b'', extras=b''):
        nonlocal opaque
        opaque += 1
        body = extras + key + value
        header = struct.pack('!BBHBBHIIQ', 0x80, opcode, len(key), len(extras),
                             0, 0, len(body), opaque, 0)
        c.sock.sendall(header[:7])
        time.sleep(.002)
        c.sock.sendall(header[7:] + body)

    def exact(length):
        data = b''
        while len(data) < length:
            part = c.sock.recv(length - len(data))
            assert part
            data += part
        return data

    def response(opcode, status, body):
        magic, op, keylen, extlen, datatype, result, size, token, cas = struct.unpack(
            '!BBHBBHIIQ', exact(24))
        assert (magic, op, result, token) == (0x81, opcode, status, opaque)
        assert exact(size) == body
        return cas

    request(1, b'binary', b'value', struct.pack('!II', 17, 0))
    assert response(1, 0, b'') > 0
    request(0, b'binary')
    assert response(0, 0, struct.pack('!I', 17) + b'value') > 0
    request(4, b'binary')
    response(4, 0, b'')
    request(7)
    response(7, 0, b'')
    assert not c.sock.recv(1)
    c.close()


if __name__ == '__main__':
    binary_protocol()
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(exercise, range(16)))
    slow = Client()
    slow.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    slow.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_WINDOW_CLAMP, 4096)
    value = b'b' * 65536
    slow.command(b'set backpressure 0 0 65536\r\n' + value + b'\r\n', b'STORED\r\n')
    slow.sock.sendall(b'get backpressure\r\n' * 16)
    time.sleep(.2)
    exercise(21)  # An unread response must not monopolize connection execution.
    slow.expect((b'VALUE backpressure 0 65536\r\n' + value + b'\r\nEND\r\n') * 16)
    slow.command(b'delete backpressure\r\n', b'DELETED\r\n')
    slow.close()
    # Aborted response retirement must leave replacement connections functional.
    for _ in range(4):
        c = Client()
        c.sock.sendall(b'get key0\r\n' * 64)
        c.sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
        c.close()
        exercise(20)
    print('MEMCACHED_TCP_STREAM_PASS', HOST, PORT, flush=True)
