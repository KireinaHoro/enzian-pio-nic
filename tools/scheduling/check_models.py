#!/usr/bin/env python3
"""Run the bounded scheduling contracts with locked Nix TLC; preserve failures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
ROOT = Path(__file__).resolve().parents[2]
# Bounds, fault injections, properties and suite membership are model inputs.
SUITE = ROOT / 'model/config/scheduling/suite.json'
CATALOG = json.loads(SUITE.read_text())
CASES = {name: (case['module'], case['options'], case['expected_violation'])
         for name, case in CATALOG['cases'].items()}
DEFAULTS = CATALOG['defaults']
INVARIANTS = CATALOG['invariants']
PROPERTIES = CATALOG['properties']
GROUPS = CATALOG['groups']

def tla_value(v):
    return json.dumps(v) if isinstance(v, str) else str(v).upper()

def config(module, options, negative, safety_only=False):
    constants = DEFAULTS[module] | options
    invs, props = INVARIANTS[module], PROPERTIES[module]
    if module == 'CombinedScheduling' and not negative:
        invs = ['Safety']  # Already includes every named diagnostic invariant.
    if not negative:
        invs = CATALOG.get('positive_invariants', {}).get(module, invs)
    if safety_only:
        props = []
    if negative in CATALOG.get('witnesses', {}).get(module, []):
        invs = [negative]
    if negative and negative not in invs + props:
        raise ValueError(f'{module}: unconfigured negative property {negative}')
    if negative:
        invs = [negative] if negative in invs else []
        props = [negative] if negative in props else []
    invs = list(dict.fromkeys(CATALOG.get('required_invariants', {}).get(module, []) + invs))
    return ('SPECIFICATION Spec\n' + ('CONSTANTS\n' if constants else '') + ''.join(f'{k} = {tla_value(v)}\n' for k,v in constants.items())
            + ('INVARIANTS ' + ' '.join(invs) + '\n' if invs else '')
            + ('PROPERTIES ' + ' '.join(props) + '\n' if props else '')
            + 'CHECK_DEADLOCK FALSE\n')

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    selection = p.add_mutually_exclusive_group()
    selection.add_argument('--case', action='append', choices=CASES)
    selection.add_argument('--transport-only', action='store_true')
    selection.add_argument('--axi-only', action='store_true')
    selection.add_argument('--cache-contracts-only', action='store_true')
    selection.add_argument('--kill-only', action='store_true')
    selection.add_argument('--kill-transport-only', action='store_true')
    selection.add_argument('--full-only', action='store_true')
    selection.add_argument('--races-only', action='store_true')
    selection.add_argument('--composed-only', action='store_true')
    selection.add_argument('--composed-invariants', action='store_true', help='composed state invariants plus cache progress and all fault diagnostics; omit positive combined temporal properties')
    p.add_argument('--safety-only', action='store_true', help='positive cases only; omit temporal properties (including response stability)')
    p.add_argument('--timeout', type=int, default=300)
    p.add_argument('--heap-mib', type=int, default=2048)
    p.add_argument('--workers', type=int, default=1)
    args = p.parse_args()
    group = ('full' if args.full_only else 'kill-transport' if args.kill_transport_only else 'transport' if args.transport_only else 'axi' if args.axi_only else
             'cache-contracts' if args.cache_contracts_only else 'kill' if args.kill_only else
             'composed' if args.composed_only or args.composed_invariants else
             'races' if args.races_only else 'default')
    selected = args.case or GROUPS[group]
    if args.safety_only and any(CASES[name][2] for name in selected):
        p.error('--safety-only requires an explicit selection of positive cases')
    jar = os.environ.get('TLA2TOOLS_JAR')
    if not jar: p.error('run through nix develop: TLA2TOOLS_JAR is required')
    if min(args.timeout,args.heap_mib,args.workers) <= 0: p.error('bounds must be positive')
    args.output.mkdir(parents=True,exist_ok=False)
    sources = sorted((ROOT/'model/tla').glob('*/*.tla'))
    sources += [SUITE] + sorted((ROOT/'tools/scheduling').glob('*.py')) + [ROOT/'model/data/selected-8k.json', ROOT/'tools/progress/capacity.py']
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    snapshot = {f: f.read_bytes() for f in sources}
    summary = {'jar_sha256': sha(Path(jar)), 'sources': {str(f.relative_to(ROOT)): hashlib.sha256(data).hexdigest() for f,data in snapshot.items()},
               'java_version': subprocess.run(['java','-version'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=True).stdout,
               'cases': {}}
    summary['verification_kind'] = ('combined state invariants; cache progress and fault diagnostics' if args.composed_invariants else
        'state invariants only' if args.safety_only else 'state invariants and temporal properties')
    for name in selected:
        module, options, negative = CASES[name]
        state_only = args.safety_only or (args.composed_invariants and module == 'CombinedScheduling' and not negative)
        work = args.output/name
        work.mkdir()
        for path in sources:
            if path.suffix == '.tla': (work/path.name).write_bytes(snapshot[path])
        cfg = work/(module+'.cfg')
        cfg.write_text(config(module,options,negative,state_only))
        cmd = ['java',f'-Xmx{args.heap_mib}m','-XX:+UseParallelGC','-cp',jar,'tlc2.TLC',
               '-workers',str(args.workers),'-lncheck','final','-metadir',str((work/'states').resolve()),
               '-teSpecOutDir',str(work.resolve()),'-config',str(cfg.resolve()),str((work/(module+'.tla')).resolve())]
        print(f'{name}: checking',flush=True)
        start = time.monotonic()
        log = work/'TLC.log'
        with log.open('w') as out:
            with subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT) as proc:
                deadline = start + args.timeout
                while True:
                    try:
                        code = proc.wait(timeout=max(0.001, min(60, deadline-time.monotonic())))
                        break
                    except subprocess.TimeoutExpired:
                        if time.monotonic() >= deadline:
                            proc.kill()
                            proc.wait()
                            code = 124
                            out.write('\nINCOMPLETE: timeout\n')
                            break
                        progress = [line for line in log.read_text().splitlines()
                                    if line.startswith(('Progress(', 'Checking ', 'Finished checking temporal'))]
                        print(f'{name}: {progress[-1] if progress else "TLC still running"}', flush=True)
        output = log.read_text()
        violation_names = [negative] + CATALOG.get('violation_aliases', {}).get(module, {}).get(negative, [])
        expected = bool(negative) and ((code == 12 and f'Invariant {negative} is violated' in output)
                    or (code == 13 and negative in PROPERTIES[module] and ('Temporal properties were violated' in output or any(f'Action property {name} is violated' in output for name in violation_names))))
        passed = expected if negative else code == 0 and 'No error has been found' in output
        directory_contract = 'LCI-only: release on LCIA acceptance'
        result = dict(directory_contract=directory_contract, passed=passed, expected_violation=negative, returncode=code,
                      verification_kind='state invariants only' if state_only else 'configured invariants and temporal properties',
                      config_sha256=sha(cfg), log_sha256=sha(log), command=cmd,
                      elapsed_seconds=round(time.monotonic()-start,3),
                      statistics=[line for line in output.splitlines() if any(x in line for x in ('distinct states found','depth of the complete'))])
        summary['cases'][name] = result
        (args.output/'summary.json').write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n')
        status = f'EXPECTED {negative} VIOLATION' if expected else 'PASS' if passed else 'INCOMPLETE' if code == 124 else 'FAIL'
        print(f'{name}: {status}',flush=True)
        if not passed: print('\n'.join(output.splitlines()[-28:]),flush=True)
    return 0 if all(c['passed'] for c in summary['cases'].values()) else 1
if __name__ == '__main__': raise SystemExit(main())
