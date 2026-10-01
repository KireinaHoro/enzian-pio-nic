#!/usr/bin/env python3
"""Reconstruct Jasmin's legacy DC generator/checker workflow; not a full proof."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from audit_rom_evolution import ROOT, DC, PLAIN, source, transitions, difference

THX = ROOT / 'deps/thx-fsms'


def exclusive(text):
    text, count = re.subn(r"(R12, )2pRA2", r"\g<1>3pRA3", text)
    assert count == 17
    return text


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, str(ROOT / 'tools/progress/audit_rom_evolution.py'),
                    '--output', str(out / 'history')], stdout=subprocess.DEVNULL, check=True)
    legacy = THX / 'legacy'
    corrected = (legacy / 'eci_stmc_auto_corrected.pl').read_text()
    plain = source('9c9abae', PLAIN + 'eci_stmc_auto.pl')
    for name in ['rmt_evts', 'rs_eqns']:
        pattern = rf'my @{name} = \(.*?\n\s*\);'
        block = re.search(pattern, corrected, re.S).group()
        plain, count = re.subn(pattern, lambda _: block, plain, flags=re.S)
        assert count == 1
    generators = {'jasmin-ra2': corrected, 'jasmin-ra3': exclusive(corrected),
                  'plain-jasmin-ra3': exclusive(plain)}
    tables = {}
    cases = {name: out / 'history' / name for name in
             ['lock-9c9abae', 'lock-simplified-ra2', 'lock-2d77ada', 'plain-simplified-ra3']}
    result = {'scope': __doc__, 'thx_revision': subprocess.check_output(
        ['git', '-C', str(THX), 'rev-parse', 'HEAD']).decode().strip(),
        'source_sha256': {name: hashlib.sha256((legacy / name).read_bytes()).hexdigest()
                          for name in ['eci_stmc_auto_corrected.pl', 'check_DirC_csv.py', 'remote_fsm.txt']},
        'cases': {}}
    for name, text in generators.items():
        directory = out / name
        directory.mkdir(exist_ok=True)
        (directory / 'eci_stmc_auto.pl').write_text(text)
        # Both historical variants use their own unchanged converter/templates.
        template = out / 'history' / ('plain-original' if name.startswith('plain') else 'lock-9c9abae')
        for filename in ['cc_rom_from_cc_tbl.pl', 'eci_cc_defs.tmpl', 'eci_cc_table.tmpl']:
            shutil.copyfile(template / filename, directory / filename)
        with (directory / 'generation.log').open('w') as log:
            subprocess.run(['perl', 'eci_stmc_auto.pl'], cwd=directory, stdout=log, stderr=log, check=True)
            subprocess.run(['perl', 'cc_rom_from_cc_tbl.pl', 'ECI_CC.csv'], cwd=directory, stdout=log, stderr=log, check=True)
        with (directory / 'lint.log').open('w') as log:
            subprocess.run(['verilator', '--lint-only', '-Wno-fatal', '--top-module', 'eci_cc_table',
                            'eci_cc_defs.sv', 'eci_cc_table.sv'], cwd=directory, stdout=log, stderr=log, check=True)
        cases[name] = directory
    for name, directory in cases.items():
        table = transitions((directory / 'eci_cc_table.sv').read_text(encoding='latin1'))
        tables[name] = table
        shutil.copyfile(legacy / 'remote_fsm.txt', directory / 'remote_fsm.txt')
        logpath = directory / 'checker.log'
        with logpath.open('w') as log:
            try:
                proc = subprocess.run([sys.executable, str(legacy / 'check_DirC_csv.py'), 'ECI_CC.csv'],
                                      cwd=directory, stdout=log, stderr=log, timeout=60)
                status = proc.returncode
            except subprocess.TimeoutExpired:
                status = 'timeout'
        log = logpath.read_text()
        count = re.findall(r'Encountered (\d+) problems', log)
        issues = sorted(set(re.findall(r'Node .*', log)))
        result['cases'][name] = {'states': len({s for s, _ in table}), 'entries': len(table),
                                'checker_exit': status, 'reported_problems': int(count[-1]) if count else None,
                                'unique_diagnostics': issues, 'deadlock_reports': log.count('deadlock state!')}
        print(name, json.dumps({k: v for k, v in result['cases'][name].items() if k != 'unique_diagnostics'}), flush=True)
    expected_warnings = {"Node 0 not able to receive input %s in state (('I', '%s'), ())" % (msg, state)
                         for msg in ['IE_d', 'IS_d'] for state in ['E', 'S']}
    for name in generators:
        case = result['cases'][name]
        assert case['checker_exit'] == 0 and case['reported_problems'] == 4
        assert set(case['unique_diagnostics']) == expected_warnings
        assert case['deadlock_reports'] == 0
        case['only_documented_stable_request_warnings'] = True
    result['simplified_to_jasmin_ra3'] = difference(tables['lock-2d77ada'], tables['jasmin-ra3'])
    for key, (nxt, action) in tables['plain-jasmin-ra3'].items():
        ln, la = tables['jasmin-ra3'][key]
        assert (ln.replace('s1pUL__', 's1__'), la) == (nxt, action), key
    result['plain_port_matches_shared_lock_entries'] = len(tables['plain-jasmin-ra3'])
    remote = json.loads((THX / 'specifications/remote_fsm.json').read_text())
    counts = {msg: sum(o['msg'] == msg for t in remote['fsmTransitions'] for o in t['outputs'])
              for msg in ['ES', 'ES_d', 'FAS']}
    assert counts == {'ES': 0, 'ES_d': 0, 'FAS': 10}
    result['remote_json_output_counts'] = counts
    (out / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
