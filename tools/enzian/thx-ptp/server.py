#!/usr/bin/env python3
"""Run a bounded probe with the zuestoll14 BGX1 packet-kind mapping corrected.

Requires the isolated lh_nicvf module, root, and an active reservation verified
by tools/enzian/run.py. This is board-specific experimental support.
"""
import ctypes
import mmap
import os
from pathlib import Path
import subprocess
import sys


def main():
    device = Path('/sys/bus/pci/devices/0002:01:00.2')
    if (device/'driver').resolve().name != 'lh_nicvf':
        raise RuntimeError('Expected isolated test driver on the data VF')
    resource = '/sys/bus/pci/devices/0000:01:10.1/resource0'
    fd = os.open(resource, os.O_RDWR | os.O_SYNC)
    mapping = mmap.mmap(fd, 65536, flags=mmap.MAP_SHARED,
                        prot=mmap.PROT_READ | mmap.PROT_WRITE)
    register = ctypes.c_uint64.from_buffer(mapping, 0x60)
    saved = register.value
    if saved != 0:
        raise RuntimeError(f'Unexpected BGX1 RX_ID_MAP: {saved:#x}')
    try:
        register.value = 4
        if register.value != 4:
            raise RuntimeError('Packet-kind write did not read back')
        result = subprocess.run([sys.executable,
            str(Path(__file__).with_name('packets.py')), 'server', *sys.argv[1:]],
            timeout=150)
        return result.returncode
    finally:
        # Also disable prefix insertion if the bounded child was killed.
        import socket
        from packets import configure
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            configure(sock.fileno(), 'cpu40g1', False)
        register.value = saved
        if register.value != saved:
            raise RuntimeError('Packet-kind restoration failed')
        del register
        mapping.close()
        os.close(fd)


if __name__ == '__main__':
    raise SystemExit(main())
