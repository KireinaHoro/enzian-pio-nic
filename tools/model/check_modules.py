#!/usr/bin/env python3
"""Check the project TLA+ inventory, import boundaries and SANY semantics.

This is a structural check, not TLC verification or a refinement proof.
Historical Toolbox snapshots are intentionally outside the active import path.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
STANDARD = {'Naturals', 'Integers', 'Sequences', 'FiniteSets', 'Bags'}


def uncomment(source):
    # TLA+ block comments nest; strings can contain apparent comment markers.
    tokens = re.compile(r'"(?:\\.|[^"\\])*"|\\\*[^\n]*|\(\*|\*\)|.', re.S)
    depth = 0
    result = []
    for token in tokens.findall(source):
        if token == '(*':
            depth += 1
        elif token == '*)':
            depth -= 1
        elif not depth and not token.startswith('\\*'):
            result.append(token)
    if depth:
        raise ValueError('unbalanced block comment')
    return ''.join(result)


def imports(source):
    text = uncomment(source)
    found = set(re.findall(r'\bINSTANCE\s+(\w+)', text))
    for line in re.findall(r'^EXTENDS\s+([^\n]+)', text, re.M):
        found.update(re.findall(r'\w+', line))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    jar = os.environ.get('TLA2TOOLS_JAR')
    if not jar:
        parser.error('run through nix develop: TLA2TOOLS_JAR is required')
    inventory_path = ROOT / 'model/config/modules.json'
    inventory = json.loads(inventory_path.read_text())
    paths = [ROOT / 'model/legacy/tla/lauberhorn.tla']
    paths += sorted((ROOT / 'model/tla/steady').glob('*.tla'))
    paths += sorted((ROOT / 'model/tla/scheduling').glob('*.tla'))
    names = {p.stem for p in paths}
    if len(names) != len(paths) or names != set(inventory):
        raise ValueError(f'module inventory mismatch: {names ^ set(inventory)}')
    args.output.mkdir(parents=True, exist_ok=False)
    work = args.output / 'sources'
    work.mkdir()
    for path in paths:
        source = path.read_text()
        header = re.search(r'-+ MODULE (\w+) -+', source)
        if not header or header[1] != path.stem:
            raise ValueError(f'module/file name mismatch: {path}')
        if 'legacy' not in path.parts and re.search(r'\b(?:unlockQ|Unlock|Unlocked|DeliverUnlock|Nonlocking|AllocateOnInvalidate)\b', uncomment(source)):
            raise ValueError(f'legacy endpoint leaked into active module: {path}')
        actual = imports(source)
        expected = set(inventory[path.stem]['imports'])
        if actual - STANDARD != expected or actual - STANDARD - names:
            raise ValueError(f'{path.stem}: expected imports {expected}, got {actual - STANDARD}')
        shutil.copyfile(path, work / path.name)
    # Exact import allow-lists also prevent generic resources importing harnesses.
    def visit(name, stack):
        if name in stack:
            raise ValueError(f'import cycle: {stack + [name]}')
        for child in inventory[name]['imports']:
            visit(child, stack + [name])
    for name in names:
        visit(name, [])
    result = {'kind': 'inventory/import boundaries and SANY; no TLC properties',
              'jar_sha256': hashlib.sha256(Path(jar).read_bytes()).hexdigest(),
              'inventory_sha256': hashlib.sha256(inventory_path.read_bytes()).hexdigest(),
              'modules': {}}
    for path in paths:
        command = ['java', '-cp', str(Path(jar).resolve()), 'tla2sany.SANY', path.name]
        run = subprocess.run(command, cwd=work, text=True, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, timeout=60)
        (args.output / (path.stem + '.log')).write_text(run.stdout)
        # SANY may report semantic errors with exit status zero.
        passed = run.returncode == 0 and 'Semantic processing of module ' + path.stem in run.stdout
        passed = passed and not re.search(r'\*\*\* (?:Errors|Abort messages)|Fatal errors|Exception', run.stdout)
        result['modules'][path.stem] = {
            'passed': passed, 'source_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'role': inventory[path.stem]['role'], 'imports': inventory[path.stem]['imports']}
        print(f'{path.stem}: {"PASS" if passed else "FAIL"}', flush=True)
        if not passed:
            print(run.stdout)
    (args.output / 'summary.json').write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    return 0 if all(m['passed'] for m in result['modules'].values()) else 1


if __name__ == '__main__':
    raise SystemExit(main())
