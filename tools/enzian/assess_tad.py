#!/usr/bin/env python3
"""Assess recorded TAD snapshots/kernel logs without changing any hardware state."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROW = re.compile(r'TAD(\d+) BAR=(?:0x)?([0-9a-fA-F]+) INT=([0-9a-fA-F]+) STAT=([0-9a-fA-F]+) TIMEOUT=([0-9a-fA-F]+)')
# Includes the pinned thunderx_edac.c ISR/thread diagnostics. A cleared INT
# register does not erase a logged fault. Avoid matching module initialization's
# ordinary "mapped ... STAT=... TIMEOUT=..." address report.
FAULT = re.compile(r'Internal error:|Kernel panic|SError|external abort|'
                   r'L2C_(?:TAD|CBC|MCI)_INT|LFB entry timeout|Global sync CCPI timeout|'
                   r'EDAC.*(?:\bCE\b|\bUE\b|error)', re.IGNORECASE)


def snapshot(text):
    units = {}
    for line in text.splitlines():
        match = ROW.fullmatch(line.strip())
        if not match:
            raise ValueError('malformed TAD snapshot row')
        index = int(match[1])
        if index in units:
            raise ValueError('duplicate TAD unit')
        units[index] = {name: int(match[pos], 16) for pos, name in enumerate(['bar', 'interrupt', 'status', 'timeout'], 2)}
    if set(units) != set(range(8)):
        raise ValueError('snapshot does not contain exactly TAD0..7')
    if len({u['bar'] for u in units.values()}) != 8:
        raise ValueError('duplicate TAD BAR mapping')
    return units


def assess(before, after, kernel_before, kernel_after):
    errors = []
    first, last = {}, {}
    try:
        first, last = snapshot(before), snapshot(after)
        for index in range(8):
            a, b = first[index], last[index]
            if a['interrupt'] or b['interrupt']:
                errors.append(f'TAD{index}: nonzero interrupt state')
            if a['bar'] != b['bar']:
                errors.append(f'TAD{index}: BAR identity changed')
            if a['timeout'] != b['timeout']:
                errors.append(f'TAD{index}: TIMEOUT state changed')
    except ValueError as exc:
        errors.append(str(exc))
    if not kernel_before.strip() or not kernel_after.startswith(kernel_before):
        errors.append('kernel log continuity not established; possible ring loss or reset')
    faults = [line for line in kernel_after.splitlines() if FAULT.search(line)]
    if faults:
        errors.append('kernel fault diagnostics present')
    return dict(passed=not errors, errors=errors, before=first, after=last,
                kernel_faults=faults,
                scope='Recorded finite state only. Combine with matched provenance, exposure, trace holds and independent client completions; this is not an infinite-stall guarantee.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['before', 'after', 'kernel-before', 'kernel-after']:
        p.add_argument('--'+name, required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    args = p.parse_args()
    if args.output.exists():
        p.error('output must be new')
    names = ['before', 'after', 'kernel_before', 'kernel_after']
    result = assess(*(getattr(args, name).read_text() for name in names))
    result['input_sha256'] = {name: hashlib.sha256(getattr(args, name).read_bytes()).hexdigest() for name in names}
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({key: result[key] for key in ['passed', 'errors', 'kernel_faults']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
