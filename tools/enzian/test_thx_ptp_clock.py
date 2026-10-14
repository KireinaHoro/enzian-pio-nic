#!/usr/bin/env python3
"""Bounded CN88xx PTP enable/increment test; restore configuration afterwards.

Explicit --enable-test required. Only CLOCK_CFG bit 0 and CLOCK_COMP are
written. Counter registers are never written; elapsed counter state is not
restored. Run on a reserved machine, with no PTP consumer, via run.py.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import resource
import subprocess
import time

BASE = 0x807000000000

class Registers:
    def __init__(self, address, writable=False):
        self.lib = ctypes.CDLL(None, use_errno=True)
        self.lib.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_long]
        self.lib.mmap.restype = ctypes.c_void_p
        self.lib.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
        self.size = os.sysconf('SC_PAGE_SIZE')
        self.page = address & ~(self.size-1)
        self.fd = os.open('/dev/mem', (os.O_RDWR if writable else os.O_RDONLY) | os.O_SYNC)
        self.ptr = self.lib.mmap(None, self.size, 3 if writable else 1, 1, self.fd, self.page)
        if self.ptr == ctypes.c_void_p(-1).value:
            error = ctypes.get_errno()
            os.close(self.fd)
            raise OSError(error, os.strerror(error))
    def cell(self, address):
        assert address % 8 == 0 and self.page <= address <= self.page+self.size-8
        return ctypes.c_uint64.from_address(self.ptr+address-self.page)
    def read(self, address):
        return self.cell(address).value
    def write(self, address, value):
        self.cell(address).value = value
        return self.read(address)
    def close(self):
        self.lib.munmap(self.ptr, self.size)
        os.close(self.fd)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--enable-test', action='store_true', required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    args.output.mkdir(parents=True, exist_ok=False)
    report = {'base':hex(BASE), 'writes':[], 'samples':[], 'restored':False}
    def save():
        (args.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
    def snapshot(phase):
        (args.output/f'dmesg-{phase}.txt').write_text(subprocess.check_output(['dmesg'],text=True))
        tad=Path('/sys/module/lauberhorn/parameters/l2c_tad_snapshot')
        if tad.exists(): (args.output/f'tad-{phase}.txt').write_text(tad.read_text())
    snapshot('before')
    if list(Path('/sys/class/ptp').iterdir()):
        raise RuntimeError('Refusing test with an existing PTP clock')
    reset = [d for d in Path('/sys/bus/pci/devices').iterdir()
             if (d/'vendor').read_text().strip()=='0x177d' and
                (d/'device').read_text().strip()=='0xa00e']
    if len(reset)!=1: raise RuntimeError('Expected one enumerated reset controller')
    start,end,_ = [int(v,16) for v in (reset[0]/'resource').read_text().splitlines()[0].split()]
    assert start and start+0x1607<=end
    rst=Registers(start+0x1600)
    try: boot=rst.read(start+0x1600)
    finally: rst.close()
    multiplier=(boot>>33)&0x3f
    if not multiplier: raise RuntimeError('Zero clock multiplier; no guessed fallback')
    rate=50000000*multiplier
    compensation=(1000000000<<32)//rate
    report.update(reset_bdf=reset[0].name,reset_boot=hex(boot),clock_rate_hz=rate,
                  requested_comp=hex(compensation))
    ptp=Registers(BASE+0xf00,True)
    cfg=ptp.read(BASE+0xf00);comp=ptp.read(BASE+0xf18)
    report['original']={'cfg':hex(cfg),'comp':hex(comp)};save()
    if cfg&1:
        ptp.close()
        raise RuntimeError('Clock already enabled; refusing to change it')
    def write(name,offset,value):
        report['writes'].append({'register':name,'requested':hex(value),'readback':None});save()
        result=ptp.write(BASE+offset,value)
        report['writes'][-1]['readback']=hex(result);save()
        return result
    try:
        # Match upstream Linux: enable, then program the fixed-point increment.
        enabled=write('cfg',0xf00,cfg|1)
        increment=write('comp',0xf18,compensation)
        report['write_readback_matches']=(enabled==cfg|1 and increment==compensation)
        for i in range(6):
            a=time.monotonic_ns();value=ptp.read(BASE+0xf10);b=time.monotonic_ns()
            report['samples'].append({'before_ns':a,'after_ns':b,'clock_hi':value})
            save();time.sleep(.02)
        report['clock_advanced']=report['samples'][-1]['clock_hi']>report['samples'][0]['clock_hi']
    finally:
        try:
            write('cfg_restore_disabled',0xf00,cfg&~1)
            write('comp_restore',0xf18,comp)
            write('cfg_restore',0xf00,cfg)
            report['restored']=(ptp.read(BASE+0xf00)==cfg and ptp.read(BASE+0xf18)==comp)
            save()
        finally:
            ptp.close();snapshot('after')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
