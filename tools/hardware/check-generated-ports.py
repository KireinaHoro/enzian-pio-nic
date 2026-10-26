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

# The retired application ILA must not reappear in the block design.
tcl = (a.vhdl.parent.parent / 'bd/design_1.tcl').read_text()
assert not re.search(r'\bila_app\b|hier_ilas/core[0-4]_states|dcs_(?:even|odd)_mon', tcl)
assert not re.search(r'\bul_(?:even|odd)\b', tcl)
print('Application ILA absent; no unlock probes')

# Retain width/packing checks for generated NIC debug outputs.
source = (a.generated / 'NicEngine.v').read_text().split('module NicEngine', 1)[1].split(');', 1)[0]
port_widths = {}
for hi, lo, name in re.findall(r'\boutput\s+(?:wire|reg)?\s*(?:\[(\d+):(\d+)\])?\s*(\w+)', source):
    port_widths[name] = int(hi) - int(lo) + 1 if hi else 1
state_hi = int(re.search(r'signal core0_states[^;]*std_logic_vector\((\d+) downto 0\)', vhdl)[1])
for core in range(5):
    used = set()
    bindings = re.findall(rf'(core{core}_\w+)\s*=>\s*core{core}_states\((\d+)(?: downto (\d+))?\)', vhdl)
    assert bindings, core
    for port, hi, lo in bindings:
        bits = set(range(int(lo or hi), int(hi) + 1))
        assert port_widths[port] == len(bits), (port, 'generated width', port_widths[port], 'wrapper width', len(bits))
        assert not used & bits, (port, 'overlapping debug bits')
        used |= bits
    assert used == set(range(state_hi + 1)), (core, 'incomplete debug packing')
print(f'Debug: five {state_hi + 1}-bit state bundles match generated RTL and wrapper packing')
