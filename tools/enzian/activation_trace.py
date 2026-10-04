#!/usr/bin/env python3
"""Capture one optional activation trace record per yield in a private tracefs instance.
Run on the DUT as root. Does not reset/program hardware or enable global tracing.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time


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
    try:
        (instance/'tracing_on').write_text('0')
        (instance/'buffer_size_kb').write_text(str(a.buffer_kb))
        # Event embeds raw ns: analysis never subtracts tracefs-clock timestamps.
        (instance/'events/lauberhorn/activation/enable').write_text('1')
        calibration = Path('/sys/module/lauberhorn/parameters/activation_clock_cost_ns')
        metadata['clock_cost_before'] = calibration.read_text().strip()
        (instance/'tracing_on').write_text('1')
        try:
            result = subprocess.run(command)
            metadata['returncode'] = result.returncode
        finally:
            (instance/'tracing_on').write_text('0')
            (instance/'events/lauberhorn/activation/enable').write_text('0')
            (a.output/'trace.txt').write_text((instance/'trace').read_text())
            stats = {str(f.parent.name): f.read_text()
                     for f in sorted((instance/'per_cpu').glob('cpu*/stats'))}
            metadata['per_cpu_stats'] = stats
            metadata['clock_cost_after'] = calibration.read_text().strip()
            metadata['loss_free'] = loss_free(stats)
    finally:
        (instance/'tracing_on').write_text('0')
        enable = instance/'events/lauberhorn/activation/enable'
        if enable.exists():
            enable.write_text('0')
        (a.output/'capture.json').write_text(json.dumps(metadata, indent=2)+'\n')
        instance.rmdir()
    if not metadata.get('loss_free') or metadata.get('returncode'):
        raise SystemExit('capture/command failed; evidence retained')


if __name__ == '__main__':
    main()
