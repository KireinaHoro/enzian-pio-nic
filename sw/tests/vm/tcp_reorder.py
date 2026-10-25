"""Raw-peer retransmission, gap and duplicate checks for VM or ECI TCP."""
import os
import socket
import struct
import subprocess
import time

host = os.environ.get('LH_TEST_HOST', os.environ.get('LH_VM_HOST', '192.168.1.1'))
address = socket.inet_aton(host)
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
    route.connect((host, 19501))
    local = socket.inet_aton(route.getsockname()[0])
source, destination = 20001, 19501
client_next = 100001


def checksum(data):
    if len(data) % 2:
        data += b'\0'
    value = sum(struct.unpack('!%dH' % (len(data) // 2), data))
    while value >> 16:
        value = (value & 65535) + (value >> 16)
    return (~value) & 65535


wire = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_RAW)
capture = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0800))
capture.bind((os.environ.get('LH_TEST_INTERFACE', 'lo'), 0))
capture.settimeout(5)


def send(sequence, acknowledgement, flags, payload=b''):
    tcp = struct.pack('!HHIIBBHHH', source, destination, sequence, acknowledgement,
                      5 << 4, flags, 65535, 0, 0) + payload
    check = checksum(local + address + struct.pack('!BBH', 0, 6, len(tcp)) + tcp)
    tcp = tcp[:16] + struct.pack('!H', check) + tcp[18:]
    ip = struct.pack('!BBHHHBBH', 0x45, 0, 20 + len(tcp), 0, 0, 64, 6, 0) + local + address
    wire.sendto(ip + tcp, (host, 0))


def receive():
    end = time.monotonic() + 5
    while time.monotonic() < end:
        frame, metadata = capture.recvfrom(65536)
        if metadata[2] == socket.PACKET_OUTGOING:
            continue
        ip = frame[14:]
        if len(ip) < 40 or ip[9] != 6 or ip[12:16] != address or ip[16:20] != local:
            continue
        tcp = ip[(ip[0] & 15) * 4:struct.unpack('!H', ip[2:4])[0]]
        if len(tcp) < 20 or struct.unpack('!HH', tcp[:4]) != (destination, source):
            continue
        sequence, acknowledgement = struct.unpack('!II', tcp[4:12])
        assert not tcp[13] & 4, 'unexpected server reset'
        return sequence, acknowledgement, tcp[13], tcp[(tcp[12] >> 4) * 4:]
    raise AssertionError('no matching TCP response')


# The injector owns this tuple; suppress Linux's automatic no-socket reset.
subprocess.run(['nft', '-f', '-'], input='''
table ip tcp_reorder {
 chain output {
  type filter hook output priority -100; policy accept;
  tcp sport 20001 tcp flags & rst == rst drop
 }
}
''', text=True, check=True)
try:
    send(client_next - 1, 0, 2)
    sequence, acknowledgement, flags, data = receive()
    assert flags & 18 == 18 and acknowledgement == client_next and not data
    # Withhold the handshake ACK: no further peer packets can drive stack work.
    # A repeated SYN-ACK therefore requires the server's timer/DEADLINE path.
    retry_sequence, retry_ack, retry_flags, retry_data = receive()
    assert retry_sequence == sequence and retry_ack == acknowledgement
    assert retry_flags & 18 == 18 and not retry_data
    print('TCP_IDLE_RETRANSMISSION_PASS')
    server_next = sequence + 1
    send(client_next, server_next, 16)
    first, second = b'first segment/', b'second segment'
    # A hole must be ACKed without exposing the later bytes to the application.
    # Repeating that segment also exercises duplicate reassembly ownership.
    for _ in range(2):
        send(client_next + len(first), server_next, 24, second)
        _, ack, _, data = receive()
        assert ack == client_next and not data, ('gap exposed to application', ack, data)
    send(client_next, server_next, 24, first)
    expected = first + second
    client_next += len(expected)
    received = bytearray()
    while len(received) < len(expected):
        seq, ack, _, data = receive()
        assert ack == client_next
        if data:
            assert seq == server_next
            received.extend(data)
            server_next += len(data)
            send(client_next, server_next, 16)
    assert received == expected
    send(client_next, server_next, 17)  # FIN, then consume the peer FIN normally.
    client_next += 1
    while True:
        seq, ack, flags, data = receive()
        assert not data, 'duplicate segment was echoed twice'
        if flags & 1:
            assert seq == server_next and ack == client_next
            send(client_next, server_next + 1, 16)
            break
finally:
    subprocess.run(['nft', 'delete', 'table', 'ip', 'tcp_reorder'], check=True)
    wire.close()
    capture.close()
print('TCP_REORDER_DUPLICATE_FIN_PASS')
