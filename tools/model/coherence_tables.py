#!/usr/bin/env python3
"""Translate selected generated DC ROM and unmodified THX JSON into TLA data.
No handwritten replacement equations. These tables do not model RTL pipelines.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def tla(value):
    if isinstance(value, str): return json.dumps(value)
    if isinstance(value, list): return '<<' + ', '.join(map(tla, value)) + '>>'
    if isinstance(value, dict): return '[' + ', '.join(f'{k} |-> {tla(v)}' for k,v in value.items()) + ']'
    raise TypeError(value)


def generate(rom, thx, out):
    table = rom / 'eci_cc_table.sv'
    spec = thx / 'specifications/remote_fsm.json'
    dc = []
    blocks = re.findall(r'(s\w+): begin\s*case\(curr_req_i\)(.*?)endcase', table.read_text(), re.S)
    for state, body in blocks:
        for event, next_state, action in re.findall(
                r'(\w+): begin\s*next_state_reg <= (\w+);\s*next_action_reg <= (\w+);', body):
            if event == "default": continue
            dc.append(dict(state=state,event=event,next=next_state,action=action))
    assert len(blocks) == 14 and len(dc) == 14*18, (len(blocks),len(dc))
    assert len({(t['state'],t['event']) for t in dc}) == len(dc)
    raw = json.loads(spec.read_text())
    th = [dict(state=t['current'],port=t['input']['port'],msg=t['input']['msg'],
               next=t['next'],outputs=t['outputs']) for t in raw['fsmTransitions']]
    assert len({(t['state'],t['port'],t['msg']) for t in th}) == len(th)
    out.mkdir(parents=True, exist_ok=True)
    (out/'CoherenceTables.tla').write_text(
        '-------------------------- MODULE CoherenceTables --------------------------\n'
        + 'SelectedDcTable == {\n' + ',\n'.join(map(tla,dc)) + '\n}\n'
        + 'SelectedThxTable == {\n' + ',\n'.join(map(tla,th)) + '\n}\n'
        + '=============================================================================\n')
    result = {'scope': __doc__, 'dc_states':len(blocks), 'dc_entries':len(dc),
              'thx_transitions':len(th), 'sources':{str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in [table,spec,Path(__file__)]}}
    (out/'tables.json').write_text(json.dumps(result,indent=2)+'\n')
    return result

if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rom',type=Path,required=True)
    p.add_argument('--thx',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();generate(a.rom,a.thx,a.output)
