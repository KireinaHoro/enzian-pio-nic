#!/usr/bin/env python3
"""Check generated Lauberhorn ROM against the unmodified THX remote FSM.
Only FPGA spontaneous operations are restricted to LCI/EW; CPU operations remain
unchanged. The legacy checker is a compatibility diagnostic, not a liveness proof.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rom', type=Path, required=True)
    p.add_argument('--thx', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    csv = (a.rom / 'ECI_CC.csv').read_text()
    header = [x.strip() for x in csv.splitlines()[0].split(',')][1:]
    assert 'LCI' in header and 'A22' in header
    assert not {'LC', 'LR', 'LW', 'ICI', 'UL', 'V32', 'V32d'} & set(header)
    table = (a.rom / 'eci_cc_table.sv').read_text()
    assert not re.search(r'SEND_(?:F32|LCA|LRA|LWA|RA2)\b|s1pUL__', table)
    assert re.search(r'R12: begin\s*next_state_reg <= s1__3pRA3;\s*next_action_reg <= RDD;', table)
    assert re.search(r'A22: begin\s*next_state_reg <= s1__1;\s*next_action_reg <= NO_ACTION;', table)
    for f in ['eci_cc_defs.sv', 'eci_cc_table.sv']:
        shutil.copyfile(a.rom / f, a.output / f)
    with (a.output / 'lint.log').open('w') as log:
        subprocess.run(['verilator', '--lint-only', '-Wno-fatal', '--top-module', 'eci_cc_table',
                        'eci_cc_defs.sv', 'eci_cc_table.sv'], cwd=a.output, stdout=log, stderr=log, check=True)
    checker = (a.thx / 'legacy/check_DirC_csv.py').read_text()
    old = 'return incoming | spontaneous_events | lambda_inputs'
    assert checker.count(old) == 1
    # Identify the DC by its transition table, leaving remote software unchanged.
    checker = checker.replace(old, 'return incoming | ({"EW"} if t is transitions[0] else spontaneous_events) | lambda_inputs')
    checker = checker.replace('problem_counter = 0', 'observed_outputs = [set(), set()]\nproblem_counter = 0')
    old = 'for output, next in transitions[i][(states[i], proxy_input)]:'
    assert checker.count(old) == 1
    checker = checker.replace(old, old + '\n                observed_outputs[i].add(output)')
    checker += '\nprint("OBSERVED_OUTPUTS", [sorted(s) for s in observed_outputs])\n'
    (a.output / 'checker.py').write_text(checker)
    shutil.copyfile(a.thx / 'legacy/remote_fsm.txt', a.output / 'remote_fsm.txt')
    shutil.copyfile(a.rom / 'ECI_CC.csv', a.output / 'ECI_CC.csv')
    run = subprocess.run([sys.executable, 'checker.py', 'ECI_CC.csv'], cwd=a.output,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60, check=True)
    (a.output / 'checker.log').write_text(run.stdout)
    warnings = set(re.findall(r'Node .*', run.stdout))
    expected = {"Node 0 not able to receive input %s in state (('I', '%s'), ())" % (msg, state)
                for msg in ['IE_d', 'IS_d'] for state in ['E']}
    assert warnings == expected, warnings
    assert 'Encountered 2 problems' in run.stdout
    import ast
    outputs = ast.literal_eval(run.stdout.split('OBSERVED_OUTPUTS ')[1].strip())
    assert 'FS_d' not in outputs[0] and 'FAS' not in outputs[1]
    result = {'scope': __doc__, 'passed': True, 'only_documented_warnings': sorted(warnings),
              'observed_outputs': outputs, 'states': len(re.findall(r'case\(curr_req_i\)', table)),
              'local_requests': ['LCI'], 'retained_A22': True, 'emits_F32': False,
              'cpu_software_restricted': False}
    (a.output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
