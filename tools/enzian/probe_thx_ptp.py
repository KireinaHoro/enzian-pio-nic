#!/usr/bin/env python3
"""Read only CN88xx PTP registers at the documented fixed BAR0 address.

Run as root through tools/enzian/run.py after checking the reservation.
Each read runs in a bounded child; stop immediately on access failure.
No PCI, clock-enable, clock-compensation or firmware writes are performed.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import resource
import subprocess
import sys
import time

BASE = 0x807000000000
REGISTERS = {'cfg': 0xf00, 'clock_lo': 0xf08, 'clock_hi': 0xf10, 'comp': 0xf18}


def read_register(name):
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    libc = ctypes.CDLL(None, use_errno=True)
    libc.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int,
                          ctypes.c_int, ctypes.c_int, ctypes.c_long]
    libc.mmap.restype = ctypes.c_void_p
    libc.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    address = BASE + REGISTERS[name]
    page = address & ~(os.sysconf('SC_PAGE_SIZE') - 1)
    length = os.sysconf('SC_PAGE_SIZE')
    fd = os.open('/dev/mem', os.O_RDONLY | os.O_SYNC)
    try:
        pointer = libc.mmap(None, length, 1, 1, fd, page)  # PROT_READ, MAP_SHARED
        if pointer == ctypes.c_void_p(-1).value:
            error = ctypes.get_errno()
            raise OSError(error, os.strerror(error))
        try:
            start = time.monotonic_ns()
            value = ctypes.c_uint64.from_address(pointer + address - page).value
            end = time.monotonic_ns()
            print(json.dumps(dict(register=name, address=hex(address), value=hex(value),
                                  start_ns=start, end_ns=end)), flush=True)
        finally:
            libc.munmap(pointer, length)
    finally:
        os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--read', choices=REGISTERS)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.read:
        read_register(args.read)
        return
    if args.output is None:
        parser.error('--output required')
    args.output.mkdir(exist_ok=False, parents=True)
    def snapshot(suffix):
        result = subprocess.run(['dmesg'], capture_output=True, text=True, check=True)
        (args.output / f'dmesg-{suffix}.txt').write_text(result.stdout)
        tad = Path('/sys/module/lauberhorn/parameters/l2c_tad_snapshot')
        if tad.exists():
            (args.output / f'tad-{suffix}.txt').write_text(tad.read_text())
    snapshot('before')
    report = dict(base=hex(BASE), read_only=True, reads=[], passed=False)
    for name in ('cfg', 'clock_hi', 'clock_lo', 'comp', 'clock_hi', 'cfg'):
        try:
            result = subprocess.run([sys.executable, __file__, '--read', name],
                                    capture_output=True, text=True, timeout=5)
            if result.returncode:
                report['error'] = dict(register=name, returncode=result.returncode,
                                       stderr=result.stderr[-2000:])
                break
            report['reads'].append(json.loads(result.stdout))
        except subprocess.TimeoutExpired:
            report['error'] = dict(register=name, timeout_seconds=5)
            break
        time.sleep(.02)
    else:
        report['passed'] = True
    snapshot('after')
    (args.output / 'report.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
