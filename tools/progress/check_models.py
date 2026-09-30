#!/usr/bin/env python3
"""Run pinned TLC scenarios; negative tests must fail with a temporal violation."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CASES = {'no-stalls': ('Steady', False), 'positive': ('Steady', False), 'minimal': ('Steady', False),
         'bad-color': ('Steady', True), 'bad-credits': ('Steady', True),
         'probe': ('Probe', False), 'bad-hol': ('Probe', True),
         'two-stalls': ('Steady', False), 'bypass': ('Steady', False),
         'bad-slice': ('Steady', True), 'bad-cycle': ('Steady', True),
         'bad-ack': ('Steady', True), 'separate-ack': ('Steady', False)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--timeout', type=int, default=300)
    p.add_argument('--workers', type=int, default=1)
    p.add_argument('--case', choices=tuple(CASES) + ('bypass-overflow',), action='append')
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    (a.output/'trace-specs').mkdir()
    summary = {'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                 for p in sorted((ROOT/'model/steady').glob('*')) if p.is_file() and p.suffix in ('.tla', '.cfg') and '_TTrace_' not in p.name},
               'tlc_sha256': hashlib.sha256(a.jar.read_bytes()).hexdigest(), 'cases': {}}
    for name in a.case or CASES:
        module, negative = CASES.get(name, ('Steady', False))
        cmd = ['java', '-XX:+UseParallelGC', '-cp', str(a.jar.resolve()), 'tlc2.TLC',
               '-workers', str(a.workers), '-lncheck', 'final', '-teSpecOutDir', str((a.output/'trace-specs').resolve()), '-metadir', str((a.output/name).resolve()),
               '-config', str(ROOT/f'model/steady/{name}.cfg'), str(ROOT/f'model/steady/{module}.tla')]
        try:
            result = subprocess.run(cmd, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=a.timeout)
        except subprocess.TimeoutExpired as error:
            output = error.stdout or b''
            if isinstance(output, bytes):
                output = output.decode(errors='replace')
            result = subprocess.CompletedProcess(cmd, 124, output + '\nCHECK INCOMPLETE: timeout\n')
        (a.output/f'{name}.log').write_text(result.stdout)
        temporal_failure = result.returncode == 13 and any(
            'Error: Temporal propert' in line and 'violated' in line
            for line in result.stdout.splitlines())
        expected_failure = (result.returncode == 12 and 'Invariant NoReadDependencyCycle is violated' in result.stdout
                            if name == 'bad-cycle' else temporal_failure)
        ok = expected_failure if negative else (
            result.returncode == 0 and 'No error has been found' in result.stdout)
        summary['cases'][name] = {'expected_counterexample': negative, 'passed': ok,
                                  'returncode': result.returncode,
                                  'statistics': [s for s in result.stdout.splitlines() if 'distinct states found' in s]}
        print(f'{name}: {"PASS" if ok else "FAIL"}', flush=True)
        (a.output/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    (a.output/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    raise SystemExit(0 if all(c['passed'] for c in summary['cases'].values()) else 1)


if __name__ == '__main__':
    main()
