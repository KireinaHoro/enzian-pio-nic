#!/usr/bin/env python3
"""Count an explicit AArch64 execution path, including all selected callees.

The manifest pins binaries by SHA256 and lists executed inclusive address ranges
in order (repeat ranges for loops). This is a path audit, not an emulator: branch
conditions and indirect targets must be justified by the accompanying report.
Reject missing instructions, impossible edges and unbound calls. Emit the full
ordered instruction ledger so subtotals cannot hide omitted/double-counted calls.
"""
import argparse
import collections
import hashlib
import json
import pathlib
import re
import subprocess

INSN = re.compile(r'^\s*([0-9a-f]+):\s+([0-9a-f]{8})\s+(\S+)\s*(.*)$')
TARGET = re.compile(r'([0-9a-f]+)\s+<([^>]+)>')


def count(manifest, assembly):
    nodes = manifest['nodes']
    instructions = {}
    for image, text in assembly.items():
        instructions[image] = {
            int(m[1], 16): (m[3], m[4], m[2])
            for line in text.splitlines() if (m := INSN.match(line))
        }
    ledger, branches = [], []

    def visit(name, stage=None, ancestors=()):
        if name in ancestors:
            raise ValueError(f'recursive call: {ancestors} -> {name}')
        node = nodes[name]
        image = node['image']
        stage = node.get('stage', stage)
        seq = []
        for item in node['ranges']:
            ends = [int(x, 16) for x in item.split('-')]
            seq.extend(range(ends[0], ends[-1] + 4, 4))
        for idx, addr in enumerate(seq):
            op, args, encoding = instructions[image][addr]
            ledger.append({'stage': stage, 'node': name, 'image': image,
                           'address': f'{addr:x}', 'opcode': encoding,
                           'instruction': f'{op} {args}'.strip()})
            nxt = seq[idx + 1] if idx + 1 < len(seq) else None
            target = TARGET.search(args)
            dest = int(target[1], 16) if target else None
            explicit = node.get('calls', {}).get(f'{addr:x}')
            conditional = op.startswith('b.') or op in ('cbz', 'cbnz', 'tbz', 'tbnz')
            if conditional:
                if nxt not in (addr + 4, dest):
                    raise ValueError(f'{name}:{addr:x}: impossible conditional edge {nxt}')
                branches.append({'node': name, 'address': f'{addr:x}',
                                 'taken': nxt == dest, 'target': f'{dest:x}'})
            elif op in ('bl', 'blr') or (op in ('b', 'br') and (explicit or nxt is None)):
                terminal = manifest.get('terminal_edges', {}).get(f'{name}:{addr:x}')
                if terminal:
                    if dest != int(terminal, 16):
                        raise ValueError('incorrect terminal edge')
                    continue
                symbol = target[2] if target else None
                callee = explicit or manifest.get('aliases', {}).get(symbol, symbol)
                # Calls through a PLT still execute the caller image's four
                # instructions before entering the selected implementation.
                if symbol and symbol.endswith('@plt'):
                    symbol = symbol[:-4]
                    callee = explicit or manifest.get('aliases', {}).get(symbol, symbol)
                    for p in range(dest, dest + 16, 4):
                        po, pa, pe = instructions[image][p]
                        ledger.append({'stage': nodes[callee].get('stage', stage),
                                       'node': symbol + '@plt', 'image': image,
                                       'address': f'{p:x}', 'opcode': pe,
                                       'instruction': f'{po} {pa}'.strip()})
                    assert instructions[image][dest + 12][0] == 'br'
                if callee not in nodes:
                    raise ValueError(f'unbound call {name}:{addr:x} -> {callee}')
                visit(callee, stage, ancestors + (name,))
                if op in ('b', 'br') and nxt is not None:
                    raise ValueError(f'tail call with continuation {name}:{addr:x}')
                if op in ('bl', 'blr') and nxt != addr + 4:
                    raise ValueError(f'bad return continuation {name}:{addr:x}')
            elif op == 'b':
                if nxt != dest:
                    raise ValueError(f'{name}:{addr:x}: bad branch edge')
            elif op == 'ret':
                if nxt is not None:
                    raise ValueError(f'{name}:{addr:x}: instructions after return')
            elif nxt != addr + 4:
                raise ValueError(f'{name}:{addr:x}: missing fall-through')

    visit(manifest['root'])
    return {'instructions': len(ledger),
            'by_stage': dict(collections.Counter(row['stage'] for row in ledger)),
            'by_node': dict(collections.Counter(row['node'] for row in ledger)),
            'branches': branches, 'ledger': ledger}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('manifest', type=pathlib.Path)
    p.add_argument('--objdump', required=True)
    p.add_argument('--output', required=True, type=pathlib.Path)
    args = p.parse_args()
    manifest = json.loads(args.manifest.read_text())
    assembly = {}
    for image, spec in manifest['inputs'].items():
        binary = pathlib.Path(spec['path'])
        if hashlib.sha256(binary.read_bytes()).hexdigest() != spec['sha256']:
            raise ValueError(f'{image}: binary hash mismatch')
        assembly[image] = subprocess.check_output([args.objdump, '-d', str(binary)], text=True)
    result = count(manifest, assembly)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: v for k, v in result.items() if k not in ('ledger', 'branches')}, indent=2))


if __name__ == '__main__':
    main()
