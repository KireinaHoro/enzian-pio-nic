"""Temporary device IRQ and workqueue isolation for dedicated benchmark CPUs."""
import json
import errno
import re
from pathlib import Path


def cpus(text):
    result=set()
    for item in text.strip().split(','):
        if not item:continue
        ends=list(map(int,item.split('-')))
        result.update(range(ends[0],ends[-1]+1))
    return result


def mask(values):
    value=sum(1<<cpu for cpu in values)
    groups=[]
    while value:
        groups.append(f'{value & 0xffffffff:08x}');value>>=32
    return ','.join(reversed(groups)) or '0'


class Isolation:
    def __init__(self, isolated, output):
        self.isolated=set(isolated);self.output=Path(output);self.saved=[];self.guarded={}
        self.housekeeping=cpus(Path('/sys/devices/system/cpu/online').read_text())-self.isolated
        if not self.housekeeping:raise ValueError('Housekeeping CPUs required')

    def change(self,path,value):
        old=path.read_text().strip()
        if old==value:return
        self.saved.append((str(path),old))
        self.output.write_text(json.dumps(dict(isolated=sorted(self.isolated),saved=self.saved),indent=2)+'\n')
        try:path.write_text(value+'\n')
        except OSError:
            if path.read_text().strip()==old:self.saved.pop()
            raise

    def apply(self):
        for path in Path('/proc').glob('[0-9]*/comm'):
            try:name=path.read_text().strip()
            except FileNotFoundError:continue
            if name=='irqbalance':
                raise RuntimeError('Stop or configure irqbalance before benchmark isolation')
        configured=cpus(Path('/sys/devices/system/cpu/isolated').read_text())
        if not self.isolated<=configured:raise ValueError('Requested CPUs are not boot-isolated')
        for path in sorted(Path('/proc/irq').glob('[0-9]*/smp_affinity_list')):
            effective=path.with_name('effective_affinity_list')
            if effective.exists() and cpus(effective.read_text()) & self.isolated:
                try:self.change(path,','.join(map(str,sorted(self.housekeeping))))
                except OSError as error:
                    # Managed NVMe queues whose affinity contains no housekeeping
                    # CPU cannot be repinned. Permit only unused queues, guarded
                    # by exact interrupt counters across the entire process.
                    irq=path.parent.name
                    lines=[l.split() for l in Path('/proc/interrupts').read_text().splitlines() if l.lstrip().startswith(irq+':')]
                    fields=lines[0] if len(lines)==1 else []
                    counts=[int(x) for x in fields[1:49]] if len(fields)>=49 else []
                    if error.errno!=errno.EIO or not fields or not re.fullmatch(r'nvme[0-9]+q[0-9]+',fields[-1]) or len(counts)!=48 or any(counts):
                        raise RuntimeError('Cannot isolate IRQ '+irq) from error
                    self.guarded[irq]=dict(name=fields[-1],counts=counts)
                    continue
                if cpus(effective.read_text()) & self.isolated:
                    raise RuntimeError('IRQ remains on benchmark CPUs: '+str(path))
        for path in [Path('/sys/devices/virtual/workqueue/cpumask'), *Path('/sys/devices/virtual/workqueue').glob('*/cpumask')]:
            if not path.exists():continue
            self.change(path,mask(self.housekeeping))
        for path in Path('/sys/class/net').glob('*/queues/rx-*/rps_cpus'):
            self.change(path,'0')
        return dict(isolated=sorted(self.isolated),housekeeping=sorted(self.housekeeping),changed=len(self.saved),guarded_idle_nvme=self.guarded)

    def verify(self):
        rows={f[0].rstrip(':'):f for line in Path('/proc/interrupts').read_text().splitlines() if (f:=line.split())}
        for irq,old in self.guarded.items():
            fields=rows.get(irq,[])
            if not fields or fields[-1]!=old['name'] or [int(x) for x in fields[1:49]]!=old['counts']:
                raise RuntimeError('Managed NVMe IRQ activity during benchmark: '+irq)

    def restore(self):
        errors=[]
        for name,value in reversed(self.saved):
            try:Path(name).write_text(value+'\n')
            except OSError as error:errors.append(f'{name}: {error}')
        if errors:raise RuntimeError('; '.join(errors))
