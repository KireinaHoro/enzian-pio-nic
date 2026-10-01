#!/usr/bin/env python3
"""Export exact generated enum names for the matching DCS trace map."""
import json
import re
import sys
from pathlib import Path
p = Path(sys.argv[1])
s = (p / 'eci_cc_defs.sv').read_text()
names = {}
for key, typ in [('request', 'cc_req_t'), ('state', 'cc_state_t'), ('action', 'cc_action_t')]:
    body = re.search(r'typedef enum logic \[[^]]+\] \{([^}]+)\}\s*' + typ, s, re.S).group(1)
    value = -1
    table = {}
    for entry in body.split(','):
        entry = entry.strip()
        if not entry:
            continue
        name, *number = entry.split('=')
        value = int(number[0].strip()) if number else value + 1
        table[str(value)] = name.strip()
    names[key] = table
if len(sys.argv) == 2:
    (p / 'trace-enums.json').write_text(json.dumps(names, indent=2) + '\n')

if len(sys.argv) > 2:
    target = Path(sys.argv[2])
    trace = json.loads(target.read_text())
    fields = trace['payload_formats']['dcs_event']['fields']
    for key, values in names.items():
        assert max(map(int, values)) < (1 << fields[key]['width'])
        fields[key]['enum'] = values
    trace['dcs_protocol'] = 'lauberhorn_lci_only_nonlocking_ra3'
    target.write_text(json.dumps(trace, indent=2) + '\n')

if len(sys.argv) == 2:
    import csv
    from collections import Counter
    rows = list(csv.reader((p / 'ECI_CC.csv').open(), skipinitialspace=True))
    cells = [x.strip() for row in rows[1:] for x in row[1:]]
    kinds = Counter('forbidden' if x == 'X' else 'stall' if x == 'Stall' else 'permitted' for x in cells)
    statistics = {'states': len(rows)-1, 'input_events': len(rows[0])-1,
                  'explicit_entries': len(cells), **dict(kinds)}
    (p / 'statistics.json').write_text(json.dumps(statistics, indent=2) + '\n')
