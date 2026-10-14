#!/usr/bin/env python3
"""Bounded CN88xx data-interface TX timestamp watchdog experiment.
Run only on reserved zuestoll14 with the isolated test driver already active.
"""
import argparse
import ctypes
import json
import mmap
import os
from pathlib import Path
import subprocess
import sys

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--period', type=int, choices=range(16), required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if Path('/sys/bus/pci/devices/0002:01:00.2/driver').resolve().name != 'lh_nicvf':
    raise RuntimeError('Expected isolated data-VF test driver')
a.output.mkdir(parents=True, exist_ok=False)
fd = os.open('/sys/bus/pci/devices/0002:01:00.0/resource0', os.O_RDWR | os.O_SYNC)
m = mmap.mmap(fd, 4096, flags=mmap.MAP_SHARED, prot=mmap.PROT_READ | mmap.PROT_WRITE)
reg = ctypes.c_uint64.from_buffer(m, 0x300)
management = ctypes.c_uint64.from_buffer(m, 0x200)
saved = reg.value
assert saved == 0x10009, hex(saved)
record = dict(before=hex(saved), management_before=hex(management.value),
              period=a.period, timer_ticks=4 * (2**a.period),
              coprocessor_cycles=4 * (2**a.period) * 1024)
try:
    reg.value = (saved & ~(15 << 16)) | (a.period << 16)
    record['during'] = hex(reg.value)
    assert reg.value == (saved & ~(15 << 16)) | (a.period << 16)
    r = subprocess.run([sys.executable, str(Path(__file__).with_name('server.py')),
        '--interface', 'cpu40g1', '--bind', '192.168.129.193',
        '--peer', '192.168.128.225', '--count', '1',
        '--output', str(a.output/'packets')], timeout=155)
    record['server_returncode'] = r.returncode
finally:
    reg.value = saved
    record['restored'] = hex(reg.value)
    record['management_after'] = hex(management.value)
    (a.output/'watchdog.json').write_text(json.dumps(record, indent=2)+'\n')
    assert reg.value == saved
    assert record['management_after'] == record['management_before']
    del reg, management
    m.close()
    os.close(fd)
