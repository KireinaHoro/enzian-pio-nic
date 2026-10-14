#!/usr/bin/env python3
"""Check the active chart inventory against scripts, PDFs and TeX inclusions."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
PAPER = ROOT.parent / 'paper'


def main():
    charts = json.loads((ROOT / 'tools/paper/charts.json').read_text())['charts']
    outputs = {(ROOT / c['output']).resolve() for c in charts}
    scripts = {(ROOT / c['script']).resolve() for c in charts}
    if len(outputs) != len(charts):
        raise ValueError('Duplicate chart output')
    actual = set((PAPER / 'plots').glob('*.pdf'))
    if actual != outputs:
        raise ValueError(f'Missing PDFs: {outputs - actual}; stale PDFs: {actual - outputs}')
    actual_scripts = set((ROOT / 'tools/paper').glob('plot_*.py'))
    if actual_scripts != scripts:
        raise ValueError(f'Missing/stale plotting scripts: {actual_scripts ^ scripts}')
    inclusions = {}
    for tex in PAPER.rglob('*.tex'):
        text = re.sub(r'(?<!\\)%[^\n]*', '', tex.read_text())
        for name in re.findall(r'\\includegraphics(?:\[[^\]]*\])?\{([^}]+)\}', text):
            path = PAPER / name
            if not path.suffix:
                path = path.with_suffix('.pdf')
            if path.parent == PAPER / 'plots':
                inclusions.setdefault(path, set()).add(str(tex.relative_to(PAPER)))
    if set(inclusions) - outputs:
        raise ValueError('Paper includes an unregistered chart')
    for chart in charts:
        output = (ROOT / chart['output']).resolve()
        used = inclusions.get(output, set())
        expected = {chart['tex']} if chart['status'] == 'included' else set()
        if used != expected or not (ROOT / chart['manifest']).is_file():
            raise ValueError(f'Inventory does not match paper/data: {chart["id"]}')
        print(f'{chart["id"]}: {chart["status"]}' + (f' ({chart["tex"]})' if used else ''))


if __name__ == '__main__':
    main()
