#!/usr/bin/env python3
"""Generate the raw-wire and runtime registration table from the RPC schema."""
import re
import sys
from pathlib import Path


def generate(schema):
    types = dict(re.findall(r'typedef opaque (\w+)\[(\d+)\];', schema))
    match = re.search(r'program ECHO_PROG\s*\{\s*version ECHO_VERS\s*\{(.*?)\}\s*=\s*(\d+);\s*\}\s*=\s*(0x[\da-fA-F]+);', schema, re.S)
    if not match:
        raise ValueError('Missing echo program')
    body, version, program = match.groups()
    rows = []
    for result, proc, argument, number in re.findall(r'(\w+)\s+(ECHO\d+)\((\w+)\)\s*=\s*(\d+);', body):
        if result != argument:
            raise ValueError('Echo must return the argument type')
        size = 0 if argument == 'void' else int(types[argument])
        if size % 4 or size > 1432:
            raise ValueError('Fixed XDR echo exceeds MTU or needs padding')
        rows.append(f'X({proc.lower()}, {size}, {number}, xdr_{argument})')
    if not rows or len(rows) != len(set(rows)):
        raise ValueError('Empty or duplicate echo procedures')
    return ('/* Generated from bench.x; do not edit. */\n'
            f'#define ECHO_PROGRAM {program}u\n#define ECHO_VERSION {version}u\n'
            '#define ECHO_VARIANTS(X) ' + ' \\\n    '.join(rows) + '\n')


if __name__ == '__main__':
    Path(sys.argv[2]).write_text(generate(Path(sys.argv[1]).read_text()))
