"""Temporary device IRQ and workqueue isolation for dedicated benchmark CPUs."""
import json
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
        self.isolated=set(isolated);self.output=Path(output);self.saved=[];self.warnings=[];self.before={}
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

    @staticmethod
    def irq_counts():
        rows={}
        for line in Path('/proc/interrupts').read_text().splitlines():
            fields=line.split()
            if not fields or not fields[0].endswith(':'):continue
            counts=[]
            for value in fields[1:]:
                if not value.isdigit():break
                counts.append(int(value))
            if counts:rows[fields[0][:-1]]=counts
        return rows

    def apply(self):
        self.before=self.irq_counts()
        for path in Path('/proc').glob('[0-9]*/comm'):
            try:name=path.read_text().strip()
            except FileNotFoundError:continue
            if name=='irqbalance':
                self.warnings.append('irqbalance is active; affinity may change')
        configured=cpus(Path('/sys/devices/system/cpu/isolated').read_text())
        if not self.isolated<=configured:raise ValueError('Requested CPUs are not boot-isolated')
        for path in sorted(Path('/proc/irq').glob('[0-9]*/smp_affinity_list')):
            effective=path.with_name('effective_affinity_list')
            if effective.exists() and cpus(effective.read_text()) & self.isolated:
                try:self.change(path,','.join(map(str,sorted(self.housekeeping))))
                except OSError as error:
                    # Affinity is best effort: traffic scheduling determines validity.
                    self.warnings.append(f'Cannot move IRQ {path.parent.name}: {error}')
                if cpus(effective.read_text()) & self.isolated:
                    self.warnings.append('IRQ remains on benchmark CPUs: '+str(path))
        for path in [Path('/sys/devices/virtual/workqueue/cpumask'), *Path('/sys/devices/virtual/workqueue').glob('*/cpumask')]:
            if not path.exists():continue
            self.change(path,mask(self.housekeeping))
        for path in Path('/sys/class/net').glob('*/queues/rx-*/rps_cpus'):
            self.change(path,'0')
        return dict(isolated=sorted(self.isolated),housekeeping=sorted(self.housekeeping),changed=len(self.saved),warnings=self.warnings)

    def verify(self):
        """Return diagnostic counters; IRQ activity never invalidates traffic."""
        after=self.irq_counts()
        return dict(before=self.before,after=after,
                    changed=[irq for irq in sorted(self.before.keys() | after.keys())
                             if self.before.get(irq)!=after.get(irq)])

    def restore(self):
        errors=[]
        for name,value in reversed(self.saved):
            try:Path(name).write_text(value+'\n')
            except OSError as error:errors.append(f'{name}: {error}')
        if errors:raise RuntimeError('; '.join(errors))
