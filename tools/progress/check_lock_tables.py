#!/usr/bin/env python3
"""Compare explicit generated table transitions; not a protocol refinement proof."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'vivado/eci/directory-controller-slice/gen_dc_protocol'


def read(variant):
    path = BASE / variant / 'cc_rom_from_cc_tbl/eci_cc_table.sv'
    transitions = {}
    for state, body in re.findall(r'(\w+): begin\s*case\(curr_req_i\)(.*?)endcase', path.read_text(encoding='latin1'), re.S):
        for req, nxt, action in re.findall(r'(\w+): begin\s*next_state_reg <= (\w+);\s*next_action_reg <= (\w+);', body):
            assert (state, req) not in transitions
            transitions[state, req] = (nxt, action)
    return transitions, hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    locked, lh = read('dc_stmc_auto_lock')
    plain, ph = read('dc_stmc_auto_no_lock')
    assert len(locked) == 2028 and len(plain) == 1800
    assert plain.keys() <= locked.keys()
    differences = []
    for key in sorted(plain):
        ln, la = locked[key]
        pn, pa = plain[key]
        assert la == pa and ln.replace('s1pUL__', 's1__') == pn, (key, locked[key], plain[key])
        if locked[key] != plain[key]:
            assert pa in {'SEND_LCA', 'SEND_LCIA'}
            differences.append({'state': key[0], 'request': key[1], 'locked': ln, 'nonlocking': pn, 'action': pa})
    assert len(differences) == 36
    assert all(state.startswith('s1pUL__') or req == 'UL' for state, req in locked.keys() - plain.keys())
    print(json.dumps({'scope': __doc__, 'passed': True, 'explicit_transitions': [len(locked), len(plain)],
                      'table_sha256': {'auto_lock': lh, 'auto_no_lock': ph}, 'differences': differences}, indent=2))


if __name__ == '__main__':
    main()
