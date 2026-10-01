#!/usr/bin/env python3
"""Check generated application/trace ports against the maintained VHDL wrapper."""
import argparse
import re
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('generated', type=Path)
p.add_argument('vhdl', type=Path)
a = p.parse_args()
vhdl = a.vhdl.read_text()
for module in ('NicEngine', 'lauberhorn_trace_dma'):
    source = (a.generated / (module + '.v')).read_text()
    header = source.split('module ' + module, 1)[1].split(');', 1)[0]
    ports = set(re.findall(r'\b(?:input|output|inout)\s+(?:wire|reg)?\s*(?:\[[^\]]+\])?\s*(\w+)', header))
    instance = re.split(r':\s*(?:entity work\.)?' + module + r'\b', vhdl, maxsplit=1)[1].split(');', 1)[0]
    bindings = re.findall(r'^\s*(\w+)\s*=>', instance, re.M)
    assert ports and len(bindings) == len(set(bindings)), module
    assert ports == set(bindings), (module, 'missing', ports-set(bindings), 'extra', set(bindings)-ports)
    assert not any('unlock' in port.lower() for port in ports), module
    print(f'{module}: {len(ports)} ports match VHDL; no unlock ports')

# Keep the hand-maintained ILA declarations and connections in agreement too.
tcl = (a.vhdl.parent.parent / 'bd/design_1.tcl').read_text()
ila = tcl.split('# Create instance: ila_app,', 1)[1].split('# Create instance: app_clock', 1)[0]
count = int(re.search(r'C_NUM_OF_PROBES \{(\d+)\}', ila)[1])
widths = set(map(int, re.findall(r'C_PROBE(\d+)_WIDTH', ila)))
connections = list(map(int, re.findall(r'ila_app/probe(\d+)\]', tcl)))
assert widths == set(range(count)) and sorted(connections) == list(range(count))
assert not re.search(r'\bul_(?:even|odd)\b', tcl)
print(f'ILA: {count} probe declarations match connections; no unlock probes')
