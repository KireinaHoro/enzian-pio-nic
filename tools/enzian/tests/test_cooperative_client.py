import importlib.util
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('client', Path(__file__).resolve().parents[1] / 'cooperative_client.py')
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


class ClientTests(unittest.TestCase):
    def run_peer(self, mode):
        stop = threading.Event()
        errors = []
        received = []
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as peer, tempfile.TemporaryDirectory() as temp:
            peer.bind(('127.0.0.1', 0))
            peer.settimeout(.02)
            port = peer.getsockname()[1]
            original = client.request
            def redirected(kind, ident):
                _, packet, expected = original(kind, ident)
                return port, packet, expected
            def serve():
                while not stop.is_set():
                    try:
                        packet, address = peer.recvfrom(65536)
                    except socket.timeout:
                        continue
                    received.append(packet)
                    if mode == 'drop':
                        continue
                    if packet[:4] == b'LHBP':
                        reply = packet
                    else:
                        header = struct.unpack('!10I', packet[:40])
                        if header[1:3] != (0, 2) or header[6:] != (0, 0, 0, 0):
                            errors.append('bad RPC request header')
                        xid, _, _, program, version, procedure, *_ = header
                        if program == 0x10234567:
                            if (version, procedure) != (0x12345678, 0x23456789):
                                errors.append('bad echo endpoint')
                            body = packet[40:]
                        else:
                            if (version, procedure) != (1, 1):
                                errors.append('bad benchmark endpoint')
                            ident, a, b = struct.unpack('!3I', packet[40:])
                            body = struct.pack('!2I', ident, a+b if program == 0x23451111 else a*b)
                        reply = struct.pack('!6I', xid, 1, 0, 0, 0, 0) + body
                    if mode == 'corrupt':
                        reply = reply[:-1] + bytes([reply[-1] ^ 1])
                    peer.sendto(reply, address)
            thread = threading.Thread(target=serve)
            thread.start()
            try:
                output = Path(temp)/'run'
                with patch.object(client, 'request', redirected):
                    rc = client.load('127.0.0.1', '127.0.0.1', ['add', 'mul', 'echo', 'bypass'], .05, 1, .1, output)
                summary = json.loads((output/'summary.json').read_text())
                rows = (output/'completions.csv').read_text().splitlines()
            finally:
                stop.set()
                thread.join(1)
            self.assertFalse(errors)
            return rc, summary, rows, received

    def test_parallel_rpc_and_bypass_completions(self):
        rc, summary, rows, _ = self.run_peer('reply')
        self.assertEqual(rc, 0)
        self.assertTrue(all(summary['completions'][kind] > 0 for kind in ['add', 'mul', 'echo', 'bypass']))
        self.assertEqual(len(rows)-1, sum(summary['completions'].values()))
        self.assertEqual(summary['pending'], 0)

    def test_corruption_is_not_counted(self):
        rc, summary, _, _ = self.run_peer('corrupt')
        self.assertEqual(rc, 1)
        self.assertIn('mismatch', summary['error'])
        self.assertEqual(summary['completions'], {})

    def test_timeout_preserves_pending_without_retry(self):
        rc, summary, _, received = self.run_peer('drop')
        self.assertEqual(rc, 1)
        self.assertEqual(summary['pending'], 4)
        self.assertEqual(summary['retries'], 0)
        self.assertEqual(len(received), 4)


if __name__ == '__main__':
    unittest.main()
