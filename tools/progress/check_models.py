#!/usr/bin/env python3
"""Run pinned TLC scenarios; negative tests must fail the intended safety or temporal property."""
import argparse
import hashlib
import json
import os
import time
from pathlib import Path
import subprocess
from witness_model import generate

ROOT = Path(__file__).resolve().parents[2]
CASES = {'no-stalls': ('Steady', False), 'positive': ('Steady', False), 'minimal': ('Steady', False),
         'bad-color': ('Steady', True), 'bad-credits': ('Steady', True),
         'probe': ('Probe', False), 'bad-hol': ('Probe', True),
         'two-stalls': ('Steady', False), 'bypass': ('Steady', False),
         'bad-slice': ('Steady', True), 'bad-cycle': ('Steady', True),
         'bad-ack': ('Steady', True), 'separate-ack': ('Steady', False)}

WITNESSES = {
    'selected-workers': ([0, 15], 1, False),
    'selected-two-stalls': ([0, 7, 15], 2, False),
    'selected-bypass': ([15], 1, True),
    'selected-peer-bypass': ([0, 15], 1, True),
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, default=os.environ.get('TLA2TOOLS_JAR'),
                   help='defaults to the hash-pinned TLA2TOOLS_JAR from nix develop')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--timeout', type=int, default=300)
    p.add_argument('--workers', type=int, default=1)
    p.add_argument('--heap-mib', type=int, default=2048)
    p.add_argument('--case', choices=tuple(CASES) + ('bypass-overflow',) + tuple(WITNESSES), action='append')
    a = p.parse_args()
    if a.jar is None:
        p.error('run inside nix develop (TLA2TOOLS_JAR), or explicitly provide --jar')
    if min(a.timeout, a.workers, a.heap_mib) <= 0:
        p.error('timeout, workers and heap-mib must be positive')
    a.output.mkdir(parents=True, exist_ok=False)
    (a.output/'trace-specs').mkdir()
    sources = sorted((ROOT/'model/steady').glob('*.tla')) + sorted((ROOT/'model/steady').glob('*.cfg'))
    sources += [ROOT/'model/steady/selected-8k.json'] + sorted((ROOT/'tools/progress').glob('*.py'))
    summary = {'source_sha256': {str(f.relative_to(ROOT)): hashlib.sha256(f.read_bytes()).hexdigest()
                                 for f in sources if '_TTrace_' not in f.name},
               'tlc_sha256': hashlib.sha256(a.jar.read_bytes()).hexdigest(),
               'java_version': subprocess.run(['java', '-version'], text=True, stdout=subprocess.PIPE,
                                               stderr=subprocess.STDOUT, check=True).stdout,
               'cases': {}}
    for name in a.case or [*CASES, 'selected-workers']:
        module, negative = CASES.get(name, ('Steady', False))
        if name in WITNESSES:
            domains, stalls, bypass = WITNESSES[name]
            config = json.loads((ROOT/'model/steady/selected-8k.json').read_text())[0]['configuration']
            harness = a.output / (name + '-harness')
            generate(config, domains, stalls, bypass, harness)
            module_file, config_file = harness/'Witness.tla', harness/'Witness.cfg'
        else:
            module_file, config_file = ROOT/f'model/steady/{module}.tla', ROOT/f'model/steady/{name}.cfg'
        cmd = ['java', f'-Xmx{a.heap_mib}m', '-XX:+UseParallelGC', '-cp', str(a.jar.resolve()), 'tlc2.TLC',
               '-workers', str(a.workers), '-lncheck', 'final', '-teSpecOutDir', str((a.output/'trace-specs').resolve()),
               '-metadir', str((a.output/name).resolve()), '-config', str(config_file.resolve()), str(module_file.resolve())]
        started = time.monotonic()
        log = a.output/f'{name}.log'
        print(f'{name}: checking (timeout {a.timeout}s, heap {a.heap_mib} MiB)', flush=True)
        with log.open('w') as output_file:
            try:
                result = subprocess.run(cmd, text=True, stdout=output_file,
                                        stderr=subprocess.STDOUT, timeout=a.timeout)
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = 124
                output_file.write('\nCHECK INCOMPLETE: timeout\n')
        output = log.read_text()
        temporal_failure = code == 13 and any(
            'Error: Temporal propert' in line and 'violated' in line for line in output.splitlines())
        expected_failure = (code == 12 and 'Invariant NoReadDependencyCycle is violated' in output
                            if name == 'bad-cycle' else temporal_failure)
        ok = expected_failure if negative else (code == 0 and 'No error has been found' in output)
        summary['cases'][name] = {
            'expected_counterexample': negative, 'passed': ok, 'returncode': code,
            'elapsed_seconds': round(time.monotonic()-started, 3), 'command': cmd,
            'module_sha256': hashlib.sha256(module_file.read_bytes()).hexdigest(),
            'config_sha256': hashlib.sha256(config_file.read_bytes()).hexdigest(),
            'log_sha256': hashlib.sha256(log.read_bytes()).hexdigest(),
            'statistics': [line for line in output.splitlines() if 'distinct states found' in line]}
        print(f'{name}: {"PASS" if ok else "INCOMPLETE" if code == 124 else "FAIL"}', flush=True)
        if not ok:
            print('\n'.join(output.splitlines()[-16:]), flush=True)
        (a.output/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    raise SystemExit(0 if all(c['passed'] for c in summary['cases'].values()) else 1)


if __name__ == '__main__':
    main()
