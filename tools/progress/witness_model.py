#!/usr/bin/env python3
"""Instantiate the unchanged resource composition with emitted new-design PAs.

This checks a selected finite subset of workers/domains, not all configurations.
Use the capacity verifier for domain-invariant signatures across the full map.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from capacity import encode, color

ROOT = Path(__file__).resolve().parents[2]


def case_function(variable, entries):
    return 'CASE ' + '\n        [] '.join(f'{variable} = {key} -> {value}' for key, value in entries)


def generate(config, domains, stalls, bypass, output):
    if not 0 <= stalls <= len(domains) or stalls >= len(domains) + 2 * bypass:
        raise ValueError('need at least one progressing endpoint')
    if len(domains) > config['worker_count']:
        raise ValueError('too many selected workers')
    clients = []
    for worker, domain in enumerate(domains):
        clients.append((encode(config, worker, domain), None))
    if bypass:
        clients += [(config['bypass']['base_bytes'], direction) for direction in ('rx', 'tx')]
    controls, dependencies, colors = [], [], {}
    allowed = []
    for c, (base, fixed_direction) in enumerate(clients, 1):
        dirs, deps = [], []
        allowed.append((c, '{0}' if c <= stalls or fixed_direction else 'Directions'))
        for d, direction in enumerate(('rx', 'tx')):
            offsets = config['directions'][fixed_direction or direction]
            ctrl = [base + offset for offset in offsets['control_offsets_bytes']]
            overflow = [base + offset for offset in offsets['overflow_offsets_bytes']]
            for addr in ctrl + overflow:
                colors[addr] = color(addr, config['color_bits'])
            dirs.append((d, '[p \\in 0..1 |-> IF p = 0 THEN "0x%x" ELSE "0x%x"]' % tuple(ctrl)))
            deps.append((d, '[p \\in 0..1 |-> IF p = 0 THEN %s ELSE %s]' % tuple(
                '<<' + ', '.join('"0x%x"' % a for a in [ctrl[1-p]] + overflow) + '>>' for p in (0, 1))))
        controls.append((c, '[d \\in Directions |-> ' + case_function('d', dirs) + ']'))
        dependencies.append((c, '[d \\in Directions |-> ' + case_function('d', deps) + ']'))
    text = r'''------------------------------ MODULE Witness ------------------------------
EXTENDS Naturals, Sequences, FiniteSets
Clients == 1..%d
Addresses == {%s}
Colors == 0..%d
ColorOf == [a \in Addresses |-> %s]
Directions == {0,1}
Stalled == 1..%d
AllowedDirections == [c \in Clients |-> %s]
Control == [c \in Clients |-> %s]
Dependencies == [c \in Clients |-> %s]
Queues == Colors
QueueOf == [c \in Colors |-> c]
QueueDepth == 1
Pools == {0}
PoolOf == [c \in Clients |-> 0]
Slots == %d
SharedAck == TRUE
Slices == {0,1}
SliceOf == [c \in Colors |-> c %% 2]
ReadSlots == %d
VARIABLES reads, invQ, held, acknowledgments, phase, parity, direction, remaining, completed, unlockQ
System == INSTANCE ProgressSystem WITH Nonlocking <- FALSE, reads <- reads, invQ <- invQ,
    held <- held, acknowledgments <- acknowledgments, phase <- phase,
    parity <- parity, direction <- direction, remaining <- remaining,
    completed <- completed, unlockQ <- unlockQ
Spec == System!Spec
Settled == System!Settled
NoReadDependencyCycle == System!NoReadDependencyCycle
Progress == System!Progress
Admission == System!Admission
=============================================================================
''' % (len(clients), ', '.join('"0x%x"' % a for a in colors), (1 << config['color_bits'])-1,
       case_function('a', [('"0x%x"' % a, c) for a, c in colors.items()]), stalls, case_function('c', allowed),
       case_function('c', controls), case_function('c', dependencies), len(clients)+1, len(clients)+1)
    output.mkdir(parents=True, exist_ok=False)
    for module in ('ProgressSystem', 'DcsResources', 'ThxResources', 'TwoFClient', 'Coherence'):
        shutil.copyfile(ROOT/f'model/steady/{module}.tla', output/f'{module}.tla')
    (output/'Witness.tla').write_text(text)
    (output/'Witness.cfg').write_text('SPECIFICATION Spec\nINVARIANTS Settled NoReadDependencyCycle\nPROPERTIES Progress Admission\nCHECK_DEADLOCK FALSE\n')
    (output/'manifest.json').write_text(json.dumps(dict(configuration=config, selected_domains=domains,
        stalled_workers=stalls, include_bypass=bypass,
        configuration_sha256=hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()), indent=2)+'\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('capacity_json', type=Path)
    p.add_argument('--domains', nargs='+', type=int, default=[0, 15])
    p.add_argument('--stalls', type=int, default=1)
    p.add_argument('--bypass', action='store_true')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    config = json.loads(a.capacity_json.read_text())[0]['configuration']
    generate(config, a.domains, a.stalls, a.bypass, a.output)


if __name__ == '__main__':
    main()
