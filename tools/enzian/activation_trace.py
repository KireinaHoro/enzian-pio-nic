#!/usr/bin/env python3
"""Capture one optional activation trace record per yield in a private tracefs instance.
Run on the DUT as root. Does not reset/program hardware or enable global tracing.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

IPG_EVENTS = ["raw_syscalls:sys_enter", "raw_syscalls:sys_exit",
              "irq:irq_handler_entry", "irq:irq_handler_exit",
              "irq:softirq_entry", "irq:softirq_exit", "sched:sched_switch"]
OPTIONAL_IPG_EVENTS = ["ipi:ipi_entry", "ipi:ipi_exit"]

def cpu_mask(cpus):
    values = sorted(set(int(c) for c in cpus.split(",")))
    if not values or min(values) < 0 or max(values) > 4095:
        raise ValueError("invalid CPU list")
    mask = sum(1 << c for c in values)
    groups = [f"{mask >> n & 0xffffffff:08x}" for n in range(0, max(values)+1, 32)]
    return ",".join(reversed(groups)), values



def loss_free(stats):
    for text in stats.values():
        fields = dict(line.split(':',1) for line in text.splitlines() if ':' in line)
        fields = {k.strip():v.strip() for k,v in fields.items()}
        if any(k not in fields or int(fields[k]) != 0
               for k in ('overrun','commit overrun','dropped events')):
            return False
    return bool(stats)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--tracefs', type=Path, default=Path('/sys/kernel/tracing'))
    p.add_argument('--ipg-cpus', help='enable kernel IPG events on comma-separated Linux CPUs, e.g. 44,45,46,47')
    p.add_argument('--buffer-kb', type=int, default=4096)
    p.add_argument('command', nargs=argparse.REMAINDER)
    a = p.parse_args()
    command = a.command[1:] if a.command[:1] == ['--'] else a.command
    if not command or a.buffer_kb <= 0:
        p.error('positive buffer size and a command after -- are required')
    a.output.mkdir(parents=True, exist_ok=False)
    instance = a.tracefs / 'instances' / ('lh-activation-' + str(time.time_ns()))
    instance.mkdir()
    metadata = {'command': command, 'clock': 'embedded CLOCK_MONOTONIC_RAW ns',
                'events': ['lauberhorn:activation'], 'buffer_kb_per_cpu': a.buffer_kb}
    enabled = []
    try:
        (instance/'tracing_on').write_text('0')
        events = ['lauberhorn:activation']
        if a.ipg_cpus:
            mask, cpus = cpu_mask(a.ipg_cpus)
            (instance/'tracing_cpumask').write_text(mask)
            # Do not resize unrelated CPUs: FIFO workers can starve resize work.
            for cpu in cpus:
                (instance/f'per_cpu/cpu{cpu}/buffer_size_kb').write_text(str(a.buffer_kb))
            clocks = (instance/'trace_clock').read_text().replace('[', '').replace(']', '').split()
            if 'mono_raw' not in clocks:
                raise RuntimeError('mono_raw trace clock unavailable')
            (instance/'trace_clock').write_text('mono_raw')
            if '[mono_raw]' not in (instance/'trace_clock').read_text():
                raise RuntimeError('mono_raw trace clock selection failed')
            metadata.update(clock='mono_raw', cpus=cpus, timestamp_quantum_ns=1000,
                            limitations=['text timestamps quantized to microseconds',
                                          'untraced exceptions and faults remain unattributed'])
            events += IPG_EVENTS
            available = [e for e in OPTIONAL_IPG_EVENTS
                         if (instance/'events'/e.replace(':','/')/'enable').exists()]
            if len(available) == len(OPTIONAL_IPG_EVENTS):
                events += available
            metadata['missing_optional_events'] = [e for e in OPTIONAL_IPG_EVENTS if e not in events]
        if not a.ipg_cpus:
            (instance/'buffer_size_kb').write_text(str(a.buffer_kb))
        metadata['events'] = events
        for event in events:
            directory = instance/'events'/event.replace(':','/')
            (a.output/(event.replace(':','_')+'.format')).write_text((directory/'format').read_text())
            (directory/'enable').write_text('1')
            enabled.append(directory/'enable')
        calibration = Path('/sys/module/lauberhorn/parameters/activation_clock_cost_ns')
        metadata['clock_cost_before'] = calibration.read_text().strip()
        (instance/'tracing_on').write_text('1')
        try:
            result = subprocess.run(command)
            metadata['returncode'] = result.returncode
        finally:
            (instance/'tracing_on').write_text('0')
            for enable in enabled:
                enable.write_text('0')
            (a.output/'trace.txt').write_text((instance/'trace').read_text())
            stats = {str(f.parent.name): f.read_text()
                     for f in sorted((instance/'per_cpu').glob('cpu*/stats'))
                     if not a.ipg_cpus or int(f.parent.name[3:]) in cpus}
            metadata['per_cpu_stats'] = stats
            metadata['clock_cost_after'] = calibration.read_text().strip()
            metadata['loss_free'] = loss_free(stats)
    finally:
        (instance/'tracing_on').write_text('0')
        for enable in enabled:
            enable.write_text('0')
        (a.output/'capture.json').write_text(json.dumps(metadata, indent=2)+'\n')
        instance.rmdir()
    if not metadata.get('loss_free') or metadata.get('returncode'):
        raise SystemExit('capture/command failed; evidence retained')


if __name__ == '__main__':
    main()
