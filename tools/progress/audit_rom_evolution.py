#!/usr/bin/env python3
"""Reproduce historical ROMs and trial ports from the existing non-lock generator.
Requires local DCS history and the Perl environment in nix develop. No RTL install.
Comparisons are symbolic explicit transitions, not protocol correctness proofs.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DC = ROOT / 'vivado/eci/directory-controller-slice'
BASE = 'gen_dc_protocol/'
LOCK = BASE + 'dc_stmc_auto_lock/'
PLAIN = BASE + 'dc_stmc_auto_no_lock/'


def source(rev, path):
    return subprocess.check_output(['git', '-C', str(DC), 'show', f'{rev}:{path}']).decode('latin1')


def transitions(text):
    result = {}
    for state, body in re.findall(r'(\w+): begin\s*case\(curr_req_i\)(.*?)endcase', text, re.S):
        for req, nxt, action in re.findall(r'(\w+): begin\s*next_state_reg <= (\w+);\s*next_action_reg <= (\w+);', body):
            assert (state, req) not in result
            result[state, req] = nxt, action
    assert result
    return result


def difference(a, b):
    return {'removed': len(a.keys() - b.keys()), 'added': len(b.keys() - a.keys()),
            'changed': [{'state': s, 'request': r, 'before': a[s, r], 'after': b[s, r]}
                        for s, r in sorted(a.keys() & b.keys()) if a[s, r] != b[s, r]]}


def port(text, exclusive=False, simplify=False):
    # Preserve non-locking local seeds, actions and generation algorithm.
    if exclusive:
        text, n = re.subn(r'(R12, )2pRA2', r'\g<1>3pRA3', text)
        assert n == 24
    if simplify:
        text = '\n'.join(line for line in text.splitlines()
                         if not (re.match(r'\s*"', line) and
                                 re.search(r'\b(?:V32d?|A22)\b', line))) + '\n'
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    records, tables = {}, {}

    def generate(name, text, variant):
        directory = (args.output / name).resolve()
        directory.mkdir(exist_ok=True)
        files = {'eci_stmc_auto.pl': text}
        for filename in ['cc_rom_from_cc_tbl.pl', 'eci_cc_defs.tmpl', 'eci_cc_table.tmpl']:
            files[filename] = source('9c9abae', variant + 'cc_rom_from_cc_tbl/' + filename)
        for filename, contents in files.items():
            (directory / filename).write_text(contents, encoding='latin1')
        with (directory / 'generation.log').open('w') as log:
            subprocess.run(['perl', 'eci_stmc_auto.pl'], cwd=directory, stdout=log, stderr=log, check=True)
            subprocess.run(['perl', 'cc_rom_from_cc_tbl.pl', 'ECI_CC.csv'], cwd=directory, stdout=log, stderr=log, check=True)
        with (directory / 'lint.log').open('w') as log:
            subprocess.run(['verilator', '--lint-only', '-Wno-fatal', '--top-module',
                            'eci_cc_table', 'eci_cc_defs.sv', 'eci_cc_table.sv'],
                           cwd=directory, stdout=log, stderr=log, check=True)
        table = transitions((directory / 'eci_cc_table.sv').read_text(encoding='latin1'))
        tables[name] = table
        records[name] = {'states': len({s for s, _ in table}), 'explicit_transitions': len(table),
                         'generator_sha256': hashlib.sha256(text.encode('latin1')).hexdigest(),
                         'absent_R12': table['s1__1', 'R12'],
                         'absent_LCI': table['s1__1', 'LCI']}

    for rev in ['9c9abae', 'a818e5d', '2d77ada']:
        generate('lock-' + rev, source(rev, LOCK + 'eci_stmc_auto.pl'), LOCK)
    old_lock = source('9c9abae', LOCK + 'eci_stmc_auto.pl')
    old_plain = source('9c9abae', PLAIN + 'eci_stmc_auto.pl')
    assert old_plain == (DC / PLAIN / 'eci_stmc_auto.pl').read_text(encoding='latin1')
    generate('lock-simplified-ra2', port(old_lock, simplify=True), LOCK)
    generate('plain-original', old_plain, PLAIN)
    generate('plain-ra3', port(old_plain, exclusive=True), PLAIN)
    generate('plain-simplified-ra2', port(old_plain, simplify=True), PLAIN)
    generate('plain-simplified-ra3', port(old_plain, exclusive=True, simplify=True), PLAIN)

    checks = {'all_eight_generated_roms_verilator_lint': True}
    for name, rev in [('lock-9c9abae', '9c9abae'), ('lock-a818e5d', 'a818e5d'),
                      ('lock-simplified-ra2', '4bc6183')]:
        checked = transitions(source(rev, LOCK + 'cc_rom_from_cc_tbl/eci_cc_table.sv'))
        assert tables[name] == checked, name
        checks[name + '-reproduces-checked-rom'] = True
    assert tables['plain-original'] == transitions(source('9c9abae', PLAIN + 'cc_rom_from_cc_tbl/eci_cc_table.sv'))
    assert tables['lock-2d77ada'] == transitions(source('2d77ada', LOCK + 'cc_rom_from_cc_tbl/eci_cc_table.sv'))
    checks['later-generator-reproduces-merged-ra3-simplified-rom'] = True
    comparisons = {}
    for before, after in [('lock-9c9abae', 'lock-a818e5d'),
                          ('lock-9c9abae', 'lock-simplified-ra2'),
                          ('lock-simplified-ra2', 'lock-2d77ada')]:
        comparisons[before + '-to-' + after] = difference(tables[before], tables[after])
    for name in ['plain-ra3', 'plain-simplified-ra2', 'plain-simplified-ra3']:
        comparisons[name] = difference(tables['plain-original'], tables[name])
    for lock, plain in [('lock-9c9abae', 'plain-original'),
                        ('lock-simplified-ra2', 'plain-simplified-ra2'),
                        ('lock-2d77ada', 'plain-simplified-ra3')]:
        # Only compare shared states/requests: lock states themselves have different behavior.
        shared = tables[lock].keys() & tables[plain].keys()
        for key in shared:
            nxt, action = tables[lock][key]
            assert (nxt.replace('s1pUL__', 's1__'), action) == tables[plain][key], (lock, key)
        checks[lock + '-matches-' + plain + '-on-shared-entries-after-lock-erasure'] = len(shared)
    revisions = {r: subprocess.check_output(['git', '-C', str(DC), 'rev-parse', r]).decode().strip()
                 for r in ['9c9abae', 'a818e5d', '4bc6183', 'cf9b1cf', 'acbf558', '2d77ada', 'HEAD']}
    summary = {'scope': __doc__, 'revisions': revisions, 'generated': records, 'checks': checks, 'comparisons': comparisons}
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({'generated': records, 'checks': checks}, indent=2))


if __name__ == '__main__':
    main()
