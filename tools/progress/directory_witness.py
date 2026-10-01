#!/usr/bin/env python3
"""Construct 16 retained old-domain RX lines plus an absent same-set LCI target."""
import argparse
import json
from pathlib import Path
from capacity import alias, encode, decode

def witness(configuration):
    c=json.loads(json.dumps(configuration))
    c['domain_count']=1 << len(c['domain_encoding']['kernel_basis_bytes'])
    assert c['domain_encoding']['prefix_indices'] is None and not c['reserved_regions']
    offset=c['directions']['rx']['control_offsets_bytes'][1]
    wanted=alias(encode(c,0,0,offset)>>7)&8191
    rows=[]
    for domain in range(c['domain_count']):
        address=encode(c,0,domain,offset)
        if alias(address>>7)&8191 == wanted:
            assert decode(c,address)==(0,domain,offset)
            rows.append(dict(domain=domain,pa=address,pa_hex=hex(address)))
            if len(rows)==17:break
    assert len(rows)==17 and len({r['pa'] for r in rows})==17
    door=encode(c,0,rows[-1]['domain'],c['directions']['rx']['control_offsets_bytes'][0])
    assert alias(door>>7)&8191 != wanted
    return dict(scope='Address witness, not a board-observed execution; selected layout expanded to full domain-code capacity',
        worker=0,rtg_set=wanted,retained_old_lines=rows[:-1],absent_lci_target=rows[-1],
        new_rx_door=dict(pa=door,pa_hex=hex(door),rtg_set=alias(door>>7)&8191),
        domain_codes_required=rows[-1]['domain']+1,
        assumptions=['16 old RX-control lines may remain cached across assignments',
                     'current CPU RX is to the other parity/set; no other CPU access forces target-set eviction',
                     'DCS auto_lock LCI allocates a directory entry even on an absent line'])

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('configuration',type=Path)
    args=p.parse_args()
    print(json.dumps(witness(json.loads(args.configuration.read_text())[0]['configuration']),indent=2))
if __name__=='__main__':main()
