"""Exercise the capture CLI against a loopback UDP FPGA responder."""
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "dump_trace_udp.py"


def response(seq, offset, data=b"", flags=0):
    header = bytearray(32)
    struct.pack_into("!4sBBBBIQHI", header, 0,
                     b"LHTD", 1, 0, flags, 0, seq, offset, len(data), 64)
    return header + data


class CaptureTest(unittest.TestCase):
    def capture(self, extra=(), dma_error=False):
        with tempfile.TemporaryDirectory() as directory, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as fpga:
            fpga.bind(("127.0.0.1", 0))
            fpga.settimeout(5)
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reserve:
                reserve.bind(("127.0.0.1", 0))
                port = reserve.getsockname()[1]
            output = pathlib.Path(directory) / "raw.bin"
            proc = subprocess.Popen(
                [sys.executable, str(SCRIPT), "--bind-ip", "127.0.0.1",
                 "--port", str(port), "--wait-timeout", "5", "--timeout", "0.1",
                 "--out", str(output), *extra],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.assertIn("waiting for FPGA", proc.stderr.readline())
                fpga.sendto(response(0, 0), ("127.0.0.1", port))
                expected = 4096 - (64 if "--offset" in extra else 0)
                received = 0
                lengths = []
                while received < expected:
                    payload, peer = fpga.recvfrom(4096)
                    magic, version, op, _, _, seq, offset, length = struct.unpack("!4sBBBBIQI", payload)
                    self.assertEqual((magic, version, op), (b"LHTR", 1, 1))
                    self.assertEqual(length % 64, 0)
                    lengths.append(length)
                    data = bytes((offset + i) % 251 for i in range(length))
                    fpga.sendto(response(seq, offset, data, int(dma_error)), peer)
                    received += length
                    if dma_error:
                        break
                stdout, stderr = proc.communicate(timeout=5)
                if dma_error:
                    self.assertNotEqual(proc.returncode, 0)
                    self.assertIn("trace DMA error", stderr)
                    self.assertEqual(output.read_bytes(), b"")
                else:
                    self.assertEqual(proc.returncode, 0, stderr)
                    start = 4096 - expected
                    self.assertEqual(output.read_bytes(), bytes(i % 251 for i in range(start, 4096)))
                    self.assertEqual(lengths[0], 1408)
            finally:
                if proc.poll() is None:
                    proc.kill()
                    proc.communicate()
                proc.stdout.close()
                proc.stderr.close()

    def test_default_chunks(self):
        self.capture()

    def test_offset_stops_at_write_slot(self):
        self.capture(("--offset", "64"))

    def test_dma_error_rejected(self):
        self.capture(dma_error=True)


if __name__ == "__main__":
    unittest.main()
